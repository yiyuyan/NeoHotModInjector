package cn.ksmcbrigade.nhmj.mixin.ex;

import cn.ksmcbrigade.nhmj.NHMJMod;
import cn.ksmcbrigade.nhmj.utils.Injector;
import com.terraformersmc.mod_menu.gui.ModsScreen;
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

        new Thread(()->{
            try {
                NHMJMod.LOGGER.info("Injecting {} from dropped files.",result);
                Injector.inject(result);
            } catch (Throwable e) {
                NHMJMod.LOGGER.error("Failed to inject mod.",e);
            }
        },"DroppedModInjector"+ RandomStringUtils.randomNumeric(16)).start();

        return result;
    }
}
