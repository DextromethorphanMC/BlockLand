package com.blockland.server.lobby;

import com.blockland.network.BLNetwork;
import com.blockland.network.packet.LobbyGroupEntry;
import com.blockland.network.packet.LobbyModeEntry;
import com.blockland.network.packet.PartyPackets;
import com.blockland.network.packet.S2CLobbyState;
import com.blockland.server.config.BlockLandConfig;
import com.blockland.server.content.ContentRepository;
import com.blockland.server.content.ContentSnapshot;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.content.model.ModeGroupDef;
import com.blockland.server.game.GameManager;
import com.blockland.server.game.GameType;
import com.blockland.server.game.GameTypeRegistry;
import com.blockland.server.game.MatchFormat;
import com.blockland.server.kit.KitService;
import com.blockland.server.player.PlayerProfiles;
import com.blockland.server.matchmaking.MatchmakingService;
import com.blockland.server.party.PartyService;
import com.blockland.server.util.PlayerUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The lobby: where players wait between matches, queue for modes and arrange kits.
 * Publishes lobby state to clients; client requests reach it through {@code ServerPacketRouter}.
 */
public final class LobbyService {
    private final MinecraftServer server;
    private final ContentRepository content;
    private final GameTypeRegistry types;
    private final KitService kits;
    private final PlayerProfiles profiles;
    private final PartyService parties;
    private final LobbyWorld world;
    private GameManager games;
    private MatchmakingService matchmaking;
    private boolean dirty;
    private int openModes = -1;
    private int tickCounter;

    public LobbyService(MinecraftServer server, ContentRepository content, GameTypeRegistry types, KitService kits,
                        PlayerProfiles profiles, PartyService parties, LobbyWorld world) {
        this.server = server;
        this.parties = parties;
        this.world = world;
        this.content = content;
        this.types = types;
        this.kits = kits;
        this.profiles = profiles;
    }

    /** Second construction phase; the services depend on each other through callbacks. */
    public void bind(GameManager games, MatchmakingService matchmaking) {
        this.games = games;
        this.matchmaking = matchmaking;
    }

    public void markDirty() {
        dirty = true;
    }

    public void tick() {
        // limited-time modes close on their own: notice it and refresh every lobby screen
        if (tickCounter % 20 == 0) {
            int open = content.snapshot().enabledModes().size();
            if (open != openModes) {
                openModes = open;
                dirty = true;
            }
        }
        if (++tickCounter % 10 != 0 || !dirty) return;
        dirty = false;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            BLNetwork.sendTo(player, buildState(player, false));
        }
    }

    public void sendToLobby(ServerPlayer player, boolean openScreen) {
        LobbyWorld.Spot spot = world.spot();
        boolean keepCreative = player.isCreative() && player.hasPermissions(2);
        PlayerUtil.reset(player, keepCreative ? net.minecraft.world.level.GameType.CREATIVE
                : BlockLandConfig.LOBBY_ADVENTURE_MODE.get() ? net.minecraft.world.level.GameType.ADVENTURE : net.minecraft.world.level.GameType.SURVIVAL);
        PlayerUtil.teleport(player, spot.level(), spot.pos(), spot.yaw(), 0);
        BLNetwork.sendTo(player, buildState(player, openScreen && BlockLandConfig.LOBBY_OPEN_SCREEN_ON_JOIN.get()));
    }

    public boolean isInLobby(ServerPlayer player) {
        return games == null || !games.isPlaying(player);
    }

    public S2CLobbyState buildState(ServerPlayer player, boolean openScreen) {
        ContentSnapshot snapshot = content.snapshot();
        List<LobbyModeEntry> entries = new ArrayList<>();
        for (GameModeDef mode : snapshot.enabledModes()) {
            Optional<GameType> type = types.get(mode.type);
            String reason;
            MatchFormat format;
            String typeName;
            int color;
            boolean hasKit = type.isPresent() && type.get().usesKit() && !mode.kitOrEmpty().isEmpty();
            if (type.isPresent()) {
                format = type.get().format(mode);
                String unavailable = type.get().checkAvailable(mode, snapshot);
                reason = unavailable == null ? "" : unavailable;
                typeName = type.get().displayName();
                color = type.get().accentColor();
            } else {
                format = new MatchFormat(1, 1);
                reason = "服务器不支持该游戏类型";
                typeName = mode.type;
                color = 0xFF808080;
            }
            entries.add(new LobbyModeEntry(mode.id, nullToEmpty(mode.title), typeName, nullToEmpty(mode.description),
                    format.label(), format.requiredPlayers(), mode.timeLimitOrZero(),
                    matchmaking.queued(mode.id), games.playingCount(mode.id), color, reason, hasKit, nullToEmpty(mode.coverVersion),
                    mode.availableUntilMillis()));
        }
        MatchmakingService.Ticket ticket = matchmaking.ticketOf(player.getUUID());
        return new S2CLobbyState(entries, groups(snapshot, entries),
                ticket == null ? "" : ticket.modeId(),
                ticket == null ? 0 : ticket.waitedSeconds(),
                games.isPlaying(player),
                openScreen,
                profiles.playtimeSeconds(player.getUUID()),
                matchmaking.hasPriority(player.getUUID()),
                partyInfo(player.getUUID()));
    }

    private PartyPackets.PartyInfo partyInfo(UUID playerId) {
        PartyService.Party party = parties.partyOf(playerId);
        List<PartyPackets.Member> members = new ArrayList<>();
        if (party != null) {
            for (UUID id : party.members()) {
                ServerPlayer member = server.getPlayerList().getPlayer(id);
                PartyPackets.Status status = member != null && games.isPlaying(member) ? PartyPackets.Status.IN_GAME
                        : matchmaking.ticketOf(id) != null ? PartyPackets.Status.QUEUED : PartyPackets.Status.LOBBY;
                members.add(new PartyPackets.Member(id, member == null ? "?" : member.getGameProfile().getName(),
                        id.equals(party.leader()), status));
            }
        }
        List<PartyPackets.Invite> invites = parties.invitesTo(playerId).stream()
                .map(i -> new PartyPackets.Invite(i.from(), i.fromName(), parties.membersOf(i.from()).size(), i.secondsLeft()))
                .toList();
        return new PartyPackets.PartyInfo(members, parties.maxSize(), parties.pendingFrom(playerId), invites);
    }

    // ---- client requests ---------------------------------------------------------------------

    /** The client opened its lobby screen and wants fresh data (it opens the screen itself). */
    public void sendState(ServerPlayer player) {
        BLNetwork.sendTo(player, buildState(player, false));
    }

    /** Queue for a mode; the client's saved kit arrangement for that mode comes along. */
    public void joinQueue(ServerPlayer player, String modeId, String kitSignature, List<String> kitSlots) {
        String error = parties.isLeader(player.getUUID()) ? matchmaking.join(modeId, onlineParty(player)) : "只有队长可以开始匹配";
        if (error != null) {
            player.sendSystemMessage(Component.literal("§c" + error));
        } else {
            content.snapshot().mode(modeId).filter(this::hasKit).ifPresent(mode -> {
                String kitError = kits.submitLayout(player, mode, kitSignature, kitSlots);
                if (kitError != null) player.sendSystemMessage(Component.literal("§e装备布局未生效: " + kitError));
            });
        }
        BLNetwork.sendTo(player, buildState(player, false));
    }

    /** A party member's kit arrangement, accepted while their party is queued for that mode. */
    public void submitKit(ServerPlayer player, String modeId, String kitSignature, List<String> kitSlots) {
        MatchmakingService.Ticket ticket = matchmaking.ticketOf(player.getUUID());
        if (ticket == null || !ticket.modeId().equals(modeId)) return;
        content.snapshot().mode(modeId).filter(this::hasKit).ifPresent(mode -> {
            String kitError = kits.submitLayout(player, mode, kitSignature, kitSlots);
            if (kitError != null) player.sendSystemMessage(Component.literal("§e装备布局未生效: " + kitError));
        });
    }

    public void partyAction(ServerPlayer player, PartyPackets.Action action, String argument) {
        String error = switch (action) {
            case INVITE -> parties.invite(player, argument);
            case ACCEPT -> parseId(argument).map(id -> parties.accept(player, id)).orElse("邀请已失效");
            case DECLINE -> {
                parseId(argument).ifPresent(id -> parties.decline(player, id));
                yield null;
            }
            case LEAVE -> {
                parties.leave(player, true);
                yield null;
            }
            case KICK -> parseId(argument).map(id -> parties.kick(player, id)).orElse("该玩家不在你的队伍中");
            case PROMOTE -> parseId(argument).map(id -> parties.transfer(player, id)).orElse("该玩家不在你的队伍中");
        };
        if (error != null) player.sendSystemMessage(Component.literal("§c" + error));
        if (action == PartyPackets.Action.INVITE && error == null) {
            player.sendSystemMessage(Component.literal("§a已向 " + argument.trim() + " 发送组队邀请"));
        }
        BLNetwork.sendTo(player, buildState(player, false));
    }

    private List<ServerPlayer> onlineParty(ServerPlayer player) {
        List<ServerPlayer> online = new ArrayList<>();
        for (UUID id : parties.membersOf(player.getUUID())) {
            ServerPlayer member = server.getPlayerList().getPlayer(id);
            if (member != null) online.add(member);
        }
        return online;
    }

    private static Optional<UUID> parseId(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public void leaveQueue(ServerPlayer player) {
        matchmaking.leave(player, true);
        BLNetwork.sendTo(player, buildState(player, false));
    }

    public void sendKit(ServerPlayer player, String modeId) {
        content.snapshot().mode(modeId).filter(this::hasKit)
                .ifPresent(mode -> BLNetwork.sendTo(player, kits.editorData(mode)));
    }

    /** Enabled groups with at least one visible mode, by sort order. */
    private static List<LobbyGroupEntry> groups(ContentSnapshot snapshot, List<LobbyModeEntry> modes) {
        java.util.Set<String> visible = new java.util.HashSet<>();
        for (LobbyModeEntry mode : modes) visible.add(mode.id());
        return snapshot.groups.stream()
                .filter(g -> g.enabled)
                .sorted(java.util.Comparator.comparingInt((ModeGroupDef g) -> g.sortOrder))
                .map(g -> new LobbyGroupEntry(g.id, nullToEmpty(g.title), nullToEmpty(g.description), nullToEmpty(g.coverVersion),
                        g.modeIds.stream().filter(visible::contains).toList()))
                .filter(g -> !g.modeIds().isEmpty())
                .toList();
    }

    private boolean hasKit(GameModeDef mode) {
        return mode.isOpen() && types.get(mode.type).map(GameType::usesKit).orElse(false) && !mode.kitOrEmpty().isEmpty();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
