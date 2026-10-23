package com.blockland.client.combat;

import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.blockland.network.packet.CombatPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.ArrayList;
import java.util.List;

/**
 * The kill feed in the top-right corner: "killer [weapon] victim", a reticle for headshots, rows sliding in from the
 * right and fading after a few seconds; rows with the player in them are marked.
 */
final class KillFeedHud {
    private static final int MAX = 5, ROW = 13;
    private static final float LIFE = 6f, SLIDE = 0.28f, FADE = 0.5f;

    private record Row(String killer, int killerColor, String victim, int victimColor, String weapon, boolean headshot, long born) {
        float age() {
            return (System.nanoTime() - born) / 1e9f;
        }
    }

    private static final List<Row> rows = new ArrayList<>();

    private KillFeedHud() {
    }

    static void add(CombatPackets.S2CKillFeed feed) {
        rows.add(0, new Row(feed.killer(), feed.killerColor(), feed.victim(), feed.victimColor(), weaponName(feed.weapon()),
                feed.headshot(), System.nanoTime()));
        while (rows.size() > MAX) rows.remove(rows.size() - 1);
    }

    static void clear() {
        rows.clear();
    }

    private static String weaponName(String id) {
        ResourceLocation location = id.isEmpty() ? null : ResourceLocation.tryParse(id);
        if (location == null) return "";
        return BuiltInRegistries.ITEM.getOptional(location).map(item -> item.getDefaultInstance().getHoverName().getString()).orElse("");
    }

    static void render(GuiGraphics g, int w, int h) {
        rows.removeIf(r -> r.age() > LIFE + FADE);
        if (rows.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        String self = mc.player == null ? "" : mc.player.getGameProfile().getName();
        // vanilla's effect icons live in the top-right corner too
        boolean effects = mc.player != null && mc.player.getActiveEffects().stream().anyMatch(MobEffectInstance::showIcon);
        int y = effects ? 52 : 6;
        for (Row r : rows) {
            float age = r.age();
            float a = age < LIFE ? 1 : 1 - (age - LIFE) / FADE;
            float slide = (float) Ease.CUBIC_OUT.apply(Math.min(1, age / SLIDE));
            a *= Math.min(1, age / (SLIDE * 0.6f));
            if (a <= 0.03f) continue;
            String weapon = r.weapon.isEmpty() ? "" : "[" + r.weapon + "]";
            float kw = UiFont.widthF(r.killer), ww = UiFont.widthF(weapon), vw = UiFont.widthF(r.victim);
            float icon = r.headshot ? 12 : 0;
            float width = 8 + (r.killer.isEmpty() ? 0 : kw + 5) + (weapon.isEmpty() ? 0 : ww + 5) + icon + vw;
            int x = Math.round(w - 6 - width + (1 - slide) * 40);
            boolean mine = !self.isEmpty() && (self.equals(r.killer) || self.equals(r.victim));
            int bg = self.equals(r.victim) ? 0x70501018 : mine ? 0x70303848 : 0x60000000;
            Draw.rect(g, x, y, Math.round(width), ROW, ColorMath.fade(bg, a));
            if (mine) g.fill(x, y + 1, x + 1, y + ROW - 1, ColorMath.fade(self.equals(r.victim) ? 0xFFFF5C5C : 0xFFF2F4F7, a));
            float tx = x + 4, ty = y + 2.5f;
            if (!r.killer.isEmpty()) tx = UiFont.draw(g, r.killer, tx, ty, ColorMath.fade(0xFF000000 | r.killerColor, a), true) + 5;
            if (!weapon.isEmpty()) tx = UiFont.draw(g, weapon, tx, ty, ColorMath.fade(0xFFB8C0CC, a), true) + 5;
            if (r.headshot) {
                KillMedals.headshotIcon(g, tx + 5, y + ROW / 2f, 0.75f, ColorMath.fade(0xFFFFA235, a));
                tx += icon;
            }
            UiFont.draw(g, r.victim, tx, ty, ColorMath.fade(0xFF000000 | r.victimColor, a), true);
            y += ROW + 2;
        }
    }
}
