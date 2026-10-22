package com.blockland.client.gun;

import com.blockland.BlockLand;
import com.blockland.anim.AnimValue;
import com.blockland.anim.Ease;
import com.blockland.anim.Spring;
import com.blockland.client.gltf.Pose;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.Textures;
import com.blockland.client.viewmodel.ViewmodelRenderer;
import com.blockland.client.viewmodel.ViewmodelRig;
import com.blockland.gun.GunData;
import com.blockland.gun.GunState;
import com.blockland.gun.GunStats;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.util.Random;
import java.util.function.Consumer;

/**
 * First-person gun: drives the shared {@link ViewmodelRig} (draw / fire / reload / inspect clips over the idle,
 * player arms, procedural motion) and adds what only guns have — aiming down the fitted sight, spring recoil, the
 * slide locked back on an empty magazine, hidden unfitted attachments, the muzzle flash and the laser beam.
 * Pure presentation: the controller decides when to fire or reload.
 */
public final class Viewmodel {
    private static final Vector3f HIP_PIVOT = new Vector3f(0.11f, -0.11f, -0.30f);

    public enum Action { NONE, DRAW, FIRE, RELOAD, INSPECT }

    private final Random random = new Random();
    private final ViewmodelRig<Action> rig = new ViewmodelRig<>();

    @Nullable
    private GunData.Gun gun;
    @Nullable
    private GunStats stats;
    private boolean ready;
    private boolean[] hidden = new boolean[0];
    private String visibilityKey = "";
    private int muzzle = -1, ejection = -1, laserEmitter = -1, slide = -1, nodeCount;
    private final Vector3f adsTranslation = new Vector3f();
    private final Quaternionf adsRotation = new Quaternionf();
    private boolean adsResolved;
    /** The magazine is empty this frame: the base pose holds the slide back. */
    private boolean slideLocked;

    // procedural layers
    public final AnimValue ads = new AnimValue(0);
    private final Spring kickBack = new Spring(0).config(520, 26);
    private final Spring kickPitch = new Spring(0).config(360, 20);
    private final Spring kickYaw = new Spring(0).config(300, 20);
    private final Spring kickRoll = new Spring(0).config(260, 15);
    private float flash;
    private float flashRoll;
    private float flashScale;

    // scratch (no per-frame allocation)
    private final Matrix4f root = new Matrix4f();
    private final Matrix4f modelView = new Matrix4f();
    private final Quaternionf q = new Quaternionf();

    public Viewmodel() {
        rig.setBaseModifier(this::lockSlide);
    }

    // ---- equip ------------------------------------------------------------------------------------

    /** @return false when the gun has no usable model (vanilla rendering is kept then) */
    public boolean equip(GunData.Gun gun) {
        this.gun = gun;
        visibilityKey = "";
        adsResolved = false;
        ready = rig.load(BlockLand.id(gun.model), gun.anims.idle, gun.anims.sprint);
        if (!ready) return false;
        muzzle = rig.node("muzzle");
        ejection = rig.node("ejection");
        laserEmitter = rig.node("laser_emitter");
        slide = rig.node(gun.slideLock.node);
        nodeCount = rig.model().source.nodes.size();
        ads.snap(0);
        flash = 0;
        play(Action.DRAW, gun.anims.draw, gun.drawTime);
        return true;
    }

    public boolean isReady() {
        return ready && rig.isReady();
    }

    public void setStats(GunStats stats) {
        this.stats = stats;
    }

    public void setEventSink(Consumer<String> sink) {
        rig.setEventSink(sink);
    }

    // ---- actions ----------------------------------------------------------------------------------

    /**
     * Plays a one-shot clip, stretched to {@code seconds} when given (> 0) so animations always match the
     * gameplay timing in guns.json. Shots snap in (they are short and must feel instant); everything else fades in.
     */
    public void play(Action kind, String clipName, float seconds) {
        rig.play(kind, clipName, seconds, kind == Action.FIRE);
    }

    public void stopAction() {
        rig.stopAction();
    }

    public Action action() {
        Action action = rig.action();
        return action == null ? Action.NONE : action;
    }

    /** Seconds left in the current one-shot (0 when idle). */
    public float actionRemaining() {
        return rig.actionRemaining();
    }

    /** Muzzle flash, kick springs, a little roll; scaled down when aiming. */
    public void fired(float kick, boolean aiming) {
        float k = kick * (aiming ? 0.55f : 1f);
        kickBack.impulse(0.55f * k);
        kickPitch.impulse(55f * k);
        kickYaw.impulse((random.nextFloat() - 0.5f) * 22f * k);
        kickRoll.impulse((random.nextFloat() - 0.5f) * 60f * k);
        flash = 0.05f;
        flashRoll = random.nextFloat() * 360f;
        flashScale = 0.8f + random.nextFloat() * 0.45f;
    }

    // ---- per frame --------------------------------------------------------------------------------

    /**
     * Advances animations and springs.
     *
     * @param sprinting the player is sprinting (gun in the sprint carry)
     */
    public void update(float dt, AbstractClientPlayer player, boolean sprinting) {
        if (!isReady()) return;
        rig.update(dt, player, sprinting, Mth.clamp(ads.get(), 0, 1));
        if (flash > 0) flash -= dt;
    }

    /** 0..1 how far into the sprint carry the weapon is (firing waits until it is lowered). */
    public float sprintAmount() {
        return rig.sprintAmount();
    }

    /** View rotation for this frame: the clip's camera track plus the procedural camera bob. */
    public float cameraPitch() {
        return rig.cameraPitch();
    }

    public float cameraYaw() {
        return rig.cameraYaw();
    }

    public float cameraRoll() {
        return rig.cameraRoll();
    }

    /**
     * The slide stays back while the gun is empty. Clips that animate the slide (fire, empty reload) overwrite it,
     * so the lock blends in and out with them instead of popping.
     */
    private void lockSlide(Pose into) {
        if (slideLocked && slide >= 0 && gun != null) {
            float[] o = gun.slideLock.offset;
            into.addTranslation(slide, o[0], o[1], o[2]);
        }
    }

    /** Hides attachments that are not fitted (spec §3) and helper roots; recomputed only when the build changes. */
    private void updateVisibility(GunState state) {
        String key = state.magazine() + '|' + state.sight() + '|' + state.laser();
        if (key.equals(visibilityKey) && hidden.length == nodeCount) return;
        visibilityKey = key;
        hidden = GunLook.hiddenNodes(rig.model().source, gun, state, rig.node("viewmodel"));
        adsResolved = false;
    }

    /** Pre-computes the transform that puts the fitted sight's eye point on the camera (rest pose). */
    private void resolveAds() {
        if (adsResolved) return;
        adsResolved = true;
        adsTranslation.zero();
        adsRotation.identity();
        GunData.Sight sight = stats == null ? null : stats.sight();
        int aim = sight == null ? -1 : rig.node(sight.aimNode);
        if (aim < 0) aim = rig.node("aim_iron");
        if (aim < 0) return;
        Pose rest = rig.restPose();
        Matrix4f inverse = new Matrix4f(rest.world[aim]).invert();
        inverse.getTranslation(adsTranslation);
        inverse.getUnnormalizedRotation(adsRotation).normalize();
    }

    /** Motion offsets × ADS × (sprint carry, lean, inertia, recoil about the hand), applied to the viewmodel root. */
    private void buildRoot() {
        float a = (float) Ease.SINE_IN_OUT.apply(Mth.clamp(ads.get(), 0, 1));
        root.identity();
        rig.motion().applyOffsets(root, a);
        // aim: lerp from the hip pose to "eye point on the camera"
        if (a > 0) {
            root.translate(adsTranslation.x * a, adsTranslation.y * a, adsTranslation.z * a);
            root.rotate(q.identity().slerp(adsRotation, a));
        }
        root.translate(HIP_PIVOT);
        rig.motion().applyRotations(root, a, !rig.hasSprintClip());
        root.rotateX((float) Math.toRadians(kickPitch.get() * 0.05f));
        root.rotateY((float) Math.toRadians(kickYaw.get() * 0.05f));
        root.rotateZ((float) Math.toRadians(kickRoll.get() * 0.05f));
        root.translate(-HIP_PIVOT.x, -HIP_PIVOT.y, -HIP_PIVOT.z);
        root.translate(0, 0, kickBack.get() * 0.06f);
    }

    // ---- drawing ----------------------------------------------------------------------------------

    /**
     * Queues the viewmodel. {@code base} maps viewmodel space to view space (camera rotation × hand pose stack).
     */
    public void draw(Matrix4f base, GunState state, int ammo, AbstractClientPlayer player, int blockLight, int skyLight) {
        if (!isReady() || stats == null) return;
        slideLocked = ammo <= 0;
        updateVisibility(state);
        resolveAds();
        buildRoot();
        rig.pose(root);
        rig.drawMeshes(base, hidden, GunLook.skin(stats.skin()), blockLight, skyLight);
        rig.drawArms(base, player, hidden, blockLight, skyLight);
        drawEffects(base);
    }

    private static final GpuModel.Style FX_STYLE = new GpuModel.Style() {
        @Override
        public GpuTextureView texture(String material) {
            GpuTextureView view = FxMeshes.texture(material);
            return view != null ? view : Textures.white();
        }
    };
    private final float[] beamColor = new float[3];
    private final GpuModel.Style beamStyle = new GpuModel.Style() {
        @Override
        public float[] tint(String material) {
            return beamColor;
        }

        @Override
        public GpuTextureView texture(String material) {
            return FX_STYLE.texture(material);
        }
    };

    private void drawEffects(Matrix4f base) {
        GpuModel fx = FxMeshes.model();
        GpuModel.Style fxStyle = FX_STYLE;
        if (flash > 0 && muzzle >= 0 && !hidden[muzzle]) {
            float life = Mth.clamp(flash / 0.05f, 0, 1);
            float scale = flashScale * (0.6f + 0.4f * life);
            float aim = Mth.clamp(ads.get(), 0, 1);
            modelView.set(base).mul(rig.world(muzzle)).rotateZ((float) Math.toRadians(flashRoll)).scale((0.085f + 0.035f * aim) * scale);
            fx.addMesh(rig.draws(), FxMeshes.flashFront(), modelView, fxStyle, 15, 15, life);
            float side = life * (1 - 0.85f * aim);
            if (side > 0.02f) {
                modelView.set(base).mul(rig.world(muzzle)).rotateZ((float) Math.toRadians(flashRoll * 0.5f)).scale(0.12f * scale, 0.12f * scale, 0.16f * scale);
                fx.addMesh(rig.draws(), FxMeshes.flashSide(), modelView, fxStyle, 15, 15, side);
            }
        }
        GunData.Laser laser = stats.laser();
        if (laser.hasBeam() && laserEmitter >= 0 && !hidden[laserEmitter]) {
            int c = laser.argb();
            beamColor[0] = (c >> 16 & 0xFF) / 255f;
            beamColor[1] = (c >> 8 & 0xFF) / 255f;
            beamColor[2] = (c & 0xFF) / 255f;
            // a long thin beam; it fades out along its length (glow texture) and towards the eye when aiming
            modelView.set(base).mul(rig.world(laserEmitter)).scale(0.0035f, 0.0035f, 24f);
            fx.addMesh(rig.draws(), FxMeshes.beam(), modelView, beamStyle, 15, 15, 0.85f * (1 - 0.6f * ads.get()));
        }
    }

    /** "draws, triangles" of the last submitted frame. */
    public String stats() {
        return rig.stats();
    }

    /** Submits everything queued this frame with the viewmodel projection. */
    public void submit() {
        ViewmodelRenderer.submit(rig, "BlockLand viewmodel");
    }

    /** View-space matrix of a node from the last drawn frame (for spawning shells and tracers). */
    @Nullable
    public Matrix4f nodeMatrix(String name, Matrix4f base, Matrix4f into) {
        if (!isReady()) return null;
        int index = name.equals("muzzle") ? muzzle : name.equals("ejection") ? ejection : rig.node(name);
        return rig.nodeMatrix(index, base, into);
    }

    public Vector4f flashColor() {
        return new Vector4f(1, 0.85f, 0.55f, Mth.clamp(flash / 0.05f, 0, 1));
    }
}
