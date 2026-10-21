package com.blockland.client.screen.lobby;

import com.blockland.anim.Anim;
import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Ticker;
import com.blockland.client.ClientLobbyState;
import com.blockland.client.ui.Draw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import static com.blockland.client.screen.lobby.LobbyTheme.*;

/** The player's character on a glowing platform, with a slow idle bob and a status line. */
public final class CharacterStage {
    private final Font font;
    private final AnimValue reveal = new AnimValue(1);

    public CharacterStage(Font font) {
        this.font = font;
    }

    public void playIntro(double delay) {
        Anim.fromTo(reveal, 0, 1, 0.8).ease(Ease.EXPO_OUT).delay(delay);
    }

    /**
     * @param nameAbove  true on narrow screens: name top-left instead of under the feet
     * @param nameRight  the name must end before this x (other content sits to its right)
     */
    public void render(GuiGraphics g, int left, int right, int top, int bottom, boolean nameAbove, int nameTop, int nameRight) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        float r = reveal.get();
        double time = Ticker.global().time();
        int cx = (left + right) / 2;
        int boxH = Math.max(40, bottom - top);
        int halfW = Math.max(30, Math.min((right - left) / 2, boxH / 2));
        int scale = (int) (boxH * 0.42f);
        int floorY = (top + bottom) / 2 + (int) (scale * 0.95f);

        // spotlight + platform
        int status = ClientLobbyState.inGame() ? OK : ClientLobbyState.isQueued() ? ACCENT : INFO;
        float pulse = (float) (0.5 + 0.5 * Math.sin(time * 1.4));
        Draw.glow(g, cx, (top + bottom) / 2f, halfW * 1.3f, boxH * 0.62f, ColorMath.fade(0xFF2A4C7A, 0.35f * r));
        Draw.glow(g, cx, floorY, scale * 0.85f, scale * 0.16f, ColorMath.fade(status, (0.35f + 0.2f * pulse) * r));
        Draw.glow(g, cx, floorY, scale * 0.45f, scale * 0.07f, ColorMath.fade(0xFFFFFFFF, 0.25f * r));

        // idle bob (the model itself never follows the mouse)
        // entity previews are placed by their screen rectangle, so the bob moves the rectangle
        int bob = Math.round((float) Math.sin(time * 1.6) * 2f + (1 - r) * 30);
        if (r > 0.05f) renderStill(g, cx - halfW, top + bob, cx + halfW, bottom + bob, scale, mc.player);

        String name = mc.player.getGameProfile().getName();
        float nameY = nameAbove ? nameTop : bottom + 3;
        String label = ClientLobbyState.inGame() ? "对局进行中" : ClientLobbyState.isQueued() ? "匹配中" : "大厅";
        float lw = font.width(label) + 14;
        float nameX, labelX;
        if (nameAbove) {
            name = Draw.fit(font, name, (int) ((nameRight - left) / 1.5f));
            nameX = left;
            labelX = left;
        } else {
            nameX = cx - font.width(name) * 1.5f / 2;
            labelX = cx - lw / 2;
        }
        Draw.text(g, font, name, nameX, nameY + (1 - r) * 8, 1.5f, ColorMath.fade(TEXT, r), true);
        float ly = nameY + 15;
        Draw.glow(g, labelX + 4, ly + 4, 4, 4, ColorMath.fade(status, r * (0.4f + 0.4f * pulse)));
        Draw.rect(g, Math.round(labelX + 2), Math.round(ly + 2), 4, 4, ColorMath.fade(status, r));
        Draw.text(g, font, label, labelX + 10, ly, 1f, ColorMath.fade(MUTED, r), false);
    }

    /** Draws the entity facing the viewer at a slight angle; unlike the inventory it does not follow the mouse. */
    private static void renderStill(GuiGraphics g, int x1, int y1, int x2, int y2, int scale, LivingEntity entity) {
        Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
        Quaternionf camera = new Quaternionf();
        float bodyRot = entity.yBodyRot, yRot = entity.getYRot(), xRot = entity.getXRot();
        float headRotO = entity.yHeadRotO, headRot = entity.yHeadRot;
        float facing = 160F;
        entity.yBodyRot = facing;
        entity.setYRot(facing);
        entity.setXRot(0);
        entity.yHeadRot = facing;
        entity.yHeadRotO = facing;
        float entityScale = entity.getScale();
        Vector3f offset = new Vector3f(0, entity.getBbHeight() / 2.0F + 0.0625F * entityScale, 0);
        InventoryScreen.renderEntityInInventory(g, x1, y1, x2, y2, scale / entityScale, offset, pose, camera, entity);
        entity.yBodyRot = bodyRot;
        entity.setYRot(yRot);
        entity.setXRot(xRot);
        entity.yHeadRotO = headRotO;
        entity.yHeadRot = headRot;
    }
}
