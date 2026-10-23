package com.blockland.client.infection;

import com.blockland.anim.ColorMath;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.blockland.network.packet.InfectionPackets;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import static com.blockland.client.ui.Tr.tr;

/** Monster HUD (bottom right, like the weapon HUDs): the monster, its health and the skill with its cooldown. */
final class InfectionHud {
    private static final int TEXT = 0xFFF2F4F7;
    private static final int MUTED = 0xFF9AA4B5;
    private static final int MONSTER = 0xFF7CDB5A;
    private static final int MOTHER = 0xFFE0463C;
    private static final int ACTIVE = 0xFFFFC24A;

    private InfectionHud() {
    }

    static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || !ClientInfection.isMonster() || mc.player.isSpectator()) return;
        InfectionPackets.S2CRole role = ClientInfection.role();
        int w = g.guiWidth(), h = g.guiHeight();
        int right = w - 14, bottom = h - 14;
        int accent = role.mother() ? MOTHER : MONSTER;
        Draw.glow(g, right - 60, bottom - 22, 100, 38, 0x66000000);

        String name = MonsterText.name(role.monsterId(), role.monster()) + (role.mother() ? tr("blockland.infection.hud.mother_suffix") : "");
        Draw.text(g, name, right - UiFont.width(name) * 1.3f, bottom - 40, 1.3f, accent, true);
        String hp = tr("blockland.infection.hud.health", Math.round(mc.player.getHealth()), Math.round(mc.player.getMaxHealth()));
        Draw.text(g, hp, right - UiFont.width(hp), bottom - 26, 1f, 0xFFFF7A7A, true);

        // skill: ready / active / cooling down
        String key = ClientInfection.SKILL.getTranslatedKeyMessage().getString();
        float cooldown = ClientInfection.skillCooldownLeft(), active = ClientInfection.skillActive();
        String state;
        int color;
        if (active > 0) {
            state = tr("blockland.infection.hud.skill_active");
            color = ACTIVE;
        } else if (cooldown > 0) {
            state = tr("blockland.infection.hud.seconds", (int) Math.ceil(cooldown));
            color = MUTED;
        } else {
            state = tr("blockland.infection.hud.skill_ready");
            color = TEXT;
        }
        String skill = "[" + key + "] " + MonsterText.skill(role.monsterId(), role.skillName()) + " · " + state;
        Draw.text(g, skill, right - UiFont.width(skill), bottom - 14, 1f, color, true);
        int barW = UiFont.width(skill), x0 = right - barW, y = bottom - 3;
        g.fill(x0, y, right, y + 1, 0x30FFFFFF);
        float fill = active > 0 ? active : cooldown > 0 ? 1 - cooldown / Math.max(0.1f, role.skillCooldown()) : 1;
        g.fill(x0, y, x0 + Math.round(barW * fill), y + 1, ColorMath.fade(active > 0 ? ACTIVE : accent, 0.95f));
    }
}
