package com.blockland.mixin.chat;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * What BlockLand's chat (UI spec §3.3) reads of vanilla's: the wrapped lines (newest first), how far the history is
 * scrolled and whether something new arrived since. Vanilla keeps the messages, the scrolling and the deletions.
 */
@Mixin(ChatComponent.class)
public interface ChatComponentAccess {
    @Accessor("trimmedMessages")
    List<GuiMessage.Line> blockland$lines();

    @Accessor("chatScrollbarPos")
    int blockland$scroll();

    @Accessor("newMessageSinceScroll")
    boolean blockland$newSinceScroll();
}
