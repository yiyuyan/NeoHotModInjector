package cn.ksmcbrigade.nhmj.mixin.fix;

import cn.ksmcbrigade.nhmj.NHMJMod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChatComponent.class)
public class ChatComponentMixin {
    @WrapOperation(method = "addMessage(Lnet/minecraft/network/chat/Component;)V",at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V"))
    public void add(ChatComponent instance, Component chatComponent, MessageSignature headerSignature, GuiMessageTag tag, Operation<Void> original){
        try {
            original.call(instance,chatComponent,headerSignature,tag);
        }
        catch (Throwable e){
            NHMJMod.LOGGER.warn("{}:{}",e.getClass(),e.getMessage());
        }
    }
}
