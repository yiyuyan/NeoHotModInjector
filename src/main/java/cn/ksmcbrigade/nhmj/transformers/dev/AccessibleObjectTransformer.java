package cn.ksmcbrigade.nhmj.transformers.dev;

import java.lang.reflect.AccessibleObject;
import java.security.ProtectionDomain;

import cn.ksmcbrigade.nhmj.transformers.ExportableClassFileTransformer;
import org.objectweb.asm.*;

public class AccessibleObjectTransformer extends ExportableClassFileTransformer {

    public AccessibleObjectTransformer() {
        super(AccessibleObject.class);
    }

    @Override
    public byte[] transformClass(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {

        ClassReader cr = new ClassReader(classfileBuffer);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access,
                                             String name,
                                             String descriptor,
                                             String signature,
                                             String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor,
                        signature, exceptions);
                if (name.equals("checkCanSetAccessible") &&
                        descriptor.equals("(Ljava/lang/Class;Ljava/lang/Class;Z)Z")) {

                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitCode() {
                            mv.visitCode();
                            mv.visitInsn(Opcodes.ICONST_1);
                            mv.visitInsn(Opcodes.IRETURN);
                        }

                        @Override
                        public void visitMaxs(int maxStack, int maxLocals) {}

                        @Override
                        public void visitEnd() {
                            mv.visitEnd();
                        }
                    };
                }
                return mv;
            }
        };
        cr.accept(cv, ClassReader.SKIP_FRAMES);
        return cw.toByteArray();
    }
}