package cn.ksmcbrigade.nhmj.utils;

import cn.ksmcbrigade.mr.utils.UnsafeUtils;
import cn.ksmcbrigade.mr.utils.mixin.MixinAgentUtils;
import cn.ksmcbrigade.nhmj.NHMJMod;
import net.neoforged.fml.loading.FMLLoader;

import java.lang.instrument.Instrumentation;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ModuleUtilsAccess {

    @SuppressWarnings("unchecked")
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
