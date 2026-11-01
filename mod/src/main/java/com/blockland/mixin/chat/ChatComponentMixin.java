package com.blockland.mixin.chat;

import com.blockland.client.hud.chat.ChatHud;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Vanilla's chat while BlockLand draws it (UI spec §3.3): messages are wrapped with BlockLand's font and width, the
 * chat screen's drawing goes to BlockLand's panel, and what is under the mouse (a message's style, its tag, the queued
 * message line) is looked up in BlockLand's layout. Forwarding only; the logic is {@link ChatHud}'s.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @Redirect(method = "addMessageToDisplayQueue", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/ComponentRenderUtils;wrapComponents(Lnet/minecraft/network/chat/FormattedText;ILnet/minecraft/client/gui/Font;)Ljava/util/List;"))
    private List<FormattedCharSequence> blockland$wrap(FormattedText text, int width, Font font) {
        return ChatHud.active() ? ChatHud.wrap(text, width) : ComponentRenderUtils.wrapComponents(text, width, font);
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void blockland$render(GuiGraphics g, int ticks, int mouseX, int mouseY, boolean focused, CallbackInfo ci) {
        if (!ChatHud.active()) return;
        ci.cancel();
        if (focused) ChatHud.renderFocused(g, ticks, mouseX, mouseY);
    }

    @Inject(method = "getClickedComponentStyleAt", at = @At("HEAD"), cancellable = true)
    private void blockland$styleAt(double x, double y, CallbackInfoReturnable<Style> cir) {
        if (ChatHud.active()) cir.setReturnValue(ChatHud.styleAt(x, y));
    }

    @Inject(method = "getMessageTagAt", at = @At("HEAD"), cancellable = true)
    private void blockland$tagAt(double x, double y, CallbackInfoReturnable<GuiMessageTag> cir) {
        if (ChatHud.active()) cir.setReturnValue(ChatHud.tagAt(x, y));
    }

    @Inject(method = "handleChatQueueClicked", at = @At("HEAD"), cancellable = true)
    private void blockland$queueClicked(double x, double y, CallbackInfoReturnable<Boolean> cir) {
        if (ChatHud.active()) cir.setReturnValue(ChatHud.queueClicked(x, y));
    }
}
