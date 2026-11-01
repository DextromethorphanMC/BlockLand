package com.blockland.client.hud.compass;

import com.blockland.anim.ColorMath;
import com.blockland.client.font.UiFont;
import com.blockland.client.hud.layout.HudAnchor;
import com.blockland.client.hud.layout.HudElement;
import com.blockland.client.hud.layout.HudLayout;
import com.blockland.client.ui.Draw;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import static com.blockland.client.ui.Tr.tr;

/**
 * The compass strip (UI spec §3.6): a band of the horizon round the way the camera faces — a tick every 5°, a longer
 * one with its bearing every 15°, the eight points by name (the four cardinals larger, north gold) — sliding smoothly
 * past a caret in the middle, over a plate with the bearing (north 0°, east 90°). Allies (a team's mates, never anyone
 * else) are small markers under the band at their bearing, held at its ends when out of view. Both ends fade out.
 * Resizable in the editor: the height sets the ticks' length, the text's size and how many pixels a degree takes; the
 * width how much of the horizon shows.
 */
final class CompassElement implements HudElement {
    static final Resize RESIZE = new Resize(200, 22, 80, 12, 480, 48, false);
    /** The strip's design height: its sizes are given at this height and scale with the real one. */
    private static final float DESIGN_H = 22;
    /** Pixels per degree at the design height (a 200 px strip shows 100°); never so few that more than 300° show. */
    private static final float PIXELS_PER_DEGREE = 2, MOST_DEGREES = 300;
    private static final int BAND = 0x90101418, HAIRLINE = 0x60FFFFFF, TICK = 0xFFDCE2EA, TEXT = 0xFFF2F4F7, MUTED = 0xFFAEB7C6;
    private static final int NORTH = 0xFFE8B04A, CARET = 0xFFFFFFFF, PLATE = 0xE0101418, ALLY = 0xFF5AC8FA, OUTLINE = 0xFF101418;
    private static final String[] POINTS = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};
    /** The editor's sample when there is no world: a heading and two team-mates. */
    private static final float SAMPLE_HEADING = 318;
    private static final float[] SAMPLE_ALLIES = {287, 352};

    @Override
    public String id() {
        return "compass";
    }

    @Override
    public String nameKey() {
        return "blockland.hud.element.compass";
    }

    @Override
    public Resize resize() {
        return RESIZE;
    }

    @Override
    public int width() {
        return HudLayout.size(this)[0];
    }

    @Override
    public int height() {
        return HudLayout.size(this)[1];
    }

    @Override
    public HudAnchor defaultAnchor() {
        return HudAnchor.TOP;
    }

    @Override
    public int defaultX() {
        return 0;
    }

    @Override
    public int defaultY() {
        return 4;
    }

    /** Not with the F3 screen (it writes over the top of the screen). */
    @Override
    public boolean visible() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.level != null && !mc.getDebugOverlay().showDebugScreen();
    }

    @Override
    public void render(GuiGraphics g, DeltaTracker delta, int x, int y, boolean editing) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        boolean live = player != null && mc.level != null;
        if (!live && !editing) return;
        int[] size = HudLayout.size(this);
        int w = size[0], h = size[1];
        float u = h / DESIGN_H;
        int bandH = Math.max(8, Math.round(h * 0.64f));
        float ppd = Math.max(PIXELS_PER_DEGREE * u, w / MOST_DEGREES);
        // the camera's way this frame (interpolated; a vehicle's free look and the third-person views included)
        Camera camera = mc.gameRenderer.getMainCamera();
        float heading = live ? bearing(camera.getYRot()) : SAMPLE_HEADING;

        band(g, x, y, w, bandH);
        g.enableScissor(x, y, x + w, y + bandH);
        scale(g, x, y, w, bandH, u, ppd, heading);
        g.disableScissor();
        int marker = Math.max(2, Math.round(2.5f * u));
        // the team-mates under the band (on its lower edge when there is no room), then the plate over them
        int markerY = h - bandH >= marker + 1 ? y + bandH + 1 : y + bandH - marker;
        if (live) {
            allies(g, player, camera.getPosition(), delta.getGameTimeDeltaPartialTick(false), x, w, markerY, marker, ppd, heading);
        } else {
            for (float b : SAMPLE_ALLIES) ally(g, x, w, markerY, marker, ppd, Mth.wrapDegrees(b - heading), ALLY);
        }
        caret(g, x + w / 2, y + bandH, Math.max(2, Math.round(2.5f * u)));
        plate(g, x + w / 2f, y + bandH, h - bandH, u, heading);
    }

    /**
     * Minecraft's yaw (0 south, 90 west, ±180 north) as a compass bearing: 0 north, 90 east, 180 south, 270 west, in
     * [0, 360).
     */
    static float bearing(float yaw) {
        return Mth.positiveModulo(yaw + 180, 360f);
    }

    /** The bearing from {@code from} to {@code to} on the ground (north is −Z, east +X), in [0, 360). */
    static float bearing(Vec3 from, double toX, double toZ) {
        double dx = toX - from.x, dz = toZ - from.z;
        return Mth.positiveModulo((float) Math.toDegrees(Math.atan2(dx, -dz)), 360f);
    }

    /** The dark band with a hairline under it, solid in the middle and fading out at both ends. */
    private static void band(GuiGraphics g, int x, int y, int w, int bandH) {
        int fade = Math.round(w * 0.22f);
        Draw.fadeX(g, x, y, fade, bandH, BAND, false);
        g.fill(x + fade, y, x + w - fade, y + bandH, BAND);
        Draw.fadeX(g, x + w - fade, y, fade, bandH, BAND, true);
        Draw.fadeX(g, x, y + bandH - 1, fade, 1, HAIRLINE, false);
        g.fill(x + fade, y + bandH - 1, x + w - fade, y + bandH, HAIRLINE);
        Draw.fadeX(g, x + w - fade, y + bandH - 1, fade, 1, HAIRLINE, true);
    }

    /** The ticks and their labels, at sub-pixel places so the strip slides smoothly as the camera turns. */
    private static void scale(GuiGraphics g, int x, int y, int w, int bandH, float u, float ppd, float heading) {
        float half = w / 2f, cx = x + half;
        int bottom = y + bandH - 1;
        int minor = Math.max(1, Math.round(2.5f * u)), major = Math.max(2, Math.round(4 * u)), cardinal = Math.max(2, Math.round(5 * u));
        // the labels sit in the room over the longest ticks, centred on one line
        float room = bandH - 1 - cardinal;
        float numberScale = Math.max(0.4f, 0.62f * u), pointScale = Math.max(0.45f, 0.72f * u), cardinalScale = Math.max(0.5f, 0.9f * u);
        float span = half / ppd + 10;
        for (int d = (int) Math.floor((heading - span) / 5) * 5; d <= heading + span; d += 5) {
            float dx = (d - heading) * ppd;
            float a = edgeFade(Math.abs(dx), half);
            if (a <= 0.01f) continue;
            float px = cx + dx;
            int deg = Math.floorMod(d, 360);
            boolean isPoint = deg % 45 == 0, isCardinal = deg % 90 == 0;
            int length = isCardinal ? cardinal : deg % 15 == 0 ? major : minor;
            vline(g, px, bottom - length, bottom, ColorMath.fade(TICK, a * (deg % 15 == 0 ? 1 : 0.55f)));
            if (deg % 15 != 0) continue;
            String label = isPoint ? tr("blockland.hud.compass." + POINTS[deg / 45]) : String.valueOf(deg);
            float s = isCardinal ? cardinalScale : isPoint ? pointScale : numberScale;
            int color = deg == 0 ? NORTH : isCardinal ? TEXT : isPoint ? TEXT : MUTED;
            float tw = UiFont.widthF(label) * s;
            float ly = y + (room - 8 * s) / 2f;
            Draw.text(g, label, px - tw / 2f, ly, s, ColorMath.fade(color, a * (isPoint ? 1 : 0.85f)), false);
        }
    }

    /** How much of a mark at {@code dx} from the middle shows: all of it, fading out over the outer 28 % of each half. */
    private static float edgeFade(float dx, float half) {
        float t = Mth.clamp((half - dx) / (half * 0.28f), 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** A 1 px line at a fractional x. */
    private static void vline(GuiGraphics g, float px, int y0, int y1, int color) {
        g.pose().pushMatrix();
        g.pose().translate(px - 0.5f, 0);
        g.fill(0, y0, 1, y1, color);
        g.pose().popMatrix();
    }

    /** Every ally (a team's mate, never anyone else) as a marker at their bearing. */
    private static void allies(GuiGraphics g, Player self, Vec3 eye, float pt, int x, int w, int y, int size, float ppd, float heading) {
        for (Player other : self.level().players()) {
            if (other == self || other.isSpectator() || !self.isAlliedTo(other)) continue;
            double ox = Mth.lerp(pt, other.xo, other.getX()), oz = Mth.lerp(pt, other.zo, other.getZ());
            int team = other.getTeamColor() & 0xFFFFFF;
            int color = team == 0xFFFFFF ? ALLY : 0xFF000000 | team;
            ally(g, x, w, y, size, ppd, Mth.wrapDegrees(bearing(eye, ox, oz) - heading), color);
        }
    }

    /**
     * A small triangle pointing up at {@code off} degrees from the middle, outlined; out of view it is held, smaller, at
     * that end of the strip.
     */
    private static void ally(GuiGraphics g, int x, int w, int y, int size, float ppd, float off, int color) {
        float half = w / 2f, dx = off * ppd;
        boolean held = Math.abs(dx) > half - size - 1;
        if (held) {
            dx = Math.signum(dx) * (half - size - 1);
            size = Math.max(2, size - 1);
        }
        g.pose().pushMatrix();
        g.pose().translate(x + half + dx, y);
        // the outline is the same triangle a pixel bigger all round
        for (int i = -1; i <= size; i++) g.fill(-i - 1, i, i + 2, i + 1, OUTLINE);
        for (int i = 0; i < size; i++) g.fill(-i, i, i + 1, i + 1, color);
        g.pose().popMatrix();
    }

    /** The fixed mark the strip slides past: a small triangle on the band's lower edge, pointing up. */
    private static void caret(GuiGraphics g, int cx, int bottom, int size) {
        for (int i = 0; i < size; i++) g.fill(cx - i, bottom - size + i, cx + i + 1, bottom - size + i + 1, CARET);
    }

    /** The bearing in a small plate under the caret, when there is room for it. */
    private static void plate(GuiGraphics g, float cx, int top, int room, float u, float heading) {
        if (room < 6) return;
        String text = String.valueOf(Math.round(heading) % 360);
        float s = Math.min(0.75f * u, (room - 1) / 8f);
        float tw = UiFont.widthF(text) * s;
        int pw = Math.round(tw + 6 * Math.max(0.6f, u)), ph = room;
        int px = Math.round(cx - pw / 2f);
        Draw.rect(g, px, top, pw, ph, PLATE);
        Draw.text(g, text, cx - tw / 2f, top + (ph - 8 * s) / 2f, s, TEXT, false);
    }
}
