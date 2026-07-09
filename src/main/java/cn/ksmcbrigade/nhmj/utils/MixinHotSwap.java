package cn.ksmcbrigade.nhmj.utils;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.logging.FileHandler;
import java.util.logging.Logger;

/**
 * Hot-applies mixin-transformed bytecode to already-loaded classes on stock HotSpot,
 * even when the mixin adds methods/fields (a "schema change" that plain
 * Instrumentation#redefineClasses cannot perform).
 *
 * Strategy: try a direct redefine first (works if the mixin only changed method bodies).
 * If HotSpot rejects it as a schema change, extract every added method/field into a
 * separate, freshly-defined "trampoline" class (`Owner$$MixinExt$N`) and rewrite the
 * owner's bytecode to call into it, leaving the owner's own shape (methods, fields,
 * nest/record/permitted-subclass attributes) byte-for-byte identical to what was already
 * loaded. That keeps the owner's redefine schema-safe while still delivering full
 * schema-changing mixin behavior via the auxiliary class.
 */
public final class MixinHotSwap {

    private static final Map<Class<?>, Class<?>> EXT_CLASSES = new WeakHashMap<>();
    private static int swapCounter = 0;

    private static final Logger LOGGER = Logger.getLogger(MixinHotSwap.class.getSimpleName());

    static {
        try {
            LOGGER.addHandler(new FileHandler("logs/mixinHotSwap-logs.xml",true));
        } catch (IOException e) {
            LOGGER.info("Failed to add file handler for "+MixinHotSwap.class.getSimpleName());
        }
    }

    private MixinHotSwap() {}

    /**
     * @param mixinAgent unused hook slot for triggering your MixinAgent's own bookkeeping
     *                   after a successful swap; safe to pass null.
     */
    public static void replaceMixedClasses(Class<?> clazz, byte[] newBytes,
                                           Instrumentation inst, Object mixinAgent) throws Exception {
        byte[] originalBytes = captureCurrentBytecode(clazz, inst);

        try {
            inst.redefineClasses(new ClassDefinition(clazz, newBytes));
            LOGGER.info("[MixinHotSwap] direct redefine ok: "+ clazz.getName());
            return;
        } catch (UnsupportedOperationException schemaChange) {
            LOGGER.info("[MixinHotSwap] schema change detected for "+ clazz.getName() +
                    ", building trampoline patch...");
        }

        int id = swapCounter++;
        Patch patch = buildTrampolinePatch(clazz, originalBytes, newBytes, id);

        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(clazz, MethodHandles.lookup());

        // Real, named, classloader-visible class — NOT defineHiddenClass. Hidden classes get an
        // internal synthetic name invisible to ordinary symbolic INVOKESTATIC/GETSTATIC resolution,
        // which is exactly what the redefined owner bytecode needs to call into this class by name.
        Class<?> extClass = lookup.defineClass(patch.extClassBytes);
        EXT_CLASSES.put(clazz, extClass);

        // Wire up any super-call MethodHandles. This must happen via a Lookup whose lookupClass is
        // genuinely `clazz` (owner), since only the real subclass has authority to findSpecial a
        // superclass method — the ext class itself is not a legitimate caller for that resolution.
        for (SuperCallSite sc : patch.superCalls) {
            Class<?> targetOwner = Class.forName(sc.targetOwner.replace('/', '.'), false, clazz.getClassLoader());
            MethodType mt = MethodType.fromMethodDescriptorString(sc.desc, clazz.getClassLoader());
            MethodHandle handle = lookup.findSpecial(targetOwner, sc.name, mt, clazz);
            Field f = extClass.getDeclaredField("HANDLE$" + sc.wrapperName);
            f.setAccessible(true); // if this throws InaccessibleObjectException, open the target
            f.set(null, handle);   // module/package to your agent's module first (see ModuleUtilsAccess)
        }

        inst.redefineClasses(new ClassDefinition(clazz, patch.ownerBytes));
        LOGGER.info("[MixinHotSwap] trampoline redefine ok: " + clazz.getName() + " -> " + extClass.getName());
    }

    // =================================================================================
    // Step 1: capture the bytecode currently installed in the JVM for `clazz`
    // =================================================================================

    private static byte[] captureCurrentBytecode(Class<?> clazz, Instrumentation inst) throws Exception {
        byte[][] holder = new byte[1][];
        ClassFileTransformer capture = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> beingRedefined,
                                    ProtectionDomain pd, byte[] buf) {
                if (beingRedefined == clazz) holder[0] = buf;
                return null; // observe only, don't actually change anything
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

    // =================================================================================
    // Data carriers
    // =================================================================================

    private static final class Patch {
        byte[] ownerBytes;
        byte[] extClassBytes;
        List<SuperCallSite> superCalls;
    }

    /** Describes one super/interface-super call relocated out of the owner class. */
    private static final class SuperCallSite {
        final String wrapperName;
        final String targetOwner; // internal name of the class actually declaring the super method
        final String name;
        final String desc;        // ORIGINAL descriptor, without the self param

        SuperCallSite(String wrapperName, String targetOwner, String name, String desc) {
            this.wrapperName = wrapperName;
            this.targetOwner = targetOwner;
            this.name = name;
            this.desc = desc;
        }
    }

    /** Bridge on the owner class that republishes a private existing method as public. */
    private static final class BridgeInfo {
        final String bridgeName;
        final boolean targetWasStatic;

        BridgeInfo(String bridgeName, boolean targetWasStatic) {
            this.bridgeName = bridgeName;
            this.targetWasStatic = targetWasStatic;
        }
    }

    // =================================================================================
    // Step 2: diff old vs new, extract added members into the ext class
    // =================================================================================

    private static Patch buildTrampolinePatch(Class<?> clazz, byte[] oldBytes, byte[] newBytes, int id) {
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

        // ---- bridge pre-scan: added methods calling EXISTING private methods on the same owner ----
        // (calls to OTHER added methods are excluded — those get redirected straight to ext by
        //  rewriteMemberAccess; calls to a different owner (superclass) are excluded too — those
        //  are handled by the separate MethodHandle super-call mechanism below.)
        Map<String, BridgeInfo> bridgeMap = new HashMap<>();
        List<MethodNode> ownerBridges = new ArrayList<>();
        int[] bridgeCounter = {0};

        for (String key : addedMethodKeys) {
            MethodNode m = newMethods.get(key);
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode mi)) continue;
                if (mi.getOpcode() != Opcodes.INVOKESPECIAL) continue;
                if (mi.name.equals("<init>")) continue;
                if (!mi.owner.equals(ownerInternal)) continue; // real super call, not a bridge case
                String targetKey = mi.name + mi.desc;
                if (addedMethodKeys.contains(targetKey)) continue; // -> ext directly, no bridge

                String bridgeKey = mi.owner + "." + mi.name + mi.desc;
                if (bridgeMap.containsKey(bridgeKey)) continue;

                MethodNode originalTarget = oldMethods.get(targetKey);
                if (originalTarget == null) continue; // safety: nothing sane to bridge to

                boolean targetWasStatic = (originalTarget.access & Opcodes.ACC_STATIC) != 0;
                String bridgeName = "bridge$" + mi.name + "$" + (bridgeCounter[0]++);
                bridgeMap.put(bridgeKey, new BridgeInfo(bridgeName, targetWasStatic));

                String bridgeDesc = targetWasStatic
                        ? mi.desc
                        : ("(L" + ownerInternal + ";" + mi.desc.substring(1));
                MethodNode bridge = new MethodNode(
                        Opcodes.ACC_PUBLIC | (targetWasStatic ? Opcodes.ACC_STATIC : 0),
                        bridgeName, bridgeDesc, null, null);
                InsnList bi = bridge.instructions;
                int slot = 0;
                if (!targetWasStatic) {
                    bi.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    slot = 1;
                }
                for (Type t : Type.getArgumentTypes(mi.desc)) {
                    bi.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot));
                    slot += t.getSize();
                }
                bi.add(new MethodInsnNode(
                        targetWasStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL,
                        ownerInternal, mi.name, mi.desc, false));
                bi.add(new InsnNode(Type.getReturnType(mi.desc).getOpcode(Opcodes.IRETURN)));
                ownerBridges.add(bridge);
            }
        }

        // ---- build the ext class ----
        ClassNode ext = new ClassNode();
        ext.version = oldNode.version;
        ext.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER | Opcodes.ACC_FINAL;
        ext.name = extInternal;
        // Same superclass/interfaces as owner: NOT so ext can be instantiated (it never is), but
        // so any super.xxx()-style verifier checks that might reference ext's own hierarchy resolve
        // sanely. The actual super-CALL mechanism below uses MethodHandles regardless, since a raw
        // invokespecial from ext to a superclass method fails verification no matter what ext extends
        // (invokespecial's "current class assignable to reference class" check requires ext itself to
        // be assignable to the receiver, which it structurally cannot be for a sibling class).
        ext.superName = oldNode.superName;
        ext.interfaces = oldNode.interfaces;

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
        ext.methods.add(clinit);

        // added fields -> side-table accessors (instance) or hosted directly (static)
        for (String key : addedFieldKeys) {
            FieldNode fn = newFields.get(key);
            if ((fn.access & Opcodes.ACC_STATIC) != 0) {
                ext.fields.add(fn);
            } else {
                addSideTableAccessors(ext, extInternal, fn.name);
            }
        }

        // added methods -> static methods on ext (self prepended for former instance methods)
        List<SuperCallSite> superCalls = new ArrayList<>();
        int[] superCounter = {0};

        for (String key : addedMethodKeys) {
            MethodNode m = newMethods.get(key);

            MethodNode staticVersion;
            if ((m.access & Opcodes.ACC_STATIC) != 0) {
                // Already static (e.g. handler for a static target method). Still must be relocated
                // with private/protected stripped: private-in-its-original-class means nothing once
                // this method lives on a different class (ext) than the one it was compiled for.
                int newAccess = (m.access & ~Opcodes.ACC_PRIVATE & ~Opcodes.ACC_PROTECTED) | Opcodes.ACC_PUBLIC;
                staticVersion = new MethodNode(newAccess, m.name, m.desc, m.signature,
                        m.exceptions == null ? null : m.exceptions.toArray(new String[0]));
                m.accept(staticVersion);
            } else {
                // Instance method -> static with explicit self as first param. Local slot 0 already
                // held `this`'s type, so no local-index shifting is needed anywhere in the body.
                staticVersion = new MethodNode(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        m.name,
                        "(L" + ownerInternal + ";" + m.desc.substring(1),
                        m.signature,
                        m.exceptions == null ? null : m.exceptions.toArray(new String[0]));
                m.accept(staticVersion);
            }

            // Pre-pass: redirect any genuine super/interface-super calls to a MethodHandle wrapper,
            // BEFORE the general rewrite below runs. This only applies to code now living in ext —
            // retained methods that stay on the owner keep their legitimate super-call relationship
            // untouched, since the owner class really is a subtype of that superclass.
            int superCallsStart = superCalls.size();
            for (AbstractInsnNode insn : staticVersion.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode mi)) continue;
                if (mi.getOpcode() != Opcodes.INVOKESPECIAL) continue;
                if (mi.name.equals("<init>")) continue;
                if (mi.owner.equals(ownerInternal)) continue; // handled by the added/bridge paths below

                String wrapperName = "invokeSuper$" + mi.name + "$" + (superCounter[0]++);
                superCalls.add(new SuperCallSite(wrapperName, mi.owner, mi.name, mi.desc));
                mi.owner = extInternal;
                mi.name = wrapperName;
                mi.desc = "(L" + ownerInternal + ";" + mi.desc.substring(1);
                mi.setOpcode(Opcodes.INVOKESTATIC);
            }

            // Generate the wrapper method + handle field for ONLY the entries added just now —
            // NOT the whole accumulated `superCalls` list, or earlier wrappers get duplicated
            // (ClassFormatError: duplicate field/method) on every subsequent added method processed.
            for (int i = superCallsStart; i < superCalls.size(); i++) {
                SuperCallSite sc = superCalls.get(i);

                FieldNode handleField = new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        "HANDLE$" + sc.wrapperName, "Ljava/lang/invoke/MethodHandle;", null, null);
                ext.fields.add(handleField);

                Type[] argTypes = Type.getArgumentTypes(sc.desc);
                String wrapperDesc = "(L" + ownerInternal + ";" + sc.desc.substring(1);
                MethodNode wrapper = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        sc.wrapperName, wrapperDesc, null, null);
                InsnList wi = wrapper.instructions;
                wi.add(new FieldInsnNode(Opcodes.GETSTATIC, extInternal, "HANDLE$" + sc.wrapperName,
                        "Ljava/lang/invoke/MethodHandle;"));
                wi.add(new VarInsnNode(Opcodes.ALOAD, 0)); // self
                int slot = 1;
                for (Type t : argTypes) {
                    wi.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot));
                    slot += t.getSize();
                }
                wi.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle",
                        "invoke", wrapperDesc, false));
                wi.add(new InsnNode(Type.getReturnType(sc.desc).getOpcode(Opcodes.IRETURN)));
                ext.methods.add(wrapper);
            }

            // General rewrite: added-field access, calls to other added methods, calls to bridges,
            // and lambda invokedynamic handles referencing added methods.
            rewriteMemberAccess(staticVersion.instructions, ownerInternal, extInternal,
                    addedMethodKeys, addedFieldKeys, bridgeMap);

            ext.methods.add(staticVersion);
        }
        
        Set<String> addedInterfaces = new LinkedHashSet<>(newNode.interfaces);
        addedInterfaces.removeAll(oldNode.interfaces);
        if (!addedInterfaces.isEmpty()) {
            throw new UnsupportedOperationException(
                    "Cannot hot-swap " + ownerInternal + ": mixin adds interface(s) " + addedInterfaces
                            + " (likely an @Implements/accessor-interface mixin). Interface changes require a full "
                            + "restart or DCEVM/enhanced-hotswap — the trampoline approach cannot satisfy instanceof/"
                            + "checkcast against the already-loaded Class object.");
        }

        ClassWriter extWriter = new SafeClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, clazz.getClassLoader());
        ext.accept(extWriter);

        // ---- build the schema-safe owner patch ----
        ClassNode patchedOwner = new ClassNode();
        patchedOwner.version = oldNode.version;
        patchedOwner.access = oldNode.access;
        patchedOwner.name = oldNode.name;
        patchedOwner.superName = oldNode.superName;
        patchedOwner.interfaces = oldNode.interfaces;
        patchedOwner.fields = oldNode.fields; // unchanged shape

        // Copy every attribute HotSpot's redefinition schema check is sensitive to. Leaving any of
        // these null/default when the original class actually had them is itself a schema change
        // ("attempted to change NestHost/NestMembers/Record/PermittedSubclasses attribute").
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
            MethodNode source = newMethods.containsKey(key) ? newMethods.get(key) : oldM;
            MethodNode copy = new MethodNode(source.access, source.name, source.desc,
                    source.signature, source.exceptions == null ? null : source.exceptions.toArray(new String[0]));
            source.accept(copy);
            rewriteMemberAccess(copy.instructions, ownerInternal, extInternal,
                    addedMethodKeys, addedFieldKeys, bridgeMap);
            patchedOwner.methods.add(copy);
        }

        // Bridges live on the owner itself (public wrappers around originally-private members),
        // so ext can call them legally across the class boundary.
        patchedOwner.methods.addAll(ownerBridges);

        ClassWriter ownerWriter = new SafeClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, clazz.getClassLoader());
        patchedOwner.accept(ownerWriter);

        Patch patch = new Patch();
        patch.ownerBytes = ownerWriter.toByteArray();
        patch.extClassBytes = extWriter.toByteArray();
        patch.superCalls = superCalls;
        return patch;
    }

    // =================================================================================
    // Side-table accessors for added instance fields
    // =================================================================================

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
        ext.methods.add(getter);

        MethodNode setter = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "set$" + fieldName, "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
        InsnList si = setter.instructions;
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
        ext.methods.add(setter);
    }

    // =================================================================================
    // Call-site / field-access rewriting
    // =================================================================================

    private static void rewriteMemberAccess(InsnList insns, String owner, String ext,
                                            Set<String> addedMethods, Set<String> addedFields,
                                            Map<String, BridgeInfo> bridgeMap) {
        for (AbstractInsnNode insn : insns.toArray()) {

            if (insn instanceof MethodInsnNode mi && mi.owner.equals(owner)) {
                String key = mi.name + mi.desc;

                if (addedMethods.contains(key) && mi.getOpcode() != Opcodes.INVOKESTATIC) {
                    // call to a method that got extracted to ext -> redirect there directly
                    mi.owner = ext;
                    mi.desc = "(L" + owner + ";" + mi.desc.substring(1);
                    mi.setOpcode(Opcodes.INVOKESTATIC);
                    mi.itf = false;
                } else if (mi.getOpcode() == Opcodes.INVOKESPECIAL && !mi.name.equals("<init>")) {
                    // call to an EXISTING (possibly private) method on the same owner ->
                    // redirect through its public bridge, if one was generated for it
                    BridgeInfo bi = bridgeMap.get(mi.owner + "." + mi.name + mi.desc);
                    if (bi != null) {
                        mi.name = bi.bridgeName;
                        if (bi.targetWasStatic) {
                            mi.setOpcode(Opcodes.INVOKESTATIC);
                        } else {
                            mi.desc = "(L" + owner + ";" + mi.desc.substring(1);
                            mi.setOpcode(Opcodes.INVOKESTATIC);
                        }
                    }
                    // else: legitimate same-class private call untouched by these edits — leave as-is
                }

            } else if (insn instanceof FieldInsnNode fi && fi.owner.equals(owner)) {
                String fieldKey = fi.name + ":" + fi.desc;
                if (!addedFields.contains(fieldKey)) continue;

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
                    insns.insert(call, after); // cast/unbox AFTER the call returns, not before
                } else {
                    InsnList before = new InsnList();
                    if (primitive) {
                        before.add(boxInsns(fi.desc));
                    }
                    insns.insertBefore(fi, before);
                    insns.set(fi, new MethodInsnNode(Opcodes.INVOKESTATIC, ext, "set$" + fi.name,
                            "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
                }

            } else if (insn instanceof InvokeDynamicInsnNode indy) {
                Object[] newArgs = indy.bsmArgs.clone();
                boolean changed = false;

                for (int i = 0; i < newArgs.length; i++) {
                    if (!(newArgs[i] instanceof Handle h) || !h.getOwner().equals(owner)) continue;
                    String key = h.getName() + h.getDesc();
                    if (!addedMethods.contains(key)) continue;

                    boolean wasInstance = h.getTag() == Opcodes.H_INVOKESPECIAL
                            || h.getTag() == Opcodes.H_INVOKEVIRTUAL
                            || h.getTag() == Opcodes.H_INVOKEINTERFACE;
                    String newDesc = wasInstance
                            ? "(L" + owner + ";" + h.getDesc().substring(1)
                            : h.getDesc();

                    newArgs[i] = new Handle(Opcodes.H_INVOKESTATIC, ext, h.getName(), newDesc, false);
                    changed = true;

                    if (wasInstance && indy.desc.startsWith("()")) {
                        // captureless instance-ref lambda -> now must capture `this`
                        indy.desc = "(L" + owner + ";" + indy.desc.substring(2);
                        insns.insertBefore(indy, new VarInsnNode(Opcodes.ALOAD, 0));
                    } else if (wasInstance) {
                        System.err.println("[MixinHotSwap] WARNING: multi-capture lambda not fully "
                                + "handled: " + owner + "." + h.getName() + h.getDesc());
                    }
                }

                if (changed) {
                    indy.bsmArgs = newArgs;
                }
            }
        }
    }

    // =================================================================================
    // Primitive box/unbox helpers
    // =================================================================================

    private static boolean isPrimitive(String desc) {
        return desc.length() == 1;
    }

    private static String internalNameOf(String desc) {
        return desc.substring(1, desc.length() - 1); // strips leading L / trailing ;
    }

    private static InsnList unboxInsns(String desc) {
        InsnList list = new InsnList();
        String wrapper, method, retDesc;
        switch (desc) {
            case "I" -> { wrapper = "java/lang/Integer";   method = "intValue";     retDesc = "()I"; }
            case "J" -> { wrapper = "java/lang/Long";      method = "longValue";    retDesc = "()J"; }
            case "F" -> { wrapper = "java/lang/Float";     method = "floatValue";   retDesc = "()F"; }
            case "D" -> { wrapper = "java/lang/Double";    method = "doubleValue";  retDesc = "()D"; }
            case "Z" -> { wrapper = "java/lang/Boolean";   method = "booleanValue"; retDesc = "()Z"; }
            case "B" -> { wrapper = "java/lang/Byte";      method = "byteValue";    retDesc = "()B"; }
            case "C" -> { wrapper = "java/lang/Character"; method = "charValue";    retDesc = "()C"; }
            case "S" -> { wrapper = "java/lang/Short";     method = "shortValue";   retDesc = "()S"; }
            default -> throw new IllegalArgumentException("Unhandled primitive: " + desc);
        }
        list.add(new TypeInsnNode(Opcodes.CHECKCAST, wrapper));
        list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, wrapper, method, retDesc, false));
        return list;
    }

    private static InsnList boxInsns(String desc) {
        InsnList list = new InsnList();
        String wrapper, argDesc;
        switch (desc) {
            case "I" -> { wrapper = "java/lang/Integer";   argDesc = "(I)Ljava/lang/Integer;"; }
            case "J" -> { wrapper = "java/lang/Long";      argDesc = "(J)Ljava/lang/Long;"; }
            case "F" -> { wrapper = "java/lang/Float";     argDesc = "(F)Ljava/lang/Float;"; }
            case "D" -> { wrapper = "java/lang/Double";    argDesc = "(D)Ljava/lang/Double;"; }
            case "Z" -> { wrapper = "java/lang/Boolean";   argDesc = "(Z)Ljava/lang/Boolean;"; }
            case "B" -> { wrapper = "java/lang/Byte";      argDesc = "(B)Ljava/lang/Byte;"; }
            case "C" -> { wrapper = "java/lang/Character"; argDesc = "(C)Ljava/lang/Character;"; }
            case "S" -> { wrapper = "java/lang/Short";     argDesc = "(S)Ljava/lang/Short;"; }
            default -> throw new IllegalArgumentException("Unhandled primitive: " + desc);
        }
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, wrapper, "valueOf", argDesc, false));
        return list;
    }

    // =================================================================================
    // Misc helpers
    // =================================================================================

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

    /**
     * ClassWriter.getCommonSuperClass() by default resolves types via Class.forName using the
     * classloader that loaded ClassWriter itself — which typically can't see Minecraft's classes
     * in a NeoForge/ModLauncher per-module ModuleClassLoader setup, and can throw or produce
     * incorrect results deep inside ASM's frame-merge computation (e.g. NegativeArraySizeException).
     * This override resolves the hierarchy by reading raw class bytes from the target's own
     * classloader instead, avoiding Class.forName/reflection entirely.
     */
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
                // fall through rather than throwing mid-write
            }
            return "java/lang/Object";
        }

        private List<String> superChain(String internalName) throws IOException {
            List<String> chain = new ArrayList<>();
            String current = internalName;
            while (current != null && !current.equals("java/lang/Object")) {
                chain.add(current);
                byte[] bytes = readClassBytes(current);
                if (bytes == null) break;
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
