package com.blockland.server.vehicle;

import com.blockland.network.BLNetwork;
import com.blockland.network.VehiclePacketListeners;
import com.blockland.network.packet.VehiclePackets;
import com.blockland.server.anticheat.api.AntiCheatSignals;
import com.blockland.server.anticheat.api.Suspicion;
import com.blockland.vehicle.VehicleControl;
import com.blockland.vehicle.VehicleData;
import com.blockland.vehicle.VehicleEntity;
import com.blockland.vehicle.Vehicles;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * Server authority for vehicles (vehicle spec §1, §4): whether a player may use them here (the mode setting
 * {@code allowVehicles}, or the config outside matches), the driver's inputs (queued on the vehicle, which steps them;
 * each step's result goes back to the driver for its prediction), riders' actions — firing and reloading their seat's
 * weapon, changing seats, the horn — and the shelter of a hull: riders in seats inside it are not reached by rounds,
 * blades or bodies (only blasts, fire and the like). Impossible packets are reported to the anti-cheat.
 */
public final class VehicleService implements VehiclePacketListeners.Server, Vehicles.Server {
    /** Ticks of slack on a weapon's fire interval (packets bunch up on the way). */
    private static final int FIRE_SLACK = 3;

    private final Predicate<ServerPlayer> allowed;

    public VehicleService(Predicate<ServerPlayer> allowed) {
        this.allowed = allowed;
    }

    // ---- hooks of the shared code ---------------------------------------------------------------

    @Override
    public boolean mayUse(ServerPlayer player) {
        return allowed.test(player);
    }

    @Override
    public void stepped(VehicleEntity vehicle) {
        if (!(vehicle.driver() instanceof ServerPlayer driver)) return;
        Vec3 v = vehicle.getDeltaMovement();
        BLNetwork.sendTo(driver, new VehiclePackets.S2CState(vehicle.getId(), vehicle.processedSeq(), vehicle.getX(), vehicle.getY(),
                vehicle.getZ(), vehicle.getYRot(), (float) v.x, (float) v.y, (float) v.z, vehicle.hullPitch(1), vehicle.hullRoll(1)));
    }

    // ---- packets --------------------------------------------------------------------------------

    @Override
    public void onControl(ServerPlayer player, VehiclePackets.C2SControl control) {
        VehicleEntity vehicle = vehicle(player, control.vehicle());
        if (vehicle == null) return;
        if (vehicle.driver() != player) {
            // right after a seat change a few inputs are still on their way; a rider who never drove is something else
            if (vehicle.seatOf(player) < 0) report(player, "非驾驶员发送载具操控");
            return;
        }
        if (Math.abs(control.throttle()) > 100 || Math.abs(control.steer()) > 100 || !Float.isFinite(control.lookYaw())
                || !Float.isFinite(control.lookPitch()) || Math.abs(control.lookPitch()) > 90.5f) {
            report(player, String.format(Locale.ROOT, "载具操控越界 %d/%d %.1f/%.1f", control.throttle(), control.steer(),
                    control.lookYaw(), control.lookPitch()));
            return;
        }
        vehicle.queueControl(new VehicleControl(control.seq(), control.throttle() / 100f, control.steer() / 100f, control.brake(), control.boost(),
                control.lookYaw(), control.lookPitch()));
    }

    @Override
    public void onAction(ServerPlayer player, VehiclePackets.C2SAction action) {
        VehicleEntity vehicle = vehicle(player, action.vehicle());
        if (vehicle == null) return;
        int seat = vehicle.seatOf(player);
        if (seat < 0) return;
        switch (action.action()) {
            case FIRE -> fire(player, vehicle, seat, action.arg());
            case RELOAD -> {
                int weapon = action.arg();
                if (weapon >= 0 && weapon < vehicle.data.weapons.size() && vehicle.data.weapons.get(weapon).seat == seat) vehicle.startReload(weapon);
            }
            case SEAT -> vehicle.moveTo(player, action.arg());
            case HORN -> {
                if (seat == 0) vehicle.horn();
            }
        }
    }

    private void fire(ServerPlayer player, VehicleEntity vehicle, int seat, int weapon) {
        if (weapon < 0 || weapon >= vehicle.data.weapons.size()) return;
        VehicleData.Weapon w = vehicle.data.weapons.get(weapon);
        if (w.seat != seat) return;
        int early = vehicle.cooldownLeft(weapon);
        if (!vehicle.tryFire(weapon)) {
            if (early > FIRE_SLACK) {
                Suspicion.report(player, AntiCheatSignals.VEHICLE_FIRE_RATE, 0.5,
                        () -> String.format(Locale.ROOT, "%s 的 %s 冷却还剩 %d tick 时开火", vehicle.data.id, w.id, early));
            }
            return;
        }
        VehicleWeapons.fire(player.level(), vehicle, weapon, player);
    }

    /** The vehicle {@code id} the player rides (its own, nothing else), or null. */
    private static VehicleEntity vehicle(ServerPlayer player, int id) {
        Entity ridden = player.getVehicle();
        if (!(ridden instanceof VehicleEntity vehicle) || vehicle.getId() != id) return null;
        return vehicle;
    }

    private static void report(ServerPlayer player, String detail) {
        Suspicion.report(player, AntiCheatSignals.VEHICLE_CONTROL, 0.2, detail);
    }

    // ---- shelter --------------------------------------------------------------------------------

    /**
     * Whether a hurt of {@code victim} is kept off by the hull it sits in: rounds, blades, bodies and the like do not
     * reach a rider inside a vehicle; blasts, fire, drowning, falling and what bypasses invulnerability do.
     */
    public boolean shelters(LivingEntity victim, DamageSource source) {
        if (!VehicleEntity.isSheltered(victim)) return false;
        return !source.is(DamageTypeTags.IS_EXPLOSION) && !source.is(DamageTypeTags.IS_FIRE) && !source.is(DamageTypeTags.IS_DROWNING)
                && !source.is(DamageTypeTags.IS_FALL) && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
    }
}
