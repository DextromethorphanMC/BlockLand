package com.blockland.client.scope;

import com.blockland.BlockLand;
import com.blockland.anim.Ease;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.ModelCache;
import com.blockland.client.settings.ClientSettings;
import com.blockland.client.settings.ScopeSensitivity;
import com.blockland.client.viewmodel.ViewmodelRenderer;
import com.blockland.craft.CraftData;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Set;

/**
 * Magnified scopes (asset spec §22): the eyepiece shows the scope's own picture of the world — rendered a second time
 * along the optical axis ({@link ScopePicture}) — with the optics of a real scope ({@link ScopeOptics},
 * {@link ScopeEyepiece}), while the view around the scope stays the normal one, out of focus around the eyepiece
 * ({@link AimFocus}, run by {@link Optics}). A variable scope steps its power with the mouse wheel
 * ({@link ScopeZoom}); the rifle breathes and the breath can be held ({@link ScopeSteady}). With picture in picture switched off, or for a scope model without an eyepiece, the whole view zooms instead and
 * {@link ScopeOverlay} masks the screen with the scope's outline and reticle.
 * <p>
 * Driven by {@link Optics}; nothing here knows guns.
 */
final class Scopes {
    /** How scopes are shown right now. */
    enum Mode { NONE, PICTURE, ZOOM }

    /** The picture is rendered from this far into raising the sight (the eye box keeps it dark until the eye arrives). */
    private static final float PICTURE_FROM = 0.2f;
    /** ... and faded in up to here (no pop when the second render starts). */
    private static final float PICTURE_FULL = 0.45f;
    /** Classic zoom: the overlay replaces the viewmodel from here. */
    private static final float ZOOM_IN = 0.9f;
    /** Length of the black blink that hides the switch to and from the overlay (s). */
    private static final float BLINK = 0.12f;
    /** The picture's target is freed after this long without a raised scope (ns). */
    private static final long RELEASE_AFTER = 10_000_000_000L;
    /** The aimed eye stays at least this many eyepiece radii behind the glass (clear of the eyecup). */
    private static final float MIN_RELIEF = 2.2f;
    /** The picture's size is kept while the wanted size stays within this share of it (no resize churn while swaying). */
    private static final float RESIZE_SLACK = 0.12f;
    /** The model's ocular glass material: the eyepiece pass draws this surface instead, so the viewmodel leaves it out. */
    private static final Set<String> OCULAR = Set.of("lens");

    private static final ScopePicture picture = new ScopePicture();
    private static final ScopeEyepiece eyepiece = new ScopeEyepiece();
    private static final ScopeEyepiece.Frame frame = new ScopeEyepiece.Frame();
    private static final ScopeZoom zoom = new ScopeZoom();
    private static final ScopeSteady steady = new ScopeSteady();

    @Nullable
    private static CraftData.Attachment sight;
    private static Mode mode = Mode.NONE;
    private static float aim;
    private static boolean zoomed;
    private static float blink;
    private static long lastPicture;
    /** Sunlight reaching the glass, eased (a cloud of blocks passing the sun fades it instead of switching it). */
    private static float sunlight;
    /** When the power last changed (ns), for the HUD's power readout. */
    private static long zoomChanged;

    // this frame's eyepiece, located once (see locate)
    private static long locatedFrame = -1;
    @Nullable
    private static Matrix4f lensView;
    @Nullable
    private static ScopeLens lens;
    /** The eyepiece on screen this frame (normalised device coordinates; radius in screen heights / 2). */
    private static boolean lensOnScreen;
    private static float lensX, lensY, lensRadius;

    private Scopes() {
    }

    static void init(BusGroup modBus) {
        CustomizeGuiOverlayEvent.DebugText.BUS.addListener(event -> {
            if (event.getSide() == CustomizeGuiOverlayEvent.DebugText.Side.Left && mode != Mode.NONE) {
                event.getText().add(String.format("[BlockLand] scope %.1f×: %s", zoom.magnification(), switch (mode) {
                    case PICTURE -> "picture " + picture.stats();
                    case ZOOM -> "zoom" + (picture.usable() ? "" : " (picture in picture unavailable)");
                    case NONE -> "";
                }));
            }
            return false;
        });
        RegisterClientReloadListenersEvent.getBus(modBus).addListener(event -> event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> ReticleTextures.clear()));
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> {
            if (lastPicture != 0 && System.nanoTime() - lastPicture > RELEASE_AFTER) {
                picture.release();
                lastPicture = 0;
            }
        });
    }

    // ---- per frame ------------------------------------------------------------------------------------------------

    /**
     * Every frame: the held optic (null when no gun is held) and how far its sight is raised (0 hip … 1 aimed).
     *
     * @param wantSteady the player holds the steady (hold breath) key
     */
    static void update(@Nullable CraftData.Attachment optic, float raised, float dt, boolean wantSteady) {
        boolean firstPerson = Minecraft.getInstance().options.getCameraType().isFirstPerson();
        sight = optic != null && optic.isScope() && optic.hasModel() && firstPerson ? optic : null;
        aim = sight == null ? 0 : Mth.clamp(raised, 0, 1);
        mode = sight == null ? Mode.NONE : canShowPicture(sight) ? Mode.PICTURE : Mode.ZOOM;
        zoom.track(sight);
        zoom.update(dt);
        steady.update(dt, sight == null ? null : sight.scope, (float) Ease.SINE_IN_OUT.apply(aim), wantSteady);
        boolean nowZoomed = mode == Mode.ZOOM && aim >= ZOOM_IN;
        if (nowZoomed != zoomed) {
            zoomed = nowZoomed;
            blink = 1;
        }
        blink = Math.max(0, blink - dt / BLINK);
        if (mode != Mode.PICTURE) lensOnScreen = false;
    }

    /** The mouse wheel while aiming: steps a variable scope's power; true when it did (the hotbar keeps its slot). */
    static boolean stepZoom(int direction) {
        if (sight == null || aim < 0.5f || !zoom.variable()) return false;
        if (zoom.step(direction)) {
            zoomChanged = System.nanoTime();
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.SPYGLASS_USE, direction > 0 ? 1.25f : 0.95f, 0.45f));
        }
        return true; // at either end the wheel still belongs to the scope
    }

    /**
     * The scope's ADS eye relief: how far its eye point is moved towards the eyepiece when aimed (in the optic model's
     * units; 0 for anything but a scope). A real 85 mm eye relief shows a 37 mm eyepiece as a small circle on a 54°
     * viewmodel; like modern shooters the aimed pose brings the eye closer, so the eyepiece covers
     * {@link CraftData.Scope#apparentSize} of the screen height by true perspective — the eyecup becomes the frame and
     * the whole tube stays hidden behind the eyepiece. The optics (eye box, field of view) use the shortened relief.
     */
    static float adsEyeShift(@Nullable CraftData.Attachment optic) {
        if (optic == null || !optic.isScope() || !optic.hasModel()) return 0;
        ScopeLens lens = lensOf(optic);
        if (lens == null || optic.scope.apparentSize <= 0) return 0;
        float tanViewmodel = (float) Math.tan(Math.toRadians(ViewmodelRenderer.FOV) / 2);
        float relief = lens.radius() / (optic.scope.apparentSize * tanViewmodel);
        // never closer than the eyecup allows (the eye stays clear of the rubber)
        relief = Math.max(relief, Math.min(lens.relief(), lens.radius() * MIN_RELIEF));
        return Math.max(0, lens.relief() - relief);
    }

    /**
     * The world's field of view through an optic at this eased aim amount: divided by its focus zoom, or — while
     * classic zoom is up — narrowed so the whole view is the scope's current magnification of the unzoomed view.
     */
    static float zoomedFov(float fov, CraftData.Attachment optic, float eased) {
        float focus = fov / (1 + (optic.zoom - 1) * eased);
        if (!zoomed || optic != sight) return focus;
        double half = Math.atan(Math.tan(Math.toRadians(fov) / 2) / zoom.magnification());
        return Math.min(focus, (float) Math.toDegrees(2 * half));
    }

    /** Classic zoom is up: the viewmodel is not drawn (the overlay shows the scope). */
    static boolean hidesViewmodel() {
        return zoomed;
    }

    /**
     * Materials of the fitted scope's model the eyepiece pass draws itself this frame, so the viewmodel leaves them out:
     * the ocular glass, whose own (dark, translucent) mesh would otherwise tint the view and z-fight the eyepiece disc
     * drawn at the same depth. Empty unless a magnified scope is showing its picture (zoom and shader-pack fall back to
     * the model's own glass).
     */
    static Set<String> replacedMaterials() {
        return mode == Mode.PICTURE ? OCULAR : Set.of();
    }

    /**
     * Multiplier on the look sensitivity while looking through a scope (1 otherwise): turning matches the power, times the
     * player's multiplier for this magnification (eased powers interpolate between the classes).
     */
    static float sensitivityScale() {
        if (sight == null || aim < 0.5f) return 1;
        return ScopeSensitivity.scopeScale(zoom.magnification());
    }

    /** The rifle's sway about the support hand this frame (degrees), while the eyepiece shows its picture. */
    static float swayYaw() {
        return mode == Mode.PICTURE ? steady.yaw() : 0;
    }

    static float swayPitch() {
        return mode == Mode.PICTURE ? steady.pitch() : 0;
    }

    /** With classic zoom the view itself sways (the overlay stays centred on the screen). */
    static float cameraSwayYaw() {
        return zoomed ? steady.yaw() : 0;
    }

    static float cameraSwayPitch() {
        return zoomed ? steady.pitch() : 0;
    }

    /**
     * Where the sight line points this frame, in view space as the viewmodel draws it: the optical axis while the
     * eyepiece shows its picture, null with classic zoom (the reticle is the screen's centre) or without a scope.
     */
    @Nullable
    static Vector3f sightLine() {
        if (mode != Mode.PICTURE || lensView == null || locatedFrame != frameId()) return null;
        return lensView.transformDirection(new Vector3f(0, 0, -1)).normalize();
    }

    // ---- drawing --------------------------------------------------------------------------------------------------

    /** Finds this frame's eyepiece (once per frame): its node matrix, its glass and its circle on screen. */
    private static void locate(SightPose pose) {
        long id = frameId();
        if (locatedFrame == id) return;
        locatedFrame = id;
        lensView = null;
        lens = null;
        lensOnScreen = false;
        if (mode != Mode.PICTURE || sight == null) return;
        lens = lensOf(sight);
        lensView = lens == null ? null : pose.opticNode(sight.scope.lensNode);
        if (lensView == null) return;
        Vector3f centre = ScopeEyepiece.discMatrix(lensView, lens, new Matrix4f()).transformPosition(new Vector3f());
        float radius = lens.radius() * lensView.getScale(new Vector3f()).x;
        if (centre.z >= -1e-3f) return;
        Minecraft mc = Minecraft.getInstance();
        float aspect = (float) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
        float tanViewmodel = (float) Math.tan(Math.toRadians(ViewmodelRenderer.FOV) / 2);
        lensX = centre.x / -centre.z / (tanViewmodel * aspect);
        lensY = centre.y / -centre.z / tanViewmodel;
        lensRadius = radius / -centre.z / tanViewmodel;
        lensOnScreen = true;
    }

    /** Before the viewmodel is drawn: finds this frame's eyepiece (the aim focus is centred on it). */
    static void renderBehind(SightPose pose) {
        locate(pose);
    }

    /**
     * Renders the raised scope's picture and draws its eyepiece over the viewmodel, which has just been submitted.
     * The scope model itself is drawn normally; everything optical — the magnified picture, the reticle, the scope
     * shadow (field stop and exit pupil), glare and the glass's reflections — happens inside the eyepiece glass.
     */
    static void render(SightPose pose, int blockLight, int skyLight) {
        locate(pose);
        if (mode != Mode.PICTURE || sight == null || lensView == null || lens == null) return;
        CraftData.Scope data = sight.scope;
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        float aspect = (float) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
        float worldFov = Optics.worldFov();
        float tanWorld = (float) Math.tan(Math.toRadians(worldFov) / 2);
        float tanViewmodel = (float) Math.tan(Math.toRadians(ViewmodelRenderer.FOV) / 2);
        float k = tanWorld / tanViewmodel;
        // the aimed pose's eye relief (see adsEyeShift): the eye point the optics are designed around
        float relief = lens.relief() - adsEyeShift(sight);
        float power = zoom.magnification();
        float startPower = data.levels()[data.startLevel()];

        // the optical axis is the eyepiece node's −Z; the picture camera looks along it, rolled like the main camera
        Vector3f axis = lensView.transformDirection(new Vector3f(0, 0, -1)).normalize();
        ScopeOptics.cameraToScope(axis, k, frame.cameraToScope);
        float scale = lensView.getScale(new Vector3f()).x;
        // the exit pupil shrinks as the power goes up: a variable scope is fussier about the eye at its high end
        float eyeBox = data.eyeBox * (float) Math.sqrt(startPower / Math.max(1, power));
        ScopeOptics.eye(ScopeOptics.eyeInLens(lensView, new Vector3f()), lens.centre(), relief, eyeBox / Math.max(1e-4f, scale), frame.eye);
        float fieldTan = ScopeOptics.fieldTan(lens.radius(), relief, tanWorld, tanViewmodel);
        float zoomDivisor = 1 + (sight.zoom - 1) * (float) Ease.SINE_IN_OUT.apply(aim);
        float magnification = ScopeOptics.relativeMagnification(power, worldFov, zoomDivisor);
        float fov = ScopeOptics.pictureFov(fieldTan, magnification);
        ScopeEyepiece.discMatrix(lensView, lens, frame.modelView);

        // the picture is only as sharp as the eyepiece is large on screen: its resolution follows the eyepiece's pixels
        ScopeQuality quality = ScopeQuality.of(ClientSettings.get().scopeQuality);
        int screenHeight = Math.max(1, mc.getMainRenderTarget().height);
        float eyepiecePixels = lensOnScreen ? lensRadius * screenHeight * ScopeOptics.PICTURE_MARGIN : 0;
        int side = pictureSide(quality.scale * eyepiecePixels);
        Quaternionf world = new Quaternionf(camera.rotation()).mul(new Quaternionf(frame.cameraToScope).conjugate());
        // rendered every frame once the sight is coming up: skipping frames by the eye box (which sway and recoil move
        // back and forth across its edge) blinked the eyepiece black. Lower, the eyepiece is still drawn, as dark glass,
        // so the tube behind it never shows through.
        GpuTextureView image = null;
        if (aim >= PICTURE_FROM && lensOnScreen) {
            image = picture.render(world, fov, side, power);
            lastPicture = System.nanoTime();
            if (!picture.usable()) return; // it just broke: zoom from the next frame on
        }

        // the scope's cant turns the reticle with it
        Vector3f right = lensView.transformDirection(new Vector3f(1, 0, 0));
        float cant = (float) Math.atan2(right.y, right.x);
        frame.tanWorldX = tanWorld * aspect;
        frame.tanWorldY = tanWorld;
        frame.fieldTan = fieldTan;
        frame.image = image;
        frame.picture = smooth((aim - PICTURE_FROM) / (PICTURE_FULL - PICTURE_FROM));
        frame.reticle = ReticleTextures.get(data.reticle);
        frame.reticleScale = 1 / ScopeOptics.reticleSize(data.reticleSize, data.firstFocalPlane(), power, startPower);
        frame.parallax = data.parallax;
        frame.cantCos = (float) Math.cos(cant);
        frame.cantSin = (float) Math.sin(cant);
        frame.reticleRgb = data.reticleRgb();
        frame.illumination = Math.max(0, data.illumination);
        frame.coatingRgb = data.coatingRgb();
        frame.vignette = data.vignette;
        frame.fringe = 0.012f;
        frame.distortion = 0.06f;
        frame.dirt = Mth.clamp(data.dirt, 0, 1);
        frame.flare = Mth.clamp(data.flare, 0, 1);
        frame.texel = 1f / side;
        frame.sharpen = quality.sharpen;
        light(mc, camera, blockLight, skyLight);
        eyepiece.draw(frame);
    }

    /**
     * What the glass reflects (the player's sky over their ground, lit like the player) and the sun against the scope:
     * its direction in the scope's frame and how much of it reaches the glass (day, weather, the dimension's sky, and
     * nothing in the way — one ray towards the sun, only while it is near the field).
     */
    private static void light(Minecraft mc, Camera camera, int blockLight, int skyLight) {
        ClientLevel level = mc.level;
        float partial = camera.getPartialTickTime();
        frame.ambient = OpticLight.environment(camera, blockLight, skyLight, frame.environment);

        float target = 0;
        if (level != null && level.effects().skyType() == DimensionSpecialEffects.SkyType.OVERWORLD) {
            Vector3f sunWorld = ScopeOptics.sunDirection(level.getSunAngle(partial), new Vector3f());
            Vector3f sunCamera = new Quaternionf(camera.rotation()).conjugate().transform(new Vector3f(sunWorld));
            frame.cameraToScope.transform(sunCamera, frame.sun);
            float up = smooth((sunWorld.y + 0.05f) / 0.2f);
            float weather = 1 - level.getRainLevel(partial);
            if (up * weather > 0.01f && -frame.sun.z > 0.6f) {
                Vec3 eye = camera.getPosition();
                Vec3 far = eye.add(sunWorld.x * 256, sunWorld.y * 256, sunWorld.z * 256);
                boolean blocked = mc.player != null && level.clip(new ClipContext(eye, far, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE,
                        mc.player)).getType() != HitResult.Type.MISS;
                target = blocked ? 0 : up * weather;
            }
        } else {
            frame.sun.set(0, 0, 1);
        }
        sunlight += (target - sunlight) * 0.25f;
        frame.sunlight = sunlight;
    }

    /** Side of the square picture for this many wanted pixels: in 32-pixel steps, kept while the wish stays close. */
    private static int pictureSide(float wanted) {
        int current = picture.target() == null ? 0 : picture.target().width;
        int side = Mth.clamp(Math.round(wanted / 32f) * 32, 192, 2048);
        if (current > 0 && Math.abs(side - current) <= current * RESIZE_SLACK) return current;
        return side;
    }

    // ---- read by the overlay and the facade -----------------------------------------------------------------------

    static Mode mode() {
        return mode;
    }

    @Nullable
    static CraftData.Attachment sight() {
        return sight;
    }

    static boolean zoomed() {
        return zoomed;
    }

    static float blink() {
        return blink;
    }

    static float aim() {
        return aim;
    }

    static boolean lensOnScreen() {
        return lensOnScreen && locatedFrame == frameId();
    }

    static float lensX() {
        return lensX;
    }

    static float lensY() {
        return lensY;
    }

    static float lensRadius() {
        return lensRadius;
    }

    static float magnification() {
        return zoom.magnification();
    }

    /** The reticle's size against the field at the current power (see {@link ScopeOptics#reticleSize}). */
    static float reticleSize() {
        if (sight == null) return 1;
        CraftData.Scope data = sight.scope;
        return ScopeOptics.reticleSize(data.reticleSize, data.firstFocalPlane(), zoom.magnification(), data.levels()[data.startLevel()]);
    }

    static boolean variableZoom() {
        return zoom.variable();
    }

    /** Seconds since the power last changed (large when it never did). */
    static float sinceZoomChange() {
        return zoomChanged == 0 ? 1e3f : (System.nanoTime() - zoomChanged) / 1e9f;
    }

    static ScopeSteady steady() {
        return steady;
    }

    /** The picture's target (dev tools read it back). */
    @Nullable
    static com.mojang.blaze3d.pipeline.RenderTarget pictureTarget() {
        return picture.target();
    }

    /** Whether the eyepiece can show its own picture: setting, a working second render, the model. */
    private static boolean canShowPicture(CraftData.Attachment optic) {
        if (ScopeQuality.of(ClientSettings.get().scopeQuality) == ScopeQuality.OFF || !picture.usable()) return false;
        return lensOf(optic) != null;
    }

    @Nullable
    private static ScopeLens lensOf(CraftData.Attachment optic) {
        GpuModel model = ModelCache.get(BlockLand.id(optic.model));
        return model == null ? null : ScopeLens.of(model.source, optic.scope.lensNode, optic.aimNode);
    }

    /** Identifies the frame being rendered (the game time's frame counter). */
    private static long frameId() {
        return Optics.frameId();
    }

    static float smooth(float t) {
        float c = Math.max(0, Math.min(1, t));
        return c * c * (3 - 2 * c);
    }
}
