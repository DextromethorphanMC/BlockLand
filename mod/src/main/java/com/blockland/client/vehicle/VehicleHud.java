package com.blockland.client.vehicle;

import com.blockland.client.font.UiFont;
import com.blockland.client.hud.layout.HudAnchor;
import com.blockland.client.hud.layout.HudElement;
import com.blockland.client.ui.Draw;
import com.blockland.vehicle.VehicleData;
import com.blockland.vehicle.VehicleEntity;
import com.blockland.vehicle.VehicleFrame;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

import static com.blockland.client.ui.Tr.tr;

/**
 * The rider's HUD (vehicle spec §1): a panel — a movable HUD element (UI spec §3) — with the vehicle, its health, the
 * speed, the seat's role, the seat weapon's rounds or its reload and the keys of this seat (wrapped, never cut off);
 * through a gunner's sight, a reticle. An aircraft's pilot sees airspeed and
 * altitude, both weapons, a stall or pull-up warning, the ring they steer with (the middle of the view) and where the
 * nose — the guns — points.
 */
final class VehicleHud {
    private static final int TEXT = 0xFFF2F4F7, MUTED = 0xFF9AA4B5, ACCENT = 0xFFE8B04A, PANEL = 0xB0101418;
    private static final int PANEL_W = 220, HINT_LINE = 9, WEAPONS_ROW = 13;

    /** The rider's panel, a movable HUD element (UI spec §3): above the hotbar by default. */
    static final HudElement PANEL_ELEMENT = new HudElement() {
        @Override
        public String id() {
            return "vehicle";
        }

        @Override
        public String nameKey() {
            return "blockland.hud.element.vehicle";
        }

        @Override
        public int width() {
            return PANEL_W;
        }

        @Override
        public int height() {
            Riding r = riding();
            return (r != null && r.pilot ? WEAPONS_ROW : 0) + 37 + hintLines(r == null ? tr("blockland.vehicle.hint.driver") : r.hint).size() * HINT_LINE + 3;
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
            return -64;
        }

        @Override
        public boolean visible() {
            return riding() != null;
        }

        @Override
        public void render(GuiGraphics g, DeltaTracker delta, int x, int y, boolean editing) {
            panel(g, riding(), x, y);
        }
    };

    private VehicleHud() {
    }

    /** What the local player rides and in which seat (null when not riding a vehicle). */
    private record Riding(VehicleEntity v, int seatIndex, VehicleData.Seat seat, boolean pilot, String hint) {
    }

    @Nullable
    private static Riding riding() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !(player.getVehicle() instanceof VehicleEntity v)) return null;
        int seatIndex = Math.max(0, v.seatOf(player));
        VehicleData.Seat seat = v.data.seats.get(Math.min(seatIndex, v.data.seats.size() - 1));
        boolean pilot = seatIndex == 0 && v.data.kind().flies();
        boolean gunner = !pilot && v.data.weaponIndexOf(seatIndex) >= 0;
        String hint = tr(pilot ? "blockland.vehicle.hint." + v.data.movement.kind : gunner ? "blockland.vehicle.hint.gunner"
                : seatIndex == 0 ? "blockland.vehicle.hint.driver" : "blockland.vehicle.hint.passenger");
        return new Riding(v, seatIndex, seat, pilot, hint);
    }

    /** The marks over the view (not part of the panel): the gun sight, a pilot's flight marks. */
    static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        Riding r = riding();
        if (r == null || mc.options.hideGui) return;
        int w = g.guiWidth(), h = g.guiHeight();
        if (ClientVehicles.sighting()) reticle(g, w / 2, h / 2);
        if (r.pilot) flightMarks(g, r.v, delta.getGameTimeDeltaPartialTick(false), w, h);
    }

    /** The key hints in lines of whole " · " parts that fit the panel (none is ever cut off). */
    private static List<String> hintLines(String hint) {
        float room = PANEL_W - 12, scale = 0.75f;
        List<String> lines = new ArrayList<>();
        String line = "";
        for (String part : hint.split(" · ")) {
            String joined = line.isEmpty() ? part : line + " · " + part;
            if (!line.isEmpty() && UiFont.width(joined) * scale > room) {
                lines.add(line);
                line = part;
            } else {
                line = joined;
            }
        }
        if (!line.isEmpty()) lines.add(line);
        return lines;
    }

    /** The panel with its top-left corner at (x, y); a sample of the driver's when not riding (the HUD editor). */
    private static void panel(GuiGraphics g, @Nullable Riding r, int x, int y) {
        if (r != null && r.pilot) {
            pilotWeapons(g, r.v, x, y, PANEL_W);
            y += WEAPONS_ROW;
        }
        List<String> hints = hintLines(r == null ? tr("blockland.vehicle.hint.driver") : r.hint);
        int panelH = 37 + hints.size() * HINT_LINE + 3;
        Draw.rect(g, x, y, PANEL_W, panelH, PANEL);
        Draw.rect(g, x, y, PANEL_W, 1, ACCENT);
        String name = r == null ? tr("itemGroup.blockland.vehicles") : Component.translatable("entity.blockland." + r.v.data.id).getString();
        Draw.text(g, name, x + 6, y + 5, 1f, TEXT, true);
        String role = r == null ? tr("blockland.vehicle.role.driver")
                : tr("blockland.vehicle.role." + r.seat.role) + " · " + (r.seatIndex + 1) + "/" + r.v.data.seats.size();
        Draw.text(g, role, x + PANEL_W - 6 - UiFont.width(role) * 0.75f, y + 6, 0.75f, MUTED, false);

        // health
        float health = r == null ? 1 : Mth.clamp(r.v.health() / Math.max(1, r.v.data.health), 0, 1);
        int barW = PANEL_W - 12;
        Draw.rect(g, x + 6, y + 18, barW, 3, 0x40FFFFFF);
        int color = health > 0.5f ? 0xFF7CDB5A : health > 0.25f ? 0xFFE8B04A : 0xFFE0463C;
        Draw.rect(g, x + 6, y + 18, Math.round(barW * health), 3, color);

        // speed, or the seat's weapon
        String line = tr("blockland.vehicle.hud.speed", 0);
        if (r != null) {
            VehicleEntity v = r.v;
            float speed = v.data.kind().flies() ? v.airspeed() : r.seatIndex == 0 ? (float) Math.abs(v.forwardSpeed()) * 20 : Math.abs(v.speed());
            line = tr("blockland.vehicle.hud.speed", Math.round(speed * 3.6f));
            int weapon = v.data.weaponIndexOf(r.seatIndex);
            if (r.pilot) {
                line = tr("blockland.vehicle.hud.flight", Math.round(speed * 3.6f), Math.round(v.getY()));
            } else if (weapon >= 0) {
                VehicleData.Weapon wd = v.data.weapons.get(weapon);
                int reload = v.reloadLeft(weapon);
                line = reload > 0
                        ? tr("blockland.vehicle.hud.reloading", Math.round(100 * (1 - reload / (float) wd.reloadTicks())))
                        : tr("blockland.vehicle.hud.ammo", weaponName(v, wd), v.ammo(weapon), wd.magazine);
            }
        }
        Draw.text(g, line, x + 6, y + 25, 1f, TEXT, true);
        for (int i = 0; i < hints.size(); i++) Draw.text(g, hints.get(i), x + 6, y + 37 + i * HINT_LINE, 0.75f, MUTED, false);
    }

    /** A weapon's name: the vehicle's own for it ({@code blockland.vehicle.weapon.<vehicle>.<weapon>}) or the common one. */
    private static String weaponName(VehicleEntity v, VehicleData.Weapon w) {
        String own = "blockland.vehicle.weapon." + v.data.id + "." + w.id;
        return tr(net.minecraft.client.resources.language.I18n.exists(own) ? own : "blockland.vehicle.weapon." + w.id);
    }

    /** The pilot's weapons over the panel: each one's rounds, or how far its reload has come. */
    private static void pilotWeapons(GuiGraphics g, VehicleEntity v, int x, int y, int panelW) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < v.data.weapons.size(); i++) {
            VehicleData.Weapon wd = v.data.weapons.get(i);
            if (wd.seat != 0) continue;
            if (!text.isEmpty()) text.append("   ");
            int reload = v.reloadLeft(i);
            text.append(reload > 0 ? tr("blockland.vehicle.hud.reloading_named", weaponName(v, wd),
                    Math.round(100 * (1 - reload / (float) wd.reloadTicks())))
                    : tr("blockland.vehicle.hud.ammo", weaponName(v, wd), v.ammo(i), wd.magazine));
        }
        if (text.isEmpty()) return;
        String line = text.toString();
        Draw.rect(g, x, y, panelW, 12, PANEL);
        Draw.text(g, Draw.fit(line, (int) ((panelW - 12) / 0.8f)), x + 6, y + 3, 0.8f, TEXT, false);
    }

    /**
     * The pilot's marks: a ring in the middle of the view (where they ask it to fly), a mark where the nose and its guns
     * point, and a warning when a jet stalls or the ground comes up fast.
     */
    private static void flightMarks(GuiGraphics g, VehicleEntity v, float pt, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        int cx = w / 2, cy = h / 2;
        Draw.outline(g, cx - 5, cy - 5, 10, 10, 0xA0F2F4F7);
        Draw.rect(g, cx, cy, 1, 1, 0xE0F2F4F7);
        // where the first pilot weapon points (the nose for a jet)
        int weapon = -1;
        for (int i = 0; i < v.data.weapons.size() && weapon < 0; i++) if (v.data.weapons.get(i).seat == 0) weapon = i;
        float yaw = Mth.rotLerp(pt, v.yRotO, v.getYRot());
        Vec3 dir = weapon >= 0 ? v.muzzle(weapon)[1] : VehicleFrame.direction(VehicleFrame.hull(yaw, v.hullPitch(pt), v.hullRoll(pt)), 0, 0, -1);
        float[] at = project(mc.gameRenderer.getMainCamera(), dir, w, h);
        if (at != null) {
            int px = Math.round(at[0]), py = Math.round(at[1]);
            Draw.rect(g, px - 12, py, 8, 1, ACCENT);
            Draw.rect(g, px + 5, py, 8, 1, ACCENT);
            Draw.outline(g, px - 3, py - 3, 7, 7, ACCENT);
        }
        String warning = null;
        boolean airborne = !v.onGround();
        if (v.data.kind() == VehicleData.Kind.JET && airborne && v.airspeed() < v.data.movement.takeoffSpeed) {
            warning = "blockland.vehicle.hud.stall";
        }
        double sink = -v.getDeltaMovement().y * 20;
        if (airborne && sink > 8 && v.heightAboveGround(sink * 3) < sink * 3) warning = "blockland.vehicle.hud.pull_up";
        if (warning != null && (mc.level == null || (mc.level.getGameTime() / 5) % 2 == 0)) {
            String text = tr(warning);
            Draw.text(g, text, cx - UiFont.width(text) * 0.6f, cy + 26, 1.2f, 0xFFE0463C, true);
        }
    }

    /** A world direction's place on the screen (GUI units), or null behind the camera. */
    private static float[] project(Camera camera, Vec3 dir, int w, int h) {
        Vector3f look = camera.getLookVector(), up = camera.getUpVector(), left = camera.getLeftVector();
        double z = dir.x * look.x() + dir.y * look.y() + dir.z * look.z();
        if (z < 0.05) return null;
        double x = -(dir.x * left.x() + dir.y * left.y() + dir.z * left.z()) / z, y = (dir.x * up.x() + dir.y * up.y() + dir.z * up.z()) / z;
        double scale = (h / 2.0) / Math.tan(Math.toRadians(ClientVehicles.fov()) / 2);
        return new float[]{(float) (w / 2.0 + x * scale), (float) (h / 2.0 - y * scale)};
    }

    /** A gun sight: a centre ring, a fine cross and range ticks under it. */
    private static void reticle(GuiGraphics g, int cx, int cy) {
        int c = 0xE0F2F4F7, dim = 0x90F2F4F7;
        Draw.rect(g, cx - 1, cy - 1, 2, 2, c);
        Draw.rect(g, cx - 40, cy, 30, 1, c);
        Draw.rect(g, cx + 10, cy, 30, 1, c);
        Draw.rect(g, cx, cy + 10, 1, 30, c);
        for (int i = 1; i <= 4; i++) Draw.rect(g, cx - 4 - i, cy + 10 + i * 6, 9 + 2 * i, 1, dim);
        Draw.outline(g, cx - 8, cy - 8, 16, 16, dim);
    }
}
