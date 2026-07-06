package cn.ksmcbrigade.nhmj;

import cn.ksmcbrigade.mr.utils.mixin.MixinAgentUtils;
import cn.ksmcbrigade.nhmj.transformers.DeferredMixinConfigRegistrationTransformer;
import cn.ksmcbrigade.nhmj.transformers.ModuleLayerHandlerTransformer;
import cn.ksmcbrigade.nhmj.transformers.dev.AccessibleObjectTransformer;
import cn.ksmcbrigade.nhmj.transformers.dev.ReflectionTransformer;
import cn.ksmcbrigade.nhmj.utils.Injector;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import cpw.mods.modlauncher.ModuleLayerHandler;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.mixin.DeferredMixinConfigRegistration;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.apache.commons.lang3.RandomStringUtils;
import org.slf4j.Logger;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.nio.file.Path;
import java.rmi.UnexpectedException;
import java.util.Arrays;
import java.util.Objects;

@Mod(NHMJMod.MODID)
public class NHMJMod {
    public static final String MODID = "nhmj";
    public static final Logger LOGGER = LogUtils.getLogger();

    public NHMJMod() throws UnmodifiableClassException, ClassNotFoundException, UnexpectedException {
        Instrumentation inst = MixinAgentUtils.getInst();
        if(inst==null) throw new UnexpectedException("Wait,What? How did you get there?");

        inst.addTransformer(new DeferredMixinConfigRegistrationTransformer(),true);
        inst.addTransformer(new ModuleLayerHandlerTransformer(),true);

        inst.retransformClasses(DeferredMixinConfigRegistration.class);
        inst.retransformClasses(ModuleLayerHandler.class);

        if(!FMLLoader.isProduction()){
            //from SuperTransformer 1.1.0
            inst.addTransformer(new AccessibleObjectTransformer(),true);
            inst.addTransformer(new ReflectionTransformer(),true);

            inst.retransformClasses(Class.forName("jdk.internal.reflect.Reflection"));
            inst.retransformClasses(Class.forName("java.lang.reflect.AccessibleObject"));
        }

        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void registerClientCommands(RegisterClientCommandsEvent event){
        event.getDispatcher().register(Commands.literal("inject-mod").executes(context -> {
            context.getSource().sendSystemMessage(Component.literal("Injecting..."));
            new Thread(()->{
                try {
                    Arrays.stream(Objects.requireNonNull(selectMods())).map(File::toPath).forEach(p -> {
                        try {
                            NHMJMod.LOGGER.info("Injecting {} by command",p);
                            Injector.inject(p);
                        } catch (Throwable e) {
                            throw new RuntimeException(e);
                        }
                    });
                    context.getSource().sendSystemMessage(Component.literal("Done."));
                } catch (Exception e) {
                    NHMJMod.LOGGER.error("Failed to inject mods.",e);
                    context.getSource().sendFailure(Component.literal("Error! "+e.getMessage()));
                }
            },"ModInjector-"+ RandomStringUtils.randomNumeric(16)).start();
            return 0;
        }).then(Commands.argument("path", StringArgumentType.string()).executes(context -> {
            context.getSource().sendSystemMessage(Component.literal("Injecting..."));
            new Thread(()->{
                try {
                    NHMJMod.LOGGER.info("Injecting {} by command with pars",StringArgumentType.getString(context, "path"));
                    Injector.inject(Path.of(StringArgumentType.getString(context, "path")));
                    context.getSource().sendSystemMessage(Component.literal("Done."));
                } catch (Throwable e) {
                    NHMJMod.LOGGER.error("Failed to inject mods.",e);
                    context.getSource().sendFailure(Component.literal("Error! "+e.getMessage()));
                }
            },"ModInjector"+ RandomStringUtils.randomNumeric(16)).start();
            return 0;
        })));
    }

    public static File[] selectMods(){
        if(Boolean.parseBoolean(System.getProperty("java.awt.headless"))){
            System.setProperty("java.awt.headless", "false");
        }
        JFrame frame = new JFrame();
        JFileChooser chooser = new JFileChooser(".");
        FileNameExtensionFilter filter = new FileNameExtensionFilter("Choose the mod file.(*.jar & *.zip)","jar","zip");
        chooser.addChoosableFileFilter(filter);
        chooser.setAcceptAllFileFilterUsed(false);
        int flag = chooser.showOpenDialog(frame);
        if(flag == JFileChooser.APPROVE_OPTION){
            return chooser.getSelectedFiles();
        }
        return null;
    }
}
