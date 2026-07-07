package cn.ksmcbrigade.nhmj.transformers;

import net.neoforged.fml.loading.ModSorter;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.security.ProtectionDomain;

public class ModSorterTransformer extends ExportableClassFileTransformer {

    public ModSorterTransformer() {
        super(ModSorter.class);
    }

    @Override
    public byte[] transformClass(ClassLoader loader, String className,
                                 Class<?> classBeingRedefined, ProtectionDomain pd,
                                 byte[] classfileBuffer) {
        ClassNode classNode = new ClassNode();
        ClassReader cr = new ClassReader(classfileBuffer);
        cr.accept(classNode, 0);

        for (MethodNode method : classNode.methods) {
            if ("verifyDependencyVersions".equals(method.name) && method.desc.startsWith("()")) {
                InsnList insns = new InsnList();

                insns.add(new TypeInsnNode(Opcodes.NEW,
                        "net/neoforged/fml/loading/ModSorter$DependencyResolutionResult"));
                insns.add(new InsnNode(Opcodes.DUP));

                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "java/util/Collections", "emptySet", "()Ljava/util/Set;", false));
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "java/util/Collections", "emptySet", "()Ljava/util/Set;", false));
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "java/util/Collections", "emptySet", "()Ljava/util/Set;", false));
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "java/util/Collections", "emptyMap", "()Ljava/util/Map;", false));

                insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                        "net/neoforged/fml/loading/ModSorter$DependencyResolutionResult",
                        "<init>",
                        "(Ljava/util/Collection;Ljava/util/Collection;Ljava/util/Collection;Ljava/util/Map;)V",
                        false));

                insns.add(new InsnNode(Opcodes.ARETURN));

                method.instructions = insns;
                method.maxStack = 6;
                method.maxLocals = 1;
                method.tryCatchBlocks.clear();
                if (method.localVariables != null) {
                    method.localVariables.clear();
                }
                break;
            }
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        classNode.accept(cw);
        return cw.toByteArray();
    }
}