package com.blockland.client.hud.minimap;

import com.blockland.client.font.UiFont;
import com.blockland.client.hud.layout.HudAnchor;
import com.blockland.client.hud.layout.HudElement;
import com.blockland.client.perf.FrameTimings;
import com.blockland.client.ui.Draw;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

import static com.blockland.client.ui.Tr.tr;

/**
 * The minimap on the HUD (UI spec §3.4): a framed square of the terrain round the player — turning with them (their
 * way up, north marked on the rim) or north up —, the player an arrow in the middle, allies as dots (pinned to the rim
 * when further out; never anyone else), the player's block coordinates under it. One textured quad of the
 * {@link MinimapCache}'s picture, turned through the GUI pose and clipped to the square.
 */
final class MinimapElement implements HudElement {
    private static final int PAD = 3, LINE = 11;
    private static final int PANEL = 0xB0101418, EDGE = 0x40FFFFFF, TEXT = 0xFFF2F4F7, MUTED = 0xFF9AA4B5;
    private static final int ARROW = 0xFFFFFFFF, ARROW_EDGE = 0xFF101418, ALLY = 0xFF5AC8FA, NORTH = 0xFFE8B04A;

    private final MinimapCache cache;

    MinimapElement(MinimapCache cache) {
        this.cache = cache;
    }

    @Override
    public String id() {
        return "minimap";
    }

    @Override
    public String nameKey() {
        return "blockland.hud.element.minimap";
    }

    private static int size() {
        return Minimap.settings().size;
    }

    @Override
    public int width() {
        return size() + PAD * 2;
    }

    @Override
    public int height() {
        return size() + PAD * 2 + LINE;
    }

    /** Top left: the kill feed and vanilla's effect icons hold the top right, the 大逃杀 map too. */
    @Override
    public HudAnchor defaultAnchor() {
        return HudAnchor.TOP_LEFT;
    }

    @Override
    public int defaultX() {
        return 6;
    }

    @Override
    public int defaultY() {
        return 6;
    }

    /** Not with the F3 screen (it writes over the top left), nor while a mode's own map shows. */
    @Override
    public boolean visible() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.level != null && !Minimap.hidden() && !mc.getDebugOverlay().showDebugScreen();
    }

    @Override
    public void render(GuiGraphics g, DeltaTracker delta, int x, int y, boolean editing) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        int s = size(), mapX = x + PAD, mapY = y + PAD;
        Draw.rect(g, x, y, s + PAD * 2, s + PAD * 2, PANEL);
        if (player == null || mc.level == null || !cache.ready()) {
            placeholder(g, mapX, mapY, s);
            return;
        }
        try (FrameTimings.Span ignored = FrameTimings.span("bl/hud/minimap")) {
            float pt = delta.getGameTimeDeltaPartialTick(false);
            map(g, player, pt, mapX, mapY, s);
        }
        Draw.outline(g, mapX - 1, mapY - 1, s + 2, s + 2, EDGE);
        String coords = Mth.floor(player.getX()) + "  " + Mth.floor(player.getY()) + "  " + Mth.floor(player.getZ());
        float scale = 0.75f;
        Draw.text(g, coords, mapX + (s - UiFont.width(coords) * scale) / 2f, mapY + s + PAD + 1, scale, TEXT, true);
    }

    private void map(GuiGraphics g, Player player, float pt, int mapX, int mapY, int s) {
        MinimapSettings settings = Minimap.settings();
        double px = Mth.lerp(pt, player.xo, player.getX()), pz = Mth.lerp(pt, player.zo, player.getZ());
        float yaw = Mth.lerp(pt, player.yRotO, player.getYRot());
        // the map turns so the way the player faces is up: their facing, (-sin yaw, cos yaw), lies at 90° + yaw on screen
        float angle = settings.rotate ? -(180 + yaw) * Mth.DEG_TO_RAD : 0;
        float k = s / (float) settings.range;
        float cx = mapX + s / 2f, cy = mapY + s / 2f;
        g.enableScissor(mapX, mapY, mapX + s, mapY + s);
        // a quad big enough to cover the square however it turns; the texture wraps, so the coordinates stay small
        int d = (int) Math.ceil(s * 0.75f);
        float ext = d / k, size = MinimapGrid.SIZE;
        float bx = (float) (px - Math.floor(px / size) * size), bz = (float) (pz - Math.floor(pz / size) * size);
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().rotate(angle);
        g.blit(MinimapCache.TEXTURE, -d, -d, d, d, (bx - ext) / size, (bx + ext) / size, (bz - ext) / size, (bz + ext) / size);
        g.pose().popMatrix();
        allies(g, player, pt, px, pz, angle, k, cx, cy, s);
        // the player: an arrow in the middle, their way (up when the map turns)
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        if (!settings.rotate) g.pose().rotate((yaw + 180) * Mth.DEG_TO_RAD);
        arrow(g);
        g.pose().popMatrix();
        g.disableScissor();
        north(g, angle, cx, cy, s, settings.rotate);
    }

    /** Allies only (a team's mates): a dot each, held on the rim when they are off the map. */
    private static void allies(GuiGraphics g, Player self, float pt, double px, double pz, float angle, float k, float cx, float cy, int s) {
        float cos = Mth.cos(angle), sin = Mth.sin(angle), edge = s / 2f - 2;
        for (Player other : self.level().players()) {
            if (other == self || other.isSpectator() || !self.isAlliedTo(other)) continue;
            float dx = (float) (Mth.lerp(pt, other.xo, other.getX()) - px) * k, dz = (float) (Mth.lerp(pt, other.zo, other.getZ()) - pz) * k;
            float sx = dx * cos - dz * sin, sy = dx * sin + dz * cos;
            float far = Math.max(Math.abs(sx), Math.abs(sy));
            if (far > edge) {
                sx *= edge / far;
                sy *= edge / far;
            }
            int ix = Math.round(cx + sx), iy = Math.round(cy + sy);
            g.fill(ix - 2, iy - 2, ix + 2, iy + 2, ARROW_EDGE);
            g.fill(ix - 1, iy - 1, ix + 1, iy + 1, ALLY);
        }
    }

    /** An arrow pointing up from the pose's origin, outlined so it reads on any ground. */
    private static void arrow(GuiGraphics g) {
        for (int pass = 0; pass < 2; pass++) {
            int grow = pass == 0 ? 1 : 0, color = pass == 0 ? ARROW_EDGE : ARROW;
            for (int i = 0; i < 7; i++) {
                int w = (i + 1) / 2 + grow;
                // the tail's notch: its last two rows leave the middle open
                if (i >= 5 && pass == 1) {
                    g.fill(-w, -4 + i, -w + 2, -3 + i, color);
                    g.fill(w - 1, -4 + i, w + 1, -3 + i, color);
                    continue;
                }
                g.fill(-w, -4 + i - grow, w + 1, -3 + i + grow, color);
            }
        }
    }

    /** North on the rim: where it lies as the map turns, or the top middle. */
    private static void north(GuiGraphics g, float angle, float cx, float cy, int s, boolean rotate) {
        float dx = rotate ? Mth.sin(angle) : 0, dy = rotate ? -Mth.cos(angle) : -1;
        float t = (s / 2f - 5) / Math.max(Math.abs(dx), Math.abs(dy));
        float nx = cx + dx * t, ny = cy + dy * t;
        String n = tr("blockland.minimap.north");
        float scale = 0.75f, w = UiFont.width(n) * scale;
        Draw.rect(g, Math.round(nx - 4), Math.round(ny - 4), 9, 9, 0xC0101418);
        Draw.text(g, n, nx - w / 2f + 0.5f, ny - 3, scale, NORTH, false);
    }

    /** No world to draw (the editor without one): the frame, a faint grid and the name. */
    private static void placeholder(GuiGraphics g, int mapX, int mapY, int s) {
        for (int i = 1; i < 4; i++) {
            g.fill(mapX + s * i / 4, mapY, mapX + s * i / 4 + 1, mapY + s, 0x20FFFFFF);
            g.fill(mapX, mapY + s * i / 4, mapX + s, mapY + s * i / 4 + 1, 0x20FFFFFF);
        }
        Draw.outline(g, mapX - 1, mapY - 1, s + 2, s + 2, EDGE);
        String name = tr("blockland.hud.element.minimap");
        Draw.text(g, name, mapX + (s - UiFont.width(name) * 0.8f) / 2f, mapY + s / 2f - 3, 0.8f, MUTED, false);
    }
}
