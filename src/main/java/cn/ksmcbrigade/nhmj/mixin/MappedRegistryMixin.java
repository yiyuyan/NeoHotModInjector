package cn.ksmcbrigade.nhmj.mixin;

import io.netty.util.internal.UnstableApi;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.WritableRegistry;
import net.neoforged.neoforge.registries.BaseMappedRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@SuppressWarnings("UnstableApiUsage")
@UnstableApi
@Deprecated
@Mixin(MappedRegistry.class)
public abstract class MappedRegistryMixin<T> extends BaseMappedRegistry<T> implements WritableRegistry<T> {
    @Shadow
    public boolean frozen;

    @Inject(method = "freeze",at = @At("HEAD"),cancellable = true)
    public void freeze(CallbackInfoReturnable<Registry<T>> cir){
        this.frozen = false;
        cir.setReturnValue(this);
    }

    @Inject(method = "validateWrite*",at = @At("HEAD"),cancellable = true)
    public void assertFrozen(CallbackInfo ci){
        ci.cancel();
    }
}
