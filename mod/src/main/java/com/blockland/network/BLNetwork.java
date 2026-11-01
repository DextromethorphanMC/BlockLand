package com.blockland.network;

import com.blockland.BlockLand;
import com.blockland.network.packet.ActivityPackets;
import com.blockland.network.packet.BattleRoyalePackets;
import com.blockland.network.packet.BombPackets;
import com.blockland.network.packet.FarChunkPackets;
import com.blockland.network.packet.FarTerrainPackets;
import com.blockland.network.packet.GraffitiPackets;
import com.blockland.network.packet.SpectatePackets;
import com.blockland.network.packet.ViewmodelPackets;
import com.blockland.network.packet.ProfilePackets;
import com.blockland.network.packet.CheckInPackets;
import com.blockland.network.packet.C2SJoinQueue;
import com.blockland.network.packet.RankedPackets;
import com.blockland.network.packet.ParachutePackets;
import com.blockland.network.packet.VehiclePackets;
import com.blockland.network.packet.AmbientPackets;
import com.blockland.network.packet.SupplyPackets;
import com.blockland.network.packet.ArmorPackets;
import com.blockland.network.packet.C2SLeaveQueue;
import com.blockland.network.packet.C2SLobbyRequest;
import com.blockland.network.packet.C2SPostMatch;
import com.blockland.network.packet.C2SRequestKit;
import com.blockland.network.packet.C2SSubmitKit;
import com.blockland.network.packet.CombatPackets;
import com.blockland.network.packet.DamagePackets;
import com.blockland.network.packet.CraftPackets;
import com.blockland.network.packet.EffectPackets;
import com.blockland.network.packet.EquipmentPackets;
import com.blockland.network.packet.GunPackets;
import com.blockland.network.packet.InfectionPackets;
import com.blockland.network.packet.LobbyPackets;
import com.blockland.network.packet.MediaPackets;
import com.blockland.network.packet.MeleePackets;
import com.blockland.network.packet.MovementPackets;
import com.blockland.network.packet.PartyPackets;
import com.blockland.network.packet.S2CKitData;
import com.blockland.network.packet.S2CLobbyState;
import com.blockland.network.packet.S2CMatchFound;
import com.blockland.network.packet.S2CMatchResult;
import com.blockland.network.packet.S2CMorph;
import com.blockland.network.packet.ShopPackets;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Network channel. Packet handling is delegated to listener interfaces so this class
 * never references server or client logic directly.
 */
public final class BLNetwork {
    private static final int PROTOCOL_VERSION = 26;

    public static final SimpleChannel CHANNEL = ChannelBuilder.named(BlockLand.id("main"))
            .networkProtocolVersion(PROTOCOL_VERSION)
            .simpleChannel();

    /** Server side: a gate every packet from a client passes before its handler (the anti-cheat's rate limits). */
    public interface InboundGuard {
        InboundGuard OPEN = (player, packet) -> true;

        /** @return false to drop the packet unhandled */
        boolean admit(UUID player, Object packet);
    }

    private static volatile InboundGuard inboundGuard = InboundGuard.OPEN;
    private static volatile ServerPacketListener serverListener = ServerPacketListener.NOOP;
    private static volatile ClientPacketListener clientListener = ClientPacketListener.NOOP;
    private static volatile GunPacketListeners.Server gunServerListener = GunPacketListeners.Server.NOOP;
    private static volatile GunPacketListeners.Client gunClientListener = GunPacketListeners.Client.NOOP;
    private static volatile MeleePacketListeners.Server meleeServerListener = MeleePacketListeners.Server.NOOP;
    private static volatile MeleePacketListeners.Client meleeClientListener = MeleePacketListeners.Client.NOOP;
    private static volatile InfectionPacketListeners.Server infectionServerListener = InfectionPacketListeners.Server.NOOP;
    private static volatile InfectionPacketListeners.Client infectionClientListener = InfectionPacketListeners.Client.NOOP;
    private static volatile MorphPacketListener morphListener = MorphPacketListener.NOOP;
    private static volatile CraftPacketListeners.Server craftServerListener = CraftPacketListeners.Server.NOOP;
    private static volatile CraftPacketListeners.Client craftClientListener = CraftPacketListeners.Client.NOOP;
    private static volatile MovementPacketListeners.Server movementServerListener = MovementPacketListeners.Server.NOOP;
    private static volatile MovementPacketListeners.Client movementClientListener = MovementPacketListeners.Client.NOOP;
    private static volatile EffectPacketListeners.Client effectClientListener = EffectPacketListeners.Client.NOOP;
    private static volatile EquipmentPacketListeners.Server equipmentServerListener = EquipmentPacketListeners.Server.NOOP;
    private static volatile EquipmentPacketListeners.Client equipmentClientListener = EquipmentPacketListeners.Client.NOOP;
    private static volatile CombatPacketListeners.Server combatServerListener = CombatPacketListeners.Server.NOOP;
    private static volatile CombatPacketListeners.Client combatClientListener = CombatPacketListeners.Client.NOOP;
    private static volatile RankedPacketListeners.Client rankedClientListener = RankedPacketListeners.Client.NOOP;
    private static volatile BombPacketListeners.Server bombServerListener = BombPacketListeners.Server.NOOP;
    private static volatile BombPacketListeners.Client bombClientListener = BombPacketListeners.Client.NOOP;
    private static volatile GraffitiPacketListeners.Server graffitiServerListener = GraffitiPacketListeners.Server.NOOP;
    private static volatile GraffitiPacketListeners.Client graffitiClientListener = GraffitiPacketListeners.Client.NOOP;
    private static volatile SpectatePacketListeners.Server spectateServerListener = SpectatePacketListeners.Server.NOOP;
    private static volatile SpectatePacketListeners.Client spectateClientListener = SpectatePacketListeners.Client.NOOP;
    private static volatile ViewmodelPacketListeners.Client viewmodelClientListener = ViewmodelPacketListeners.Client.NOOP;
    private static volatile ProfilePacketListeners.Server profileServerListener = ProfilePacketListeners.Server.NOOP;
    private static volatile ProfilePacketListeners.Client profileClientListener = ProfilePacketListeners.Client.NOOP;
    private static volatile CheckInPacketListeners.Server checkInServerListener = CheckInPacketListeners.Server.NOOP;
    private static volatile CheckInPacketListeners.Client checkInClientListener = CheckInPacketListeners.Client.NOOP;
    private static volatile BattleRoyalePacketListeners.Server battleRoyaleServerListener = BattleRoyalePacketListeners.Server.NOOP;
    private static volatile BattleRoyalePacketListeners.Client battleRoyaleClientListener = BattleRoyalePacketListeners.Client.NOOP;
    private static volatile ParachutePacketListeners.Server parachuteServerListener = ParachutePacketListeners.Server.NOOP;
    private static volatile ParachutePacketListeners.Client parachuteClientListener = ParachutePacketListeners.Client.NOOP;
    private static volatile VehiclePacketListeners.Server vehicleServerListener = VehiclePacketListeners.Server.NOOP;
    private static volatile VehiclePacketListeners.Client vehicleClientListener = VehiclePacketListeners.Client.NOOP;
    private static volatile AmbientPacketListeners.Client ambientClientListener = AmbientPacketListeners.Client.NOOP;
    private static volatile DamagePacketListeners.Client damageClientListener = DamagePacketListeners.Client.NOOP;
    private static volatile SupplyPacketListeners.Server supplyServerListener = SupplyPacketListeners.Server.NOOP;
    private static volatile SupplyPacketListeners.Client supplyClientListener = SupplyPacketListeners.Client.NOOP;
    private static volatile ArmorPacketListeners.Server armorServerListener = ArmorPacketListeners.Server.NOOP;
    private static volatile ArmorPacketListeners.Client armorClientListener = ArmorPacketListeners.Client.NOOP;
    private static volatile FarTerrainPacketListeners.Server farTerrainServerListener = FarTerrainPacketListeners.Server.NOOP;
    private static volatile FarTerrainPacketListeners.Client farTerrainClientListener = FarTerrainPacketListeners.Client.NOOP;
    private static volatile Function<Connection, UUID> senderResolver = connection -> null;
    private static volatile Function<UUID, Connection> connectionResolver = id -> null;
    @Nullable
    private static volatile Connection clientConnection;

    private BLNetwork() {
    }

    public static void init() {
        // the lobby lives in the configuration phase (no world, no ServerPlayer): its packets work in every phase
        anyToServer(C2SLobbyRequest.class, C2SLobbyRequest::encode, C2SLobbyRequest::decode,
                (msg, player) -> serverListener.onLobbyRequest(player));
        anyToServer(C2SJoinQueue.class, C2SJoinQueue::encode, C2SJoinQueue::decode,
                (msg, player) -> serverListener.onJoinQueue(player, msg.modeId(), msg.mapId(), msg.kitSignature(), msg.kitSlots()));
        anyToServer(C2SLeaveQueue.class, C2SLeaveQueue::encode, C2SLeaveQueue::decode,
                (msg, player) -> serverListener.onLeaveQueue(player));
        anyToServer(C2SRequestKit.class, C2SRequestKit::encode, C2SRequestKit::decode,
                (msg, player) -> serverListener.onRequestKit(player, msg.modeId()));
        anyToServer(ShopPackets.C2SRequest.class, ShopPackets.C2SRequest::encode, ShopPackets.C2SRequest::decode,
                (msg, player) -> serverListener.onShopRequest(player));
        anyToServer(MediaPackets.C2SRequest.class, MediaPackets.C2SRequest::encode, MediaPackets.C2SRequest::decode,
                (msg, player) -> serverListener.onMediaRequest(player, msg.namespace(), msg.id(), msg.version()));
        anyToServer(ShopPackets.C2SPurchase.class, ShopPackets.C2SPurchase::encode, ShopPackets.C2SPurchase::decode,
                (msg, player) -> serverListener.onPurchase(player, msg.productId()));
        anyToServer(C2SSubmitKit.class, C2SSubmitKit::encode, C2SSubmitKit::decode,
                (msg, player) -> serverListener.onSubmitKit(player, msg.modeId(), msg.kitSignature(), msg.kitSlots()));
        anyToServer(PartyPackets.C2SAction.class, PartyPackets.C2SAction::encode, PartyPackets.C2SAction::decode,
                (msg, player) -> serverListener.onPartyAction(player, msg.action(), msg.argument()));
        anyToServer(C2SPostMatch.class, C2SPostMatch::encode, C2SPostMatch::decode,
                (msg, player) -> serverListener.onPostMatch(player, msg.playAgain(), msg.kitSignature(), msg.kitSlots()));
        anyToServer(LobbyPackets.C2SSettings.class, LobbyPackets.C2SSettings::encode, LobbyPackets.C2SSettings::decode,
                (msg, player) -> serverListener.onSettings(player, msg.allowPartyInvites()));
        anyToServer(LobbyPackets.C2SReadAnnouncements.class, LobbyPackets.C2SReadAnnouncements::encode, LobbyPackets.C2SReadAnnouncements::decode,
                (msg, player) -> serverListener.onReadAnnouncements(player, msg.ids()));
        anyToServer(LobbyPackets.C2SEnterWorld.class, LobbyPackets.C2SEnterWorld::encode, LobbyPackets.C2SEnterWorld::decode,
                (msg, player) -> serverListener.onEnterWorld(player));

        toServer(GunPackets.C2SFire.class, GunPackets.C2SFire::encode, GunPackets.C2SFire::decode,
                (msg, player) -> gunServerListener.onFire(player, msg));
        toServer(GunPackets.C2SReload.class, GunPackets.C2SReload::encode, GunPackets.C2SReload::decode,
                (msg, player) -> gunServerListener.onReload(player, msg.slot()));
        toServer(MeleePackets.C2SAttack.class, MeleePackets.C2SAttack::encode, MeleePackets.C2SAttack::decode,
                (msg, player) -> meleeServerListener.onAttack(player, msg));
        toServer(InfectionPackets.C2SSelectMonster.class, InfectionPackets.C2SSelectMonster::encode, InfectionPackets.C2SSelectMonster::decode,
                (msg, player) -> infectionServerListener.onSelectMonster(player, msg.monsterId()));
        toServer(InfectionPackets.C2SUseSkill.class, InfectionPackets.C2SUseSkill::encode, InfectionPackets.C2SUseSkill::decode,
                (msg, player) -> infectionServerListener.onUseSkill(player));

        anyToClient(S2CLobbyState.class, S2CLobbyState::encode, S2CLobbyState::decode, msg -> clientListener.onLobbyState(msg));
        anyToClient(S2CMatchFound.class, S2CMatchFound::encode, S2CMatchFound::decode, msg -> clientListener.onMatchFound(msg));
        toClient(S2CMatchResult.class, S2CMatchResult::encode, S2CMatchResult::decode, msg -> clientListener.onMatchResult(msg));
        anyToClient(S2CKitData.class, S2CKitData::encode, S2CKitData::decode, msg -> clientListener.onKitData(msg));
        anyToClient(ShopPackets.S2CData.class, ShopPackets.S2CData::encode, ShopPackets.S2CData::decode, msg -> clientListener.onShopData(msg));
        anyToClient(MediaPackets.S2CImage.class, MediaPackets.S2CImage::encode, MediaPackets.S2CImage::decode, msg -> clientListener.onMedia(msg));
        anyToClient(ShopPackets.S2CPurchaseResult.class, ShopPackets.S2CPurchaseResult::encode, ShopPackets.S2CPurchaseResult::decode,
                msg -> clientListener.onPurchaseResult(msg));
        anyToClient(LobbyPackets.S2CNotice.class, LobbyPackets.S2CNotice::encode, LobbyPackets.S2CNotice::decode,
                msg -> clientListener.onNotice(msg));
        anyToClient(LobbyPackets.S2CAnnouncements.class, LobbyPackets.S2CAnnouncements::encode, LobbyPackets.S2CAnnouncements::decode,
                msg -> clientListener.onAnnouncements(msg));
        anyToClient(ActivityPackets.S2CActivities.class, ActivityPackets.S2CActivities::encode, ActivityPackets.S2CActivities::decode,
                msg -> clientListener.onActivities(msg));
        toClient(GunPackets.S2CHit.class, GunPackets.S2CHit::encode, GunPackets.S2CHit::decode, msg -> gunClientListener.onHit(msg));
        toClient(GunPackets.S2CShot.class, GunPackets.S2CShot::encode, GunPackets.S2CShot::decode, msg -> gunClientListener.onShot(msg));
        toClient(MeleePackets.S2CHit.class, MeleePackets.S2CHit::encode, MeleePackets.S2CHit::decode, msg -> meleeClientListener.onHit(msg));
        toClient(MeleePackets.S2CImpact.class, MeleePackets.S2CImpact::encode, MeleePackets.S2CImpact::decode,
                msg -> meleeClientListener.onImpact(msg));
        toClient(InfectionPackets.S2CSelect.class, InfectionPackets.S2CSelect::encode, InfectionPackets.S2CSelect::decode,
                msg -> infectionClientListener.onSelect(msg));
        toClient(InfectionPackets.S2CRole.class, InfectionPackets.S2CRole::encode, InfectionPackets.S2CRole::decode,
                msg -> infectionClientListener.onRole(msg));
        toClient(InfectionPackets.S2CSkill.class, InfectionPackets.S2CSkill::encode, InfectionPackets.S2CSkill::decode,
                msg -> infectionClientListener.onSkill(msg));
        toClient(InfectionPackets.S2CFuse.class, InfectionPackets.S2CFuse::encode, InfectionPackets.S2CFuse::decode,
                msg -> infectionClientListener.onFuse(msg));
        toClient(InfectionPackets.S2CBlast.class, InfectionPackets.S2CBlast::encode, InfectionPackets.S2CBlast::decode,
                msg -> infectionClientListener.onBlast(msg));
        toClient(S2CMorph.class, S2CMorph::encode, S2CMorph::decode, msg -> morphListener.onMorph(msg));

        // 工匠 (craft): builds are edited in the lobby (configuration phase) as well as in game
        anyToServer(CraftPackets.C2SRequest.class, CraftPackets.C2SRequest::encode, CraftPackets.C2SRequest::decode,
                (msg, player) -> craftServerListener.onRequest(player));
        anyToServer(CraftPackets.C2SSave.class, CraftPackets.C2SSave::encode, CraftPackets.C2SSave::decode,
                (msg, player) -> craftServerListener.onSave(player, msg));
        anyToClient(CraftPackets.S2CBuilds.class, CraftPackets.S2CBuilds::encode, CraftPackets.S2CBuilds::decode,
                msg -> craftClientListener.onBuilds(msg));

        // movement actions (slide, prone, dive): only in the world
        toServer(MovementPackets.C2SAction.class, MovementPackets.C2SAction::encode, MovementPackets.C2SAction::decode,
                (msg, player) -> movementServerListener.onAction(player, msg));
        toClient(MovementPackets.S2CAction.class, MovementPackets.S2CAction::encode, MovementPackets.S2CAction::decode,
                msg -> movementClientListener.onAction(msg));
        toClient(MovementPackets.S2CRules.class, MovementPackets.S2CRules::encode, MovementPackets.S2CRules::decode,
                msg -> movementClientListener.onRules(msg));

        // aiming down sights: timed by the server, shown to the players around (scope glint)
        toServer(GunPackets.C2SAim.class, GunPackets.C2SAim::encode, GunPackets.C2SAim::decode,
                (msg, player) -> gunServerListener.onAim(player, msg));
        toClient(GunPackets.S2CAim.class, GunPackets.S2CAim::encode, GunPackets.S2CAim::decode, msg -> gunClientListener.onAim(msg));

        // equipment effects on one player's senses (a flashbang's blinding)
        toClient(EffectPackets.S2CFlash.class, EffectPackets.S2CFlash::encode, EffectPackets.S2CFlash::decode,
                msg -> effectClientListener.onFlash(msg));

        // equipment (grenades, throwing knives, injectors): only in the world
        toServer(EquipmentPackets.C2SAction.class, EquipmentPackets.C2SAction::encode, EquipmentPackets.C2SAction::decode,
                (msg, player) -> equipmentServerListener.onAction(player, msg));
        toClient(EquipmentPackets.S2CHit.class, EquipmentPackets.S2CHit::encode, EquipmentPackets.S2CHit::decode,
                msg -> equipmentClientListener.onHit(msg));

        // kill feedback (medals, kill feed) and the Tab board: only in the world
        toServer(CombatPackets.C2SBoard.class, CombatPackets.C2SBoard::encode, CombatPackets.C2SBoard::decode,
                (msg, player) -> combatServerListener.onBoard(player, msg.open()));
        toClient(CombatPackets.S2CKillNotice.class, CombatPackets.S2CKillNotice::encode, CombatPackets.S2CKillNotice::decode,
                msg -> combatClientListener.onKillNotice(msg));
        toClient(CombatPackets.S2CKillFeed.class, CombatPackets.S2CKillFeed::encode, CombatPackets.S2CKillFeed::decode,
                msg -> combatClientListener.onKillFeed(msg));
        toClient(CombatPackets.S2CBoard.class, CombatPackets.S2CBoard::encode, CombatPackets.S2CBoard::decode,
                msg -> combatClientListener.onBoard(msg));

        // 排位 (ranked): the standings (per mode) and their changes reach the player in the lobby and in the world
        anyToClient(RankedPackets.S2CRanks.class, RankedPackets.S2CRanks::encode, RankedPackets.S2CRanks::decode,
                msg -> rankedClientListener.onRanks(msg));
        anyToClient(RankedPackets.S2CRankChange.class, RankedPackets.S2CRankChange::encode, RankedPackets.S2CRankChange::decode,
                msg -> rankedClientListener.onRankChange(msg));

        // 库存 / 个人主页 (inventory, profile, leaderboard): tabs of the lobby, so they work in every phase
        anyToServer(ProfilePackets.C2SRequest.class, ProfilePackets.C2SRequest::encode, ProfilePackets.C2SRequest::decode,
                (msg, player) -> profileServerListener.onRequest(player, msg.kind(), msg.modeId()));
        anyToServer(ProfilePackets.C2SEquip.class, ProfilePackets.C2SEquip::encode, ProfilePackets.C2SEquip::decode,
                (msg, player) -> profileServerListener.onEquip(player, msg.slot(), msg.key()));
        anyToClient(ProfilePackets.S2CInventory.class, ProfilePackets.S2CInventory::encode, ProfilePackets.S2CInventory::decode,
                msg -> profileClientListener.onInventory(msg));
        anyToClient(ProfilePackets.S2CProfile.class, ProfilePackets.S2CProfile::encode, ProfilePackets.S2CProfile::decode,
                msg -> profileClientListener.onProfile(msg));
        anyToClient(ProfilePackets.S2CLeaderboard.class, ProfilePackets.S2CLeaderboard::encode, ProfilePackets.S2CLeaderboard::decode,
                msg -> profileClientListener.onLeaderboard(msg));

        // 每日签到 (daily check-in): in the lobby
        anyToServer(CheckInPackets.C2SRequest.class, CheckInPackets.C2SRequest::encode, CheckInPackets.C2SRequest::decode,
                (msg, player) -> checkInServerListener.onRequest(player));
        anyToServer(CheckInPackets.C2SClaim.class, CheckInPackets.C2SClaim::encode, CheckInPackets.C2SClaim::decode,
                (msg, player) -> checkInServerListener.onClaim(player));
        anyToClient(CheckInPackets.S2CState.class, CheckInPackets.S2CState::encode, CheckInPackets.S2CState::decode,
                msg -> checkInClientListener.onState(msg));

        // 枪战爆破 (bomb defusal): only in the world
        toServer(BombPackets.C2SPlant.class, BombPackets.C2SPlant::encode, BombPackets.C2SPlant::decode,
                (msg, player) -> bombServerListener.onPlant(player, msg.holding()));
        toServer(BombPackets.C2SDefuse.class, BombPackets.C2SDefuse::encode, BombPackets.C2SDefuse::decode,
                (msg, player) -> bombServerListener.onDefuse(player, msg.holding()));
        toClient(BombPackets.S2CState.class, BombPackets.S2CState::encode, BombPackets.S2CState::decode,
                msg -> bombClientListener.onState(msg));
        toClient(BombPackets.S2CDetonate.class, BombPackets.S2CDetonate::encode, BombPackets.S2CDetonate::decode,
                msg -> bombClientListener.onDetonate(msg));

        // wall graffiti (喷漆): only in the world
        // spectating after death: only in the world
        toServer(SpectatePackets.C2SControl.class, SpectatePackets.C2SControl::encode, SpectatePackets.C2SControl::decode,
                (msg, player) -> spectateServerListener.onControl(player, msg.action()));
        toClient(SpectatePackets.S2CState.class, SpectatePackets.S2CState::encode, SpectatePackets.S2CState::decode,
                msg -> spectateClientListener.onState(msg));
        // the watched player's hand actions, for a spectator seeing through their eyes
        toClient(ViewmodelPackets.S2CAction.class, ViewmodelPackets.S2CAction::encode, ViewmodelPackets.S2CAction::decode,
                msg -> viewmodelClientListener.onAction(msg));
        toServer(GraffitiPackets.C2SSpray.class, GraffitiPackets.C2SSpray::encode, GraffitiPackets.C2SSpray::decode,
                (msg, player) -> graffitiServerListener.onSpray(player, msg.slot()));
        toClient(GraffitiPackets.S2CPlace.class, GraffitiPackets.S2CPlace::encode, GraffitiPackets.S2CPlace::decode,
                msg -> graffitiClientListener.onPlace(msg));
        toClient(GraffitiPackets.S2CRemove.class, GraffitiPackets.S2CRemove::encode, GraffitiPackets.S2CRemove::decode,
                msg -> graffitiClientListener.onRemove(msg));
        toClient(GraffitiPackets.S2CSync.class, GraffitiPackets.S2CSync::encode, GraffitiPackets.S2CSync::decode,
                msg -> graffitiClientListener.onSync(msg));
        toClient(GraffitiPackets.S2CSlots.class, GraffitiPackets.S2CSlots::encode, GraffitiPackets.S2CSlots::decode,
                msg -> graffitiClientListener.onSlots(msg));

        // 大逃杀 (battle royale): only in the world
        toServer(BattleRoyalePackets.C2SJump.class, BattleRoyalePackets.C2SJump::encode, BattleRoyalePackets.C2SJump::decode,
                (msg, player) -> battleRoyaleServerListener.onJump(player));
        toClient(BattleRoyalePackets.S2CState.class, BattleRoyalePackets.S2CState::encode, BattleRoyalePackets.S2CState::decode,
                msg -> battleRoyaleClientListener.onState(msg));

        // parachute (降落伞): only in the world
        toServer(ParachutePackets.C2SToggle.class, ParachutePackets.C2SToggle::encode, ParachutePackets.C2SToggle::decode,
                (msg, player) -> parachuteServerListener.onToggle(player, msg));
        toClient(ParachutePackets.S2CState.class, ParachutePackets.S2CState::encode, ParachutePackets.S2CState::decode,
                msg -> parachuteClientListener.onState(msg));

        // vehicles (载具, tools/vehicles/VEHICLE_SPEC.md): only in the world
        toServer(VehiclePackets.C2SControl.class, VehiclePackets.C2SControl::encode, VehiclePackets.C2SControl::decode,
                (msg, player) -> vehicleServerListener.onControl(player, msg));
        toServer(VehiclePackets.C2SAction.class, VehiclePackets.C2SAction::encode, VehiclePackets.C2SAction::decode,
                (msg, player) -> vehicleServerListener.onAction(player, msg));
        toClient(VehiclePackets.S2CState.class, VehiclePackets.S2CState::encode, VehiclePackets.S2CState::decode,
                msg -> vehicleClientListener.onState(msg));
        toClient(VehiclePackets.S2CShot.class, VehiclePackets.S2CShot::encode, VehiclePackets.S2CShot::decode,
                msg -> vehicleClientListener.onShot(msg));

        // ambient props a match's map asks for (rendering spec §29, the tumbleweeds): only in the world
        toClient(AmbientPackets.S2CAmbient.class, AmbientPackets.S2CAmbient::encode, AmbientPackets.S2CAmbient::decode,
                msg -> ambientClientListener.onAmbient(msg));

        // where a hurt came from (UI spec §3.5, the HUD's damage direction arrows): only in the world
        toClient(DamagePackets.S2CDamage.class, DamagePackets.S2CDamage::encode, DamagePackets.S2CDamage::decode,
                msg -> damageClientListener.onDamage(msg));

        // ammo crate resupply (弹药箱): only in the world
        toServer(SupplyPackets.C2SUse.class, SupplyPackets.C2SUse::encode, SupplyPackets.C2SUse::decode,
                (msg, player) -> supplyServerListener.onUse(player, msg));
        toClient(SupplyPackets.S2CResupplied.class, SupplyPackets.S2CResupplied::encode, SupplyPackets.S2CResupplied::decode,
                msg -> supplyClientListener.onResupplied(msg));

        // body armour (防弹衣 / 防弹板): only in the world
        toServer(ArmorPackets.C2SInsert.class, ArmorPackets.C2SInsert::encode, ArmorPackets.C2SInsert::decode,
                (msg, player) -> armorServerListener.onInsert(player, msg));
        toClient(ArmorPackets.S2CInsert.class, ArmorPackets.S2CInsert::encode, ArmorPackets.S2CInsert::decode,
                msg -> armorClientListener.onInsert(msg));
        toClient(ArmorPackets.S2CHit.class, ArmorPackets.S2CHit::encode, ArmorPackets.S2CHit::decode,
                msg -> armorClientListener.onHit(msg));

        // far terrain (远景地平线, rendering spec §22): only in the world
        toServer(FarTerrainPackets.C2SRequest.class, FarTerrainPackets.C2SRequest::encode, FarTerrainPackets.C2SRequest::decode,
                (msg, player) -> farTerrainServerListener.onRequest(player, msg));
        toClient(FarTerrainPackets.S2CHello.class, FarTerrainPackets.S2CHello::encode, FarTerrainPackets.S2CHello::decode,
                msg -> farTerrainClientListener.onHello(msg));
        toClient(FarTerrainPackets.S2CTiles.class, FarTerrainPackets.S2CTiles::encode, FarTerrainPackets.S2CTiles::decode,
                msg -> farTerrainClientListener.onTiles(msg));
        // real far chunks (rendering spec §23): the same session as the far terrain
        toServer(FarChunkPackets.C2SRequest.class, FarChunkPackets.C2SRequest::encode, FarChunkPackets.C2SRequest::decode,
                (msg, player) -> farTerrainServerListener.onColumns(player, msg));
        toClient(FarChunkPackets.S2CColumns.class, FarChunkPackets.S2CColumns::encode, FarChunkPackets.S2CColumns::decode,
                msg -> farTerrainClientListener.onColumns(msg));
    }

    private static <M> void toServer(Class<M> type, BiConsumer<M, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, M> decoder,
                                     BiConsumer<M, ServerPlayer> handler) {
        CHANNEL.messageBuilder(type, NetworkDirection.PLAY_TO_SERVER)
                .encoder(encoder::accept).decoder(decoder::apply)
                .consumerMainThread((msg, ctx) -> {
                    ServerPlayer sender = ctx.getSender();
                    if (sender != null && inboundGuard.admit(sender.getUUID(), msg)) handler.accept(msg, sender);
                    ctx.setPacketHandled(true);
                })
                .add();
    }

    private static <M> void toClient(Class<M> type, BiConsumer<M, RegistryFriendlyByteBuf> encoder,
                                     Function<RegistryFriendlyByteBuf, M> decoder, Consumer<M> handler) {
        CHANNEL.messageBuilder(type, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(encoder).decoder(decoder)
                .consumerMainThread((msg, ctx) -> {
                    clientConnection = ctx.getConnection();
                    handler.accept(msg);
                    ctx.setPacketHandled(true);
                })
                .add();
    }

    /**
     * A request that works in every phase: the lobby runs in the configuration phase (no world, no
     * {@link ServerPlayer}), matches in play. The sender is resolved to the player's id either way.
     */
    private static <M> void anyToServer(Class<M> type, BiConsumer<M, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, M> decoder,
                                        BiConsumer<M, UUID> handler) {
        CHANNEL.messageBuilder(type).direction(PacketFlow.SERVERBOUND)
                .encoder(encoder).decoder(decoder)
                .consumerMainThread((msg, ctx) -> {
                    ServerPlayer sender = ctx.getSender();
                    UUID id = sender != null ? sender.getUUID() : senderResolver.apply(ctx.getConnection());
                    if (id != null && inboundGuard.admit(id, msg)) handler.accept(msg, id);
                    ctx.setPacketHandled(true);
                })
                .add();
    }

    /** A push that works in every phase (lobby in configuration, matches in play); registry-free buffers only. */
    private static <M> void anyToClient(Class<M> type, BiConsumer<M, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, M> decoder,
                                        Consumer<M> handler) {
        CHANNEL.messageBuilder(type).direction(PacketFlow.CLIENTBOUND)
                .encoder(encoder).decoder(decoder)
                .consumerMainThread((msg, ctx) -> {
                    clientConnection = ctx.getConnection();
                    handler.accept(msg);
                    ctx.setPacketHandled(true);
                })
                .add();
    }

    /** Server side: who is on a connection that has no {@link ServerPlayer} (a lobby player in the configuration phase). */
    public static void setSenderResolver(@Nullable Function<Connection, UUID> resolver) {
        senderResolver = resolver == null ? connection -> null : resolver;
    }

    /** Server side: a player's connection, in the lobby or in the world. */
    public static void setConnectionResolver(@Nullable Function<UUID, Connection> resolver) {
        connectionResolver = resolver == null ? id -> null : resolver;
    }

    /** Client side: the connection our packets arrive on, also while the client sits in the configuration phase. */
    @Nullable
    public static Connection clientConnection() {
        Connection connection = clientConnection;
        return connection != null && connection.isConnected() ? connection : null;
    }

    public static void forgetClientConnection() {
        clientConnection = null;
    }

    /**
     * Client side: keeps the connection running while no vanilla screen does — in the lobby the client sits in the
     * configuration phase behind our own screens — and reports a lost connection (vanilla shows the disconnect screen).
     */
    public static void tickClientConnection() {
        Connection connection = clientConnection;
        if (connection == null) return;
        if (connection.isConnected()) {
            connection.tick();
        } else {
            clientConnection = null;
            connection.handleDisconnection();
        }
    }

    public static void setInboundGuard(@Nullable InboundGuard guard) {
        inboundGuard = guard == null ? InboundGuard.OPEN : guard;
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

    public static void setInfectionServerListener(InfectionPacketListeners.Server listener) {
        infectionServerListener = listener == null ? InfectionPacketListeners.Server.NOOP : listener;
    }

    public static void setInfectionClientListener(InfectionPacketListeners.Client listener) {
        infectionClientListener = listener == null ? InfectionPacketListeners.Client.NOOP : listener;
    }

    public static void setMorphListener(MorphPacketListener listener) {
        morphListener = listener == null ? MorphPacketListener.NOOP : listener;
    }

    public static void setCraftServerListener(CraftPacketListeners.Server listener) {
        craftServerListener = listener == null ? CraftPacketListeners.Server.NOOP : listener;
    }

    public static void setCraftClientListener(CraftPacketListeners.Client listener) {
        craftClientListener = listener == null ? CraftPacketListeners.Client.NOOP : listener;
    }

    public static void setMovementServerListener(MovementPacketListeners.Server listener) {
        movementServerListener = listener == null ? MovementPacketListeners.Server.NOOP : listener;
    }

    public static void setMovementClientListener(MovementPacketListeners.Client listener) {
        movementClientListener = listener == null ? MovementPacketListeners.Client.NOOP : listener;
    }

    public static void setEffectClientListener(EffectPacketListeners.Client listener) {
        effectClientListener = listener == null ? EffectPacketListeners.Client.NOOP : listener;
    }

    public static void setEquipmentServerListener(EquipmentPacketListeners.Server listener) {
        equipmentServerListener = listener == null ? EquipmentPacketListeners.Server.NOOP : listener;
    }

    public static void setEquipmentClientListener(EquipmentPacketListeners.Client listener) {
        equipmentClientListener = listener == null ? EquipmentPacketListeners.Client.NOOP : listener;
    }

    public static void setCombatServerListener(CombatPacketListeners.Server listener) {
        combatServerListener = listener == null ? CombatPacketListeners.Server.NOOP : listener;
    }

    public static void setCombatClientListener(CombatPacketListeners.Client listener) {
        combatClientListener = listener == null ? CombatPacketListeners.Client.NOOP : listener;
    }

    public static void setRankedClientListener(RankedPacketListeners.Client listener) {
        rankedClientListener = listener == null ? RankedPacketListeners.Client.NOOP : listener;
    }

    public static void setBombServerListener(BombPacketListeners.Server listener) {
        bombServerListener = listener == null ? BombPacketListeners.Server.NOOP : listener;
    }

    public static void setBombClientListener(BombPacketListeners.Client listener) {
        bombClientListener = listener == null ? BombPacketListeners.Client.NOOP : listener;
    }

    public static void setProfileServerListener(ProfilePacketListeners.Server listener) {
        profileServerListener = listener == null ? ProfilePacketListeners.Server.NOOP : listener;
    }

    public static void setProfileClientListener(ProfilePacketListeners.Client listener) {
        profileClientListener = listener == null ? ProfilePacketListeners.Client.NOOP : listener;
    }

    public static void setCheckInServerListener(CheckInPacketListeners.Server listener) {
        checkInServerListener = listener == null ? CheckInPacketListeners.Server.NOOP : listener;
    }

    public static void setCheckInClientListener(CheckInPacketListeners.Client listener) {
        checkInClientListener = listener == null ? CheckInPacketListeners.Client.NOOP : listener;
    }

    public static void setBattleRoyaleServerListener(BattleRoyalePacketListeners.Server listener) {
        battleRoyaleServerListener = listener == null ? BattleRoyalePacketListeners.Server.NOOP : listener;
    }

    public static void setBattleRoyaleClientListener(BattleRoyalePacketListeners.Client listener) {
        battleRoyaleClientListener = listener == null ? BattleRoyalePacketListeners.Client.NOOP : listener;
    }

    public static void setParachuteServerListener(ParachutePacketListeners.Server listener) {
        parachuteServerListener = listener == null ? ParachutePacketListeners.Server.NOOP : listener;
    }

    public static void setParachuteClientListener(ParachutePacketListeners.Client listener) {
        parachuteClientListener = listener == null ? ParachutePacketListeners.Client.NOOP : listener;
    }

    public static void setVehicleServerListener(VehiclePacketListeners.Server listener) {
        vehicleServerListener = listener == null ? VehiclePacketListeners.Server.NOOP : listener;
    }

    public static void setAmbientClientListener(AmbientPacketListeners.Client listener) {
        ambientClientListener = listener == null ? AmbientPacketListeners.Client.NOOP : listener;
    }

    public static void setDamageClientListener(DamagePacketListeners.Client listener) {
        damageClientListener = listener == null ? DamagePacketListeners.Client.NOOP : listener;
    }

    public static void setVehicleClientListener(VehiclePacketListeners.Client listener) {
        vehicleClientListener = listener == null ? VehiclePacketListeners.Client.NOOP : listener;
    }

    public static void setSupplyServerListener(SupplyPacketListeners.Server listener) {
        supplyServerListener = listener == null ? SupplyPacketListeners.Server.NOOP : listener;
    }

    public static void setArmorServerListener(ArmorPacketListeners.Server listener) {
        armorServerListener = listener == null ? ArmorPacketListeners.Server.NOOP : listener;
    }

    public static void setArmorClientListener(ArmorPacketListeners.Client listener) {
        armorClientListener = listener == null ? ArmorPacketListeners.Client.NOOP : listener;
    }

    public static void setFarTerrainServerListener(FarTerrainPacketListeners.Server listener) {
        farTerrainServerListener = listener == null ? FarTerrainPacketListeners.Server.NOOP : listener;
    }

    public static void setFarTerrainClientListener(FarTerrainPacketListeners.Client listener) {
        farTerrainClientListener = listener == null ? FarTerrainPacketListeners.Client.NOOP : listener;
    }

    public static void setSupplyClientListener(SupplyPacketListeners.Client listener) {
        supplyClientListener = listener == null ? SupplyPacketListeners.Client.NOOP : listener;
    }

    public static void setSpectateServerListener(SpectatePacketListeners.Server listener) {
        spectateServerListener = listener == null ? SpectatePacketListeners.Server.NOOP : listener;
    }

    public static void setSpectateClientListener(SpectatePacketListeners.Client listener) {
        spectateClientListener = listener == null ? SpectatePacketListeners.Client.NOOP : listener;
    }

    public static void setViewmodelClientListener(ViewmodelPacketListeners.Client listener) {
        viewmodelClientListener = listener == null ? ViewmodelPacketListeners.Client.NOOP : listener;
    }

    public static void setGraffitiServerListener(GraffitiPacketListeners.Server listener) {
        graffitiServerListener = listener == null ? GraffitiPacketListeners.Server.NOOP : listener;
    }

    public static void setGraffitiClientListener(GraffitiPacketListeners.Client listener) {
        graffitiClientListener = listener == null ? GraffitiPacketListeners.Client.NOOP : listener;
    }

    /** Everyone who can see {@code entity}, not the entity itself. */
    public static void sendToTracking(net.minecraft.world.entity.Entity entity, Object packet) {
        CHANNEL.send(packet, PacketDistributor.TRACKING_ENTITY.with(entity));
    }

    public static void sendTo(ServerPlayer player, Object packet) {
        CHANNEL.send(packet, PacketDistributor.PLAYER.with(player));
    }

    /** To a player wherever they are (lobby or world); dropped when they are offline. Any-phase packets only. */
    public static void sendTo(UUID player, Object packet) {
        Connection connection = connectionResolver.apply(player);
        if (connection != null && connection.isConnected()) CHANNEL.send(packet, connection);
    }

    /** Works in the lobby (configuration phase) as well as in the world. */
    public static void sendToServer(Object packet) {
        Connection connection = clientConnection();
        if (connection != null) CHANNEL.send(packet, connection);
        else CHANNEL.send(packet, PacketDistributor.SERVER.noArg());
    }
}
