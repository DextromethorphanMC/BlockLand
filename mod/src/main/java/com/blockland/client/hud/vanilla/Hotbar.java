package com.blockland.client.hud.vanilla;

import com.blockland.anim.Spring;
import com.blockland.client.hud.layout.HudAnchor;
import com.blockland.client.hud.layout.HudElement;
import com.blockland.client.hud.layout.HudLayout;
import com.blockland.client.ui.Draw;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The hotbar (UI spec §3.2): nine dark rounded cells with their items (counts, durability and cooldowns are vanilla's
 * item decorations), the selected one lit and outlined — the highlight slides between cells —, the off hand's cell on
 * its side and the attack indicator (when set to the hotbar) on the other side as a cell filling up; the experience bar
 * is an element of its own ({@link ExperienceBar}). In spectator mode vanilla's spectator bar stays. Laid out across
 * (the default) or down — the player's choice in the HUD editor: down, the cells run top to bottom with the off hand
 * and the attack indicator at the ends.
 */
final class Hotbar implements HudElement {
    static final int CELL = 20, PITCH = 22, SIDE = CELL + 6;
    static final int SLOTS_W = 9 * PITCH - 2, W = SLOTS_W + 2 * SIDE, H = CELL;
    /** The default gap under it. */
    static final int DEFAULT_Y = -2;
    /** The top of the hotbar and the experience bar over it at their defaults: the bars above sit 3 px over this. */
    static final int STACK_TOP = DEFAULT_Y - H - 2 - ExperienceBar.H;
    private static final int CELL_FILL = 0x99101418, SELECTED_FILL = 0x50F2F4F7, SELECTED_RIM = 0xE0F2F4F7;

    /** 横 / 竖: across or down. */
    static final Option ORIENTATION = new Option("orientation", "blockland.hud.option.orientation", List.of("horizontal", "vertical"));

    private final Spring selected = new Spring(0).config(700, 45);
    private boolean placed;

    private boolean vertical() {
        return "vertical".equals(HudLayout.option(this, ORIENTATION));
    }

    @Override
    public List<Option> options() {
        return List.of(ORIENTATION);
    }

    @Override
    public String id() {
        return "hotbar";
    }

    @Override
    public String nameKey() {
        return "blockland.hud.element.hotbar";
    }

    @Override
    public int width() {
        return vertical() ? H : W;
    }

    @Override
    public int height() {
        return vertical() ? W : H;
    }

    @Override
    public HudAnchor defaultAnchor() {
        return HudAnchor.BOTTOM;
    }

    @Override
    public int defaultX() {
        return 0;
    }

    @Override
    public int defaultY() {
        return DEFAULT_Y;
    }

    @Override
    public boolean visible() {
        return VanillaHud.replaces() && !VanillaHud.spectating() && VanillaHud.cameraPlayer() != null;
    }

    @Override
    public void render(GuiGraphics g, DeltaTracker delta, int x, int y, boolean editing) {
        Minecraft mc = Minecraft.getInstance();
        Player p = VanillaHud.cameraPlayer();
        if (p == null) return;
        float pt = delta.getGameTimeDeltaPartialTick(false);
        int slot = p.getInventory().getSelectedSlot();
        if (!placed) {
            selected.set(slot);
            placed = true;
        }
        selected.setTarget(slot);
        boolean down = vertical();
        // cell i's corner: across, a row under the experience bar; down, a column right of it
        int sx = down ? x : x + SIDE, sy = down ? y + SIDE : y;
        int stepX = down ? 0 : PITCH, stepY = down ? PITCH : 0;

        for (int i = 0; i < 9; i++) cell(g, sx + i * stepX, sy + i * stepY);
        // the highlight, sliding to the selected cell
        float at = selected.get() * PITCH;
        int hx = Math.round(sx + (down ? 0 : at)), hy = Math.round(sy + (down ? at : 0));
        Draw.rect(g, hx, hy, CELL, CELL, SELECTED_FILL);
        Draw.outline(g, hx - 1, hy - 1, CELL + 2, CELL + 2, SELECTED_RIM);
        if (down) g.fill(hx + CELL - 2, hy + 4, hx + CELL - 1, hy + CELL - 4, SELECTED_RIM);
        else g.fill(hx + 4, hy + CELL - 2, hx + CELL - 4, hy + CELL - 1, SELECTED_RIM);
        int seed = 1;
        for (int i = 0; i < 9; i++) item(g, mc, p, p.getInventory().getItem(i), sx + i * stepX + 2, sy + i * stepY + 2, pt, seed++);

        // the off hand at one end (vanilla shows it only when it holds something), the attack indicator at the other
        boolean offFirst = p.getMainArm() == HumanoidArm.RIGHT;
        int first = 0, last = W - CELL;
        int offAt = offFirst ? first : last, indicatorAt = offFirst ? last : first;
        int offX = down ? sx : x + offAt, offY = down ? y + offAt : sy;
        int indX = down ? sx : x + indicatorAt, indY = down ? y + indicatorAt : sy;
        ItemStack off = p.getOffhandItem();
        if (!off.isEmpty() || editing) {
            cell(g, offX, offY);
            if (!off.isEmpty()) item(g, mc, p, off, offX + 2, offY + 2, pt, seed);
        }
        if (mc.options.attackIndicator().get() == AttackIndicatorStatus.HOTBAR && mc.player != null) {
            float strength = mc.player.getAttackStrengthScale(0);
            if (strength < 1) {
                cell(g, indX, indY);
                int h = Math.round((CELL - 4) * strength);
                g.fill(indX + 3, indY + CELL - 2 - h, indX + CELL - 3, indY + CELL - 2, 0xB0F2F4F7);
            }
        }
    }

    private static void cell(GuiGraphics g, int x, int y) {
        Draw.rect(g, x, y, CELL, CELL, CELL_FILL);
        Draw.outline(g, x, y, CELL, CELL, Bars.RIM);
    }

    /** An item with vanilla's pop when it is picked up, and its decorations (count, durability, cooldown). */
    private static void item(GuiGraphics g, Minecraft mc, Player p, ItemStack stack, int x, int y, float pt, int seed) {
        if (stack.isEmpty()) return;
        float pop = stack.getPopTime() - pt;
        if (pop > 0) {
            float s = 1 + pop / 5f;
            g.pose().pushMatrix();
            g.pose().translate(x + 8, y + 12);
            g.pose().scale(1 / s, (s + 1) / 2);
            g.pose().translate(-(x + 8), -(y + 12));
        }
        g.renderItem(p, stack, x, y, seed);
        if (pop > 0) g.pose().popMatrix();
        g.renderItemDecorations(mc.font, stack, x, y);
    }
}
