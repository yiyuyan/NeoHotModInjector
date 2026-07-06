package cn.ksmcbrigade.nhmj;

import cn.ksmcbrigade.mr.utils.mixin.MixinAgentUtils;
import cn.ksmcbrigade.nhmj.transformers.DeferredMixinConfigRegistrationTransformer;
import cn.ksmcbrigade.nhmj.transformers.ModuleLayerHandlerTransformer;
import cn.ksmcbrigade.nhmj.transformers.dev.AccessibleObjectTransformer;
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
import java.lang.invoke.MethodHandles;
import java.rmi.UnexpectedException;

@Mod(NHMJMod.MODID)
public class NHMJMod {
    public static final String MODID = "nhmj";
    public static final Logger LOGGER = LogUtils.getLogger();

    public NHMJMod(IEventBus modEventBus, ModContainer modContainer) throws UnmodifiableClassException, ClassNotFoundException, UnexpectedException {
        Instrumentation inst = MixinAgentUtils.getInst();
        if(inst==null) throw new UnexpectedException("Wait,What? How did you get there?");

        inst.addTransformer(new DeferredMixinConfigRegistrationTransformer(),true);
        inst.addTransformer(new ModuleLayerHandlerTransformer(),true);

        inst.retransformClasses(DeferredMixinConfigRegistration.class);
        inst.retransformClasses(ModuleLayerHandler.class);

        inst.retransformClasses(MethodHandles.class);

        if(!FMLLoader.isProduction()){
            inst.addTransformer(new AccessibleObjectTransformer(),true);
            inst.addTransformer(new ReflectionTransformer(),true);

            inst.retransformClasses(Class.forName("jdk.internal.reflect.Reflection"));
            inst.retransformClasses(Class.forName("java.lang.reflect.AccessibleObject"));
        }

        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void registerClientCommands(RegisterClientCommandsEvent event){

    }
}
