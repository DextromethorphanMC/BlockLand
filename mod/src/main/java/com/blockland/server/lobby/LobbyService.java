package com.blockland.server.lobby;

import com.blockland.network.BLNetwork;
import com.blockland.network.packet.ActivityPackets;
import com.blockland.network.packet.LobbyGroupEntry;
import com.blockland.network.packet.LobbyModeEntry;
import com.blockland.network.packet.LobbyPackets;
import com.blockland.network.packet.PartyPackets;
import com.blockland.network.packet.S2CLobbyState;
import com.blockland.network.packet.TextJson;
import com.blockland.server.content.ContentRepository;
import com.blockland.server.content.ContentSnapshot;
import com.blockland.server.content.model.ActivityDef;
import com.blockland.server.content.model.AnnouncementDef;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.content.model.ModeGroupDef;
import com.blockland.server.game.GameInstance;
import com.blockland.server.game.GameManager;
import com.blockland.server.game.GameType;
import com.blockland.server.game.GameTypeRegistry;
import com.blockland.server.game.MatchFormat;
import com.blockland.server.game.PlayerTransit;
import com.blockland.server.kit.KitService;
import com.blockland.server.matchmaking.MatchmakingService;
import com.blockland.server.party.PartyService;
import com.blockland.server.player.PlayerProfiles;
import com.blockland.server.session.PlayerDirectory;
import com.blockland.server.util.PlayerUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The lobby: where players wait between matches (in the configuration phase, with no world loaded), queue for
 * modes, pick maps, arrange kits and read announcements. Publishes lobby state to clients; client requests reach it
 * through {@code ServerPacketRouter}, addressed by player id because lobby players have no entity.
 */
public final class LobbyService {
    /** Operators may leave the lobby for the world (building, map previews, commands). */
    private static final int WORLD_PERMISSION = 2;

    private final ContentRepository content;
    private final GameTypeRegistry types;
    private final KitService kits;
    private final PlayerProfiles profiles;
    private final PartyService parties;
    private final PlayerDirectory players;
    private final PlayerTransit transit;
    private final LobbyWorld staging;
    private GameManager games;
    private MatchmakingService matchmaking;
    /** The map each player last queued with, so "play again" asks for the same one. */
    private final Map<UUID, String> lastMap = new HashMap<>();
    private boolean dirty;
    private int openModes = -1;
    private int tickCounter;

    public LobbyService(ContentRepository content, GameTypeRegistry types, KitService kits, PlayerProfiles profiles,
                        PartyService parties, PlayerDirectory players, PlayerTransit transit, LobbyWorld staging) {
        this.content = content;
        this.types = types;
        this.kits = kits;
        this.profiles = profiles;
        this.parties = parties;
        this.players = players;
        this.transit = transit;
        this.staging = staging;
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
        for (UUID id : players.inLobby()) BLNetwork.sendTo(id, buildState(id, false));
    }

    // ---- arrivals and announcements ----------------------------------------------------------

    /** The player is in the lobby now (just connected, or back from a match): show it with fresh data. */
    public void enteredLobby(UUID id) {
        BLNetwork.sendTo(id, buildState(id, true));
        sendAnnouncements(id);
        sendActivities(id);
    }

    /** The disconnected player's lobby bookkeeping. */
    public void left(UUID id) {
        lastMap.remove(id);
    }

    /** The backend's announcements changed: every lobby gets the new list. */
    public void announcementsChanged() {
        for (UUID id : players.inLobby()) sendAnnouncements(id);
    }

    public void sendAnnouncements(UUID id) {
        Set<String> read = profiles.readAnnouncements(id);
        List<LobbyPackets.Announcement> list = new ArrayList<>();
        Set<String> existing = new HashSet<>();
        for (AnnouncementDef a : content.snapshot().announcements) {
            if (a == null || a.id == null) continue;
            existing.add(a.id);
            list.add(new LobbyPackets.Announcement(a.id, nullToEmpty(a.title), nullToEmpty(a.content), a.pinned,
                    a.publishedMillis(), a.updatedMillis()));
        }
        list.sort(Comparator.comparingLong(LobbyPackets.Announcement::publishedAt));
        BLNetwork.sendTo(id, new LobbyPackets.S2CAnnouncements(list, read.stream().filter(existing::contains).toList()));
    }

    /** The backend's activities changed: every lobby gets the new list. */
    public void activitiesChanged() {
        for (UUID id : players.inLobby()) sendActivities(id);
    }

    public void sendActivities(UUID id) {
        List<ActivityPackets.Activity> list = new ArrayList<>();
        List<ActivityDef> defs = content.snapshot().activities;
        for (ActivityDef a : defs == null ? List.<ActivityDef>of() : defs) {
            if (a == null || a.id == null) continue;
            list.add(new ActivityPackets.Activity(a.id, nullToEmpty(a.title), nullToEmpty(a.summary), nullToEmpty(a.content),
                    a.kind == null || a.kind.isBlank() ? "info" : a.kind, a.data == null ? "{}" : a.data.toString(), a.accentArgb(),
                    nullToEmpty(a.coverVersion), a.pinned, a.order, a.startsMillis(), a.endsMillis()));
        }
        BLNetwork.sendTo(id, new ActivityPackets.S2CActivities(list));
    }

    public void markRead(UUID id, List<String> announcementIds) {
        Set<String> known = new HashSet<>();
        for (AnnouncementDef a : content.snapshot().announcements) if (a != null && a.id != null) known.add(a.id);
        List<String> valid = announcementIds.stream().filter(known::contains).distinct().toList();
        if (valid.isEmpty()) return;
        profiles.markRead(id, valid);
        sendAnnouncements(id);
    }

    // ---- state -----------------------------------------------------------------------------------

    public S2CLobbyState buildState(UUID id, boolean openScreen) {
        ContentSnapshot snapshot = content.snapshot();
        List<LobbyModeEntry> entries = new ArrayList<>();
        for (GameModeDef mode : snapshot.enabledModes()) {
            Optional<GameType> type = types.get(mode.type);
            String reason;
            MatchFormat format;
            String typeName;
            int color;
            boolean hasKit = type.isPresent() && type.get().usesKit() && kits.hasEntries(mode);
            List<LobbyModeEntry.MapOption> maps = List.of();
            boolean dropIn = type.isPresent() && type.get().dropIn();
            if (type.isPresent()) {
                format = type.get().format(mode);
                Component unavailable = type.get().checkAvailable(mode, snapshot);
                reason = unavailable == null ? "" : TextJson.write(unavailable);
                typeName = type.get().displayName();
                color = type.get().accentColor();
                maps = type.get().maps(mode, snapshot).stream()
                        .map(m -> new LobbyModeEntry.MapOption(m.id, m.name == null || m.name.isBlank() ? m.id : m.name, nullToEmpty(m.coverVersion)))
                        .toList();
            } else {
                format = new MatchFormat(1, 1);
                reason = TextJson.write(Component.translatable("blockland.lobby.type_unsupported"));
                typeName = mode.type;
                color = 0xFF808080;
            }
            entries.add(new LobbyModeEntry(mode.id, nullToEmpty(mode.title), typeName, nullToEmpty(mode.description),
                    TextJson.write(format.label()), format.requiredPlayers(), mode.timeLimitOrZero(),
                    matchmaking.queued(mode.id), games.playingCount(mode.id), color, reason, hasKit, nullToEmpty(mode.coverVersion),
                    mode.availableUntilMillis(), maps, dropIn, !dropIn && mode.flag("ranked", false)));
        }
        MatchmakingService.Ticket ticket = matchmaking.ticketOf(id);
        return new S2CLobbyState(entries, groups(snapshot, entries),
                ticket == null ? "" : ticket.modeId(),
                ticket == null ? "" : ticket.mapId(),
                ticket == null ? 0 : ticket.waitedSeconds(),
                games.isPlaying(id),
                openScreen,
                profiles.playtimeSeconds(id),
                matchmaking.hasPriority(id),
                partyInfo(id),
                profiles.allowsInvites(id),
                players.permissionLevel(id) >= WORLD_PERMISSION,
                players.online().size());
    }

    private PartyPackets.PartyInfo partyInfo(UUID playerId) {
        PartyService.Party party = parties.partyOf(playerId);
        List<PartyPackets.Member> members = new ArrayList<>();
        if (party != null) {
            for (UUID id : party.members()) {
                PartyPackets.Status status = games.isPlaying(id) ? PartyPackets.Status.IN_GAME
                        : matchmaking.ticketOf(id) != null ? PartyPackets.Status.QUEUED : PartyPackets.Status.LOBBY;
                String name = players.name(id);
                members.add(new PartyPackets.Member(id, name == null ? "?" : name, id.equals(party.leader()), status));
            }
        }
        List<PartyPackets.Invite> invites = parties.invitesTo(playerId).stream()
                .map(i -> new PartyPackets.Invite(i.from(), i.fromName(), parties.membersOf(i.from()).size(), i.secondsLeft()))
                .toList();
        return new PartyPackets.PartyInfo(members, parties.maxSize(), parties.pendingFrom(playerId), invites);
    }

    // ---- client requests ---------------------------------------------------------------------

    /** The client opened its lobby screen and wants fresh data. */
    public void sendState(UUID id) {
        BLNetwork.sendTo(id, buildState(id, false));
    }

    /** Queue for a mode (optionally one of its maps); the client's saved kit arrangement for that mode comes along. */
    public void joinQueue(UUID id, String modeId, String mapId, String kitSignature, List<String> kitSlots) {
        Component error = parties.isLeader(id) ? matchmaking.join(modeId, mapId, onlineParty(id))
                : Component.translatable("blockland.queue.leader_only");
        if (error != null) {
            players.notify(id, LobbyPackets.Level.ERROR, error);
        } else {
            lastMap.put(id, mapId == null ? "" : mapId);
            content.snapshot().mode(modeId).filter(this::hasKit).ifPresent(mode -> {
                Component kitError = kits.submitLayout(id, mode, kitSignature, kitSlots);
                if (kitError != null) players.notify(id, LobbyPackets.Level.WARNING, Component.translatable("blockland.kit.layout_ignored", kitError));
            });
        }
        BLNetwork.sendTo(id, buildState(id, false));
    }

    /**
     * A results panel button. Only accepted while the player's match is ending; the mode to play again is the one
     * that match was, never what a client claims. A party leader brings the members who are still on the same
     * results screen along and queues the party; a member just goes back and waits for the leader.
     */
    public void postMatch(UUID id, boolean playAgain, String kitSignature, List<String> kitSlots) {
        ServerPlayer player = players.inWorld(id);
        GameInstance game = player == null || games == null ? null : games.gameOf(player);
        if (game == null || !game.isEnding()) return;
        String modeId = game.mode().id;
        if (!playAgain) {
            games.leave(player, false);
            return;
        }
        if (!parties.isLeader(id)) {
            games.leave(player, false);
            players.notify(id, LobbyPackets.Level.INFO, Component.translatable("blockland.lobby.wait_for_leader"));
            return;
        }
        for (UUID memberId : onlineParty(id)) {
            ServerPlayer member = players.inWorld(memberId);
            if (member != null && games.gameOf(member) == game) games.leave(member, false);
        }
        joinQueue(id, modeId, lastMap.getOrDefault(id, ""), kitSignature, kitSlots);
    }

    /** A party member's kit arrangement, accepted while their party is queued for that mode. */
    public void submitKit(UUID id, String modeId, String kitSignature, List<String> kitSlots) {
        MatchmakingService.Ticket ticket = matchmaking.ticketOf(id);
        if (ticket == null || !ticket.modeId().equals(modeId)) return;
        content.snapshot().mode(modeId).filter(this::hasKit).ifPresent(mode -> {
            Component kitError = kits.submitLayout(id, mode, kitSignature, kitSlots);
            if (kitError != null) players.notify(id, LobbyPackets.Level.WARNING, Component.translatable("blockland.kit.layout_ignored", kitError));
        });
    }

    public void partyAction(UUID id, PartyPackets.Action action, String argument) {
        Component error = switch (action) {
            case INVITE -> parties.invite(id, argument);
            case ACCEPT -> parseId(argument).map(from -> parties.accept(id, from)).orElse(Component.translatable("blockland.party.invite_expired"));
            case DECLINE -> {
                parseId(argument).ifPresent(from -> parties.decline(id, from));
                yield null;
            }
            case LEAVE -> {
                parties.leave(id, true);
                yield null;
            }
            case KICK -> parseId(argument).map(target -> parties.kick(id, target)).orElse(Component.translatable("blockland.party.not_member"));
            case PROMOTE -> parseId(argument).map(target -> parties.transfer(id, target)).orElse(Component.translatable("blockland.party.not_member"));
        };
        if (error != null) players.notify(id, LobbyPackets.Level.ERROR, error);
        if (action == PartyPackets.Action.INVITE && error == null) {
            players.notify(id, LobbyPackets.Level.SUCCESS, Component.translatable("blockland.party.invite_sent", argument.trim()));
        }
        BLNetwork.sendTo(id, buildState(id, false));
    }

    public void leaveQueue(UUID id) {
        matchmaking.leave(id, true);
        BLNetwork.sendTo(id, buildState(id, false));
    }

    public void sendKit(UUID id, String modeId) {
        content.snapshot().mode(modeId).filter(this::hasKit).ifPresent(mode -> BLNetwork.sendTo(id, kits.editorData(mode)));
    }

    /** Privacy setting from the settings page. */
    public void setAllowInvites(UUID id, boolean allow) {
        profiles.setAllowInvites(id, allow);
        BLNetwork.sendTo(id, buildState(id, false));
    }

    /** An operator leaves the lobby for the world: they land in the empty staging dimension, in creative mode. */
    public void enterWorld(UUID id) {
        if (players.permissionLevel(id) < WORLD_PERMISSION || games.isPlaying(id)) return;
        matchmaking.leave(id, true);
        transit.bring(id, player -> {
            LobbyWorld.Spot spot = staging.spot();
            PlayerUtil.reset(player, net.minecraft.world.level.GameType.CREATIVE);
            PlayerUtil.teleport(player, spot.level(), spot.pos(), spot.yaw(), 0);
            player.sendSystemMessage(Component.translatable("blockland.lobby.in_world_hint"));
        });
    }

    private List<UUID> onlineParty(UUID id) {
        return parties.membersOf(id).stream().filter(players::isOnline).toList();
    }

    private static Optional<UUID> parseId(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Enabled groups with at least one visible mode, by sort order. */
    private static List<LobbyGroupEntry> groups(ContentSnapshot snapshot, List<LobbyModeEntry> modes) {
        Set<String> visible = new HashSet<>();
        for (LobbyModeEntry mode : modes) visible.add(mode.id());
        return snapshot.groups.stream()
                .filter(g -> g.enabled)
                .sorted(Comparator.comparingInt((ModeGroupDef g) -> g.sortOrder))
                .map(g -> new LobbyGroupEntry(g.id, nullToEmpty(g.title), nullToEmpty(g.description), nullToEmpty(g.coverVersion),
                        g.modeIds.stream().filter(visible::contains).toList()))
                .filter(g -> !g.modeIds().isEmpty())
                .toList();
    }

    private boolean hasKit(GameModeDef mode) {
        return mode.isOpen() && types.get(mode.type).map(GameType::usesKit).orElse(false) && kits.hasEntries(mode);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
