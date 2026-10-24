package com.blockland.client.scope;

import com.blockland.BlockLand;
import com.blockland.client.settings.ClientSettings;
import com.blockland.client.viewmodel.ViewmodelRenderer;
import com.blockland.craft.CraftData;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.listener.Priority;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.function.Function;

/**
 * The optics of every sight, as one system the weapon talks to (asset spec §22): iron sights (the weapon's own notch
 * and post), reflex sights ({@link ReflexSights}: a reticle at infinity in the glass) and magnified scopes
 * ({@link Scopes}: the eyepiece's own magnified picture of the world, variable power, breathing and holding the breath,
 * the world around blurred). What a sight looks like and how it behaves is data — the optic's entry in
 * {@code craft/attachments.json} — and the optic model's nodes ({@code aim}, {@code lens_ocular}, {@code glass}).
 * <p>
 * The weapon module feeds it each frame — the held optic and how far it is raised, then where its sight is
 * ({@link SightPose}) around the viewmodel's draw — and asks it for the field of view, the sensitivity, the sway to
 * apply, which of the optic's surfaces it draws itself and where a shot goes. The sight line is what the player sees
 * through the sight, so shots follow it once aimed: the reticle is the point of impact, whatever the sway and recoil.
 * Everything here is this client's presentation; the server times aiming and checks every shot itself.
 */
public final class Optics {
    /** Shots follow the sight line from this aim amount (fully from 1). */
    private static final float SIGHT_FROM = 0.85f;

    @Nullable
    private static CraftData.Attachment sight;
    private static float aim;
    /** The world's final field of view this frame (degrees), as the level was drawn. */
    private static float worldFov = 70;
    private static long frame;
    /** The sight line in view space (viewmodel projection) as last drawn, the frame it was drawn in, and the FOVs then. */
    private static final Vector3f sightView = new Vector3f(0, 0, -1);
    private static long sightFrame = -10;
    private static float sightK = 1;
    private static boolean sightIsScreenCentre;
    private static final AimFocus focus = new AimFocus();
    /** When the aim focus last ran (ns); its targets are freed after a while without aiming. */
    private static long lastFocus;

    private Optics() {
    }

    public static void init(BusGroup modBus) {
        TickEvent.RenderTickEvent.Pre.BUS.addListener(event -> frame++);
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> {
            if (lastFocus != 0 && System.nanoTime() - lastFocus > 10_000_000_000L) {
                focus.release();
                lastFocus = 0;
            }
        });
        ViewportEvent.ComputeFov.BUS.addListener(Priority.MONITOR, event -> {
            if (event.usedConfiguredFov()) worldFov = (float) event.getFOV();
        });
        // below the gun HUD: ammo and hit markers stay visible over the scope's mask
        AddGuiOverlayLayersEvent.getBus(modBus).addListener(event -> event.getLayeredDraw().addBelow(ForgeLayeredDraw.PRE_SLEEP_STACK,
                BlockLand.id("scope_overlay"), BlockLand.id("gun_hud"), ScopeOverlay::render));
        // the mouse wheel steps a variable scope's power while aiming (instead of the hotbar)
        InputEvent.MouseScrollingEvent.BUS.addListener(event -> Minecraft.getInstance().screen == null && event.getDeltaY() != 0
                && Scopes.stepZoom(event.getDeltaY() > 0 ? 1 : -1));
        net.minecraftforge.client.event.CustomizeGuiOverlayEvent.DebugText.BUS.addListener(event -> {
            if (event.getSide() == net.minecraftforge.client.event.CustomizeGuiOverlayEvent.DebugText.Side.Left
                    && lastFocus != 0 && System.nanoTime() - lastFocus < 500_000_000L) {
                event.getText().add("[BlockLand] aim focus: " + focus.stats());
            }
            return false;
        });
        Scopes.init(modBus);
        ScopeGlints.init();
    }

    // ---- fed by the weapon each frame -----------------------------------------------------------------------------

    /**
     * The held weapon's optic (null when no gun is held) and how far its sight is raised (0 hip … 1 aimed). The steady
     * (hold breath) key is the sprint key: sprinting is impossible while aiming anyway.
     */
    public static void update(@Nullable CraftData.Attachment optic, float raised, float dt) {
        Minecraft mc = Minecraft.getInstance();
        sight = optic;
        aim = optic == null ? 0 : Math.max(0, Math.min(1, raised));
        boolean steady = mc.screen == null && mc.options.keySprint.isDown();
        Scopes.update(optic, aim, dt, steady);
    }

    /** The world's field of view while aiming through the held optic at this eased aim amount. */
    public static float fov(float fov, CraftData.Attachment optic, float eased) {
        return Scopes.zoomedFov(fov, optic, eased);
    }

    /** Surfaces of the optic's model the optics draw themselves this frame (the viewmodel leaves them out). */
    public static Set<String> drawnMaterials(CraftData.Attachment optic) {
        return optic.isScope() ? Scopes.replacedMaterials() : ReflexSights.takesOver(optic);
    }

    /** Classic scope zoom is up: the overlay is the scope, the viewmodel is not drawn. */
    public static boolean hidesViewmodel() {
        return Scopes.hidesViewmodel();
    }

    /** Multiplier on the look sensitivity for the raised optic (1 otherwise). */
    public static float sensitivityScale() {
        return Scopes.sensitivityScale();
    }

    /** How far the aimed pose moves a scope's eye point towards its eyepiece (see {@link Scopes#adsEyeShift}). */
    public static float adsEyeShift(@Nullable CraftData.Attachment optic) {
        return Scopes.adsEyeShift(optic);
    }

    /** The rifle's breathing sway this frame (degrees about the support hand; 0 unless a scope shows its picture). */
    public static float swayYaw() {
        return Scopes.swayYaw();
    }

    public static float swayPitch() {
        return Scopes.swayPitch();
    }

    /** With classic zoom the view itself sways (degrees). */
    public static float cameraSwayYaw() {
        return Scopes.cameraSwayYaw();
    }

    public static float cameraSwayPitch() {
        return Scopes.cameraSwayPitch();
    }

    /**
     * After the viewmodel is posed, before it is drawn: the aim focus — a sharp circle around the aim point (a scope's
     * eyepiece), the world beyond it blurring more and more towards the screen's corners. Drawn before the weapon, so
     * the weapon stays sharp; off with classic scope zoom and with the setting.
     */
    public static void renderBehind(SightPose pose) {
        if (sight == null) return;
        if (sight.isScope()) Scopes.renderBehind(pose);
        if (Scopes.hidesViewmodel()) return;
        float raised = Scopes.smooth((aim - 0.55f) / 0.45f);
        if (raised <= 0.01f) return;
        float x, y, radius, dim = 0;
        if (sight.isScope()) {
            if (Scopes.mode() != Scopes.Mode.PICTURE || !Scopes.lensOnScreen()) return;
            x = Scopes.lensX();
            y = Scopes.lensY();
            radius = Scopes.lensRadius();
            if (ClientSettings.get().scopeDimOutside) dim = 0.42f * raised;
        } else {
            // the point the sight line meets at infinity, on screen
            Matrix4f node = sight.hasModel() ? pose.opticNode(sight.aimNode) : pose.ironSight();
            Vector3f line = node == null ? new Vector3f(0, 0, -1) : node.transformDirection(new Vector3f(0, 0, -1)).normalize();
            if (line.z > -1e-3f) return;
            Minecraft mc = Minecraft.getInstance();
            float aspect = (float) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
            float tanViewmodel = (float) Math.tan(Math.toRadians(ViewmodelRenderer.FOV) / 2);
            x = line.x / -line.z / (tanViewmodel * aspect);
            y = line.y / -line.z / tanViewmodel;
            radius = sight.aimFocusRadius();
        }
        float blur = ClientSettings.get().scopeBlurOutside ? sight.aimFocusBlur() * raised : 0;
        if (blur <= 0.01f && dim <= 0.01f) return;
        focus.render(x, y, radius, blur, dim);
        lastFocus = System.nanoTime();
    }

    /**
     * Right after the viewmodel is drawn: the optics in the optic's glass (a scope's eyepiece, a reflex sight's
     * reticle), and the sight line as drawn this frame, which shots then follow.
     */
    public static void renderFront(SightPose pose, int blockLight, int skyLight) {
        if (sight == null) return;
        if (sight.isScope()) {
            Scopes.render(pose, blockLight, skyLight);
        } else if (sight.isReflex()) {
            ReflexSights.render(sight, pose::opticNode, blockLight, skyLight);
        }
        trackSightLine(pose);
    }

    /** Remembers where the sight line pointed this frame. */
    private static void trackSightLine(SightPose pose) {
        Vector3f line = null;
        sightIsScreenCentre = false;
        if (sight.isScope() && Scopes.mode() != Scopes.Mode.NONE) {
            line = Scopes.sightLine();
            sightIsScreenCentre = line == null;
        } else {
            Matrix4f node = sight.hasModel() ? pose.opticNode(sight.aimNode) : pose.ironSight();
            if (node != null) line = node.transformDirection(new Vector3f(0, 0, -1)).normalize();
        }
        if (line == null && !sightIsScreenCentre) return;
        if (line != null) sightView.set(line);
        sightFrame = frame;
        sightK = (float) (Math.tan(Math.toRadians(worldFov) / 2) / Math.tan(Math.toRadians(ViewmodelRenderer.FOV) / 2));
    }

    /**
     * Where a shot fired now goes, in the world: along the sight line as the player last saw it (the reticle is the
     * point of impact), blended in over the last stretch of raising the sight; null below that or without a fresh
     * sight line — the shot then goes where the player looks.
     */
    @Nullable
    public static Vector3f shotDirection() {
        if (sight == null || aim < SIGHT_FROM || frame - sightFrame > 2) return null;
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vector3f forward = camera.rotation().transform(new Vector3f(0, 0, -1));
        if (sightIsScreenCentre) return forward;
        Vector3f line = camera.rotation().transform(ScopeOptics.worldDirection(sightView, sightK, new Vector3f()));
        float w = Scopes.smooth((aim - SIGHT_FROM) / (1 - SIGHT_FROM));
        return forward.lerp(line, w).normalize();
    }

    // ---- other players --------------------------------------------------------------------------------------------

    /** The server says a player this client can see raised ({@code aiming}) or lowered their sights. */
    public static void remoteAim(int playerId, boolean aiming) {
        ScopeGlints.onAim(playerId, aiming);
    }

    /** Tells the glints which optic a player holds (the weapon module knows; null = no sight, or no gun). */
    public static void setSightOf(Function<Player, CraftData.Attachment> sightOf) {
        ScopeGlints.setSightOf(sightOf);
    }

    // ---- shared state ---------------------------------------------------------------------------------------------

    /** The world's field of view this frame (degrees), as the level was drawn. */
    public static float worldFov() {
        return worldFov;
    }

    /** Counts rendered frames (things computed once per frame check it). */
    static long frameId() {
        return frame;
    }

    /** Dev tools: the scope picture's target (read back by the automation driver), null when none. */
    @Nullable
    public static RenderTarget debugPicture() {
        return Scopes.pictureTarget();
    }

    /** Dev tools: one line about the raised optic and where its sight line points against the camera. */
    public static String debugLine() {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vector3f shot = shotDirection();
        return String.format("sight=%s aim=%.3f mode=%s power=%.2f lens=%s(%.3f %.3f r%.3f) sway=(%.3f %.3f) breath=%.2f%s camera=(%.2f %.2f) shot=%s",
                sight == null ? "-" : sight.id, aim, Scopes.mode(), Scopes.magnification(), Scopes.lensOnScreen(),
                Scopes.lensX(), Scopes.lensY(), Scopes.lensRadius(), Scopes.swayYaw(), Scopes.swayPitch(),
                Scopes.steady().breath(), Scopes.steady().holding() ? " holding" : "", camera.getYRot(), camera.getXRot(),
                shot == null ? "-" : String.format("(%.2f %.2f)", ScopeOptics.yaw(shot), ScopeOptics.pitch(shot)));
    }
}
