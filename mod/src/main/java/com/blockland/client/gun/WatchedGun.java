package com.blockland.client.gun;

import com.blockland.BlockLand;
import com.blockland.anim.Ease;
import com.blockland.client.craft.WeaponLook;
import com.blockland.client.gltf.GltfModel;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.ModelCache;
import com.blockland.client.viewmodel.ViewSubject;
import com.blockland.client.viewmodel.ViewmodelRenderer;
import com.blockland.craft.CraftData;
import com.blockland.gun.GunContent;
import com.blockland.gun.GunData;
import com.blockland.gun.GunItem;
import com.blockland.gun.GunState;
import com.blockland.gun.GunStats;
import com.blockland.network.packet.GunPackets;
import com.blockland.network.packet.ViewmodelPackets;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderHandEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The gun of the player a spectator watches through their eyes ({@link ViewSubject}): its own {@link Viewmodel}
 * instance, equipped from the watched player's held stack (its synced state carries the build and the rounds left),
 * moved by their movement, firing with every shot the server reports for them (recoil, the fire clip, shells and the
 * tracer from the muzzle on screen), raising its sights when they aim and reloading when the server says they started.
 * Nothing the spectator presses reaches it; the local player's own gun path is not involved.
 */
final class WatchedGun {
    private static final Viewmodel viewmodel = new Viewmodel();
    private static final Random random = new Random();

    @Nullable
    private static AbstractClientPlayer player;
    private static String gunId = "";
    private static int slot = -1;
    @Nullable
    private static GunStats stats;
    private static boolean ready;
    private static int ammo;
    private static boolean aiming;
    /** A shell-by-shell reload the server reported: a shell clip for every round that arrives, the end at the last. */
    private static boolean shellReload;

    private record PendingTracer(Vec3 end, int gun) {
    }

    private static final int[] pendingShells = new int[2];
    private static final List<PendingTracer> pendingTracers = new ArrayList<>();

    private WatchedGun() {
    }

    static void init() {
        ViewSubject.onAction((watched, kind, a, b) -> {
            if (kind == ViewmodelPackets.Kind.RELOAD && watched == player) reload(a);
        });
    }

    /** The watched gun is on screen. */
    static boolean active() {
        return player != null && ready && stats != null && ViewSubject.watched() == player;
    }

    @Nullable
    static GunStats stats() {
        return active() ? stats : null;
    }

    static int ammo() {
        return ammo;
    }

    // ---- following the watched player ----------------------------------------------------------------

    /** Client tick: who is watched, what they hold, how many rounds it has. */
    static void tick() {
        AbstractClientPlayer watched = ViewSubject.watched();
        ItemStack stack = watched == null ? ItemStack.EMPTY : watched.getMainHandItem();
        if (!(stack.getItem() instanceof GunItem item)) {
            clear();
            return;
        }
        GunState state = GunContent.stateOf(stack);
        GunStats resolved = state == null ? null : GunStats.of(item.gunId(), state).orElse(null);
        if (resolved == null) {
            clear();
            return;
        }
        int selected = watched.getInventory().getSelectedSlot();
        if (watched != player || !item.gunId().equals(gunId) || selected != slot) {
            player = watched;
            gunId = item.gunId();
            slot = selected;
            aiming = false;
            shellReload = false;
            ready = viewmodel.equip(resolved.gun(), WeaponLook.modelOf(resolved.gun(), resolved.build()));
            viewmodel.setEventSink(WatchedGun::clipEvent);
            viewmodel.setNextLeft(false);
            viewmodel.ads.snap(0);
            ClientGuns.playUi("gun.equip", 1.0f, 0.5f);
        }
        stats = resolved;
        viewmodel.setStats(resolved);
        int rounds = state.ammo();
        // a shell reload loads a round at a time: each one that arrives plays its clip, the last one the end clip
        if (shellReload && rounds > ammo) {
            GunData.Anims anims = resolved.gun().anims;
            GunData.Reload reload = resolved.gun().reload;
            if (rounds >= resolved.capacity()) {
                shellReload = false;
                viewmodel.play(Viewmodel.Action.RELOAD, anims.reloadEnd, reload.end);
            } else {
                viewmodel.play(Viewmodel.Action.RELOAD, anims.reloadShell, reload.shell);
            }
        }
        ammo = rounds;
    }

    private static void clear() {
        if (player == null && gunId.isEmpty()) return;
        player = null;
        gunId = "";
        slot = -1;
        stats = null;
        ready = false;
        aiming = false;
        shellReload = false;
        pendingShells[0] = pendingShells[1] = 0;
        pendingTracers.clear();
        viewmodel.setEventSink(null);
        viewmodel.ads.snap(0);
    }

    /** Every frame: the sights and the gun's motion from the watched player's own movement. */
    static void frame(float dt) {
        if (!active()) return;
        boolean up = aiming && stats.gun().canAim() && viewmodel.action() != Viewmodel.Action.RELOAD;
        viewmodel.ads.toIfChanged(up ? 1 : 0, stats.adsTime(), Ease.SINE_IN_OUT);
        ViewmodelRenderer.suppressViewBobbing(player);
        viewmodel.update(dt, player, player.isSprinting());
    }

    /** The server's aim report for a player (every tracker gets it). */
    static void aim(int playerId, boolean up) {
        if (player != null && player.getId() == playerId) aiming = up;
    }

    /** The server started a reload of the watched gun. */
    private static void reload(int kind) {
        if (!active()) return;
        GunData.Gun gun = stats.gun();
        aiming = false;
        if (kind == 2) {
            shellReload = true;
            viewmodel.play(Viewmodel.Action.RELOAD, gun.anims.reloadStart, gun.reload.start);
            return;
        }
        boolean empty = kind == 1;
        viewmodel.play(Viewmodel.Action.RELOAD, empty ? gun.anims.reloadEmpty : gun.anims.reload, stats.reloadTime(empty));
    }

    /**
     * A shot of the watched player (every tracker gets it): the gun on screen fires — clip, kick, the case and the tracer
     * from its own muzzle. False when the shot is someone else's (the caller draws it in third person then).
     */
    static boolean shot(GunPackets.S2CShot shot, Vec3 end) {
        if (!active() || player.getId() != shot.shooterId()) return false;
        GunData.Gun gun = stats.gun();
        int hand = gun.dual != null ? Math.max(0, Math.min(1, shot.hand())) : 0;
        shellReload = false;
        String clip = ammo <= 1 ? gun.anims.fireLast : gun.anims.fire;
        if (gun.dual != null) clip = hand == 1 ? gun.dual.fireLeft : gun.anims.fire;
        if (!viewmodel.hasClip(clip)) clip = gun.anims.fire;
        float length = viewmodel.clipLength(clip), interval = gun.fireInterval();
        viewmodel.setNextLeft(hand == 0 && gun.dual != null);
        viewmodel.play(Viewmodel.Action.FIRE, clip, length > interval && interval > 0 ? interval * 0.98f : 0);
        viewmodel.fired(stats.kick(), viewmodel.ads.get(), 1 << hand);
        pendingTracers.add(new PendingTracer(end, hand));
        if (gun.ejectEvent.isEmpty()) pendingShells[hand]++;
        return true;
    }

    private static void clipEvent(String name) {
        ClientGuns.playUi("gun." + name, 1.0f, 0.45f);
        Viewmodel.Action action = viewmodel.action();
        if (stats != null && name.equals(stats.gun().ejectEvent)
                && (action == Viewmodel.Action.FIRE || action == Viewmodel.Action.RELOAD)) {
            pendingShells[0]++;
        }
    }

    // ---- camera and drawing ---------------------------------------------------------------------------

    static float cameraPitch() {
        return active() ? viewmodel.cameraPitch() : 0;
    }

    static float cameraYaw() {
        return active() ? viewmodel.cameraYaw() : 0;
    }

    static float cameraRoll() {
        return active() ? viewmodel.cameraRoll() : 0;
    }

    static void moveCamera(Camera camera) {
        if (active()) viewmodel.moveCamera(camera);
    }

    /** Draws the watched gun in the hand phase (no optics: the spectator sees the sights, never through a scope). */
    static boolean renderHand(RenderHandEvent event) {
        if (!active()) return false;
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return true;
        Matrix4f base = new Matrix4f();
        int light = event.getPackedLight();
        viewmodel.hideOpticMaterials(Set.of());
        viewmodel.draw(base, ammo, player, LightTexture.block(light), LightTexture.sky(light));
        viewmodel.submit();
        spawnPendingEffects(base);
        return true;
    }

    /** Shells and tracers start at this frame's muzzle / ejection port of the gun on screen. */
    private static void spawnPendingEffects(Matrix4f base) {
        if (pendingShells[0] == 0 && pendingShells[1] == 0 && pendingTracers.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || player == null) return;
        Camera camera = mc.gameRenderer.getMainCamera();
        Matrix4f tmp = new Matrix4f();
        Vec3[] muzzles = new Vec3[2];
        for (int gun = 0; gun < 2; gun++) {
            Matrix4f at = viewmodel.nodeMatrix("muzzle", gun, base, tmp);
            muzzles[gun] = gun == 1 && at == null ? muzzles[0] : ViewmodelRenderer.viewToWorld(camera, at, player.getEyePosition());
        }
        CraftData.SkinFx skinFx = stats == null ? null : stats.skinFx();
        for (PendingTracer tracer : pendingTracers) {
            mc.particleEngine.add(skinFx == null ? new GunParticles.Tracer(level, muzzles[tracer.gun()], tracer.end())
                    : new GunParticles.Tracer(level, muzzles[tracer.gun()], tracer.end(),
                    skinFx.hasTracerColor() ? skinFx.tracerRgb() : GunParticles.DEFAULT_TRACER, Math.max(0.2f, skinFx.tracerWidth)));
        }
        pendingTracers.clear();
        GltfModel.Primitive shell = null;
        for (int gun = 0; gun < 2; gun++) {
            if (pendingShells[gun] == 0) continue;
            Matrix4f eject = viewmodel.nodeMatrix("ejection", gun, base, tmp);
            if (eject == null && gun == 1) eject = viewmodel.nodeMatrix("ejection", 0, base, tmp);
            Vec3 at = ViewmodelRenderer.viewToWorld(camera, eject, muzzles[gun]);
            Vector3f out = eject == null ? new Vector3f(1, 0, 0) : eject.transformDirection(new Vector3f(1, 0.6f, 0.15f)).normalize();
            Vector3f world = camera.rotation().transform(out, new Vector3f());
            if (shell == null) shell = shellMesh();
            for (int i = 0; i < pendingShells[gun]; i++) {
                Vec3 velocity = new Vec3(world.x, world.y, world.z).scale(0.14 + random.nextDouble() * 0.05)
                        .add(player.getDeltaMovement()).add(0, 0.06, 0);
                mc.particleEngine.add(new GunParticles.Shell(level, at, velocity, shell));
            }
            pendingShells[gun] = 0;
        }
    }

    @Nullable
    private static GltfModel.Primitive shellMesh() {
        if (stats == null) return null;
        GpuModel model = viewmodel.model();
        if (model == null) model = ModelCache.get(BlockLand.id(stats.gun().model));
        if (model == null) return null;
        int node = model.source.node("fx_shell");
        if (node < 0 || model.source.nodes.get(node).mesh < 0) return null;
        List<GltfModel.Primitive> primitives = model.source.meshes.get(model.source.nodes.get(node).mesh).primitives;
        return primitives.isEmpty() ? null : primitives.get(0);
    }
}
