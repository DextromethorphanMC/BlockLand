package com.blockland.client.viewmodel;

import net.minecraft.client.Camera;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Things that briefly take over the hands of the first-person weapon without the weapon module knowing them (the ammo
 * crate's resupply, asset spec §46): an overlay may keep the player's hands from the weapon (no firing, aiming,
 * reloading or inspecting while it plays), claim the use button (no aiming), move the weapon's root and change the posed
 * rig (an arm leaving the weapon) and queue meshes of its own. The weapon module only asks and calls here; overlays
 * register from their own modules.
 */
public final class WeaponOverlays {
    /** One overlay; every method is asked every frame, so an idle overlay simply answers "nothing". */
    public interface Overlay {
        /** The hands are busy with something else: no firing, aiming, reloading or inspecting. */
        default boolean busy() {
            return false;
        }

        /** The use button means something else right now (a crate under the crosshair): no aiming. */
        default boolean claimsUse() {
            return false;
        }

        /**
         * Turns and moves the first-person camera (the head) for this frame: {@code angles} are the view's pitch, yaw and
         * roll in degrees so far (Minecraft's: pitch + looks down), to add to; the camera may be placed elsewhere.
         */
        default void adjustCamera(Camera camera, float[] angles) {
        }

        /** Moves the viewmodel root (viewmodel space), after the weapon's own motion, aim and recoil. */
        default void adjustRoot(Matrix4f root) {
        }

        /**
         * The rig is posed (its node matrices, {@link ViewmodelRig#world}, may be changed now: they are drawn next);
         * extra meshes may be queued into {@link ViewmodelRig#draws}. {@code base} maps viewmodel space to view space.
         */
        default void afterPose(ViewmodelRig<?> rig, Matrix4f base, int blockLight, int skyLight) {
        }
    }

    private static final List<Overlay> overlays = new ArrayList<>();

    private WeaponOverlays() {
    }

    public static void register(Overlay overlay) {
        overlays.add(overlay);
    }

    public static boolean busy() {
        for (Overlay o : overlays) if (o.busy()) return true;
        return false;
    }

    public static boolean claimsUse() {
        for (Overlay o : overlays) if (o.claimsUse()) return true;
        return false;
    }

    public static void adjustCamera(Camera camera, float[] angles) {
        for (Overlay o : overlays) o.adjustCamera(camera, angles);
    }

    public static void adjustRoot(Matrix4f root) {
        for (Overlay o : overlays) o.adjustRoot(root);
    }

    public static void afterPose(ViewmodelRig<?> rig, Matrix4f base, int blockLight, int skyLight) {
        for (Overlay o : overlays) o.afterPose(rig, base, blockLight, skyLight);
    }
}
