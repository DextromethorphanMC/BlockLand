package com.blockland.games.world;

import com.blockland.BlockLand;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.game.GameInstance;
import com.blockland.server.game.GameServices;
import com.blockland.server.game.MatchBoard;
import com.blockland.server.game.MatchRoster;
import com.blockland.server.util.PlayerUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 开放世界: one long-running instance per mode in a generated dimension. Players drop in and out whenever they like;
 * whatever they had and wherever they stood is saved on the server when they leave ({@link WorldPlayerStore}) and back
 * on their next visit — a first visit starts at the world spawn with the mode's kit. Death never ends anything: the
 * player respawns at the spawn (dropping their things unless {@code keepInventory}). The instance ends only when the
 * mode is closed in the backend (or the server stops), saving everyone first.
 */
final class WorldGame implements GameInstance {
    /** Seconds between saving everyone (so a crash loses little). */
    private static final int AUTOSAVE_SECONDS = 300;
    private static final int RECHECK_TICKS = 100;

    private final GameServices services;
    private final UUID id = UUID.randomUUID();
    private final Map<UUID, String> players = new LinkedHashMap<>();
    private final WorldPlayerStore store;
    private final ServerLevel level;
    private GameModeDef mode;
    private WorldSettings settings;
    @Nullable
    private BlockPos spawn;
    private int ticks;
    private boolean finished;

    WorldGame(GameServices services, GameModeDef mode, WorldSettings settings, ServerLevel level, MatchRoster roster) {
        this.services = services;
        this.mode = mode;
        this.settings = settings;
        this.level = level;
        this.store = new WorldPlayerStore(services.server(), mode.id);
        for (MatchRoster.Member member : roster.players()) players.put(member.id(), member.name());
    }

    // ---- lifecycle ------------------------------------------------------------------------------------

    @Override
    public void start() {
        for (UUID member : List.copyOf(players.keySet())) services.players().bring(member, this::arrive);
    }

    @Override
    public boolean addPlayers(List<MatchRoster.Member> members) {
        if (finished || players.size() + members.size() > settings.maxPlayers()) return false;
        for (MatchRoster.Member member : members) players.put(member.id(), member.name());
        for (MatchRoster.Member member : members) services.players().bring(member.id(), this::arrive);
        return true;
    }

    @Override
    public void tick() {
        if (finished) return;
        ticks++;
        if (ticks % RECHECK_TICKS == 0) {
            // the mode was closed or changed in the backend
            GameModeDef current = services.content().snapshot().mode(mode.id).filter(GameModeDef::isOpen).orElse(null);
            if (current == null || !WorldGameType.ID.equalsIgnoreCase(current.type)) {
                for (UUID player : List.copyOf(players.keySet())) {
                    ServerPlayer online = online(player);
                    if (online != null) online.sendSystemMessage(Component.translatable("blockland.world.closed").withStyle(ChatFormatting.YELLOW));
                }
                saveAll();
                finished = true;
                return;
            }
            mode = current;
            settings = WorldSettings.parse(current.settings);
        }
        if (ticks % (AUTOSAVE_SECONDS * 20) == 0) saveAll();
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public void dispose() {
        saveAll();
        finished = true;
    }

    // ---- players --------------------------------------------------------------------------------------

    @Override
    public UUID id() {
        return id;
    }

    @Override
    public GameModeDef mode() {
        return mode;
    }

    @Override
    public Collection<UUID> participants() {
        return Collections.unmodifiableSet(players.keySet());
    }

    /** In the world (now, or once loaded in from the lobby): where they were last time, or the spawn. */
    private void arrive(ServerPlayer player) {
        if (finished || !players.containsKey(player.getUUID())) {
            services.players().toLobby(player);
            return;
        }
        PlayerUtil.reset(player, settings.gameMode());
        WorldPlayerStore.Saved saved = store.load(player.getUUID());
        if (saved != null) {
            ServerLevel savedLevel = store.level(saved);
            store.restore(player, saved);
            player.setGameMode(settings.gameMode());
            if (savedLevel != null) {
                PlayerUtil.teleport(player, savedLevel, saved.position(), saved.yaw(), saved.pitch());
            } else {
                toSpawn(player);
            }
            player.sendSystemMessage(Component.translatable("blockland.world.welcome_back", mode.title).withStyle(ChatFormatting.GRAY));
        } else {
            toSpawn(player);
            if (!mode.kitOrEmpty().isEmpty()) services.kits().apply(player, mode);
            player.sendSystemMessage(Component.translatable("blockland.world.welcome", mode.title).withStyle(ChatFormatting.GREEN));
            // a first visit counts as soon as they are in: leaving at once must not hand out the kit again
            store.save(player);
        }
        broadcast(Component.translatable("blockland.world.joined", player.getGameProfile().getName()).withStyle(ChatFormatting.YELLOW), player.getUUID());
    }

    @Override
    public void removePlayer(UUID id, @Nullable ServerPlayer player, boolean disconnected) {
        String name = players.remove(id);
        if (name == null) return;
        // only a player who really is in the world (not one still loading in from the lobby) has something to keep
        if (player != null && player.isAlive() && inWorld(player)) store.save(player);
        broadcast(Component.translatable("blockland.world.left", name).withStyle(ChatFormatting.YELLOW), id);
    }

    /** No game over: the dead get up again at the spawn. */
    @Override
    public boolean onPlayerDeath(ServerPlayer player, DamageSource source) {
        if (!players.containsKey(player.getUUID())) return false;
        broadcast(source.getLocalizedDeathMessage(player).copy().withStyle(ChatFormatting.GRAY), null);
        if (!settings.keepInventory()) {
            player.getInventory().dropAll();
            player.setExperienceLevels(0);
            player.setExperiencePoints(0);
        }
        player.removeAllEffects();
        player.clearFire();
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0F);
        player.resetFallDistance();
        player.setDeltaMovement(Vec3.ZERO);
        toSpawn(player);
        PlayerUtil.title(player, Component.translatable("blockland.world.you_died").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                Component.translatable(settings.keepInventory() ? "blockland.world.respawned_kept" : "blockland.world.respawned")
                        .withStyle(ChatFormatting.GRAY), 5, 40, 10);
        return true;
    }

    /** {@code pvp} off: players cannot hurt each other (the world itself still can). */
    @Override
    public boolean allowDamage(ServerPlayer victim, DamageSource source) {
        if (settings.pvp()) return true;
        return !(source.getEntity() instanceof ServerPlayer attacker) || attacker == victim;
    }

    @Override
    public MatchBoard board(MatchBoard.Stats stats) {
        List<MatchBoard.Row> rows = players.keySet().stream()
                .map(id -> new MatchBoard.Row(id, stats.name(id), stats.kills(id), stats.deaths(id), List.of(), null))
                .sorted(Comparator.comparing(MatchBoard.Row::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new MatchBoard(Component.literal(mode.title == null ? "" : mode.title),
                Component.translatable("blockland.world.board.subtitle"),
                Component.translatable("blockland.world.board.online", rows.size(), settings.maxPlayers()), List.of(),
                List.of(new MatchBoard.Team(Component.translatable("blockland.board.players"), 0xFF7CDB5A, Component.empty(), rows)));
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private void toSpawn(ServerPlayer player) {
        if (spawn == null) spawn = WorldSpawn.find(level);
        PlayerUtil.teleport(player, level, Vec3.atBottomCenterOf(spawn), player.getYRot(), 0);
    }

    /** Somewhere in the open world (its dimension, or one its portals lead to) — not the staging lobby or an arena. */
    private boolean inWorld(ServerPlayer player) {
        ResourceKey<net.minecraft.world.level.Level> dimension = player.level().dimension();
        return dimension == level.dimension() || !BlockLand.MODID.equals(dimension.location().getNamespace());
    }

    private void saveAll() {
        for (UUID player : players.keySet()) {
            ServerPlayer online = online(player);
            if (online != null && online.isAlive() && inWorld(online)) store.save(online);
        }
    }

    @Nullable
    private ServerPlayer online(UUID id) {
        return services.server().getPlayerList().getPlayer(id);
    }

    private void broadcast(Component message, @Nullable UUID except) {
        for (UUID player : players.keySet()) {
            if (player.equals(except)) continue;
            ServerPlayer online = online(player);
            if (online != null) online.sendSystemMessage(message);
        }
    }

    /** The dimension a mode's world lives in. */
    static ResourceKey<net.minecraft.world.level.Level> dimensionKey(WorldSettings settings) {
        return ResourceKey.create(Registries.DIMENSION, settings.dimension() == null
                ? net.minecraft.resources.ResourceLocation.parse(WorldSettings.DEFAULT_DIMENSION) : settings.dimension());
    }
}
