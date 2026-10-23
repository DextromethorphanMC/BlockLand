package com.blockland.server.equipment;

import com.blockland.effect.ActionLocks;
import com.blockland.entity.BLEntities;
import com.blockland.equipment.EquipmentContent;
import com.blockland.equipment.EquipmentData;
import com.blockland.equipment.ThrownEquipment;
import com.blockland.equipment.effect.EquipmentEffects;
import com.blockland.movement.Slides;
import com.blockland.network.BLNetwork;
import com.blockland.network.EquipmentPacketListeners;
import com.blockland.network.packet.EquipmentPackets;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Server authority for equipment: validates every request (selected slot, item held, cooldown), times the hand — the
 * pin coming out ({@code pullTime}), cooking, the release point of the throw clip ({@code releaseAt}), an injection's
 * {@code injectAt} — launches the {@link ThrownEquipment} from the eye ({@link Slides#eyePosition}), uses the item up
 * (unless the player's game hands out infinite equipment) and tells the thrower about knife hits. A grenade cooked past
 * its fuse goes off in the hand; one whose pin is out when the player switches away or dies drops live at their feet.
 */
public final class EquipmentService implements EquipmentPacketListeners.Server {
    /** Requests may arrive this many ticks early (client clock, packets batched per tick). */
    private static final int EARLY_TICKS = 2;

    private final MinecraftServer server;
    /** The player's game hands out infinite equipment (practice modes). */
    private final Predicate<ServerPlayer> infinite;
    private final Map<UUID, Hand> hands = new HashMap<>();

    private static final class Hand {
        int readyTick;
        @Nullable
        Prep prep;
    }

    /** An item being readied, thrown or used. */
    private static final class Prep {
        final int slot;
        final EquipmentData.Equipment item;
        final boolean lob;
        final int startTick;
        /** Grenades: the pin is out (the rag lit) from this tick; knives and injectors: unused. */
        final int pinTick;
        /** The item leaves the hand (or the injection takes hold) at this tick; -1 while still held. */
        int releaseTick = -1;
        boolean pinSounded;

        Prep(int slot, EquipmentData.Equipment item, boolean lob, int startTick, int pinTick) {
            this.slot = slot;
            this.item = item;
            this.lob = lob;
            this.startTick = startTick;
            this.pinTick = pinTick;
        }

        boolean pinOut(int now) {
            return item.held() && now >= pinTick;
        }
    }

    public EquipmentService(MinecraftServer server, Predicate<ServerPlayer> infinite) {
        this.server = server;
        this.infinite = infinite;
        ThrownEquipment.setHitListener(hit -> BLNetwork.sendTo(hit.thrower(),
                new EquipmentPackets.S2CHit(hit.head(), hit.killed(), hit.damage())));
    }

    @Override
    public void onAction(ServerPlayer player, EquipmentPackets.C2SAction action) {
        Hand hand = hands.computeIfAbsent(player.getUUID(), id -> new Hand());
        int now = server.getTickCount();
        switch (action.action()) {
            case PRESS -> press(player, hand, action, now);
            case RELEASE -> {
                Prep prep = hand.prep;
                if (prep == null || !prep.item.held() || prep.releaseTick >= 0) return;
                prep.releaseTick = Math.max(now, prep.pinTick) + ticks(prep.item.throwing.releaseAt);
            }
            case CANCEL -> cancel(player, hand, now);
        }
    }

    private void press(ServerPlayer player, Hand hand, EquipmentPackets.C2SAction action, int now) {
        if (!player.isAlive() || player.isSpectator() || action.slot() != player.getInventory().getSelectedSlot()) return;
        if (ActionLocks.isLocked(player) || hand.prep != null || now < hand.readyTick - EARLY_TICKS) return;
        EquipmentData.Equipment item = EquipmentContent.equipmentOf(player.getMainHandItem()).orElse(null);
        if (item == null) return;
        Prep prep = new Prep(action.slot(), item, action.lob() && item.throwing.lob != null, now, now + ticks(item.pullTime));
        if (!item.held()) prep.releaseTick = now + ticks(item.thrown() ? item.throwing.releaseAt : item.injectAt);
        hand.prep = prep;
    }

    private void cancel(ServerPlayer player, Hand hand, int now) {
        Prep prep = hand.prep;
        if (prep == null) return;
        hand.prep = null;
        // a live grenade (pin out, rag burning) cannot be put back: it drops at the feet
        if (prep.pinOut(now)) {
            launch(player, prep, now, player.position().add(0, 0.4, 0), Vec3.ZERO);
            hand.readyTick = now + ticks(prep.item.cooldown);
        }
    }

    public void tick() {
        if (hands.isEmpty()) return;
        int now = server.getTickCount();
        Iterator<Map.Entry<UUID, Hand>> it = hands.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Hand> entry = it.next();
            Hand hand = entry.getValue();
            Prep prep = hand.prep;
            if (prep == null) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            step(player, hand, prep, now);
        }
    }

    private void step(ServerPlayer player, Hand hand, Prep prep, int now) {
        boolean holding = player.isAlive() && !player.isSpectator() && player.getInventory().getSelectedSlot() == prep.slot
                && EquipmentContent.equipmentOf(player.getMainHandItem()).map(i -> i.id.equals(prep.item.id)).orElse(false);
        if (!holding) {
            // switched away, dropped it, died: a live grenade falls where the player is
            cancel(player, hand, now);
            return;
        }
        EquipmentData.Equipment item = prep.item;
        ServerLevel level = player.level();
        if (item.held() && !prep.pinSounded && now >= prep.pinTick) {
            prep.pinSounded = true;
            play(level, player, EquipmentData.MOLOTOV.equals(item.kind) ? "equipment.molotov_light" : "equipment.pin", 0.7f);
        }
        // cooked too long: it goes off in the hand
        if (item.cookable && item.fuse > 0 && now >= prep.pinTick + ticks(item.fuse)
                && (prep.releaseTick < 0 || now < prep.releaseTick)) {
            hand.prep = null;
            ThrownEquipment thrown = launch(player, prep, now, handPosition(player), Vec3.ZERO);
            if (thrown != null) thrown.goOffNow();
            hand.readyTick = now + ticks(item.cooldown);
            return;
        }
        if (prep.releaseTick < 0 || now < prep.releaseTick) return;
        hand.prep = null;
        if (item.thrown()) {
            throwItem(player, prep, now);
            hand.readyTick = now + ticks(item.throwing.recover + item.cooldown);
        } else {
            EquipmentEffects.use(player, item.kind, item.effect);
            play(level, player, "equipment.adrenaline_inject", 0.8f);
            useUp(player, prep);
            hand.readyTick = prep.startTick + ticks(item.useTime + item.cooldown);
        }
    }

    /** The throw clip reached its release point: the item leaves the hand along the view (a little above it). */
    private void throwItem(ServerPlayer player, Prep prep, int now) {
        EquipmentData.Launch launch = prep.item.launch(prep.lob);
        float pitch = player.getXRot() - launch.pitchUp, yaw = player.getYRot();
        Vec3 direction = Vec3.directionFromRotation(pitch, yaw);
        // the body's own movement carries over (most of it: the arm throws relative to the shoulder)
        Vec3 carried = player.position().subtract(player.xo, player.yo, player.zo).scale(0.7);
        if (carried.lengthSqr() > 0.36) carried = carried.normalize().scale(0.6);
        Vec3 velocity = direction.scale(launch.speed).add(carried);
        Vec3 eye = Slides.eyePosition(player);
        Vec3 right = Vec3.directionFromRotation(0, yaw + 90);
        Vec3 start = eye.add(direction.scale(0.35)).add(right.scale(-0.12)).add(0, -0.08, 0);
        launch(player, prep, now, clear(player, eye, start), velocity);
        player.swing(InteractionHand.MAIN_HAND, true);
        String sound = EquipmentData.KNIFE.equals(prep.item.kind) ? "equipment.knife_throw" : prep.lob ? "equipment.lob" : "equipment.throw";
        play(player.level(), player, sound, 0.8f);
        if (prep.item.cookable) play(player.level(), player, "equipment.spoon", 0.5f);
    }

    /**
     * Creates the thrown entity and uses the item up.
     *
     * @return the entity, or null when the item could not be found in the slot any more
     */
    @Nullable
    private ThrownEquipment launch(ServerPlayer player, Prep prep, int now, Vec3 position, Vec3 velocity) {
        ItemStack stack = player.getInventory().getItem(prep.slot);
        if (!EquipmentContent.equipmentOf(stack).map(i -> i.id.equals(prep.item.id)).orElse(false)) return null;
        ServerLevel level = player.level();
        ThrownEquipment thrown = new ThrownEquipment(BLEntities.THROWN_EQUIPMENT.get(), level);
        // a cookable fuse has been burning since the pin came out; the others start now
        float fuse = prep.item.cookable ? prep.item.fuse - Math.max(0, now - prep.pinTick) / 20f : prep.item.fuse;
        thrown.launch(stack, player, position, velocity, fuse);
        level.addFreshEntity(thrown);
        useUp(player, prep);
        return thrown;
    }

    private void useUp(ServerPlayer player, Prep prep) {
        if (player.isCreative() || infinite.test(player)) return;
        ItemStack stack = player.getInventory().getItem(prep.slot);
        if (EquipmentContent.equipmentOf(stack).map(i -> i.id.equals(prep.item.id)).orElse(false)) stack.shrink(1);
    }

    /** Where the item starts: pulled back to the eye's side of any wall between the eye and the hand. */
    private static Vec3 clear(ServerPlayer player, Vec3 eye, Vec3 start) {
        BlockHitResult hit = player.level().clip(new ClipContext(eye, start, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.MISS) return start;
        Vec3 back = eye.subtract(hit.getLocation());
        return hit.getLocation().add(back.lengthSqr() < 1e-6 ? Vec3.ZERO : back.normalize().scale(0.12));
    }

    private static Vec3 handPosition(ServerPlayer player) {
        Vec3 eye = Slides.eyePosition(player);
        return clear(player, eye, eye.add(player.getLookAngle().scale(0.4)).add(0, -0.25, 0));
    }

    private static int ticks(float seconds) {
        return Math.max(0, Mth.ceil(seconds * 20 - 1e-3f));
    }

    /** A sound for everyone near the player except the player, who hears it from the viewmodel's clip events. */
    private static void play(ServerLevel level, ServerPlayer player, String name, float volume) {
        SoundEvent sound = EquipmentContent.own(name);
        if (sound != null) {
            level.playSound(player, player.getX(), player.getEyeY(), player.getZ(), sound, SoundSource.PLAYERS, volume,
                    0.95F + level.random.nextFloat() * 0.1F);
        }
    }

    public void forget(ServerPlayer player) {
        Hand hand = hands.remove(player.getUUID());
        if (hand != null && hand.prep != null && hand.prep.pinOut(server.getTickCount())) {
            Prep prep = hand.prep;
            launch(player, prep, server.getTickCount(), player.position().add(0, 0.4, 0), Vec3.ZERO);
        }
    }

    public void clear() {
        hands.clear();
        ThrownEquipment.setHitListener(null);
    }
}
