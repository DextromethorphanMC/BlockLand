package com.blockland.client.melee;

import com.blockland.BlockLand;
import com.blockland.anim.Ticker;
import com.blockland.client.decal.Decals;
import com.blockland.client.hud.HitMarker;
import com.blockland.client.input.WeaponInput;
import com.blockland.client.viewmodel.Holster;
import com.blockland.client.viewmodel.ViewmodelRenderer;
import com.blockland.effect.ActionLocks;
import com.blockland.melee.KnifeItem;
import com.blockland.melee.MeleeCatalog;
import com.blockland.melee.MeleeContent;
import com.blockland.melee.MeleeData;
import com.blockland.melee.MeleeTrace;
import com.blockland.movement.Slides;
import com.blockland.network.BLNetwork;
import com.blockland.network.MeleePacketListeners;
import com.blockland.network.packet.MeleePackets;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.Random;

import static com.blockland.client.ui.Tr.tr;

/**
 * Client side of knives: input (light attack on left click — held to keep swinging — heavy attack on right click,
 * inspect), the attack timing (cooldown, combo alternation, buffered presses), instant local effects when the blade
 * connects (cut decals, particles, sounds, camera punch) and the hooks that put the {@link KnifeViewmodel} on
 * screen; switching away hands the knife on screen to the {@link Holster} (two viewmodels take turns). The server
 * stays authoritative for hits and damage.
 */
public final class ClientKnives {
    /** A press this long before the knife is ready still counts (it plays as soon as the cooldown ends). */
    private static final long BUFFER_NANOS = 250_000_000L;

    /** The knife on screen, and the other instance (dropping out of view after a switch, or idle). */
    private static KnifeViewmodel viewmodel = new KnifeViewmodel();
    private static KnifeViewmodel spare = new KnifeViewmodel();
    private static final Random random = new Random();

    // equipped knife
    private static int slot = -1;
    private static String knifeId = "";
    @Nullable
    private static MeleeData.Knife knife;
    private static boolean modelReady;

    // attack timing
    private static long drawEnd;
    private static long readyAt;
    private static long attackEnd;
    private static long lastLightAt;
    /** When the current cooldown (or draw) began, for the HUD. */
    private static long cooldownFrom;
    private static int lightCombo;
    /** The attack whose blade has not connected yet. */
    private static boolean strikePending;
    private static long strikeAt;
    private static boolean strikeHeavy;
    private static int strikeCombo;
    /** A press during the cooldown: -1 none, 0 light, 1 heavy. */
    private static int buffered = -1;
    private static long bufferedAt;

    private static long lastFrame;
    private static double renderMillis;

    private ClientKnives() {
    }

    public static void init(BusGroup modBus) {
        RegisterClientReloadListenersEvent.getBus(modBus).addListener(event -> event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> knifeId = ""));
        AddGuiOverlayLayersEvent.getBus(modBus).addListener(event -> event.getLayeredDraw()
                .addAbove(ForgeLayeredDraw.PRE_SLEEP_STACK, BlockLand.id("knife_hud"), ForgeLayeredDraw.CROSSHAIR, KnifeHud::render));
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> clientTick());
        TickEvent.RenderTickEvent.Pre.BUS.addListener(event -> frame());
        RenderHandEvent.BUS.addListener(ClientKnives::renderHand);
        InputEvent.InteractionKeyMappingTriggered.BUS.addListener(ClientKnives::interaction);
        WeaponInput.onAttackPressed(() -> pressed(false));
        WeaponInput.onUsePressed(() -> pressed(true));
        // no sprinting while attacking or inspecting: the knife needs the hand
        MovementInputUpdateEvent.BUS.addListener(event -> {
            if (!holdingKnife() || !busy()) return;
            Input keys = event.getInput().keyPresses;
            if (keys.sprint()) {
                event.getInput().keyPresses = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(), keys.shift(), false);
            }
            if (event.getEntity().isSprinting()) event.getEntity().setSprinting(false);
        });
        ViewportEvent.ComputeCameraAngles.BUS.addListener(event -> {
            if (!holdingKnife() || !modelReady || Minecraft.getInstance().options.getCameraType() != CameraType.FIRST_PERSON) return;
            event.setPitch(event.getPitch() + viewmodel.cameraPitch());
            event.setYaw(event.getYaw() + viewmodel.cameraYaw());
            event.setRoll(event.getRoll() + viewmodel.cameraRoll());
            viewmodel.camera().moveCamera(event.getCamera());
        });
        ViewportEvent.ComputeFov.BUS.addListener(event -> {
            if (event.usedConfiguredFov() && holdingKnife() && modelReady) event.setFOV(event.getFOV() * viewmodel.camera().fovScale());
        });
        CustomizeGuiOverlayEvent.DebugText.BUS.addListener(event -> {
            if (event.getSide() == CustomizeGuiOverlayEvent.DebugText.Side.Left && holdingKnife() && modelReady) {
                event.getText().add(String.format("[BlockLand] knife viewmodel: %s, %.3f ms CPU", viewmodel.stats(), renderMillis));
            }
            return false;
        });
        BLNetwork.setMeleeClientListener(new MeleePacketListeners.Client() {
            @Override
            public void onHit(MeleePackets.S2CHit hit) {
                boolean backstab = hit.kind() == MeleePackets.HitKind.BACKSTAB || hit.kind() == MeleePackets.HitKind.BACKSTAB_KILL;
                boolean kill = hit.kind() == MeleePackets.HitKind.KILL || hit.kind() == MeleePackets.HitKind.BACKSTAB_KILL;
                HitMarker.show(backstab, kill);
                if (backstab) KnifeHud.flash(tr("blockland.knife.hud.backstab"));
                MeleeData.Sounds sounds = knife != null ? knife.sounds : new MeleeData.Sounds();
                playUi(kill ? sounds.kill : backstab ? sounds.critical : sounds.hitMarker, 1.0f, 0.9f);
            }

            @Override
            public void onImpact(MeleePackets.S2CImpact impact) {
                remoteImpact(impact);
            }
        });
    }

    // ---- state queried by the HUD ---------------------------------------------------------------------

    public static boolean holdingKnife() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getMainHandItem().getItem() instanceof KnifeItem && !mc.player.isSpectator();
    }

    @Nullable
    static MeleeData.Knife knife() {
        return knife;
    }

    /** 0..1 how far the knife is through its cooldown or draw (1 = ready). */
    static float readiness() {
        long end = Math.max(readyAt, drawEnd), now = System.nanoTime();
        if (knife == null || now >= end) return 1;
        return Math.max(0, (now - cooldownFrom) / (float) Math.max(1, end - cooldownFrom));
    }

    private static boolean busy() {
        KnifeViewmodel.Action action = viewmodel.action();
        return strikePending || System.nanoTime() < attackEnd || action == KnifeViewmodel.Action.INSPECT;
    }

    // ---- ticking ------------------------------------------------------------------------------------

    private static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        syncEquipped(player);
        if (knife != null) viewmodel.setBuild(MeleeContent.buildOf(player.getMainHandItem()));
        if (WeaponInput.inspectPressed() && knife != null && mc.screen == null) inspect(player);
    }

    private static void syncEquipped(LocalPlayer player) {
        int selected = player.getInventory().getSelectedSlot();
        MeleeData.Knife held = player.isSpectator() ? null : MeleeContent.knifeOf(player.getMainHandItem()).orElse(null);
        if (held == null) {
            if (!knifeId.isEmpty()) unequip();
            return;
        }
        if (selected == slot && held.id.equals(knifeId)) return;
        holster(player);
        slot = selected;
        knifeId = held.id;
        knife = held;
        strikePending = false;
        buffered = -1;
        long now = System.nanoTime();
        modelReady = viewmodel.equip(held);
        viewmodel.setEventSink(name -> playUi("blockland:knife." + name, 0.97f + random.nextFloat() * 0.06f, 0.7f));
        drawEnd = now + (long) (held.drawTime * 1e9);
        readyAt = Math.min(readyAt, drawEnd);
        cooldownFrom = now;
        playUi(held.sounds.equip, 1.0f, 0.6f);
    }

    private static void unequip() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) holster(player);
        slot = -1;
        knifeId = "";
        knife = null;
        modelReady = false;
        strikePending = false;
        buffered = -1;
    }

    /** The knife on screen drops out of view (spec §31.4) while the next weapon is drawn; the spare takes over. */
    private static void holster(LocalPlayer player) {
        if (!modelReady || !viewmodel.isReady()) return;
        KnifeViewmodel out = viewmodel;
        out.setEventSink(name -> {
        });
        Holster.start(out, new Holster.Outgoing() {
            @Override
            public void update(float dt) {
                out.update(dt, player, false);
            }

            @Override
            public void draw(Matrix4f base, int blockLight, int skyLight) {
                out.draw(base, player, blockLight, skyLight);
                out.submit();
            }
        });
        viewmodel = spare;
        spare = out;
        Holster.release(viewmodel);
        modelReady = false;
    }

    /** Every frame: animation clock, the pending strike, buffered and held attacks. */
    private static void frame() {
        Ticker.global().tick();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        if (player == null || knife == null || !holdingKnife()) return;
        if (modelReady) ViewmodelRenderer.suppressViewBobbing(player);
        boolean free = mc.screen == null && mc.getOverlay() == null;
        if (strikePending && now >= strikeAt) strike(player);
        if (buffered >= 0) {
            if (now - bufferedAt > BUFFER_NANOS) buffered = -1;
            else if (ready(now)) {
                boolean heavy = buffered == 1;
                buffered = -1;
                attack(player, heavy, false);
            }
        }
        // holding the button keeps slashing
        if (free && mc.options.keyAttack.isDown() && ready(now)) attack(player, false, false);
        viewmodel.update(dt, player, player.isSprinting());
    }

    // ---- input ----------------------------------------------------------------------------------------

    /** Attack and use never reach vanilla while a knife is held (no mining, no vanilla hit, no block use). */
    private static boolean interaction(InputEvent.InteractionKeyMappingTriggered event) {
        if (Minecraft.getInstance().player == null || !holdingKnife() || event.isPickBlock()) return false;
        event.setSwingHand(false);
        return true;
    }

    private static void pressed(boolean heavy) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || knife == null || !holdingKnife()) return;
        attack(mc.player, heavy, true);
    }

    private static boolean ready(long now) {
        return now >= drawEnd && now >= readyAt && !strikePending;
    }

    private static void attack(LocalPlayer player, boolean heavy, boolean click) {
        if (knife == null || slot < 0) return;
        if (ActionLocks.isLocked(player)) {
            buffered = -1;
            if (click) KnifeHud.flash(tr("blockland.effect.slimed"));
            return;
        }
        long now = System.nanoTime();
        if (!ready(now)) {
            if (click) {
                buffered = heavy ? 1 : 0;
                bufferedAt = now;
            }
            return;
        }
        if (player.isSprinting()) player.setSprinting(false);
        MeleeData.Attack attack = knife.attack(heavy);
        int combo = 0;
        if (!heavy) {
            // light swings alternate while they keep coming
            combo = now - lastLightAt < knife.comboWindow * 1e9 ? lightCombo + 1 : 0;
            lightCombo = combo;
            lastLightAt = now;
        }
        MeleeData.Swing swing = attack.swing(combo);
        viewmodel.play(heavy ? KnifeViewmodel.Action.HEAVY : KnifeViewmodel.Action.LIGHT, swing.clip, attack.duration);
        readyAt = now + (long) (attack.cooldown * 1e9);
        cooldownFrom = now;
        attackEnd = now + (long) (attack.duration * 1e9);
        strikePending = true;
        strikeAt = now + (long) (attack.impact * 1e9);
        strikeHeavy = heavy;
        strikeCombo = combo;
        BLNetwork.sendToServer(new MeleePackets.C2SAttack(slot, heavy, combo));
    }

    private static void inspect(LocalPlayer player) {
        if (knife == null || System.nanoTime() < drawEnd || busy()) return;
        if (player.isSprinting()) player.setSprinting(false);
        viewmodel.play(KnifeViewmodel.Action.INSPECT, knife.anims.inspect, 0);
    }

    /** The blade connects: this client's own trace drives the instant effects (the server re-traces for damage). */
    private static void strike(LocalPlayer player) {
        strikePending = false;
        if (knife == null) return;
        MeleeData.Attack attack = knife.attack(strikeHeavy);
        MeleeData.Swing swing = attack.swing(strikeCombo);
        ClientLevel level = (ClientLevel) player.level();
        Vec3 eye = Slides.eyePosition(player);
        MeleeTrace.Hit hit = MeleeTrace.trace(level, player, eye, player.getViewVector(1), attack);
        Vec3 cut = cutDirection(player.getViewYRot(1), player.getViewXRot(1), swing.slashAngle);
        switch (hit.type()) {
            case ENTITY -> {
                Decals.onEntity(attack.bodyDecal, hit.entity(), eye, hit.point(), cut);
                blood(level, hit.point(), cut);
                playUi(knife.sounds.hitFlesh, 0.95f + random.nextFloat() * 0.1f, 0.9f);
                viewmodel.impact(strikeHeavy ? 1.5f : 1f, attack.punch, swing.slashAngle);
            }
            case BLOCK -> {
                Decals.onBlock(attack.blockDecal, hit.point(), hit.face(), hit.pos(), cut);
                chips(level, hit.point(), hit.pos(), hit.face());
                playUi(knife.sounds.hitBlock, 0.95f + random.nextFloat() * 0.1f, 0.8f);
                BlockState state = level.getBlockState(hit.pos());
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(state.getSoundType().getHitSound(), 0.8f, 0.5f));
                // a wall stops the blade hard
                viewmodel.impact(strikeHeavy ? 1.9f : 1.35f, attack.punch, swing.slashAngle);
            }
            case MISS -> { }
        }
    }

    // ---- other players --------------------------------------------------------------------------------

    private static void remoteImpact(MeleePackets.S2CImpact impact) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        Entity attacker = level.getEntity(impact.attackerId());
        MeleeData.Knife used = attacker instanceof Player player ? MeleeContent.knifeOf(player.getMainHandItem()).orElse(null) : null;
        if (used == null) used = MeleeCatalog.get().knives().stream().findFirst().orElse(null);
        if (used == null) return;
        MeleeData.Attack attack = used.attack(impact.heavy());
        Vec3 point = new Vec3(impact.x(), impact.y(), impact.z());
        Vec3 from = attacker != null ? attacker.getEyePosition() : point.add(0, 0.5, 0);
        Vec3 cut = attacker != null ? cutDirection(attacker.getViewYRot(1), attacker.getViewXRot(1), attack.swing(impact.combo()).slashAngle) : null;
        if (impact.target() == MeleePackets.Target.ENTITY) {
            Entity target = level.getEntity(impact.targetId());
            if (target != null) Decals.onEntity(attack.bodyDecal, target, from, point, cut);
            blood(level, point, cut == null ? new Vec3(1, 0, 0) : cut);
        } else if (impact.face() >= 0 && impact.face() < Direction.values().length) {
            Direction face = Direction.values()[impact.face()];
            BlockPos pos = BlockPos.containing(point.subtract(face.getStepX() * 0.01, face.getStepY() * 0.01, face.getStepZ() * 0.01));
            Decals.onBlock(attack.blockDecal, point, face, pos, cut);
            chips(level, point, pos, face);
        }
    }

    // ---- effects --------------------------------------------------------------------------------------

    /**
     * World direction of a cut seen from a view: {@code slashAngle} degrees counter-clockwise from the view's right.
     * Minecraft yaw 0 looks along +Z, where the screen's right is −X.
     */
    static Vec3 cutDirection(float yaw, float pitch, float slashAngle) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        Vec3 forward = new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
        Vec3 right = new Vec3(-Math.cos(y), 0, -Math.sin(y));
        Vec3 up = right.cross(forward);
        double a = Math.toRadians(slashAngle);
        return right.scale(Math.cos(a)).add(up.scale(Math.sin(a))).normalize();
    }

    private static void blood(ClientLevel level, Vec3 at, Vec3 cut) {
        BlockParticleOption drop = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState());
        for (int i = 0; i < 7; i++) {
            double s = 0.05 + random.nextDouble() * 0.12;
            level.addParticle(drop, at.x, at.y, at.z, cut.x * s + (random.nextDouble() - 0.5) * 0.06,
                    cut.y * s + random.nextDouble() * 0.08, cut.z * s + (random.nextDouble() - 0.5) * 0.06);
        }
    }

    private static void chips(ClientLevel level, Vec3 at, BlockPos pos, Direction face) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return;
        BlockParticleOption chip = new BlockParticleOption(ParticleTypes.BLOCK, state);
        double ox = face.getStepX() * 0.05, oy = face.getStepY() * 0.05, oz = face.getStepZ() * 0.05;
        for (int i = 0; i < 5; i++) {
            level.addParticle(chip, at.x + ox, at.y + oy, at.z + oz, face.getStepX() * 0.08 + (random.nextDouble() - 0.5) * 0.15,
                    face.getStepY() * 0.08 + random.nextDouble() * 0.12, face.getStepZ() * 0.08 + (random.nextDouble() - 0.5) * 0.15);
        }
        // steel on stone: a couple of sparks
        level.addParticle(ParticleTypes.CRIT, at.x + ox, at.y + oy, at.z + oz, face.getStepX() * 0.2, 0.1, face.getStepZ() * 0.2);
    }

    // ---- rendering ------------------------------------------------------------------------------------

    /** Replaces the vanilla first-person hands with the knife viewmodel. */
    private static boolean renderHand(RenderHandEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || knife == null || !modelReady || !holdingKnife()) return false;
        if (event.getHand() != InteractionHand.MAIN_HAND) return true; // the rig owns both arms
        long start = System.nanoTime();
        int light = event.getPackedLight();
        viewmodel.draw(new Matrix4f(), player, LightTexture.block(light), LightTexture.sky(light));
        viewmodel.submit();
        renderMillis = renderMillis * 0.95 + (System.nanoTime() - start) / 1e6 * 0.05;
        return true;
    }

    private static void playUi(String sound, float pitch, float volume) {
        SoundEvent event = MeleeContent.sound(sound);
        if (event != null) Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(event, pitch, volume));
    }
}
