package cn.ksmcbrigade.nhmj.mixin.ex;

import cn.ksmcbrigade.nhmj.NHMJMod;
import cn.ksmcbrigade.nhmj.config.InjectorConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

@Mixin(ModConfigSpec.ConfigValue.class)
public abstract class ModConfigSpecMixin<T> implements Supplier<T> {
    @Shadow
    private @Nullable ModConfigSpec spec;

    @SuppressWarnings("unchecked")
    @Inject(method = "set",at = @At("TAIL"))
    public void set(T value, CallbackInfo ci){
        ModConfigSpec.ConfigValue<T> self = (ModConfigSpec.ConfigValue<T>) ((Object) this);
        if(this.spec!=null
                && this.spec.isLoaded()
                && self.equals(InjectorConfig.MIXIN_TRANSFORM_MODE)
                && self.get().equals(InjectorConfig.MixinTransformMode.NATIVE)
                && !NHMJMod.jvmHooked){
            try {
                NHMJMod.enableAllowEnhancedClassRedefinition();
            } catch (Exception e) {
                NHMJMod.LOGGER.error("Failed to enable AllowEnhancedClassRedefinition runtime.",e);
                InjectorConfig.MIXIN_TRANSFORM_MODE.set(InjectorConfig.MixinTransformMode.TRAMPOLINE);
                if(Minecraft.getInstance().screen instanceof ConfigurationScreen configurationScreen){
                    Minecraft.getInstance().screen = new ConfigurationScreen(configurationScreen.mod,configurationScreen.lastScreen,configurationScreen.sectionScreen);
                    Minecraft.getInstance().getToasts().addToast(new SystemToast(SystemToast.SystemToastId.WORLD_ACCESS_FAILURE, Component.literal("Can't enable NATIVE mode!"),Component.literal("Please use JetBrainsRuntime 21.0.11+1-b1163.116.")));
                }
            }
        }
    }
}
