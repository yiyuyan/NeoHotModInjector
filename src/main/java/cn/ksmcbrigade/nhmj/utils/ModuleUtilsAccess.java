package cn.ksmcbrigade.nhmj.utils;

import cn.ksmcbrigade.mr.utils.UnsafeUtils;
import cn.ksmcbrigade.mr.utils.mixin.MixinAgentUtils;
import cn.ksmcbrigade.nhmj.NHMJMod;
import net.neoforged.fml.loading.FMLLoader;

import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static cn.ksmcbrigade.mr.utils.UnsafeUtils.getFieldValue;

public class ModuleUtilsAccess {

    public static final MethodHandles.Lookup lookup = getFieldValue(MethodHandles.Lookup.class, "IMPL_LOOKUP", MethodHandles.Lookup.class);

    public static void implAddReads(Module module,Module other) throws Throwable {
        MethodHandle methodHandle = lookup.findVirtual(Module.class,"implAddReads", MethodType.methodType(Void.TYPE,Module.class));
        methodHandle.invokeExact(module,other);
    }

    public static void addReads(Module module,Module other) throws Throwable {
        MethodHandle methodHandle = lookup.findStatic(Module.class,"addReads0", MethodType.methodType(void.class, Module.class, Module.class));
        methodHandle.invokeExact(module,other);
    }

    public static void addIntoReads(Module module,Module other) throws NoSuchFieldException, IllegalAccessException {
        Field field = Module.class.getDeclaredField("reads");
        field.setAccessible(true);
        Set<Module> reads = (Set<Module>) field.get(module);
        if(reads==null) reads = new HashSet<>();
        Set<Module> newReads = new HashSet<>(reads);
        newReads.add(module);
        field.set(module,newReads);
    }

    public static Set<Module> getReads(Module module){
        Set<Module> moduleSet = UnsafeUtils.getFieldValue(module,"reads",Set.class);
        if(moduleSet==null){
            moduleSet = new HashSet<>();
            NHMJMod.LOGGER.warn("The reads of {} is null!",moduleSet);
        }
        return moduleSet;
    }

    public static void redefineModuleToAddAllReads(Module module) {
        Instrumentation inst = MixinAgentUtils.getInst();
        if(inst==null) throw new NullPointerException("The instrumentation from runtime mixin agent is null.How did you do it?");
        Set<Module> allModules = new HashSet<>();

        for (Module module1 : FMLLoader.getGameLayer().modules()) {
            allModules.addAll(getReads(module1));
        }

        Set<Module> extraReads = new HashSet<>();
        for (Module m : allModules) {
            if (m.isNamed() && m != module) {
                extraReads.add(m);
            }
        }

        inst.redefineModule(module, extraReads, Map.of(), Map.of(), Set.of(), Map.of());
    }
}
