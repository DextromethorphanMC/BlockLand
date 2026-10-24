package com.blockland.client.loading;

import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Ticker;
import com.blockland.client.ClientMedia;
import com.blockland.client.font.UiFont;
import com.blockland.client.profile.NameCards;
import com.blockland.client.screen.lobby.FigureRenderer;
import com.blockland.client.screen.lobby.Skins;
import com.blockland.client.ui.Draw;
import com.blockland.common.RankTier;
import com.blockland.network.packet.MediaPackets;
import com.blockland.network.packet.S2CMatchFound;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.PlayerModelPart;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

import static com.blockland.client.screen.lobby.LobbyTheme.ACCENT;
import static com.blockland.client.screen.lobby.LobbyTheme.BG_BOTTOM;
import static com.blockland.client.screen.lobby.LobbyTheme.BG_TOP;
import static com.blockland.client.screen.lobby.LobbyTheme.MUTED;
import static com.blockland.client.screen.lobby.LobbyTheme.TEXT;
import static com.blockland.client.ui.Tr.tr;

/**
 * The match loading screen ({@link MatchLoading}): the map's cover slowly panning and zooming behind the mode's and the
 * map's titles, the player's side standing in a row — each one's character above their name card with their tier —
 * the stage of the way in, and a tip changing every few seconds. Fades away into the game. Draws in the lobby too (no
 * world needed): skins come by account, cards from {@link NameCards}.
 */
final class MatchLoadingScreen extends Screen {
    private static final int TIPS = 10;
    private static final float TIP_SECONDS = 6;
    private static final float HEIGHT = 1.8f;

    private final MatchLoading.Session session;
    private final List<PlayerRenderState> figures = new ArrayList<>();

    MatchLoadingScreen(MatchLoading.Session session) {
        super(Component.translatable("blockland.loading.title"));
        this.session = session;
        for (int i = 0; i < session.match.team().size(); i++) figures.add(new PlayerRenderState());
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected boolean shouldNarrateNavigation() {
        return false;
    }

    /** The screen paints its own backdrop (no blur, no menu background). */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        S2CMatchFound match = session.match;
        float seconds = session.seconds();
        float in = (float) Ease.CUBIC_OUT.apply(Mth.clamp(seconds / 0.35f, 0, 1));
        float alpha = in * (1 - (float) Ease.CUBIC_IN.apply(session.fade()));
        if (alpha <= 0.002f) return;
        float time = (float) Ticker.global().time();
        int w = width, h = height;
        int accent = 0xFF000000 | match.accent();

        backdrop(g, match, w, h, seconds, alpha, accent);

        // ---- titles
        int left = 26, top = 24;
        Draw.text(g, match.typeName(), left, top, 1f, ColorMath.fade(accent, alpha), true);
        Draw.text(g, Draw.fit(match.modeTitle(), (w - left * 2) / 2.4f), left, top + 12, 2.4f, ColorMath.fade(TEXT, alpha), true);
        String sub = match.mapName().isEmpty() ? tr(match.dropIn() ? "blockland.loading.drop_in" : "blockland.loading.no_map")
                : tr("blockland.loading.map", match.mapName());
        Draw.text(g, sub, left, top + 38, 1.2f, ColorMath.fade(MUTED, alpha), true);

        // ---- the format, top right
        int right = w - 26;
        String format = match.opponents() > 0 ? tr("blockland.loading.opponents", match.opponents())
                : tr("blockland.loading.players", match.players());
        Draw.text(g, format, right - UiFont.width(format) * 1.1f, top + 2, 1.1f, ColorMath.fade(TEXT, alpha), true);
        if (match.ranked()) {
            String badge = tr("blockland.loading.ranked");
            int bw = UiFont.width(badge) + 12;
            int bx = right - bw, by = top + 16;
            Draw.rect(g, bx, by, bw, 13, ColorMath.fade(ACCENT, 0.9f * alpha));
            Draw.text(g, badge, bx + 6, by + 3, 1f, ColorMath.fade(0xFF1A1206, alpha), false);
        }

        // ---- the side
        team(g, match, w, h, seconds, time, alpha);

        // ---- the way in and a tip
        footer(g, w, h, seconds, time, alpha, accent);
    }

    /** The cover, slowly panning and zooming (Ken Burns), darkened towards the bottom; a plain gradient without one. */
    private void backdrop(GuiGraphics g, S2CMatchFound match, int w, int h, float seconds, float alpha, int accent) {
        g.fill(0, 0, w, h, ColorMath.fade(BG_TOP, alpha));
        Draw.fadeY(g, 0, h / 3, w, h - h / 3, ColorMath.fade(BG_BOTTOM, alpha), false);
        ClientMedia.Image cover = match.mapId().isEmpty() ? null : ClientMedia.get(MediaPackets.MAP, match.mapId(), match.coverVersion());
        if (cover != null) {
            float zoom = 1.06f + 0.05f * Mth.sin(seconds * 0.045f);
            int margin = Math.round(Math.max(w, h) * 0.04f);
            int px = Math.round(Mth.sin(seconds * 0.06f) * margin * 0.8f), py = Math.round(Mth.cos(seconds * 0.05f) * margin * 0.6f);
            float coverIn = Mth.clamp(seconds / 0.8f, 0, 1);
            g.enableScissor(0, 0, w, h);
            Draw.cover(g, cover, -margin + px, -margin + py, w + margin * 2, h + margin * 2, zoom,
                    ColorMath.fade(0xFF9AA2AE, alpha * coverIn));
            g.disableScissor();
        }
        Draw.glow(g, w * 0.22f, h * 0.18f, w * 0.5f, h * 0.45f, ColorMath.fade(accent, 0.16f * alpha));
        // legible titles and cards: dark at the top-left and along the bottom
        Draw.fadeX(g, 0, 0, w / 2, h, ColorMath.fade(0xB0060A11, alpha), true);
        Draw.fadeY(g, 0, h / 2, w, h - h / 2, ColorMath.fade(0xE0060A11, alpha), false);
    }

    /** Everyone on the player's side in a row: their character over their name card, the player first (centred alone). */
    private void team(GuiGraphics g, S2CMatchFound match, int w, int h, float seconds, float time, float alpha) {
        List<S2CMatchFound.Member> team = match.team();
        int n = Math.max(1, team.size());
        int gap = 12;
        int cardW = n == 1 ? Math.min(210, w / 3) : Math.max(70, Math.min(160, (w - 60 - gap * (n - 1)) / n));
        int cardH = Math.round(cardW / NameCards.ASPECT);
        int figH = Math.max(40, Math.min(Math.round(h * 0.34f), Math.round(cardW * (n == 1 ? 1.1f : 1.4f))));
        int rowW = n * cardW + (n - 1) * gap;
        int x0 = (w - rowW) / 2;
        int cardY = Math.round(h * 0.56f + figH * 0.35f);
        boolean showFigures = session.fade() < 0.3f;
        for (int i = 0; i < team.size(); i++) {
            S2CMatchFound.Member member = team.get(i);
            float appear = (float) Ease.EXPO_OUT.apply(Mth.clamp((seconds - 0.12f - i * 0.09f) / 0.6f, 0, 1));
            if (appear <= 0.01f) continue;
            float a = alpha * appear;
            int x = x0 + i * (cardW + gap);
            int lift = Math.round((1 - appear) * 18);
            int y = cardY + lift;
            int cx = x + cardW / 2;
            Draw.glow(g, cx, y - 4, cardW * 0.55f, cardH * 0.5f, ColorMath.fade(i == 0 ? 0xFF2A4C7A : 0xFF1C2A40, 0.5f * a));
            if (showFigures && appear > 0.6f) figure(g, figures.get(i), member, x, y - figH, x + cardW, y - 2, figH, time + i * 1.7f);
            RankTier tier = member.placed() ? RankTier.of(member.tier()) : null;
            NameCards.draw(g, member.card().isEmpty() ? null : member.card(), x, y, cardW, cardH, member.name(), tier, time);
            int infoY = y + cardH + 4;
            if (match.ranked()) {
                String score = member.placed() ? tr("blockland.loading.score", member.score()) : tr("blockland.loading.unranked");
                int color = tier != null ? tier.color : MUTED;
                Draw.text(g, score, cx - UiFont.width(score) / 2f, infoY, 1f, ColorMath.fade(color, a), true);
            }
            if (i == 0 && n > 1) {
                String you = tr("blockland.loading.you");
                Draw.text(g, you, cx - UiFont.width(you) / 2f, y - figH - 10, 1f, ColorMath.fade(ACCENT, a), true);
            }
        }
    }

    /** A teammate's skin on a standing player model, gently breathing and turning. */
    private static void figure(GuiGraphics g, PlayerRenderState s, S2CMatchFound.Member member, int x1, int y1, int x2, int y2,
                               int boxH, float time) {
        Minecraft mc = Minecraft.getInstance();
        s.entityType = EntityType.PLAYER;
        s.skin = Skins.of(member.id());
        s.ageInTicks = time * 20;
        s.bodyRot = 180 + Mth.sin(time * 0.31f) * 8;
        s.yRot = Mth.sin(time * 0.37f) * 6;
        s.xRot = Mth.sin(time * 0.29f) * 3;
        s.scale = 1;
        s.ageScale = 1;
        s.boundingBoxWidth = 0.6F;
        s.boundingBoxHeight = HEIGHT;
        s.eyeHeight = 1.62F;
        s.hitboxesRenderState = null;
        // other players' model parts are theirs to choose; ours stand in for everyone
        s.showHat = mc.options.isModelPartEnabled(PlayerModelPart.HAT);
        s.showJacket = mc.options.isModelPartEnabled(PlayerModelPart.JACKET);
        s.showLeftPants = mc.options.isModelPartEnabled(PlayerModelPart.LEFT_PANTS_LEG);
        s.showRightPants = mc.options.isModelPartEnabled(PlayerModelPart.RIGHT_PANTS_LEG);
        s.showLeftSleeve = mc.options.isModelPartEnabled(PlayerModelPart.LEFT_SLEEVE);
        s.showRightSleeve = mc.options.isModelPartEnabled(PlayerModelPart.RIGHT_SLEEVE);
        s.showCape = false;
        s.name = member.name();
        int scale = Math.round(boxH * 0.5f);
        Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
        Vector3f offset = new Vector3f(0, HEIGHT / 2.0F + 0.0625F, 0);
        g.getRenderState().submitPicturesInPictureState(FigureRenderer.State.of(s, scale, offset, pose, x1, y1, x2, y2));
    }

    /** The stage of the way in on a moving bar, the time it has taken, and a tip that changes every few seconds. */
    private void footer(GuiGraphics g, int w, int h, float seconds, float time, float alpha, int accent) {
        MatchLoading.Stage stage = session.stage;
        int barW = Math.min(260, w - 80), barX = (w - barW) / 2, barY = h - 40;
        String label = tr(switch (stage) {
            case CONNECTING -> "blockland.loading.stage.connecting";
            case LOADING_MAP -> "blockland.loading.stage.loading_map";
            case ENTERING, DONE -> "blockland.loading.stage.entering";
        });
        int dots = (int) (time * 2.5f) % 4;
        String shown = label + ".".repeat(stage == MatchLoading.Stage.DONE ? 0 : dots);
        Draw.text(g, shown, barX, barY - 11, 1f, ColorMath.fade(TEXT, alpha), true);
        String clock = String.format("%d:%02d", (int) seconds / 60, (int) seconds % 60);
        Draw.text(g, clock, barX + barW - UiFont.width(clock), barY - 11, 1f, ColorMath.fade(MUTED, alpha), true);
        g.fill(barX, barY, barX + barW, barY + 2, ColorMath.fade(0x30FFFFFF, alpha));
        // the share of the way done (stages), with a highlight running along it while waiting
        float done = switch (stage) {
            case CONNECTING -> 0.18f;
            case LOADING_MAP -> 0.5f;
            case ENTERING -> 0.82f;
            case DONE -> 1f;
        };
        int fill = Math.round(barW * done);
        g.fill(barX, barY, barX + fill, barY + 2, ColorMath.fade(accent, alpha));
        if (stage != MatchLoading.Stage.DONE) {
            float run = (time * 0.6f) % 1f;
            int hx = barX + Math.round(run * barW);
            Draw.glow(g, hx, barY + 1, 22, 3, ColorMath.fade(0xFFFFFFFF, 0.5f * alpha));
        }

        // a tip, crossfading every few seconds
        int index = (int) (seconds / TIP_SECONDS) % TIPS;
        float phase = (seconds % TIP_SECONDS) / TIP_SECONDS;
        float tipAlpha = Mth.clamp(Math.min(phase / 0.08f, (1 - phase) / 0.08f), 0, 1);
        String tip = Draw.fit(tr("blockland.loading.tip." + index), w - 60);
        Draw.text(g, tip, (w - UiFont.width(tip)) / 2f, h - 22, 1f, ColorMath.fade(MUTED, alpha * tipAlpha), true);
    }
}
