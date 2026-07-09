package cn.ksmcbrigade.nhmj;

import cn.ksmcbrigade.mr.utils.mixin.MixinAgentUtils;
import cn.ksmcbrigade.nhmj.config.InjectorConfig;
import cn.ksmcbrigade.nhmj.transformers.DeferredMixinConfigRegistrationTransformer;
import cn.ksmcbrigade.nhmj.transformers.ExportableClassFileTransformer;
import cn.ksmcbrigade.nhmj.transformers.MethodHandlesLookupTransformer;
import cn.ksmcbrigade.nhmj.transformers.ModSorterTransformer;
import cn.ksmcbrigade.nhmj.transformers.dev.AccessibleObjectTransformer;
import cn.ksmcbrigade.nhmj.transformers.dev.ReflectionTransformer;
import cn.ksmcbrigade.nhmj.utils.Injector;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.slf4j.Logger;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.rmi.UnexpectedException;
import java.util.Objects;

@Mod(NHMJMod.MODID)
public class NHMJMod {
    public static final String MODID = "nhmj";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final boolean DEBUG = false;

    public static boolean configLoaded = false;
    public static boolean jvmHooked = false;

    public NHMJMod(IEventBus eventBus, ModContainer modContainer) throws ClassNotFoundException, UnexpectedException {
        Instrumentation inst = MixinAgentUtils.getInst();
        if(inst==null) throw new UnexpectedException("Wait,What? How did you get there?");

        inst.addTransformer(new DeferredMixinConfigRegistrationTransformer(),true);
        inst.addTransformer(new ModSorterTransformer(),true);

        inst.addTransformer(new MethodHandlesLookupTransformer(),true);

        if(!FMLLoader.isProduction()){
            //only for development
            //in protection env,use SuperAccessTransformer 1.1.0
            inst.addTransformer(new AccessibleObjectTransformer(),true);
            inst.addTransformer(new ReflectionTransformer(),true);
        }

        ExportableClassFileTransformer.retransformAllTransformers(inst);

        modContainer.registerConfig(ModConfig.Type.CLIENT, InjectorConfig.CONFIG);
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        new Thread(()->{
            while (Minecraft.getInstance().isRunning()){
                Thread.yield();
                if(Boolean.parseBoolean(System.getProperty("java.awt.headless"))){
                    System.setProperty("java.awt.headless", "false");
                }
            }
        }).start();

        eventBus.addListener(this::configLoadEvent);

        NeoForge.EVENT_BUS.register(this);
    }

    private void configLoadEvent(ModConfigEvent.Loading event) {
        if(event.getConfig().getSpec().equals(InjectorConfig.CONFIG) && !configLoaded){
            configLoaded = true;
            LOGGER.info("Using mixinTransformMode: {}", InjectorConfig.MIXIN_TRANSFORM_MODE.get());
            if(InjectorConfig.MIXIN_TRANSFORM_MODE.get().equals(InjectorConfig.MixinTransformMode.NATIVE)){
                enableAllowEnhancedClassRedefinition();
            }
        }
    }

    public static void enableAllowEnhancedClassRedefinition(){
        try {
            if(!jvmHooked){
                FileUtils.writeByteArrayToFile(new File("hook.dll"), IOUtils.toByteArray(Objects.requireNonNull(NHMJMod.class.getResourceAsStream("/JVMFlagHook.dll"))));
                System.load(new File("hook.dll").getAbsolutePath());
                jvmHooked = true;

                Process process = new ProcessBuilder(
                        new File(System.getProperty("java.home"),"bin/jinfo").getAbsolutePath()
                        ,"-flag"
                        ,"+AllowEnhancedClassRedefinition"
                        ,String.valueOf(ProcessHandle.current().pid())
                ).start();
                process.waitFor();

                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }

                if (process.exitValue()==0){
                    LOGGER.info("Enable AllowEnhancedClassRedefinition successfully!");
                    LOGGER.info("Output: \n{}",output);
                }
                else{
                    throw new RuntimeException("Failed to enable AllowEnhancedClassRedefinition runtime. output:\n"+output);
                }
            }
        } catch (Throwable e) {
            throw new RuntimeException("Failed to enable AllowEnhancedClassRedefinition runtime!",e);
        }
    }

    @SubscribeEvent
    public void registerClientCommands(RegisterClientCommandsEvent event){
        event.getDispatcher().register(Commands.literal("inject-mod").executes(context -> {
            context.getSource().sendSystemMessage(Component.literal("Injecting..."));
            new Thread(()->{
                try {
                    try {
                        Path p = Objects.requireNonNull(selectMod()).toPath();
                        NHMJMod.LOGGER.info("Injecting {} by command",p);
                        Injector.inject(p);
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
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

    public static File selectMod(){
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
            return chooser.getSelectedFile();
        }
        return null;
    }
}
