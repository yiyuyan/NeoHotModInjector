package cn.ksmcbrigade.nhmj.transformers;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLLoader;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;

public abstract class ExportableClassFileTransformer implements ClassFileTransformer {

    public static final ArrayList<ExportableClassFileTransformer> transformers = new ArrayList<>();

    public final Class<?> aClass;

    public ExportableClassFileTransformer(Class<?> aClass){
        this.aClass = aClass;

        transformers.add(this);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
        if(className!=null && className.replace("/",".").equals(this.aClass.getName())){
            LogUtils.getLogger().info("[NHMJ] Transforming {}",className);
            byte[] bytes = transformClass(loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
            if(!FMLLoader.isProduction()){
                try {
                    FileUtils.writeByteArrayToFile(new File("debug/"+this.aClass.getSimpleName()+".class"),bytes);
                } catch (IOException e) {
                    LogUtils.getLogger().warn("Failed to export {}: {}",aClass.getSimpleName(),e.getMessage());
                }
            }
            return bytes;
        }
        return ClassFileTransformer.super.transform(loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
    }

    protected abstract byte[] transformClass(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer);

    public static void retransformAllTransformers(Instrumentation instrumentation){
        for (ExportableClassFileTransformer transformer : transformers) {
            if(instrumentation.isModifiableClass(transformer.aClass)){
                try {
                    instrumentation.retransformClasses(transformer.aClass);
                } catch (Throwable e) {
                    LogUtils.getLogger().error("Failed to transform {}: {}:{}",transformer.aClass,e.getClass(),e.getMessage());
                }
            }
            else{
                LogUtils.getLogger().error("The class is not modifiable! {}",transformer.aClass);
            }
        }
    }
}
