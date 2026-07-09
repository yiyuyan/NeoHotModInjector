package cn.ksmcbrigade.nhmj.mixin.ex;

import cn.ksmcbrigade.nhmj.NHMJMod;
import cn.ksmcbrigade.nhmj.config.InjectorConfig;
import cn.ksmcbrigade.nhmj.utils.Injector;
import com.terraformersmc.mod_menu.gui.ModsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.RandomStringUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;

@Mixin(ModsScreen.class)
public class ModsScreenMixin {
    @Redirect(method = "lambda$onFilesDrop$13",at = @At(value = "INVOKE", target = "Ljava/nio/file/Files;copy(Ljava/nio/file/Path;Ljava/nio/file/Path;[Ljava/nio/file/CopyOption;)Ljava/nio/file/Path;"))
    public Path injectMod(Path source, Path target, CopyOption[] options) throws IOException {
        Path result = Files.copy(source,target,options);

        Thread thread =  new Thread(()->{
            try {
                NHMJMod.LOGGER.info("Injecting {} from dropped files.",result);
                Injector.inject(result);
            } catch (Throwable e) {
                NHMJMod.LOGGER.error("Failed to inject mod.",e);
            }
        },"DroppedModInjector"+ RandomStringUtils.randomNumeric(16));

        if(InjectorConfig.INJECT_CONFIRM_SCREEN.get()){
            Minecraft.getInstance().screen = new ConfirmScreen((b)->{
                if(b) thread.start();
            }, Component.literal("Injector Confirm"),Component.translatable("Would you like to inject %s into game now?",result.toFile().getName()));
        }
        else{
            thread.start();
        }

        return result;
    }
}
