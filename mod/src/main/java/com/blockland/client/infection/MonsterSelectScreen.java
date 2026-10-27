package com.blockland.client.infection;

import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.InfectionPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

import static com.blockland.client.ui.Tr.tr;

/**
 * The panel an infected player picks their monster in: one card per monster with its model (the vanilla entity,
 * turning with the mouse), health, skill and cooldown. The first monster is taken automatically when the time runs
 * out (the server decides that; the panel just closes). Number keys pick a card too.
 */
final class MonsterSelectScreen extends Screen {
    private static final int CARD_W = 128;
    private static final int CARD_H = 186;
    private static final int GAP = 12;
    private static final int TEXT = 0xFFF2F4F7;
    private static final int MUTED = 0xFF9AA4B5;
    private static final int ACCENT = 0xFF7CDB5A;
    private static final int MOTHER = 0xFFE0463C;

    private final InfectionPackets.S2CSelect select;
    private final long openedAt = System.nanoTime();
    private final List<LivingEntity> previews = new ArrayList<>();
    private int hovered = -1;

    MonsterSelectScreen(InfectionPackets.S2CSelect select) {
        super(Component.translatable("blockland.infection.select.title"));
        this.select = select;
    }

    @Override
    protected void init() {
        previews.clear();
        for (InfectionPackets.MonsterOption option : select.options()) previews.add(preview(option.entity()));
    }

    @Nullable
    private LivingEntity preview(String entity) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        Entity created = EntityType.byString(entity).map(type -> type.create(mc.level, EntitySpawnReason.LOAD)).orElse(null);
        return created instanceof LivingEntity living ? living : null;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private float secondsLeft() {
        return Math.max(0, select.seconds() - (System.nanoTime() - openedAt) / 1e9f);
    }

    @Override
    public void tick() {
        // the server applies the default monster at the same moment
        if (secondsLeft() <= 0) onClose();
    }

    /** A light veil instead of the blurred menu background: the world stays visible around the panel. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fillGradient(0, 0, width, height, 0x88200808, 0xAA000000);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        float age = (System.nanoTime() - openedAt) / 1e9f;
        float enter = (float) Ease.BACK_OUT.apply(Mth.clamp(age / 0.4f, 0, 1));
        int accent = select.mother() ? MOTHER : ACCENT;
        int count = select.options().size();
        int total = count * CARD_W + (count - 1) * GAP;
        int x0 = (width - total) / 2, y0 = Math.max(46, (height - CARD_H) / 2 + 6) + Math.round((1 - enter) * 16);

        String title = tr(select.mother() ? "blockland.infection.select.mother" : "blockland.infection.select.infected");
        Draw.text(g, title, width / 2f - UiFont.width(title) * 1.6f / 2, y0 - 34, 1.6f, accent, true);
        if (select.mother()) {
            String bonus = tr("blockland.infection.select.mother_health", format(select.healthMultiplier()));
            Draw.text(g, bonus, width / 2f - UiFont.width(bonus) / 2f, y0 - 16, 1f, ColorMath.lighten(MOTHER, 0.1f), true);
        }

        hovered = -1;
        for (int i = 0; i < count; i++) {
            int x = x0 + i * (CARD_W + GAP);
            if (mouseX >= x && mouseX < x + CARD_W && mouseY >= y0 && mouseY < y0 + CARD_H) hovered = i;
            card(g, i, x, y0, mouseX, mouseY, accent);
        }

        // countdown to the automatic choice
        float left = secondsLeft();
        int barW = Math.min(total, 280), bx = (width - barW) / 2, by = y0 + CARD_H + 14;
        g.fill(bx, by, bx + barW, by + 2, 0x40FFFFFF);
        g.fill(bx, by, bx + Math.round(barW * left / Math.max(1, select.seconds())), by + 2, accent);
        String hint = tr("blockland.infection.select.hint", Math.max(1, (int) Math.ceil(left)), MonsterText.name(select.options().get(0).id(), select.options().get(0).name()));
        Draw.text(g, hint, width / 2f - UiFont.width(hint) / 2f, by + 7, 1f, MUTED, true);
    }

    private void card(GuiGraphics g, int index, int x, int y, int mouseX, int mouseY, int accent) {
        InfectionPackets.MonsterOption option = select.options().get(index);
        boolean hover = index == hovered;
        Draw.rect(g, x, y, CARD_W, CARD_H, hover ? 0xE0182216 : 0xD0101410);
        Draw.outline(g, x, y, CARD_W, CARD_H, hover ? accent : 0x40FFFFFF);
        Draw.rect(g, x, y, CARD_W, 2, accent);
        if (index == 0) {
            String tag = tr("blockland.infection.select.default");
            Draw.text(g, tag, x + CARD_W - 6 - UiFont.width(tag) * 0.75f, y + 6, 0.75f, MUTED, false);
        }
        Draw.text(g, (index + 1) + "", x + 6, y + 6, 0.75f, MUTED, false);

        // model, turning towards the mouse
        LivingEntity preview = previews.get(index);
        if (preview != null) {
            Draw.glow(g, x + CARD_W / 2f, y + 62, CARD_W * 0.45f, 50, ColorMath.fade(accent, hover ? 0.22f : 0.12f));
            // each card into a texture of its own (vanilla's entity preview shares one: every card would show the last model)
            MonsterPreviewRenderer.draw(g, index, x + 14, y + 12, x + CARD_W - 14, y + 108, 34, 0.0625f, mouseX, mouseY, preview);
        }

        int ty = y + 112;
        String name = MonsterText.name(option.id(), option.name());
        Draw.text(g, name, x + CARD_W / 2f - UiFont.width(name) * 1.3f / 2, ty, 1.3f, TEXT, true);
        ty += 16;
        float health = option.health() * select.healthMultiplier();
        String hp = tr("blockland.infection.select.health", format(health));
        Draw.text(g, hp, x + CARD_W / 2f - UiFont.width(hp) / 2f, ty, 1f, 0xFFFF7A7A, true);
        ty += 13;
        String skill = tr("blockland.infection.select.skill", MonsterText.skill(option.id(), option.skillName()), format(option.skillCooldown()));
        Draw.text(g, Draw.fit(skill, CARD_W - 12), x + 6, ty, 1f, ColorMath.lighten(accent, 0.1f), true);
        ty += 11;
        for (FormattedCharSequence line : Draw.wrap(option.skillDescription().isEmpty() ? MonsterText.description(option.id(), option.description())
                : MonsterText.skillDescription(option.id(), option.skillDescription()), CARD_W - 12)) {
            if (ty > y + CARD_H - 10) break;
            Draw.text(g, line, x + 6, ty, MUTED);
            ty += 10;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && hovered >= 0) {
            choose(hovered);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        int index = key - GLFW.GLFW_KEY_1;
        if (index >= 0 && index < select.options().size() && index < 9) {
            choose(index);
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    private void choose(int index) {
        BLNetwork.sendToServer(new InfectionPackets.C2SSelectMonster(select.options().get(index).id()));
        onClose();
    }

    private static String format(float value) {
        return value == Math.round(value) ? String.valueOf(Math.round(value)) : String.format("%.1f", value);
    }
}
