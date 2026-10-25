package com.blockland.mixin.anticheat;

import com.blockland.server.anticheat.net.NetHooks;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The anti-cheat's view of the player's movement (anti-cheat spec §2): every movement packet once it is on the server
 * thread (dropped when the anti-cheat set the player back), the on-ground flag vanilla trusts (the server's verdict
 * instead), and the client's tick-end packets (twice each: their arrival on the netty thread, their turn on the server
 * thread). Forwards only; the logic is in {@code server/anticheat}.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {
    @Shadow
    public ServerPlayer player;

    @Shadow
    private Vec3 awaitingPositionFromClient;

    @Inject(method = "handleMovePlayer", cancellable = true, at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V"))
    private void blockland$move(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (!NetHooks.move(player, packet, awaitingPositionFromClient != null)) ci.cancel();
    }

    @Redirect(method = "handleMovePlayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;isOnGround()Z"))
    private boolean blockland$onGround(ServerboundMovePlayerPacket packet) {
        return NetHooks.onGround(player, packet);
    }

    @Inject(method = "handleClientTickEnd", at = @At("HEAD"))
    private void blockland$tickEnd(ServerboundClientTickEndPacket packet, CallbackInfo ci) {
        NetHooks.tickEnd(player);
    }
}
