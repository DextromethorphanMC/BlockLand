package com.blockland.client.gun;

import com.blockland.BlockLand;
import com.blockland.anim.AnimValue;
import com.blockland.client.craft.WeaponLook;
import com.blockland.client.gltf.GltfModel;
import com.blockland.client.gltf.Pose;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.Textures;
import com.blockland.client.scope.Optics;
import com.blockland.client.scope.SightPose;
import com.blockland.client.spray.SprayDecals;
import com.blockland.client.viewmodel.SkinEffects;
import com.blockland.client.viewmodel.ViewmodelRenderer;
import com.blockland.client.viewmodel.ViewmodelRig;
import com.blockland.craft.Build;
import com.blockland.craft.CraftData;
import com.blockland.gun.GunData;
import com.blockland.gun.GunStats;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/**
 * First-person gun: drives the shared {@link ViewmodelRig} (draw / fire / reload / inspect clips over the idle,
 * player arms, procedural motion) and adds what only guns have — the fitted build (magazine, universal attachment
 * models at the mounts, skin), aiming down the fitted optic, spring recoil, the slide locked back on an empty
 * magazine, the muzzle flash (from a suppressor's end when one is fitted) and the laser beam. The shot recoil is its own
 * component ({@link GunRecoil}). A dual gun (asset spec §26)
 * is a second gun whose nodes repeat the first one's with a suffix: it gets the same magazine, slide lock and
 * attachments, its own flashes and beam. A special skin (§30) may bring its own model and muzzle flash, and (§38) its own
 * inspect and kill feedback — {@link SkinEffects} plays their cues, particles and lights and surges the skin's shaders.
 * Pure presentation: the controller decides when to fire or reload.
 */
public final class Viewmodel {
    private static final Vector3f HIP_PIVOT = new Vector3f(0.11f, -0.11f, -0.30f);
    /** A raised scope's breathing sway turns the rifle about the support hand, this far in front of the eye. */
    private static final float SWAY_PIVOT = 0.35f;

    public enum Action { NONE, DRAW, FIRE, RELOAD, INSPECT }

    private final Random random = new Random();
    private final ViewmodelRig<Action> rig = new ViewmodelRig<>();

    @Nullable
    private GunData.Gun gun;
    @Nullable
    private GunStats stats;
    private boolean ready;
    private boolean[] hidden = new boolean[0];
    @Nullable
    private Build visibleBuild;
    private int muzzle = -1, ejection = -1, slide = -1, nodeCount;
    /** A dual gun's left-gun twins of those nodes (spec §26), -1 when single or missing; the twin-name suffix. */
    private int muzzleL = -1, ejectionL = -1, slideL = -1;
    private String suffix = "";
    /** The model asked for (a special skin's own, spec §30) and whether the rig holds it (else the gun's own). */
    @Nullable
    private ResourceLocation requestedModel;
    private final Vector3f adsTranslation = new Vector3f();
    private final Quaternionf adsRotation = new Quaternionf();
    private boolean adsResolved;
    /** The magazine is empty this frame: the base pose holds the slide back (a dual gun's left slide: its own flag). */
    private boolean slideLocked, slideLockedL;
    /** Dual guns: the next single shot comes from the left gun (which of the pair runs dry first). */
    private boolean nextLeft;

    // procedural layers
    public final AnimValue ads = new AnimValue(0);
    private final GunRecoil recoil = new GunRecoil(rig.camera());
    /** Per gun (0 = right / the only one, 1 = a dual gun's left): flash time left, roll and size. */
    private final float[] flash = new float[2];
    private final float[] flashRoll = new float[2];
    private final float[] flashScale = new float[2];
    /** A special skin's inspect and kill feedback (asset spec §38). */
    private final SkinEffects skinEffects = new SkinEffects();
    private final SkinEffects.Nodes skinNodes = new SkinEffects.Nodes() {
        @Override
        public Matrix4f node(String name, int gun) {
            if (gun == 1 && suffix.isEmpty()) return null;
            if (name.equals("muzzle")) return muzzleWorld(gun);
            int index = gun == 1 ? twin(name) : rig.node(name);
            if (index < 0 || index < hidden.length && hidden[index] || rig.isCollapsed(index)) return null;
            return rig.world(index);
        }

        @Override
        public boolean dual() {
            return !suffix.isEmpty();
        }
    };

    // scratch (no per-frame allocation)
    private final Matrix4f root = new Matrix4f();
    private final Matrix4f modelView = new Matrix4f();
    private final Quaternionf q = new Quaternionf();

    public Viewmodel() {
        rig.setBaseModifier(this::lockSlide);
    }

    // ---- equip ------------------------------------------------------------------------------------

    /**
     * @param model the build's model ({@link WeaponLook#modelOf}: a special skin's own, else the gun's)
     * @return false when the gun has no usable model (vanilla rendering is kept then)
     */
    public boolean equip(GunData.Gun gun, ResourceLocation model) {
        this.gun = gun;
        ready = load(model);
        if (!ready) return false;
        ads.snap(0);
        java.util.Arrays.fill(flash, 0);
        skinEffects.clear();
        play(Action.DRAW, gun.anims.draw, gun.drawTime);
        return true;
    }

    /** Loads a model for the equipped gun (falling back to the gun's own when a skin's model is missing). */
    private boolean load(ResourceLocation model) {
        requestedModel = model;
        skinEffects.clear();
        visibleBuild = null;
        adsResolved = false;
        hidden = new boolean[0];
        boolean loaded = rig.load(model, gun.anims.idle, gun.anims.walk, gun.anims.sprint, gun.anims.tacSprint);
        ResourceLocation own = BlockLand.id(gun.model);
        if (!loaded && !model.equals(own)) {
            loaded = rig.load(own, gun.anims.idle, gun.anims.walk, gun.anims.sprint, gun.anims.tacSprint);
        }
        if (!loaded) return false;
        rig.motion().setHandling(gun.handling);
        recoil.setHandling(gun.handling);
        muzzle = rig.node("muzzle");
        ejection = rig.node("ejection");
        slide = rig.node(gun.slideLock.node);
        suffix = gun.dual == null || gun.dual.suffix == null ? "" : gun.dual.suffix;
        muzzleL = twin("muzzle");
        ejectionL = twin("ejection");
        slideL = twin(gun.slideLock.node);
        nodeCount = rig.model().source.nodes.size();
        return true;
    }

    /** A dual gun's left-gun twin of a node, -1 for single guns and missing twins. */
    private int twin(String name) {
        return suffix.isEmpty() ? -1 : rig.node(name + suffix);
    }

    public boolean isReady() {
        return ready && rig.isReady();
    }

    public void setStats(GunStats stats) {
        this.stats = stats;
    }

    /** Clip events go to {@code sink} (sounds) and to the skin's inspect cues; null silences both (a holstered gun). */
    public void setEventSink(@Nullable Consumer<String> sink) {
        rig.setEventSink(sink == null ? null : name -> {
            skinEffects.event(name, rig);
            sink.accept(name);
        });
    }

    // ---- actions ----------------------------------------------------------------------------------

    /**
     * Plays a one-shot clip, stretched to {@code seconds} when given (> 0) so animations always match the
     * gameplay timing in guns.json. The pose blends into it inertially (spec §31.4): a shot cuts straight in (its
     * first frames are the crack; a blend would swallow them — fire clips start and end on the rest pose, and a shot
     * that interrupts a reload or inspect still gets that clip's longer blend), the round clips of a shell reload
     * briefly (they meet in one pose), the rest a little longer.
     */
    public void play(Action kind, String clipName, float seconds) {
        float blend = switch (kind) {
            case FIRE -> 0;
            case DRAW -> 0;
            case RELOAD -> 0.14f;
            case INSPECT -> 0.24f;
            case NONE -> ViewmodelRig.BLEND;
        };
        rig.play(kind, clipName, seconds, blend);
    }

    public void stopAction() {
        rig.stopAction();
    }

    /**
     * Inspects the gun: a special skin's own inspect (asset spec §38) — its clip when the model has it, else the gun's —
     * with the skin's cues, streams and surge; otherwise the gun's inspect clip.
     */
    public void inspect(String clip) {
        CraftData.SkinInspect own = stats == null || stats.skin() == null ? null : stats.skin().inspect;
        String ownClip = own == null || own.clip == null ? "" : own.clip.trim();
        play(Action.INSPECT, !ownClip.isEmpty() && rig.hasClip(ownClip) ? ownClip : clip, 0);
        if (own != null && action() == Action.INSPECT) skinEffects.startInspect(own);
    }

    /** A confirmed kill: the special skin's kill cue (asset spec §38), when it has one. */
    public void killed() {
        CraftData.SkinFx fx = stats == null ? null : stats.skinFx();
        if (isReady() && fx != null && fx.kill != null) skinEffects.cue(fx.kill, rig);
    }

    public boolean hasClip(String name) {
        return rig.hasClip(name);
    }

    /** Seconds of a clip as authored (0 when the model has none). */
    public float clipLength(String name) {
        return rig.clipLength(name);
    }

    /** Dual guns: whether the next single shot is the left gun's (decides which slide locks on the last round). */
    public void setNextLeft(boolean left) {
        nextLeft = left;
    }

    public Action action() {
        Action action = rig.action();
        return action == null ? Action.NONE : action;
    }

    /** Seconds left in the current one-shot (0 when idle). */
    public float actionRemaining() {
        return rig.actionRemaining();
    }

    /**
     * A shot: the recoil (calmer when aimed) and the flash at each gun that fired.
     *
     * @param aim  0 = hip, 1 = aimed down sights
     * @param guns bit 1 = the right (or only) gun, bit 2 = a dual gun's left one
     */
    public void fired(float kick, float aim, int guns) {
        recoil.shot(kick, Mth.clamp(aim, 0, 1), guns, !suffix.isEmpty());
        for (int g = 0; g < 2; g++) {
            if ((guns & 1 << g) == 0) continue;
            flash[g] = 0.05f;
            flashRoll[g] = random.nextFloat() * 360f;
            flashScale[g] = 0.8f + random.nextFloat() * 0.45f;
        }
    }

    /** The trigger clicked on an empty gun: a small flinch. */
    public void dryFired() {
        recoil.dry(Mth.clamp(ads.get(), 0, 1));
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
        if (skinEffects.inspecting() && rig.action() != Action.INSPECT) skinEffects.stopInspect();
        skinEffects.update(dt, rig.actionElapsed());
        recoil.update(dt);
        for (int g = 0; g < 2; g++) if (flash[g] > 0) flash[g] -= dt;
    }

    /** 0..1 how hard a wall in front presses the weapon back (spec §31.4; aiming brings the sights up anyway). */
    public float obstruction() {
        return rig.motion().obstruction();
    }

    /** 0..1 how far into the sprint carry the weapon is (firing waits until it is lowered). */
    public float sprintAmount() {
        return rig.sprintAmount();
    }

    /** View rotation for this frame: the clip's camera track, the locomotion sway, the camera rig (shots, slams, body). */
    public float cameraPitch() {
        return rig.cameraPitch();
    }

    public float cameraYaw() {
        return rig.cameraYaw();
    }

    public float cameraRoll() {
        return rig.cameraRoll();
    }

    /** Field of view multiplier of the camera's punches. */
    public float cameraFov() {
        return rig.camera().fovScale();
    }

    /** Moves the rendered camera by the head movement of shots and landings (from the camera-angles event). */
    public void moveCamera(net.minecraft.client.Camera camera) {
        rig.camera().moveCamera(camera);
    }

    /**
     * The slide stays back while the gun is empty. Clips that animate the slide (fire, empty reload) overwrite it,
     * so the lock blends in and out with them instead of popping.
     */
    private void lockSlide(Pose into) {
        if (gun == null) return;
        float[] o = gun.slideLock.offset;
        if (slideLocked && slide >= 0) into.addTranslation(slide, o[0], o[1], o[2]);
        if (slideLockedL && slideL >= 0) into.addTranslation(slideL, o[0], o[1], o[2]);
    }

    /**
     * Applies the build: the weapon's own parts it shows or hides (spec §3/§19) and the attachment models at the mounts;
     * recomputed only when the build changes.
     */
    private void updateLook() {
        Build build = stats.build();
        if (build.equals(visibleBuild) && hidden.length == nodeCount) return;
        // a special skin with a model of its own (spec §30): the rig changes models with the skin
        ResourceLocation model = WeaponLook.modelOf(gun, build);
        if (!model.equals(requestedModel) && !(ready = load(model))) return;
        visibleBuild = build;
        hidden = WeaponLook.hiddenNodes(rig.model().source, gun, build, rig.node("viewmodel"));
        List<ViewmodelRig.Attachment> attachments = WeaponLook.attachments(gun, build);
        if (!suffix.isEmpty()) {
            mirrorHidden(rig.model().source);
            attachments = withTwins(attachments);
        }
        rig.setAttachments(attachments);
        GunData.Gun sprayed = gun;
        rig.setSprays(() -> SprayDecals.of(sprayed, build), suffix.isEmpty() ? null : suffix);
        adsResolved = false;
    }

    /** Dual guns: a left-gun twin hides with its right-gun node (the magazine not fitted, parts a slot hides). */
    private void mirrorHidden(GltfModel source) {
        for (int index : source.order) {
            GltfModel.Node node = source.nodes.get(index);
            if (node.name.endsWith(suffix) && node.name.length() > suffix.length()) {
                int base = source.node(node.name.substring(0, node.name.length() - suffix.length()));
                if (base >= 0 && hidden[base]) hidden[index] = true;
            }
            if (node.parent >= 0 && hidden[node.parent]) hidden[index] = true;
        }
    }

    /** Dual guns: an attachment fitted at a mount is fitted at the mount's left-gun twin too (keyed {@code <slot><suffix>}). */
    private List<ViewmodelRig.Attachment> withTwins(List<ViewmodelRig.Attachment> attachments) {
        List<ViewmodelRig.Attachment> all = new ArrayList<>(attachments);
        for (ViewmodelRig.Attachment a : attachments) {
            if (rig.node(a.mount() + suffix) >= 0) {
                all.add(new ViewmodelRig.Attachment(a.key() + suffix, a.mount() + suffix, a.model(), a.region()));
            }
        }
        return all;
    }

    /**
     * Pre-computes the transform that puts the fitted optic's eye point on the camera (rest pose): the optic model's
     * {@code aim} at its mount, or the gun's own {@code aim_iron}. Only position and direction count (mounts may be scaled).
     * A scope's eye point moves along its sight line to the aimed pose's shorter eye relief ({@link Optics#adsEyeShift}).
     */
    private void resolveAds() {
        if (adsResolved) return;
        adsResolved = true;
        adsTranslation.zero();
        adsRotation.identity();
        CraftData.Attachment sight = stats == null ? null : stats.sight();
        Matrix4f aim = sight != null && sight.hasModel() ? rig.restAttachmentNode("optic", sight.aimNode) : null;
        if (aim != null) aim.translate(0, 0, -Optics.adsEyeShift(sight));
        if (aim == null) {
            int iron = rig.node("aim_iron");
            if (iron < 0) return;
            Pose rest = rig.restPose();
            aim = rest.world[iron];
        }
        Vector3f eye = aim.getTranslation(new Vector3f());
        aim.getNormalizedRotation(adsRotation).conjugate();
        adsRotation.transform(eye.negate(), adsTranslation);
    }

    /**
     * Scope sway × motion offsets × ADS × (sprint carry, lean, inertia about the hand) × recoil, applied to the viewmodel
     * root. The aim value is already eased over the ADS time; the weapon follows it as it is (easing it twice made the
     * sights feel slow to answer).
     */
    private void buildRoot() {
        float a = Mth.clamp(ads.get(), 0, 1);
        root.identity();
        // a raised scope's breathing sway (already scaled by the aim): the rifle turns about the support hand, so the
        // sight line wanders and the eye drifts a little off the eyepiece's axis
        float swayYaw = Optics.swayYaw(), swayPitch = Optics.swayPitch();
        if (swayYaw != 0 || swayPitch != 0) {
            root.translate(0, 0, -SWAY_PIVOT)
                    .rotateY((float) Math.toRadians(swayYaw))
                    .rotateX((float) Math.toRadians(-swayPitch))
                    .translate(0, 0, SWAY_PIVOT);
        }
        rig.motion().applyOffsets(root, a);
        // aim: lerp from the hip pose to "eye point on the camera"
        if (a > 0) {
            root.translate(adsTranslation.x * a, adsTranslation.y * a, adsTranslation.z * a);
            root.rotate(q.identity().slerp(adsRotation, a));
        }
        root.translate(HIP_PIVOT);
        rig.motion().applyRotations(root, a, !rig.hasSprintClips());
        root.translate(-HIP_PIVOT.x, -HIP_PIVOT.y, -HIP_PIVOT.z);
        recoil.apply(root, HIP_PIVOT);
    }

    // ---- drawing ----------------------------------------------------------------------------------

    /**
     * Queues the viewmodel. {@code base} maps viewmodel space to view space (camera rotation × hand pose stack).
     */
    public void draw(Matrix4f base, int ammo, AbstractClientPlayer player, int blockLight, int skyLight) {
        if (!isReady() || stats == null) return;
        if (suffix.isEmpty()) {
            slideLocked = ammo <= 0;
        } else {
            // the pair empties alternately: with one round left, the gun that fired last is the empty one
            slideLocked = ammo <= 0 || ammo == 1 && nextLeft;
            slideLockedL = ammo <= 0 || ammo == 1 && !nextLeft;
        }
        updateLook();
        if (!isReady()) return;
        resolveAds();
        buildRoot();
        rig.pose(root);
        rig.drawMeshes(base, hidden, skinEffects.style(WeaponLook.style(stats.skin())), blockLight, skyLight);
        rig.drawArms(base, player, hidden, blockLight, skyLight);
        drawEffects(base);
        if (skinEffects.active()) skinEffects.draw(rig.draws(), base, skinNodes);
    }

    /**
     * Where shots leave a gun this frame: a fitted suppressor's end, else the gun's own muzzle (null = hidden).
     *
     * @param gun 0 = the right (or only) gun, 1 = a dual gun's left one
     */
    @Nullable
    private Matrix4f muzzleWorld(int gun) {
        String barrel = gun == 1 ? "barrel" + suffix : "barrel";
        if (rig.attachmentVisible(barrel, hidden)) {
            Matrix4f end = rig.attachmentNode(barrel, "muzzle");
            if (end != null) return end;
        }
        int node = gun == 1 ? muzzleL : muzzle;
        return node >= 0 && node < hidden.length && !hidden[node] && !rig.isCollapsed(node) ? rig.world(node) : null;
    }

    private static final GpuModel.Style FX_STYLE = new GpuModel.Style() {
        @Override
        public GpuTextureView texture(String material) {
            GpuTextureView view = FxMeshes.texture(material);
            return view != null ? view : Textures.white();
        }
    };
    /** The muzzle flash's look this frame: the shared sprites, or a special skin's (spec §30) and its tint. */
    private final float[] flashTint = new float[3];
    private boolean flashTinted;
    @Nullable
    private GpuTextureView flashFrontTexture, flashSideTexture;
    private final GpuModel.Style flashStyle = new GpuModel.Style() {
        /** A skin's own sprite already has its colours: the skin's flash colour only tints the shared sprites. */
        @Override
        public float[] tint(String material) {
            return flashTinted && ownFlash(material) == null ? flashTint : null;
        }

        @Override
        public GpuTextureView texture(String material) {
            GpuTextureView own = ownFlash(material);
            return own != null ? own : FX_STYLE.texture(material);
        }
    };

    @Nullable
    private GpuTextureView ownFlash(String material) {
        return material.equals(FxMeshes.FLASH_FRONT) ? flashFrontTexture : material.equals(FxMeshes.FLASH_SIDE) ? flashSideTexture : null;
    }
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
        CraftData.SkinFx skinFx = stats.skinFx();
        GpuModel.Style fxStyle = skinFx == null ? FX_STYLE : flashStyle(skinFx);
        float skinScale = skinFx == null ? 1 : Math.max(0.1f, skinFx.flashScale);
        for (int g = 0; g < 2; g++) {
            Matrix4f muzzleAt = flash[g] > 0 ? muzzleWorld(g) : null;
            if (muzzleAt == null) continue;
            float life = Mth.clamp(flash[g] / 0.05f, 0, 1);
            float scale = flashScale[g] * skinScale * (0.6f + 0.4f * life);
            float aim = Mth.clamp(ads.get(), 0, 1);
            // a suppressor all but kills the flash: a faint puff at the end of the can
            boolean suppressed = stats.suppressed();
            float front = suppressed ? 0.3f : 1f;
            modelView.set(base).mul(muzzleAt).rotateZ((float) Math.toRadians(flashRoll[g])).scale((0.085f + 0.035f * aim) * scale * front);
            fx.addMesh(rig.draws(), FxMeshes.flashFront(), modelView, fxStyle, 15, 15, suppressed ? life * 0.35f : life);
            float side = suppressed ? 0 : life * (1 - 0.85f * aim);
            if (side > 0.02f) {
                modelView.set(base).mul(muzzleAt).rotateZ((float) Math.toRadians(flashRoll[g] * 0.5f)).scale(0.12f * scale, 0.12f * scale, 0.16f * scale);
                fx.addMesh(rig.draws(), FxMeshes.flashSide(), modelView, fxStyle, 15, 15, side);
            }
        }
        CraftData.Attachment laser = stats.laser();
        if (!laser.hasBeam()) return;
        int c = laser.argb();
        beamColor[0] = (c >> 16 & 0xFF) / 255f;
        beamColor[1] = (c >> 8 & 0xFF) / 255f;
        beamColor[2] = (c & 0xFF) / 255f;
        for (String key : suffix.isEmpty() ? new String[]{"laser"} : new String[]{"laser", "laser" + suffix}) {
            Matrix4f emitter = rig.attachmentVisible(key, hidden) ? rig.attachmentNode(key, "emitter") : null;
            if (emitter == null) continue;
            // a long thin beam; it fades out along its length (glow texture) and towards the eye when aiming
            modelView.set(base).mul(emitter).scale(0.0035f, 0.0035f, 24f);
            fx.addMesh(rig.draws(), FxMeshes.beam(), modelView, beamStyle, 15, 15, 0.85f * (1 - 0.6f * ads.get()));
        }
    }

    /** The flash style of a special skin: its sprites (when they exist) tinted with its colour. */
    private GpuModel.Style flashStyle(CraftData.SkinFx skinFx) {
        flashFrontTexture = texture(skinFx.flashTexture);
        flashSideTexture = texture(skinFx.flashSideTexture);
        flashTinted = skinFx.hasFlashColor();
        if (flashTinted) {
            int c = skinFx.flashRgb();
            flashTint[0] = (c >> 16 & 0xFF) / 255f;
            flashTint[1] = (c >> 8 & 0xFF) / 255f;
            flashTint[2] = (c & 0xFF) / 255f;
        }
        return flashStyle;
    }

    @Nullable
    private static GpuTextureView texture(@Nullable String path) {
        if (path == null || path.isBlank()) return null;
        try {
            return Textures.resource(BlockLand.id(path.trim()));
        } catch (RuntimeException e) {
            return null;
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

    /** Materials of the fitted optic's model another pass draws this frame (a reflex sight's glass and reticle). */
    public void hideOpticMaterials(Set<String> materials) {
        rig.setHiddenMaterials("optic", materials);
    }

    /** Drops this frame's queued draws (posed but not shown: a zoomed scope covers the screen). */
    public void discard() {
        rig.draws().clear();
    }

    /** View-space matrix of a node of the fitted optic this frame; null when no optic model is on screen. */
    @Nullable
    public Matrix4f opticNode(String name, Matrix4f base, Matrix4f into) {
        if (!isReady() || !rig.attachmentVisible("optic", hidden)) return null;
        Matrix4f node = rig.attachmentNode("optic", name);
        return node == null ? null : into.set(base).mul(node);
    }

    /** Where the sight is this frame (after {@link #draw}): the fitted optic's nodes, or the gun's own iron sight. */
    public SightPose sightPose(Matrix4f base) {
        Matrix4f frozenBase = new Matrix4f(base);
        return new SightPose() {
            @Override
            public Matrix4f opticNode(String name) {
                return Viewmodel.this.opticNode(name, frozenBase, new Matrix4f());
            }

            @Override
            public Matrix4f ironSight() {
                int iron = rig.node("aim_iron");
                return iron < 0 || !isReady() ? null : rig.nodeMatrix(iron, frozenBase, new Matrix4f());
            }
        };
    }

    /** View-space matrix of a node from the last drawn frame (for spawning shells and tracers). */
    @Nullable
    public Matrix4f nodeMatrix(String name, Matrix4f base, Matrix4f into) {
        return nodeMatrix(name, 0, base, into);
    }

    /**
     * View-space matrix of a node of one gun from the last drawn frame.
     *
     * @param gun 0 = the right (or only) gun, 1 = a dual gun's left one (its twin node; null when it has none)
     */
    @Nullable
    public Matrix4f nodeMatrix(String name, int gun, Matrix4f base, Matrix4f into) {
        if (!isReady()) return null;
        if (gun == 1 && suffix.isEmpty()) return null;
        if (name.equals("muzzle")) {
            Matrix4f at = muzzleWorld(gun);
            return at == null ? null : into.set(base).mul(at);
        }
        int index = name.equals("ejection") ? (gun == 1 ? ejectionL : ejection) : gun == 1 ? twin(name) : rig.node(name);
        return rig.nodeMatrix(index, base, into);
    }

    /** The model the rig shows (a special skin's own, or the gun's), for effects that read meshes from it. */
    @Nullable
    public GpuModel model() {
        return isReady() ? rig.model() : null;
    }

    public Vector4f flashColor() {
        CraftData.SkinFx skinFx = stats == null ? null : stats.skinFx();
        int c = skinFx != null && skinFx.hasFlashColor() ? skinFx.flashRgb() : 0xFFD98C;
        return new Vector4f((c >> 16 & 0xFF) / 255f, (c >> 8 & 0xFF) / 255f, (c & 0xFF) / 255f,
                Mth.clamp(Math.max(flash[0], flash[1]) / 0.05f, 0, 1));
    }
}
