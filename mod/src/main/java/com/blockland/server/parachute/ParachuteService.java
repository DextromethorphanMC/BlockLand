package com.blockland.server.parachute;

import com.blockland.network.BLNetwork;
import com.blockland.network.ParachutePacketListeners;
import com.blockland.network.packet.ParachutePackets;
import com.blockland.parachute.ParachuteContent;
import com.blockland.parachute.ParachuteRules;
import com.blockland.parachute.Parachutes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server authority for parachutes. Movement is the client's (Minecraft trusts its positions), so the jumper predicts
 * its canopy at once — the slow, steerable descent is its own client's — and this service decides whether the canopy
 * is really open: it must be worn, the jumper high enough in the open air, the toggle not spammed. While it is open
 * the fall never adds up to fall damage; landing, water, riding, losing the parachute or dying closes it. Everyone
 * tracking the jumper is told, so they see the canopy. Games open it through {@link Parachutes#server()}.
 * <p>
 * A sky-dive lasts longer than vanilla's floating kick allows, and a canopy is the obvious cover for flying: the
 * {@link ParachuteFlightCheck} judges the motion of everyone wearing one and only then excuses them from that kick.
 */
public final class ParachuteService implements ParachutePacketListeners.Server, Parachutes.Server {
    /** Leeway for the height the client saw a moment before the request arrived (blocks). */
    private static final float HEIGHT_LEEWAY = 1.2f;
    /** Ticks on the ground (or otherwise unable to glide) before an open canopy is closed: step-ups and edges. */
    private static final int CLOSE_GRACE = 2;

    private final MinecraftServer server;
    private final Map<UUID, Jumper> jumpers = new HashMap<>();
    private final ParachuteFlightCheck flightCheck = new ParachuteFlightCheck();

    private static final class Jumper {
        boolean open;
        int lastToggleTick = Integer.MIN_VALUE / 2;
        int grounded;
    }

    public ParachuteService(MinecraftServer server) {
        this.server = server;
    }

    // ---- requests -------------------------------------------------------------------------------

    @Override
    public void onToggle(ServerPlayer player, ParachutePackets.C2SToggle toggle) {
        Jumper jumper = jumpers.computeIfAbsent(player.getUUID(), id -> new Jumper());
        int now = server.getTickCount();
        boolean spam = now - jumper.lastToggleTick < ParachuteRules.get().cooldownTicks - 2;
        if (!toggle.open()) {
            if (jumper.open) setOpen(player, jumper, false, false);
            return;
        }
        if (jumper.open) return;
        if (spam || !ParachuteRules.mayOpen(player, HEIGHT_LEEWAY)) {
            // the client predicted a canopy the server does not accept: it closes at once
            BLNetwork.sendTo(player, new ParachutePackets.S2CState(player.getId(), false));
            return;
        }
        setOpen(player, jumper, true, false);
    }

    // ---- the facade (games) ---------------------------------------------------------------------------

    @Override
    public boolean isOpen(Entity entity) {
        Jumper jumper = jumpers.get(entity.getUUID());
        return jumper != null && jumper.open;
    }

    @Override
    public boolean deploy(ServerPlayer player) {
        Jumper jumper = jumpers.computeIfAbsent(player.getUUID(), id -> new Jumper());
        if (jumper.open) return true;
        if (!ParachuteRules.mayBeOpen(player)) return false;
        setOpen(player, jumper, true, true);
        return true;
    }

    @Override
    public void close(ServerPlayer player) {
        Jumper jumper = jumpers.get(player.getUUID());
        if (jumper != null && jumper.open) setOpen(player, jumper, false, true);
    }

    @Override
    public void equip(ServerPlayer player) {
        player.setItemSlot(EquipmentSlot.CHEST, ParachuteContent.stack());
        player.containerMenu.broadcastChanges();
    }

    // ---- ticking ------------------------------------------------------------------------------------

    public void tick() {
        int now = server.getTickCount();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            flightCheck.tick(player, isOpen(player), now, this::close);
            Jumper jumper = jumpers.get(player.getUUID());
            if (jumper == null || !jumper.open) continue;
            // under the canopy the fall never builds up to damage (the landing packet comes with at most a tick of it)
            player.resetFallDistance();
            jumper.grounded = ParachuteRules.mayBeOpen(player) ? 0 : jumper.grounded + 1;
            boolean lost = !player.isAlive() || player.isSpectator() || !ParachuteRules.wears(player);
            if (lost || jumper.grounded > CLOSE_GRACE) setOpen(player, jumper, false, true);
        }
        jumpers.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    }

    /** Fall damage of a jumper whose canopy is (still) open is cancelled; called from the fall event. */
    public boolean cancelsFall(Player player) {
        return isOpen(player);
    }

    /** Someone started seeing {@code target}: its open canopy too. */
    public void startTracking(ServerPlayer viewer, Entity target) {
        if (target instanceof ServerPlayer && isOpen(target)) BLNetwork.sendTo(viewer, new ParachutePackets.S2CState(target.getId(), true));
    }

    /** @param tellJumper the jumper's client did not ask for it (the server closed or opened it) */
    private void setOpen(ServerPlayer player, Jumper jumper, boolean open, boolean tellJumper) {
        jumper.open = open;
        jumper.grounded = 0;
        jumper.lastToggleTick = server.getTickCount();
        if (open) player.resetFallDistance();
        ParachutePackets.S2CState state = new ParachutePackets.S2CState(player.getId(), open);
        BLNetwork.sendToTracking(player, state);
        if (tellJumper) BLNetwork.sendTo(player, state);
        // the jumper hears its own canopy from its prediction
        player.level().playSound(player, player.getX(), player.getY() + 2, player.getZ(),
                ParachuteContent.sound(open ? "parachute.open" : "parachute.close"), SoundSource.PLAYERS, open ? 1.2F : 0.8F,
                0.95F + player.getRandom().nextFloat() * 0.1F);
    }

    /** The player left the world (lobby, disconnect). */
    public void forget(ServerPlayer player) {
        jumpers.remove(player.getUUID());
        flightCheck.forget(player.getUUID());
    }

    public void clear() {
        jumpers.clear();
        flightCheck.clear();
    }
}
