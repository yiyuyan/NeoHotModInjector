package cn.ksmcbrigade.nhmj.utils;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.*;
import java.lang.invoke.MethodHandles;
import java.security.ProtectionDomain;
import java.util.*;

@SuppressWarnings({"unused", "MismatchedQueryAndUpdateOfCollection"})
//Very Thank Claude,you did an important and helpful job!
public final class MixinHotSwap {

    private static final Logger LOGGER = LogUtils.getLogger();
    
    private static final Map<Class<?>, Class<?>> EXT_CLASSES = new WeakHashMap<>();

    public static int swapCounter = 0;

    /**
     * Attempts to hot-apply a mixin-transformed class.
     * Fast path: try a direct redefine (works if mixin only changed method bodies).
     * Fallback: extract added methods/fields into a hidden trampoline class so the
     * owner's schema never changes, then redefine the owner with rewritten call sites.
     *
     * @param mixinAgent unused hook slot — pass your MixinAgent/transformer instance if
     *                   you want to trigger its own re-transform bookkeeping afterward;
     *                   safe to pass null.
     */
    public static void replaceMixedClasses(Class<?> clazz, byte[] newBytes,
                                           Instrumentation inst, Object mixinAgent) throws Exception {
        byte[] originalBytes = captureCurrentBytecode(clazz, inst);

        try {
            inst.redefineClasses(new ClassDefinition(clazz, newBytes));
            LOGGER.info("[MixinHotSwap] direct redefine ok: {}", clazz.getName());
            return;
        } catch (UnsupportedOperationException schemaChange) {
            LOGGER.info("[MixinHotSwap] schema change detected for {}, building trampoline patch...", clazz.getName());
        }

        int id = swapCounter++;
        Patch patch = buildTrampolinePatch(clazz, originalBytes, newBytes,id);

        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(clazz, MethodHandles.lookup());
        Class<?> extClass = lookup.defineClass(patch.extClassBytes);
        EXT_CLASSES.put(clazz, extClass);

        inst.redefineClasses(new ClassDefinition(clazz, patch.ownerBytes));
        LOGGER.info("[MixinHotSwap] trampoline redefine ok: {} -> {}", clazz.getName(), extClass.getName());
    }

    // ---------------------------------------------------------------
    // Step 1: grab the bytecode currently installed in the JVM for `clazz`
    // ---------------------------------------------------------------
    private static byte[] captureCurrentBytecode(Class<?> clazz, Instrumentation inst) throws Exception {
        byte[][] holder = new byte[1][];
        ClassFileTransformer capture = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> beingRedefined,
                                    ProtectionDomain pd, byte[] buf) {
                if (beingRedefined == clazz) holder[0] = buf;
                return null; // don't actually change anything, just observe
            }
        };
        inst.addTransformer(capture, true);
        try {
            inst.retransformClasses(clazz);
        } finally {
            inst.removeTransformer(capture);
        }
        if (holder[0] == null) {
            throw new IllegalStateException("Could not capture current bytecode for " + clazz.getName());
        }
        return holder[0];
    }

    // ---------------------------------------------------------------
    // Step 2: diff old vs new, extract added members into a side class
    // ---------------------------------------------------------------
    private static final class Patch {
        byte[] ownerBytes;
        byte[] extClassBytes;
    }

    private static Patch buildTrampolinePatch(Class<?> clazz, byte[] oldBytes, byte[] newBytes,int id) {
        ClassNode oldNode = readNode(oldBytes);
        ClassNode newNode = readNode(newBytes);

        String ownerInternal = oldNode.name;
        String extInternal = ownerInternal + "$$MixinExt$" + id;

        Map<String, MethodNode> oldMethods = indexMethods(oldNode.methods);
        Map<String, MethodNode> newMethods = indexMethods(newNode.methods);
        Map<String, FieldNode> oldFields = indexFields(oldNode.fields);
        Map<String, FieldNode> newFields = indexFields(newNode.fields);

        Set<String> addedMethodKeys = new HashSet<>(newMethods.keySet());
        addedMethodKeys.removeAll(oldMethods.keySet());

        Set<String> addedFieldKeys = new HashSet<>(newFields.keySet());
        addedFieldKeys.removeAll(oldFields.keySet());

        // ---- build the ext class ----
        ClassNode ext = new ClassNode();
        ext.version = oldNode.version;
        ext.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER | Opcodes.ACC_FINAL;
        ext.name = extInternal;
        ext.superName = "java/lang/Object";

        // side table: instance -> Object[] slot per added instance field, keyed by field name
        // (kept simple: one shared WeakHashMap<Object,Map<String,Object>> per owner)
        FieldNode sideTable = new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "SIDE_TABLE", "Ljava/util/Map;", null, null);
        ext.fields.add(sideTable);

        MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        InsnList ci = clinit.instructions;
        ci.add(new TypeInsnNode(Opcodes.NEW, "java/util/WeakHashMap"));
        ci.add(new InsnNode(Opcodes.DUP));
        ci.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/WeakHashMap", "<init>", "()V", false));
        ci.add(new FieldInsnNode(Opcodes.PUTSTATIC, extInternal, "SIDE_TABLE", "Ljava/util/Map;"));
        ci.add(new InsnNode(Opcodes.RETURN));
       // clinit.maxStack = 2;
        //clinit.maxLocals = 0;
        ext.methods.add(clinit);

        // accessor helpers for each added instance field, generated on ext:
        //   static Object get$<name>(Object self)
        //   static void   set$<name>(Object self, Object v)
        for (String key : addedFieldKeys) {
            FieldNode fn = newFields.get(key);
            if ((fn.access & Opcodes.ACC_STATIC) != 0) {
                // static added field: just host it as a real static field on ext, no side table needed
                ext.fields.add(fn);
                continue;
            }
            addSideTableAccessors(ext, extInternal, fn.name);
        }

        // added methods -> static methods on ext, self becomes explicit first arg.
        // Local var 0 is already "this" in the original instance method, so making it
        // static with an explicit self param at slot 0 requires NO local-index shifting.
        for (String key : addedMethodKeys) {
            MethodNode m = newMethods.get(key);
            if ((m.access & Opcodes.ACC_STATIC) != 0) {
                // already static (matches a static target method) — still needs to become
                // public since it now lives on a different class than the original owner
                int newAccess = (m.access & ~Opcodes.ACC_PRIVATE & ~Opcodes.ACC_PROTECTED) | Opcodes.ACC_PUBLIC;
                MethodNode relocated = new MethodNode(newAccess, m.name, m.desc, m.signature,
                        m.exceptions == null ? null : m.exceptions.toArray(new String[0]));
                m.accept(relocated);
                ext.methods.add(relocated);
                continue;
            }
            MethodNode staticVersion = new MethodNode(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    m.name,
                    "(L" + ownerInternal + ";" + m.desc.substring(1), // prepend self param
                    m.signature, m.exceptions == null ? null : m.exceptions.toArray(new String[0]));
            m.accept(staticVersion);
            rewriteMemberAccess(staticVersion.instructions, ownerInternal, extInternal, addedMethodKeys, addedFieldKeys);
            //staticVersion.maxStack = m.maxStack + 1;
            //staticVersion.maxLocals = m.maxLocals + 1;
            ext.methods.add(staticVersion);
        }

        ClassWriter extWriter = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, FMLLoader.getGameLayer().findLoader("minecraft"));
        ext.accept(extWriter);

        // ---- build the schema-safe owner patch ----
        // Keep exactly the old member set/shape; take new bodies for retained methods,
        // rewriting any references to added methods/fields to go through ext.
        ClassNode patchedOwner = new ClassNode();
        patchedOwner.version = oldNode.version;
        patchedOwner.access = oldNode.access;
        patchedOwner.name = oldNode.name;
        patchedOwner.superName = oldNode.superName;
        patchedOwner.interfaces = oldNode.interfaces;
        patchedOwner.fields = oldNode.fields; // unchanged shape

        patchedOwner.nestHostClass = oldNode.nestHostClass;
        patchedOwner.nestMembers = oldNode.nestMembers;
        patchedOwner.innerClasses = oldNode.innerClasses;
        patchedOwner.permittedSubclasses = oldNode.permittedSubclasses;
        patchedOwner.recordComponents = oldNode.recordComponents;
        patchedOwner.visibleAnnotations = oldNode.visibleAnnotations;
        patchedOwner.invisibleAnnotations = oldNode.invisibleAnnotations;
        patchedOwner.visibleTypeAnnotations = oldNode.visibleTypeAnnotations;
        patchedOwner.invisibleTypeAnnotations = oldNode.invisibleTypeAnnotations;
        patchedOwner.sourceFile = oldNode.sourceFile;
        patchedOwner.sourceDebug = oldNode.sourceDebug;
        patchedOwner.module = oldNode.module;
        patchedOwner.outerClass = oldNode.outerClass;
        patchedOwner.outerMethod = oldNode.outerMethod;
        patchedOwner.outerMethodDesc = oldNode.outerMethodDesc;
        patchedOwner.attrs = oldNode.attrs;

        for (MethodNode oldM : oldNode.methods) {
            String key = oldM.name + oldM.desc;
            MethodNode source = newMethods.getOrDefault(key, oldM);
            MethodNode copy = new MethodNode(source.access, source.name, source.desc,
                    source.signature, source.exceptions == null ? null : source.exceptions.toArray(new String[0]));
            source.accept(copy);
            rewriteMemberAccess(copy.instructions, ownerInternal, extInternal, addedMethodKeys, addedFieldKeys);
            //copy.maxStack = source.maxStack + 2;
            //copy.maxLocals = source.maxLocals + 2;
            patchedOwner.methods.add(copy);
        }

        ClassWriter ownerWriter = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS,FMLLoader.getGameLayer().findLoader("minecraft"));
        patchedOwner.accept(ownerWriter);

        Patch patch = new Patch();
        patch.ownerBytes = ownerWriter.toByteArray();
        patch.extClassBytes = extWriter.toByteArray();
        return patch;
    }

    // Generates: static Object get$name(Object self) / static void set$name(Object self, Object v)
    // backed by the shared WeakHashMap SIDE_TABLE (self -> per-field boxed value, keyed by string).
    private static void addSideTableAccessors(ClassNode ext, String extInternal, String fieldName) {
        String key = extInternal + "#" + fieldName;

        MethodNode getter = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "get$" + fieldName, "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        InsnList gi = getter.instructions;
        gi.add(new FieldInsnNode(Opcodes.GETSTATIC, extInternal, "SIDE_TABLE", "Ljava/util/Map;"));
        gi.add(new VarInsnNode(Opcodes.ALOAD, 0));
        gi.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        gi.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/Map"));
        gi.add(new LdcInsnNode(key));
        gi.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        gi.add(new InsnNode(Opcodes.ARETURN));
        //getter.maxStack = 4;
        //getter.maxLocals = 1;
        ext.methods.add(getter);

        MethodNode setter = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "set$" + fieldName, "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
        InsnList si = setter.instructions;
        // SIDE_TABLE.computeIfAbsent(self, k -> new HashMap<>()).put(key, value)
        si.add(new FieldInsnNode(Opcodes.GETSTATIC, extInternal, "SIDE_TABLE", "Ljava/util/Map;"));
        si.add(new VarInsnNode(Opcodes.ALOAD, 0));
        si.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        si.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/Map"));
        si.add(new VarInsnNode(Opcodes.ASTORE, 2));
        LabelNode haveMap = new LabelNode();
        si.add(new VarInsnNode(Opcodes.ALOAD, 2));
        si.add(new JumpInsnNode(Opcodes.IFNONNULL, haveMap));
        si.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashMap"));
        si.add(new InsnNode(Opcodes.DUP));
        si.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false));
        si.add(new VarInsnNode(Opcodes.ASTORE, 2));
        si.add(new FieldInsnNode(Opcodes.GETSTATIC, extInternal, "SIDE_TABLE", "Ljava/util/Map;"));
        si.add(new VarInsnNode(Opcodes.ALOAD, 0));
        si.add(new VarInsnNode(Opcodes.ALOAD, 2));
        si.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
        si.add(new InsnNode(Opcodes.POP));
        si.add(haveMap);
        si.add(new VarInsnNode(Opcodes.ALOAD, 2));
        si.add(new LdcInsnNode(key));
        si.add(new VarInsnNode(Opcodes.ALOAD, 1));
        si.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
        si.add(new InsnNode(Opcodes.POP));
        si.add(new InsnNode(Opcodes.RETURN));
        //setter.maxStack = 4;
        //setter.maxLocals = 3;
        ext.methods.add(setter);
    }

    // Rewrites, inside a method body:
    //   INVOKEVIRTUAL/SPECIAL owner.addedMethod(...)  -> INVOKESTATIC ext.addedMethod(self, ...)
    //   GETFIELD  owner.addedField  -> INVOKESTATIC ext.get$addedField(self)  (+ CHECKCAST/unbox)
    //   PUTFIELD  owner.addedField  -> INVOKESTATIC ext.set$addedField(self, v) (+ box)
    private static void rewriteMemberAccess(InsnList insns, String owner, String ext,
                                            Set<String> addedMethods, Set<String> addedFields) {
        for (AbstractInsnNode insn : insns.toArray()) {
            if (insn instanceof MethodInsnNode mi && mi.owner.equals(owner)) { //nfc
                String key = mi.name + mi.desc;
                if (!addedMethods.contains(key)) continue;
                if (mi.getOpcode() == Opcodes.INVOKESPECIAL) {
                    if (mi.name.equals("<init>")) {
                        continue;
                    }
                    mi.setOpcode(Opcodes.INVOKESTATIC);
                    mi.desc = "(L" + owner + ";" + mi.desc.substring(1);
                    mi.itf = false;
                    mi.owner = ext;
                } else if (mi.getOpcode() != Opcodes.INVOKESTATIC) {
                    mi.desc = "(L" + owner + ";" + mi.desc.substring(1);
                    mi.setOpcode(Opcodes.INVOKESTATIC);
                    mi.itf = false;
                    mi.owner = ext;
                } else {
                    mi.owner = ext;
                }
            } else if (insn instanceof FieldInsnNode fi && fi.owner.equals(owner)) {
                String fieldKey = fi.name + ":" + fi.desc;
                if (addedFields.contains(fieldKey)) {
                    boolean isGet = fi.getOpcode() == Opcodes.GETFIELD;
                    boolean primitive = isPrimitive(fi.desc);

                    if (isGet) {
                        MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, ext, "get$" + fi.name,
                                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                        insns.set(fi, call);
                        InsnList after = new InsnList();
                        if (primitive) {
                            after.add(unboxInsns(fi.desc));
                        } else if (!fi.desc.equals("Ljava/lang/Object;")) {
                            after.add(new TypeInsnNode(Opcodes.CHECKCAST, internalNameOf(fi.desc)));
                        }
                        insns.insert(call, after); // AFTER the call, not before
                    } else {
                        InsnList before = new InsnList();
                        if (primitive) {
                            before.add(boxInsns(fi.desc));
                        }
                        insns.insertBefore(fi, before);
                        insns.set(fi, new MethodInsnNode(Opcodes.INVOKESTATIC, ext, "set$" + fi.name,
                                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
                    }
                }
            } else if (insn instanceof InvokeDynamicInsnNode indy) {
                Object[] newArgs = indy.bsmArgs.clone();
                boolean changed = false;

                for (int i = 0; i < newArgs.length; i++) {
                    if (newArgs[i] instanceof Handle h && h.getOwner().equals(owner)) {
                        String key = h.getName() + h.getDesc();
                        if (addedMethods.contains(key)) {
                            boolean isStatic = h.getTag() == Opcodes.H_INVOKESTATIC;
                            if(isStatic){
                                newArgs[i] = new Handle(Opcodes.H_INVOKESTATIC, ext, h.getName(), h.getDesc(), false);
                            }
                            else{
                                boolean wasInstance = h.getTag() == Opcodes.H_INVOKESPECIAL
                                        || h.getTag() == Opcodes.H_INVOKEVIRTUAL
                                        || h.getTag() == Opcodes.H_INVOKEINTERFACE;
                                String newDesc = wasInstance
                                        ? "(L" + owner + ";" + h.getDesc().substring(1) // prepend self
                                        : h.getDesc();

                                newArgs[i] = new Handle(Opcodes.H_INVOKESTATIC, ext, h.getName(), newDesc, false);

                                if (wasInstance && indy.desc.startsWith("()")) {
                                    // captureless instance-ref lambda -> now must capture `this`
                                    indy.desc = "(L" + owner + ";" + indy.desc.substring(2);
                                    insns.insertBefore(indy, new VarInsnNode(Opcodes.ALOAD, 0));

                                    LOGGER.info("[MixinHotSwap] WARN: multi-capture lambda not fully handled: {}.{}{}", owner, h.getName(), h.getDesc());
                                }
                                // NOTE: if the lambda already captured other locals (indy.desc didn't
                                // start with "()"), `this` needs to be prepended to the existing
                                // captured-arg list instead — not handled here yet, see caveat below.
                            }
                            changed = true;
                        }
                    }
                }

                if (changed) {
                    indy.bsmArgs = newArgs;
                }
            }
        }
    }

    private static boolean isPrimitive(String desc) {
        return desc.length() == 1;
    }

    private static String internalNameOf(String desc) {
        return desc.substring(1, desc.length() - 1); // strips L...;
    }

    private static InsnList unboxInsns(String desc) {
        InsnList list = new InsnList();
        String wrapper; String method; String retDesc;
        switch (desc) {
            case "I" -> { wrapper = "java/lang/Integer"; method = "intValue"; retDesc = "()I"; }
            case "J" -> { wrapper = "java/lang/Long";    method = "longValue"; retDesc = "()J"; }
            case "F" -> { wrapper = "java/lang/Float";   method = "floatValue"; retDesc = "()F"; }
            case "D" -> { wrapper = "java/lang/Double";  method = "doubleValue"; retDesc = "()D"; }
            case "Z" -> { wrapper = "java/lang/Boolean"; method = "booleanValue"; retDesc = "()Z"; }
            case "B" -> { wrapper = "java/lang/Byte";    method = "byteValue"; retDesc = "()B"; }
            case "C" -> { wrapper = "java/lang/Character"; method = "charValue"; retDesc = "()C"; }
            case "S" -> { wrapper = "java/lang/Short";   method = "shortValue"; retDesc = "()S"; }
            default -> throw new IllegalArgumentException("Unhandled primitive: " + desc);
        }
        list.add(new TypeInsnNode(Opcodes.CHECKCAST, wrapper));
        list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, wrapper, method, retDesc, false));
        return list;
    }

    private static InsnList boxInsns(String desc) {
        InsnList list = new InsnList();
        String wrapper; String argDesc;
        switch (desc) {
            case "I" -> { wrapper = "java/lang/Integer"; argDesc = "(I)Ljava/lang/Integer;"; }
            case "J" -> { wrapper = "java/lang/Long";    argDesc = "(J)Ljava/lang/Long;"; }
            case "F" -> { wrapper = "java/lang/Float";   argDesc = "(F)Ljava/lang/Float;"; }
            case "D" -> { wrapper = "java/lang/Double";  argDesc = "(D)Ljava/lang/Double;"; }
            case "Z" -> { wrapper = "java/lang/Boolean"; argDesc = "(Z)Ljava/lang/Boolean;"; }
            case "B" -> { wrapper = "java/lang/Byte";    argDesc = "(B)Ljava/lang/Byte;"; }
            case "C" -> { wrapper = "java/lang/Character"; argDesc = "(C)Ljava/lang/Character;"; }
            case "S" -> { wrapper = "java/lang/Short";   argDesc = "(S)Ljava/lang/Short;"; }
            default -> throw new IllegalArgumentException("Unhandled primitive: " + desc);
        }
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, wrapper, "valueOf", argDesc, false));
        return list;
    }

    // Minimal boxing/unboxing shim; extend per-type as needed (this covers common cases).
    private static InsnList boxOrCastInsns(boolean isGet, String desc, boolean primitive) {
        InsnList list = new InsnList();
        if (isGet) {
            // after INVOKESTATIC returns Object, unbox/cast to desc — inserted AFTER the call instead;
            // simplified here: leave as Object and let caller CHECKCAST if it's a reference type.
            if (!primitive && !desc.equals("Ljava/lang/Object;")) {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, desc.substring(1, desc.length() - 1)));
            }
        }
        // Primitive boxing on PUTFIELD path is deliberately left as a TODO — box the value
        // (e.g. Integer.valueOf) before calling set$ if the added field is primitive.
        return list;
    }

    private static Map<String, MethodNode> indexMethods(List<MethodNode> methods) {
        Map<String, MethodNode> map = new HashMap<>();
        for (MethodNode m : methods) map.put(m.name + m.desc, m);
        return map;
    }

    private static Map<String, FieldNode> indexFields(List<FieldNode> fields) {
        Map<String, FieldNode> map = new HashMap<>();
        for (FieldNode f : fields) map.put(f.name + ":" + f.desc, f);
        return map;
    }

    private static ClassNode readNode(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, ClassReader.EXPAND_FRAMES);
        return cn;
    }

    private MixinHotSwap() {}

    private static final class SafeClassWriter extends ClassWriter {
        private final ClassLoader loader;

        SafeClassWriter(int flags, ClassLoader loader) {
            super(flags);
            this.loader = loader;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            if (type1.equals(type2)) return type1;
            if (type1.equals("java/lang/Object") || type2.equals("java/lang/Object")) return "java/lang/Object";
            try {
                List<String> chain1 = superChain(type1);
                List<String> chain2 = superChain(type2);
                for (String c : chain1) {
                    if (chain2.contains(c)) return c;
                }
            } catch (Exception ignored) {
                // fall through to Object below rather than throwing mid-write
            }
            return "java/lang/Object";
        }

        private List<String> superChain(String internalName) throws IOException {
            List<String> chain = new ArrayList<>();
            String current = internalName;
            while (current != null && !current.equals("java/lang/Object")) {
                chain.add(current);
                byte[] bytes = readClassBytes(current);
                if (bytes == null) break; // couldn't resolve further, stop safely
                current = new ClassReader(bytes).getSuperName();
            }
            chain.add("java/lang/Object");
            return chain;
        }

        private byte[] readClassBytes(String internalName) throws IOException {
            String resource = internalName + ".class";
            ClassLoader[] candidates = {
                    loader,
                    ClassLoader.getPlatformClassLoader(),
                    ClassLoader.getSystemClassLoader()
            };
            for (ClassLoader cl : candidates) {
                if (cl == null) continue;
                try (InputStream is = cl.getResourceAsStream(resource)) {
                    if (is != null) return is.readAllBytes();
                }
            }
            return null;
        }
    }
}