package cn.ksmcbrigade.nhmj.transformers;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class ModuleLayerHandlerTransformer implements ClassFileTransformer {
    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (!"cpw/mods/modlauncher/ModuleLayerHandler".equals(className)) {
            return null;
        }

        ClassReader cr = new ClassReader(classfileBuffer);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

                if ("addToLayer".equals(name)) {
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                            if (opcode == Opcodes.INVOKEVIRTUAL
                                    && "java/util/EnumMap".equals(owner)
                                    && "containsKey".equals(name)
                                    && "(Ljava/lang/Object;)Z".equals(descriptor)) {

                                mv.visitInsn(Opcodes.POP2);
                                mv.visitInsn(Opcodes.ICONST_0);
                            } else {
                                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                            }
                        }
                    };
                }
                return mv;
            }
        };

        cr.accept(cv, 0);
        return cw.toByteArray();
    }
}
