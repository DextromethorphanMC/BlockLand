package com.blockland.client.screen.lobby;

import com.blockland.anim.Anim;
import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Ticker;
import com.blockland.client.ClientLobbyState;
import com.blockland.client.font.UiFont;
import com.blockland.client.ui.Draw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.PlayerModelPart;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import static com.blockland.client.screen.lobby.LobbyTheme.*;
import static com.blockland.client.ui.Tr.tr;

/**
 * The player's character on a glowing platform, with a slow idle bob and a status line. The lobby has no world and
 * no player entity, so the figure is a player render state built from the account's skin and drawn by
 * {@link FigureRenderer} (model only, no entity render layers).
 */
public final class CharacterStage {
    private static final float FACING = 160F;
    private static final float HEIGHT = 1.8F;

    private final AnimValue reveal = new AnimValue(1);
    private final PlayerRenderState figure = new PlayerRenderState();

    public CharacterStage() {
    }

    public void playIntro(double delay) {
        Anim.fromTo(reveal, 0, 1, 0.8).ease(Ease.EXPO_OUT).delay(delay);
    }

    /**
     * @param nameAbove  true on narrow screens: name top-left instead of under the feet
     * @param nameRight  the name must end before this x (other content sits to its right)
     */
    public void render(GuiGraphics g, int left, int right, int top, int bottom, boolean nameAbove, int nameTop, int nameRight) {
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
        if (r > 0.05f) renderFigure(g, cx - halfW, top + bob, cx + halfW, bottom + bob, scale, (float) time);

        String name = ClientLobbyState.selfName();
        float nameY = nameAbove ? nameTop : bottom + 3;
        String label = tr(ClientLobbyState.inGame() ? "blockland.lobby.status.loading_match"
                : ClientLobbyState.isQueued() ? "blockland.lobby.status.queued" : "blockland.lobby.status.lobby");
        float lw = UiFont.width(label) + 14;
        float nameX, labelX;
        if (nameAbove) {
            name = Draw.fit(name, (int) ((nameRight - left) / 1.5f));
            nameX = left;
            labelX = left;
        } else {
            nameX = cx - UiFont.width(name) * 1.5f / 2;
            labelX = cx - lw / 2;
        }
        Draw.text(g, name, nameX, nameY + (1 - r) * 8, 1.5f, ColorMath.fade(TEXT, r), true);
        float ly = nameY + 15;
        Draw.glow(g, labelX + 4, ly + 4, 4, 4, ColorMath.fade(status, r * (0.4f + 0.4f * pulse)));
        Draw.rect(g, Math.round(labelX + 2), Math.round(ly + 2), 4, 4, ColorMath.fade(status, r));
        Draw.text(g, label, labelX + 10, ly, 1f, ColorMath.fade(MUTED, r), false);
    }

    /** The account's skin on a standing player model, facing the viewer at a slight angle, breathing. */
    private void renderFigure(GuiGraphics g, int x1, int y1, int x2, int y2, int scale, float time) {
        Minecraft mc = Minecraft.getInstance();
        PlayerRenderState s = figure;
        s.entityType = EntityType.PLAYER;
        s.skin = Skins.of(ClientLobbyState.selfId());
        s.ageInTicks = time * 20;
        s.bodyRot = FACING;
        s.yRot = (float) Math.sin(time * 0.37) * 6;
        s.xRot = (float) Math.sin(time * 0.29) * 3;
        s.scale = 1;
        s.ageScale = 1;
        s.boundingBoxWidth = 0.6F;
        s.boundingBoxHeight = HEIGHT;
        s.eyeHeight = 1.62F;
        s.hitboxesRenderState = null;
        s.showHat = mc.options.isModelPartEnabled(PlayerModelPart.HAT);
        s.showJacket = mc.options.isModelPartEnabled(PlayerModelPart.JACKET);
        s.showLeftPants = mc.options.isModelPartEnabled(PlayerModelPart.LEFT_PANTS_LEG);
        s.showRightPants = mc.options.isModelPartEnabled(PlayerModelPart.RIGHT_PANTS_LEG);
        s.showLeftSleeve = mc.options.isModelPartEnabled(PlayerModelPart.LEFT_SLEEVE);
        s.showRightSleeve = mc.options.isModelPartEnabled(PlayerModelPart.RIGHT_SLEEVE);
        s.showCape = mc.options.isModelPartEnabled(PlayerModelPart.CAPE);
        s.name = ClientLobbyState.selfName();
        Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
        Vector3f offset = new Vector3f(0, HEIGHT / 2.0F + 0.0625F, 0);
        g.getRenderState().submitPicturesInPictureState(FigureRenderer.State.of(s, scale, offset, pose, x1, y1, x2, y2));
    }
}
