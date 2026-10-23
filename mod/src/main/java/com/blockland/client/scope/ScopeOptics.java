package com.blockland.client.scope;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;

import javax.annotation.Nullable;

/**
 * The optics of a magnified scope as plain maths (no Minecraft): where the eye sits against the eyepiece, which part
 * of the field reaches it, what a point of the eyepiece shows and where the reticle lands. The eyepiece shader
 * ({@code shaders/core/scope_eyepiece.fsh}) does the same per pixel; keep the two in step.
 * <p>
 * Model: the scope magnifies angles about its optical axis. A ray from the eye through the eyepiece is expressed as a
 * point {@code t} on the tangent plane around the axis; the field stop (the sharp edge of the picture) is the circle
 * {@code |t| = fieldTan} and is anchored to the axis, not to the glass, so when the eye or the rifle moves off axis the
 * field slides against the eyepiece rim and a dark crescent appears — like looking down a tube. On top of that the exit
 * pupil (eye box and eye relief) shrinks the lit circle when the eye is too far off axis, too far back or too close.
 * The picture is a second render of the world along the axis whose field of view is the field stop divided by the
 * magnification; the reticle sits in the focal plane, so it is anchored to the axis too and only drifts against the
 * picture by its parallax when the eye is off axis.
 */
public final class ScopeOptics {
    /** The picture is rendered this much wider than the field stop (room for the rim's distortion). */
    public static final float PICTURE_MARGIN = 1.08f;
    /** How far the lit circle moves per eye box of eye offset (the dark crescent grows on the side the eye moved to). */
    public static final float PUPIL_SHIFT = 0.25f;
    /** How far the reticle drifts against the picture per eye box, at full parallax (in field stops). */
    public static final float PARALLAX_DRIFT = 0.15f;
    /** Share of the light the glass lets through. */
    public static final float TRANSMISSION = 0.94f;

    private ScopeOptics() {
    }

    /**
     * Tangent of the field stop's half angle in the world view's terms: the eyepiece rim seen from the eye point,
     * converted from the viewmodel's narrower projection to the world's (the picture must line up with the world
     * around the scope, which is drawn with the world's field of view).
     */
    public static float fieldTan(float lensRadius, float eyeRelief, float tanWorld, float tanViewmodel) {
        return lensRadius / Math.max(1e-4f, eyeRelief) * (tanWorld / tanViewmodel);
    }

    /**
     * Magnification against the view around the scope. The data's magnification is against the unzoomed view; the
     * view around the scope is already zoomed a little by the optic's {@code zoom} (the world FOV was divided by it).
     */
    public static float relativeMagnification(float magnification, float worldFovDegrees, float zoomDivisor) {
        double world = Math.toRadians(Math.min(179, worldFovDegrees)) / 2;
        double unzoomed = Math.toRadians(Math.min(179, worldFovDegrees * Math.max(1, zoomDivisor))) / 2;
        return (float) (magnification * Math.tan(world) / Math.tan(unzoomed));
    }

    /** Vertical (= horizontal, the picture is square) field of view of the scope picture in degrees. */
    public static float pictureFov(float fieldTan, float relativeMagnification) {
        return (float) Math.toDegrees(2 * Math.atan(PICTURE_MARGIN * fieldTan / Math.max(1, relativeMagnification)));
    }

    /**
     * Rotation from camera space into the scope's frame (the optical axis becomes −Z), with the axis given in the
     * viewmodel's space; {@code k} = tan(world half FOV) / tan(viewmodel half FOV) converts it to the world's view.
     */
    public static Quaternionf cameraToScope(Vector3f axisViewmodel, float k, Quaternionf into) {
        Vector3f axis = worldDirection(axisViewmodel, k, new Vector3f());
        return into.rotationTo(0, 0, -1, axis.x, axis.y, axis.z).conjugate();
    }

    /** A viewmodel-space direction as the world view draws the same screen point. */
    public static Vector3f worldDirection(Vector3f viewmodel, float k, Vector3f into) {
        return into.set(viewmodel.x * k, viewmodel.y * k, viewmodel.z).normalize();
    }

    /**
     * The tangent-plane point around the axis for a screen point, in field stops (1 = the field stop's edge).
     *
     * @param ndcX       screen x in normalised device coordinates
     * @param tanWorldX  tan(world half FOV) × aspect
     * @param cameraToScope from {@link #cameraToScope}
     */
    public static Vector2f fieldPoint(float ndcX, float ndcY, float tanWorldX, float tanWorldY, Quaternionf cameraToScope,
                                      float fieldTan, Vector2f into) {
        Vector3f ray = cameraToScope.transform(new Vector3f(ndcX * tanWorldX, ndcY * tanWorldY, -1));
        float z = Math.max(1e-4f, -ray.z);
        return into.set(ray.x / z / fieldTan, ray.y / z / fieldTan);
    }

    /** Texture coordinate of the scope picture for a field point (render-target textures have v = 0 at the bottom). */
    public static Vector2f pictureUv(Vector2f field, float distortion, Vector2f into) {
        float r2 = field.lengthSquared();
        float scale = (1 + distortion * r2) / PICTURE_MARGIN;
        return into.set(0.5f + 0.5f * field.x * scale, 0.5f + 0.5f * field.y * scale);
    }

    /**
     * The eye against the eyepiece: x/y = offset from the optical axis in eye boxes, z = eye relief error (0 at the
     * eye point, > 0 too far back, < 0 too close).
     *
     * @param eyeInLens  the eye (camera origin) in the eyepiece node's own space
     * @param lensCentre centre of the eyepiece glass in the same space
     * @param relief     distance from the glass to the eye point along +Z, same space
     * @param eyeBox     eye box in the same space's units
     */
    public static Vector3f eye(Vector3f eyeInLens, Vector3f lensCentre, float relief, float eyeBox, Vector3f into) {
        float box = Math.max(1e-5f, eyeBox);
        float r = Math.max(1e-5f, relief);
        return into.set((eyeInLens.x - lensCentre.x) / box, (eyeInLens.y - lensCentre.y) / box,
                (eyeInLens.z - lensCentre.z - r) / r);
    }

    /** The eye in the eyepiece node's own space, from the node's view-space matrix (the eye is the camera origin). */
    public static Vector3f eyeInLens(Matrix4f lensView, Vector3f into) {
        return new Matrix4f(lensView).invert().transformPosition(into.set(0, 0, 0));
    }

    /** Light reaching the eye from a field point through the exit pupil (1 = all of it). */
    public static float pupil(Vector2f field, Vector3f eye) {
        float radius = pupilRadius(eye);
        // the shadow's edge softens as the eye wanders off the axis
        float edge = 0.18f + 0.12f * Math.min((float) Math.sqrt(eye.x * eye.x + eye.y * eye.y), 2);
        float dx = field.x + eye.x * PUPIL_SHIFT, dy = field.y + eye.y * PUPIL_SHIFT;
        return 1 - smoothstep(radius - edge, radius + 0.02f, (float) Math.sqrt(dx * dx + dy * dy));
    }

    /**
     * Radius of the lit circle in field stops: past the field stop (> 1) at the eye point, shrinking as the eye moves
     * back, forward or out of the eye box; at or below −0.02 no light reaches the eye at all.
     */
    public static float pupilRadius(Vector3f eye) {
        float offset = (float) Math.sqrt(eye.x * eye.x + eye.y * eye.y);
        return 1.12f - 1.8f * Math.max(eye.z, 0) - 1.2f * Math.max(-eye.z, 0) - 0.3f * Math.max(offset - 1, 0);
    }

    /** The field stop: 1 inside, 0 outside, with a thin soft edge. */
    public static float field(float r) {
        return 1 - smoothstep(0.985f, 1f, r);
    }

    /** Darkening towards the rim. */
    public static float vignette(float r, float strength) {
        float c = Math.min(1, r);
        return 1 - strength * c * c * c;
    }

    /**
     * Texture coordinate of the reticle for a field point: drifted by the parallax when the eye is off axis, turned with
     * the scope's cant, scaled so the texture spans {@code reticleSize} of the field's diameter. Image textures have
     * v = 0 at the top.
     */
    public static Vector2f reticleUv(Vector2f field, Vector3f eye, float parallax, float reticleSize, float cos, float sin,
                                     Vector2f into) {
        float px = field.x + eye.x * parallax * PARALLAX_DRIFT, py = field.y + eye.y * parallax * PARALLAX_DRIFT;
        float rx = cos * px + sin * py, ry = -sin * px + cos * py;
        float scale = 1 / Math.max(0.05f, reticleSize);
        return into.set(0.5f + 0.5f * rx * scale, 0.5f - 0.5f * ry * scale);
    }

    /** Look sensitivity multiplier through a scope: turning matches the magnified picture. */
    public static float sensitivity(float magnification, float extra) {
        return extra / Math.max(1, magnification);
    }

    /**
     * The reticle's width as a share of the field's diameter at the current power. A second-focal-plane reticle keeps
     * its size; a first-focal-plane one sits in front of the zoom lenses, so it grows with the power (its marks keep
     * their angle in the world) — authored at the scope's starting power.
     */
    public static float reticleSize(float authoredSize, boolean firstFocalPlane, float magnification, float authoredMagnification) {
        float size = Math.max(0.05f, authoredSize);
        return firstFocalPlane ? size * magnification / Math.max(1e-3f, authoredMagnification) : size;
    }

    /**
     * Where the sun is against the field stop (1 = the edge), from its direction in the scope's frame; null when it is
     * behind the shooter's side of the scope.
     */
    @Nullable
    public static Vector2f sunInField(Vector3f sunInScope, float fieldTan, Vector2f into) {
        float ahead = -sunInScope.z;
        if (ahead <= 0.05f) return null;
        return into.set(sunInScope.x / ahead / fieldTan, sunInScope.y / ahead / fieldTan);
    }

    /** Direction of the sun in the world at this sun angle (radians, Minecraft's {@code getSunAngle}): east at dawn, up at noon. */
    public static Vector3f sunDirection(float sunAngle, Vector3f into) {
        return into.set((float) -Math.sin(sunAngle), (float) Math.cos(sunAngle), 0);
    }

    /** Minecraft yaw (degrees) of a world direction. */
    public static float yaw(Vector3f direction) {
        return (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
    }

    /** Minecraft pitch (degrees, positive = down) of a world direction. */
    public static float pitch(Vector3f direction) {
        float length = direction.length();
        return (float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, direction.y / Math.max(1e-6f, length)))));
    }

    /**
     * The aimed rifle's breathing sway at time {@code t} (s), as yaw and pitch in units of the amplitude: a slow drift
     * that never repeats exactly (two incommensurate periods per axis), wider sideways than up and down.
     */
    public static Vector2f sway(float t, Vector2f into) {
        float yaw = (float) (0.78 * Math.sin(0.83 * t) + 0.26 * Math.sin(1.97 * t + 1.3));
        float pitch = (float) (0.62 * Math.sin(1.21 * t + 0.4) + 0.18 * Math.sin(2.63 * t + 2.1));
        return into.set(yaw, pitch);
    }

    static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0, Math.min(1, (x - edge0) / (edge1 - edge0)));
        return t * t * (3 - 2 * t);
    }
}
