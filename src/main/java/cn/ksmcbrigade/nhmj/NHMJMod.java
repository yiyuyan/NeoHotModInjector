package cn.ksmcbrigade.nhmj;

import cn.ksmcbrigade.mr.utils.mixin.MixinAgentUtils;
import cn.ksmcbrigade.nhmj.transformers.DeferredMixinConfigRegistrationTransformer;
import cn.ksmcbrigade.nhmj.transformers.ModuleLayerHandlerTransformer;
import com.mojang.logging.LogUtils;
import cpw.mods.modlauncher.ModuleLayerHandler;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.mixin.DeferredMixinConfigRegistration;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.lang.instrument.UnmodifiableClassException;

@Mod(NHMJMod.MODID)
public class NHMJMod {
    public static final String MODID = "nhmj";
    private static final Logger LOGGER = LogUtils.getLogger();

    public NHMJMod(IEventBus modEventBus, ModContainer modContainer) throws UnmodifiableClassException {
        MixinAgentUtils.getInst().addTransformer(new DeferredMixinConfigRegistrationTransformer(),true);
        MixinAgentUtils.getInst().addTransformer(new ModuleLayerHandlerTransformer(),true);
        MixinAgentUtils.getInst().retransformClasses(DeferredMixinConfigRegistration.class);
        MixinAgentUtils.getInst().retransformClasses(ModuleLayerHandler.class);
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void registerClientCommands(RegisterClientCommandsEvent event){

    }
}
