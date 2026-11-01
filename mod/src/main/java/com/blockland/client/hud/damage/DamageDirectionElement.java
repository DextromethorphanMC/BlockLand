package com.blockland.client.hud.damage;

import com.blockland.BlockLand;
import com.blockland.client.hud.layout.HudAnchor;
import com.blockland.client.hud.layout.HudElement;
import com.blockland.client.hud.layout.HudLayout;
import com.blockland.client.render.RenderClock;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The damage direction arrows as a HUD element (UI spec §3.5): round the crosshair by default, its box the ring's size
 * (square, resizable in the editor). Each arrow's bearing and height are taken afresh every frame from where the
 * camera looks to the attacker (followed while it can be seen) or the point the hurt came from.
 */
final class DamageDirectionElement implements HudElement {
    private static final Resize RESIZE = new Resize(144, 144, 64, 64, 280, 280, true);
    /** The editor's sample: one ahead to the left, one behind, a plate hit from the right above. */
    private static final List<ArrowShape.Pose> SAMPLE = List.of(
            new ArrowShape.Pose((float) Math.toRadians(-40), 0, 0.7f, 0, 0.6f, 0.5f),
            new ArrowShape.Pose((float) Math.toRadians(160), (float) Math.toRadians(-12), 1, 0, 0.6f, 1),
            new ArrowShape.Pose((float) Math.toRadians(75), (float) Math.toRadians(35), 0.5f, 1, 0.6f, 0.5f));

    @Override
    public String id() {
        return "damage_direction";
    }

    @Override
    public String nameKey() {
        return "blockland.hud.element.damage_direction";
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
        return HudAnchor.CENTER;
    }

    @Override
    public int defaultX() {
        return 0;
    }

    @Override
    public int defaultY() {
        return 0;
    }

    @Override
    public boolean visible() {
        return Minecraft.getInstance().player != null && !DamageIndicator.arrows().isEmpty();
    }

    @Override
    public void render(GuiGraphics g, DeltaTracker delta, int x, int y, boolean editing) {
        DamageIndicatorData d = DamageIndicator.data();
        int size = width();
        ArrowShape shape = new ArrowShape(d, new RingView(d, x + size / 2f, y + size / 2f, size));
        QuadMesh glow = new QuadMesh(), body = new QuadMesh();
        List<DamageArrow> arrows = DamageIndicator.arrows();
        if (editing && arrows.isEmpty()) {
            for (ArrowShape.Pose p : SAMPLE) shape.build(p, 1, glow, body);
        } else {
            live(d, arrows, shape, glow, body);
        }
        glow.submit(g);
        body.submit(g);
        // the first frame of each new hit, for the debug log
        long newest = arrows.stream().mapToLong(a -> a.last).max().orElse(0);
        if (!editing && newest != logged) {
            logged = newest;
            BlockLand.LOGGER.debug("[BlockLand] Damage arrows: drawing {} at ({}, {}) size {} — {} body quads", arrows.size(), x, y, size,
                    body.quads());
        }
    }

    private static long logged;

    private static void live(DamageIndicatorData d, List<DamageArrow> arrows, ArrowShape shape, QuadMesh glow, QuadMesh body) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 eye = camera.getPosition();
        // the bearing is taken on the level, against where the camera faces: looking up or down turns no arrow over
        double yaw = Math.toRadians(camera.getYRot());
        double forwardX = -Math.sin(yaw), forwardZ = Math.cos(yaw), rightX = -forwardZ, rightZ = forwardX;
        float partial = camera.getPartialTickTime();
        long now = RenderClock.nanos();
        for (DamageArrow arrow : arrows) {
            // an attacker that can still be seen is followed as it moves; one that is gone leaves its last place
            if (arrow.attacker != 0) {
                Entity e = mc.level.getEntity(arrow.attacker);
                if (e != null && !e.isRemoved()) arrow.at = e.getEyePosition(partial);
                else arrow.attacker = 0;
            }
            double dx = arrow.at.x - eye.x, dy = arrow.at.y - eye.y, dz = arrow.at.z - eye.z;
            double ahead = dx * forwardX + dz * forwardZ, right = dx * rightX + dz * rightZ;
            float bearing = (float) Math.atan2(right, ahead);
            // how far above or below the eyes the attacker is (from the level, not from where the camera looks)
            float elevation = (float) Math.atan2(dy, Math.sqrt(dx * dx + dz * dz));
            float age = arrow.age(now);
            float alpha = age <= d.hold ? 1 : 1 - smooth((age - d.hold) / Math.max(0.01f, d.fade));
            shape.build(new ArrowShape.Pose(bearing, elevation, arrow.strength(now, d), arrow.plateShare(), age, arrow.kick), alpha, glow, body);
        }
    }

    private static float smooth(float x) {
        float t = Math.max(0, Math.min(1, x));
        return t * t * (3 - 2 * t);
    }
}
