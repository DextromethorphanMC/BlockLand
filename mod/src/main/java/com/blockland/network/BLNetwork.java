package com.blockland.network;

import com.blockland.BlockLand;
import com.blockland.network.packet.C2SJoinQueue;
import com.blockland.network.packet.C2SLeaveQueue;
import com.blockland.network.packet.C2SLobbyRequest;
import com.blockland.network.packet.C2SRequestKit;
import com.blockland.network.packet.C2SSubmitKit;
import com.blockland.network.packet.GunPackets;
import com.blockland.network.packet.MeleePackets;
import com.blockland.network.packet.PartyPackets;
import com.blockland.network.packet.MediaPackets;
import com.blockland.network.packet.S2CKitData;
import com.blockland.network.packet.S2CLobbyState;
import com.blockland.network.packet.S2CMatchFound;
import com.blockland.network.packet.ShopPackets;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Network channel. Packet handling is delegated to listener interfaces so this class
 * never references server or client logic directly.
 */
public final class BLNetwork {
    private static final int PROTOCOL_VERSION = 7;

    public static final SimpleChannel CHANNEL = ChannelBuilder.named(BlockLand.id("main"))
            .networkProtocolVersion(PROTOCOL_VERSION)
            .simpleChannel();

    private static volatile ServerPacketListener serverListener = ServerPacketListener.NOOP;
    private static volatile ClientPacketListener clientListener = ClientPacketListener.NOOP;
    private static volatile GunPacketListeners.Server gunServerListener = GunPacketListeners.Server.NOOP;
    private static volatile GunPacketListeners.Client gunClientListener = GunPacketListeners.Client.NOOP;
    private static volatile MeleePacketListeners.Server meleeServerListener = MeleePacketListeners.Server.NOOP;
    private static volatile MeleePacketListeners.Client meleeClientListener = MeleePacketListeners.Client.NOOP;

    private BLNetwork() {
    }

    public static void init() {
        toServer(C2SLobbyRequest.class, C2SLobbyRequest::encode, C2SLobbyRequest::decode,
                (msg, player) -> serverListener.onLobbyRequest(player));
        toServer(C2SJoinQueue.class, C2SJoinQueue::encode, C2SJoinQueue::decode,
                (msg, player) -> serverListener.onJoinQueue(player, msg.modeId(), msg.kitSignature(), msg.kitSlots()));
        toServer(C2SLeaveQueue.class, C2SLeaveQueue::encode, C2SLeaveQueue::decode,
                (msg, player) -> serverListener.onLeaveQueue(player));
        toServer(C2SRequestKit.class, C2SRequestKit::encode, C2SRequestKit::decode,
                (msg, player) -> serverListener.onRequestKit(player, msg.modeId()));
        toServer(ShopPackets.C2SRequest.class, ShopPackets.C2SRequest::encode, ShopPackets.C2SRequest::decode,
                (msg, player) -> serverListener.onShopRequest(player));
        toServer(MediaPackets.C2SRequest.class, MediaPackets.C2SRequest::encode, MediaPackets.C2SRequest::decode,
                (msg, player) -> serverListener.onMediaRequest(player, msg.namespace(), msg.id(), msg.version()));
        toServer(ShopPackets.C2SPurchase.class, ShopPackets.C2SPurchase::encode, ShopPackets.C2SPurchase::decode,
                (msg, player) -> serverListener.onPurchase(player, msg.productId()));
        toServer(C2SSubmitKit.class, C2SSubmitKit::encode, C2SSubmitKit::decode,
                (msg, player) -> serverListener.onSubmitKit(player, msg.modeId(), msg.kitSignature(), msg.kitSlots()));
        toServer(PartyPackets.C2SAction.class, PartyPackets.C2SAction::encode, PartyPackets.C2SAction::decode,
                (msg, player) -> serverListener.onPartyAction(player, msg.action(), msg.argument()));

        toServer(GunPackets.C2SFire.class, GunPackets.C2SFire::encode, GunPackets.C2SFire::decode,
                (msg, player) -> gunServerListener.onFire(player, msg));
        toServer(GunPackets.C2SReload.class, GunPackets.C2SReload::encode, GunPackets.C2SReload::decode,
                (msg, player) -> gunServerListener.onReload(player, msg.slot()));
        toServer(GunPackets.C2SGunsmith.class, GunPackets.C2SGunsmith::encode, GunPackets.C2SGunsmith::decode,
                (msg, player) -> gunServerListener.onGunsmith(player, msg));
        toServer(MeleePackets.C2SAttack.class, MeleePackets.C2SAttack::encode, MeleePackets.C2SAttack::decode,
                (msg, player) -> meleeServerListener.onAttack(player, msg));

        toClient(S2CLobbyState.class, S2CLobbyState::encode, S2CLobbyState::decode, msg -> clientListener.onLobbyState(msg));
        toClient(S2CMatchFound.class, S2CMatchFound::encode, S2CMatchFound::decode, msg -> clientListener.onMatchFound(msg));
        toClient(S2CKitData.class, S2CKitData::encode, S2CKitData::decode, msg -> clientListener.onKitData(msg));
        toClient(ShopPackets.S2CData.class, ShopPackets.S2CData::encode, ShopPackets.S2CData::decode, msg -> clientListener.onShopData(msg));
        toClient(MediaPackets.S2CImage.class, MediaPackets.S2CImage::encode, MediaPackets.S2CImage::decode, msg -> clientListener.onMedia(msg));
        toClient(ShopPackets.S2CPurchaseResult.class, ShopPackets.S2CPurchaseResult::encode, ShopPackets.S2CPurchaseResult::decode,
                msg -> clientListener.onPurchaseResult(msg));
        toClient(GunPackets.S2CHit.class, GunPackets.S2CHit::encode, GunPackets.S2CHit::decode, msg -> gunClientListener.onHit(msg));
        toClient(GunPackets.S2CShot.class, GunPackets.S2CShot::encode, GunPackets.S2CShot::decode, msg -> gunClientListener.onShot(msg));
        toClient(MeleePackets.S2CHit.class, MeleePackets.S2CHit::encode, MeleePackets.S2CHit::decode, msg -> meleeClientListener.onHit(msg));
        toClient(MeleePackets.S2CImpact.class, MeleePackets.S2CImpact::encode, MeleePackets.S2CImpact::decode,
                msg -> meleeClientListener.onImpact(msg));
    }

    private static <M> void toServer(Class<M> type, BiConsumer<M, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, M> decoder,
                                     BiConsumer<M, ServerPlayer> handler) {
        CHANNEL.messageBuilder(type, NetworkDirection.PLAY_TO_SERVER)
                .encoder(encoder::accept).decoder(decoder::apply)
                .consumerMainThread((msg, ctx) -> {
                    ServerPlayer sender = ctx.getSender();
                    if (sender != null) handler.accept(msg, sender);
                    ctx.setPacketHandled(true);
                })
                .add();
    }

    private static <M> void toClient(Class<M> type, BiConsumer<M, RegistryFriendlyByteBuf> encoder,
                                     Function<RegistryFriendlyByteBuf, M> decoder, Consumer<M> handler) {
        CHANNEL.messageBuilder(type, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(encoder).decoder(decoder)
                .consumerMainThread((msg, ctx) -> {
                    handler.accept(msg);
                    ctx.setPacketHandled(true);
                })
                .add();
    }

    public static void setServerListener(ServerPacketListener listener) {
        serverListener = listener == null ? ServerPacketListener.NOOP : listener;
    }

    public static void setClientListener(ClientPacketListener listener) {
        clientListener = listener == null ? ClientPacketListener.NOOP : listener;
    }

    public static void setGunServerListener(GunPacketListeners.Server listener) {
        gunServerListener = listener == null ? GunPacketListeners.Server.NOOP : listener;
    }

    public static void setGunClientListener(GunPacketListeners.Client listener) {
        gunClientListener = listener == null ? GunPacketListeners.Client.NOOP : listener;
    }

    public static void setMeleeServerListener(MeleePacketListeners.Server listener) {
        meleeServerListener = listener == null ? MeleePacketListeners.Server.NOOP : listener;
    }

    public static void setMeleeClientListener(MeleePacketListeners.Client listener) {
        meleeClientListener = listener == null ? MeleePacketListeners.Client.NOOP : listener;
    }

    /** Everyone who can see {@code entity}, not the entity itself. */
    public static void sendToTracking(net.minecraft.world.entity.Entity entity, Object packet) {
        CHANNEL.send(packet, PacketDistributor.TRACKING_ENTITY.with(entity));
    }

    public static void sendTo(ServerPlayer player, Object packet) {
        CHANNEL.send(packet, PacketDistributor.PLAYER.with(player));
    }

    public static void sendToServer(Object packet) {
        CHANNEL.send(packet, PacketDistributor.SERVER.noArg());
    }
}
