package com.blockland.games.common;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Pieces of chat and action-bar text every match game shares (translated on each player's client). */
public final class MatchText {
    /** " | " between the parts of an action bar. */
    public static final Component SEPARATOR = Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY);

    private MatchText() {
    }

    /** "---------- 对局结束 ----------" and a line break: the first line of a match summary in chat. */
    public static MutableComponent summaryHeader(ChatFormatting color) {
        return Component.empty()
                .append(Component.literal("----------").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.STRIKETHROUGH))
                .append(" ")
                .append(Component.translatable("blockland.arena.summary.title").withStyle(color, ChatFormatting.BOLD))
                .append(" ")
                .append(Component.literal("----------").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.STRIKETHROUGH))
                .append("\n");
    }

    /** "地图加载中…" for the action bar while a match loads. */
    public static Component loadingMap() {
        return Component.translatable("blockland.arena.loading_map").withStyle(ChatFormatting.YELLOW);
    }
}
