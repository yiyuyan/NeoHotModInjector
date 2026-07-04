package cn.ksmcbrigade.nhmj.mixin;

import cn.ksmcbrigade.nhmj.utils.Injector;
import com.terraformersmc.mod_menu.gui.ModsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;

@Mixin(ModsScreen.class)
public class ModsScreenMixin {
    @Redirect(method = "lambda$onFilesDrop$13",at = @At(value = "INVOKE", target = "Ljava/nio/file/Files;copy(Ljava/nio/file/Path;Ljava/nio/file/Path;[Ljava/nio/file/CopyOption;)Ljava/nio/file/Path;"))
    public Path injectMod(Path source, Path target, CopyOption[] options) throws IOException {
        Path result = Files.copy(source,target,options);

        try {
            Injector.inject(result);
        } catch (Throwable e) {
            e.printStackTrace();
        }

        return result;
    }
}
