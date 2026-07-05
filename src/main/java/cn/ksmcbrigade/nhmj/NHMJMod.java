package cn.ksmcbrigade.nhmj;

import cn.ksmcbrigade.mr.utils.mixin.MixinAgentUtils;
import cn.ksmcbrigade.nhmj.transformers.DeferredMixinConfigRegistrationTransformer;
import cn.ksmcbrigade.nhmj.transformers.ModuleLayerHandlerTransformer;
import cn.ksmcbrigade.nhmj.transformers.dev.AccessibleObjectTransformer;
import cn.ksmcbrigade.nhmj.transformers.dev.MethodHandleFieldAccessorImplTransformer;
import cn.ksmcbrigade.nhmj.transformers.dev.ReflectionTransformer;
import com.mojang.logging.LogUtils;
import cpw.mods.modlauncher.ModuleLayerHandler;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.mixin.DeferredMixinConfigRegistration;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;

@Mod(NHMJMod.MODID)
public class NHMJMod {
    public static final String MODID = "nhmj";
    public static final Logger LOGGER = LogUtils.getLogger();

    public NHMJMod(IEventBus modEventBus, ModContainer modContainer) throws UnmodifiableClassException, ClassNotFoundException {
        Instrumentation inst = MixinAgentUtils.getInst();
        
        inst.addTransformer(new DeferredMixinConfigRegistrationTransformer(),true);
        inst.addTransformer(new ModuleLayerHandlerTransformer(),true);

        inst.retransformClasses(DeferredMixinConfigRegistration.class);
        inst.retransformClasses(ModuleLayerHandler.class);

        if(!FMLLoader.isProduction()){
            inst.addTransformer(new AccessibleObjectTransformer(),true);
            inst.addTransformer(new MethodHandleFieldAccessorImplTransformer(),true);
            inst.addTransformer(new ReflectionTransformer(),true);

            inst.retransformClasses(Class.forName("jdk.internal.reflect.Reflection"));
            inst.retransformClasses(Class.forName("java.lang.reflect.AccessibleObject"));
            inst.retransformClasses(Class.forName("jdk.internal.reflect.MethodHandleFieldAccessorImpl"));
        }

        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void registerClientCommands(RegisterClientCommandsEvent event){

    }
}
