package com.blockland.client.profile;

import com.blockland.BlockLand;
import com.blockland.anim.ColorMath;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.blockland.common.RankTier;
import com.blockland.profile.NameCardCatalog;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3x2f;

import javax.annotation.Nullable;
import java.util.Locale;

import static com.blockland.client.ui.Tr.tr;

/**
 * Draws name cards ({@link NameCards.Painter}): the card's texture — one frame of its strip for animated cards — then
 * its shader effect when it has one ({@code aurora}, {@code holo}, {@code neon}, {@code inferno}: {@code core/name_card}),
 * then a shade on the left for the name and the tier badge on the right. Works at any size and in the lobby.
 */
public final class NameCardRenderer implements NameCards.Painter {
    /** Pixel size of one frame of every card texture (gen_name_cards.py). */
    static final int TEX_W = 384, TEX_H = 120;

    private static final RenderPipeline EFFECT = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
            .withLocation(BlockLand.id("pipeline/name_card"))
            .withVertexShader(BlockLand.id("core/name_card"))
            .withFragmentShader(BlockLand.id("core/name_card"))
            .build();

    @Override
    public void draw(GuiGraphics g, @Nullable String cardId, int x, int y, int w, int h, String name, @Nullable RankTier tier, float time) {
        if (w <= 2 || h <= 2) return;
        NameCardCatalog.Card card = NameCardCatalog.get().orDefault(cardId);
        if (card == null) {
            NameCards.Painter.PLAIN.draw(g, cardId, x, y, w, h, name, tier, time);
        } else {
            ResourceLocation texture = BlockLand.id(card.texture);
            int frames = Math.max(1, card.frames);
            int frame = frames == 1 ? 0 : (int) (time * Math.max(0.1f, card.fps)) % frames;
            g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, frame * TEX_H, w, h, TEX_W, TEX_H, TEX_W, TEX_H * frames);
            int effect = effectId(card.effect);
            if (effect > 0) effect(g, texture, x, y, w, h, effect, frames, time);
            if ("legendary".equals(card.rarity)) Draw.outline(g, x, y, w, h, ColorMath.withAlpha(0xFFB040, 120 + (int) (80 * Math.sin(time * 3))));
        }
        overlay(g, card, x, y, w, h, name, tier);
    }

    /** The name (left, on a soft shade) and the tier badge (right). Sizes follow the card's height. */
    private static void overlay(GuiGraphics g, @Nullable NameCardCatalog.Card card, int x, int y, int w, int h, String name, @Nullable RankTier tier) {
        float scale = Math.max(0.7f, h / 44f);
        Draw.fadeX(g, x, y, Math.max(1, w * 2 / 3), h, 0x70000000, true);
        int text = card == null ? 0xFFFFFFFF : color(card.text);
        float nameY = y + h - UiFont.LINE_HEIGHT * scale - Math.max(3, h * 0.12f);
        String shown = Draw.fit(name, (w * 0.62f) / scale);
        Draw.text(g, shown, x + Math.max(4, h * 0.14f), nameY, scale, text, true);
        if (tier == null) return;
        float badgeScale = Math.max(0.6f, scale * 0.75f);
        String label = tr(tier.langKey());
        int bw = (int) (UiFont.width(label) * badgeScale) + (int) (10 * badgeScale) + 6;
        int bh = (int) (UiFont.LINE_HEIGHT * badgeScale) + 5;
        int bx = x + w - bw - Math.max(4, (int) (h * 0.12f)), by = y + Math.max(3, (int) (h * 0.12f));
        Draw.rect(g, bx, by, bw, bh, 0xB0101418);
        Draw.outline(g, bx, by, bw, bh, ColorMath.withAlpha(tier.color, 200));
        int gem = (int) (6 * badgeScale);
        g.fill(bx + 3, by + (bh - gem) / 2, bx + 3 + gem, by + (bh - gem) / 2 + gem, tier.color);
        Draw.text(g, label, bx + 5 + gem, by + 3, badgeScale, tier.color, false);
    }

    private static void effect(GuiGraphics g, ResourceLocation texture, int x, int y, int w, int h, int effect, int frames, float time) {
        AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(texture);
        GpuTextureView view = tex.getTextureView();
        if (view == null) return;
        // the clock as 16 bits over 64 seconds, in red and green
        int phase = (int) ((time % 64f) / 64f * 65535f) & 0xFFFF;
        int blue = (effect & 0x0F) | ((Math.min(16, frames) - 1) << 4);
        int color = 0xFF000000 | (phase >> 8) << 16 | (phase & 0xFF) << 8 | blue;
        Matrix3x2f pose = new Matrix3x2f(g.pose());
        ScreenRectangle scissor = g.getScissorStack().peek();
        ScreenRectangle area = new ScreenRectangle(x, y, w, h).transformMaxBounds(pose);
        ScreenRectangle bounds = scissor != null ? scissor.intersection(area) : area;
        if (bounds == null) return;
        g.getRenderState().submitGuiElement(new CardEffectQuad(EFFECT, TextureSetup.singleTexture(view), pose, x, y, x + w, y + h, color,
                scissor, bounds));
    }

    private static int effectId(String effect) {
        return switch (effect == null ? "" : effect.toLowerCase(Locale.ROOT)) {
            case "aurora" -> 1;
            case "holo" -> 2;
            case "neon" -> 3;
            case "inferno" -> 4;
            default -> 0;
        };
    }

    /** {@code #RRGGBB} or {@code #AARRGGBB}; white when broken. */
    private static int color(String hex) {
        try {
            String s = hex.startsWith("#") ? hex.substring(1) : hex;
            long v = Long.parseLong(s, 16);
            return s.length() <= 6 ? (int) (0xFF000000L | v) : (int) v;
        } catch (RuntimeException e) {
            return 0xFFFFFFFF;
        }
    }
}
