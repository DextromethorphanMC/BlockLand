package com.blockland.games.common;

import com.blockland.BlockLand;
import com.blockland.server.arena.Arena;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.content.model.MapDef;
import com.blockland.server.game.GameInstance;
import com.blockland.server.game.GameServices;
import com.blockland.server.game.MatchRoster;
import com.blockland.server.map.MapBlueprint;
import com.blockland.server.util.PlayerUtil;
import com.blockland.server.world.PasteJob;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Shared flow of team elimination games played on an imported map:
 * LOADING (download + paste map) → COUNTDOWN (players caged at their team spawns)
 * → PLAYING (last team standing, optional time limit) → ENDING → finished.
 * Concrete games customise it through the protected hooks.
 */
public abstract class TeamArenaGame implements GameInstance {
    private static final ChatFormatting[] TEAM_COLORS = {
            ChatFormatting.RED, ChatFormatting.BLUE, ChatFormatting.GREEN, ChatFormatting.YELLOW,
            ChatFormatting.AQUA, ChatFormatting.LIGHT_PURPLE, ChatFormatting.GOLD, ChatFormatting.WHITE,
            ChatFormatting.DARK_GREEN, ChatFormatting.DARK_AQUA, ChatFormatting.DARK_PURPLE, ChatFormatting.GRAY};
    private static final String[] TEAM_NAMES = {"红队", "蓝队", "绿队", "黄队", "青队", "粉队", "橙队", "白队", "深绿队", "深青队", "紫队", "灰队"};
    private static final int DEFAULT_VOID_Y = -10;

    protected enum Phase { LOADING, COUNTDOWN, PLAYING, ENDING, FINISHED }

    protected final GameServices services;
    protected final GameModeDef mode;
    protected final TeamArenaSettings teamSettings;
    protected final MapDef map;
    protected final RandomSource random = RandomSource.create();

    private final UUID id = UUID.randomUUID();
    private final List<Team> teams = new ArrayList<>();
    private final Map<UUID, PlayerState> players = new LinkedHashMap<>();
    private final List<BlockPos> cageBlocks = new ArrayList<>();
    private final boolean competitive;

    private Phase phase = Phase.LOADING;
    private Arena arena;
    private int phaseTicks;
    private int elapsedSeconds;
    private boolean disposed;

    protected TeamArenaGame(GameServices services, GameModeDef mode, TeamArenaSettings teamSettings, MapDef map, MatchRoster roster) {
        this.services = services;
        this.mode = mode;
        this.teamSettings = teamSettings;
        this.map = map;

        // matchmaking decided who plays together (parties); only which island/colour each team gets is random
        for (int i = 0; i < teamSettings.teamCount(); i++) teams.add(new Team(i));
        List<List<ServerPlayer>> rosterTeams = new ArrayList<>(roster.teams());
        Collections.shuffle(rosterTeams, new java.util.Random(random.nextLong()));
        int overflow = 0;
        for (int i = 0; i < rosterTeams.size(); i++) {
            for (ServerPlayer player : rosterTeams.get(i)) {
                // more roster teams than arena teams can only come from a misconfigured caller: spread them out
                Team team = teams.get(i < teams.size() ? i : overflow++ % teams.size());
                team.members.add(player.getUUID());
                players.put(player.getUUID(), new PlayerState(player.getUUID(), player.getGameProfile().getName(), team));
            }
        }
        this.competitive = teams.stream().filter(t -> !t.members.isEmpty()).count() >= 2;
    }

    // ---- hooks -----------------------------------------------------------------------------

    /** Shown in the intro line, e.g. "空岛战争". */
    protected abstract String gameName();

    /** The map file was parsed; nothing is in the world yet. */
    protected void onBlueprintLoaded(MapBlueprint blueprint) {
    }

    /** The map is in the world, players are about to be placed. */
    protected void onArenaReady() {
    }

    /** A player was placed at their spawn for the countdown (already reset to adventure mode). */
    protected void onPlayerSpawned(ServerPlayer player) {
    }

    /** Called once per second while PLAYING, after {@link #elapsedSeconds()} increased. */
    protected void onSecond() {
    }

    /** Whether an eliminated player's inventory is dropped (false = items vanish). */
    protected boolean dropsItemsOnElimination() {
        return true;
    }

    protected boolean useCages() {
        return true;
    }

    // ---- lifecycle -----------------------------------------------------------------------

    @Override
    public void start() {
        forEachOnline(p -> {
            p.sendSystemMessage(Component.literal("§b§l" + gameName() + " §7» §f" + mode.title + " §7| 地图: §e" + map.name));
            PlayerUtil.actionBar(p, Component.literal("§e地图加载中…"));
        });
        services.maps().load(map).whenComplete((blueprint, error) -> services.server().execute(() -> {
            if (disposed) return;
            if (error != null) {
                BlockLand.LOGGER.error("[BlockLand] Failed to load map {}", map.id, error);
                broadcast(Component.literal("§c地图加载失败，对局取消"));
                phase = Phase.FINISHED;
                return;
            }
            arena = services.arenas().allocate(blueprint.size());
            onBlueprintLoaded(blueprint);
            services.jobs().submit(new PasteJob(arena.level(), arena.origin(), blueprint)).whenComplete((v, pasteError) -> {
                if (disposed) return;
                if (pasteError != null) {
                    broadcast(Component.literal("§c地图放置失败，对局取消"));
                    phase = Phase.FINISHED;
                    return;
                }
                onPasted();
            });
        }));
    }

    private void onPasted() {
        onArenaReady();
        setupScoreboardTeams();
        List<MapDef.TeamSpawnDef> groups = map.teamSpawns.stream().filter(t -> t.points != null && !t.points.isEmpty()).toList();
        int step = Math.max(1, groups.size() / teamSettings.teamCount());
        for (Team team : teams) {
            MapDef.TeamSpawnDef group = groups.get((team.index * step) % groups.size());
            int slot = 0;
            for (UUID memberId : team.members) {
                MapDef.SpawnPointDef point = group.points.get(slot++ % group.points.size());
                BlockPos spawn = arena.toWorld((int) Math.floor(point.x), (int) Math.floor(point.y), (int) Math.floor(point.z));
                PlayerState state = players.get(memberId);
                state.spawn = spawn;
                state.spawnYaw = point.yaw;
                if (useCages()) buildCage(spawn);
                ServerPlayer player = online(memberId);
                if (player != null) {
                    PlayerUtil.reset(player, GameType.ADVENTURE);
                    PlayerUtil.teleport(player, arena.level(), Vec3.atBottomCenterOf(spawn), point.yaw, 0);
                    onPlayerSpawned(player);
                }
            }
        }
        phase = Phase.COUNTDOWN;
        phaseTicks = teamSettings.countdownSeconds() * 20;
    }

    @Override
    public void tick() {
        switch (phase) {
            case LOADING -> {
                if (++phaseTicks % 20 == 0) forEachOnline(p -> PlayerUtil.actionBar(p, Component.literal("§e地图加载中…")));
            }
            case COUNTDOWN -> tickCountdown();
            case PLAYING -> tickPlaying();
            case ENDING -> {
                if (--phaseTicks <= 0) phase = Phase.FINISHED;
            }
            case FINISHED -> {
            }
        }
    }

    private void tickCountdown() {
        if (phaseTicks % 20 == 0) {
            int seconds = phaseTicks / 20;
            if (seconds > 0) {
                ChatFormatting color = seconds <= 3 ? ChatFormatting.RED : seconds <= 5 ? ChatFormatting.GOLD : ChatFormatting.GREEN;
                forEachOnline(p -> {
                    PlayerUtil.title(p, Component.literal(String.valueOf(seconds)).withStyle(color, ChatFormatting.BOLD),
                            Component.literal("§7准备战斗"), 0, 25, 0);
                    if (seconds <= 5) PlayerUtil.sound(p, SoundEvents.NOTE_BLOCK_HAT.value(), 1f, 1f);
                });
            }
        }
        if (--phaseTicks > 0) return;

        removeCages();
        phase = Phase.PLAYING;
        phaseTicks = 0;
        forEachOnline(p -> {
            PlayerState state = players.get(p.getUUID());
            if (state != null && state.alive) p.setGameMode(GameType.SURVIVAL);
            PlayerUtil.title(p, Component.literal("§a§l开始！"), Component.literal("§7活到最后的队伍获胜"), 0, 30, 10);
            PlayerUtil.sound(p, SoundEvents.PLAYER_LEVELUP, 1f, 1f);
        });
        checkWin();
    }

    private void tickPlaying() {
        double voidY = arena.origin().getY() + (map.voidY == null ? DEFAULT_VOID_Y : map.voidY);
        for (PlayerState state : new ArrayList<>(players.values())) {
            if (!state.alive) continue;
            ServerPlayer player = online(state.id);
            if (player == null) continue;
            if (player.level() != arena.level() || player.getY() < voidY) {
                eliminate(player, killerOf(player, null), true);
            }
        }
        if (phase != Phase.PLAYING) return;

        if (++phaseTicks % 20 != 0) return;
        elapsedSeconds++;
        onSecond();
        int limit = mode.timeLimitOrZero();
        long aliveTeams = teams.stream().filter(this::isAlive).count();
        forEachOnline(p -> {
            PlayerState state = players.get(p.getUUID());
            String time = limit > 0 ? "§e剩余 " + PlayerUtil.formatTime(limit - elapsedSeconds) : "§e已进行 " + PlayerUtil.formatTime(elapsedSeconds);
            PlayerUtil.actionBar(p, Component.literal("§b存活队伍 " + aliveTeams + " §8| " + time
                    + " §8| §c击杀 " + (state == null ? 0 : state.kills)));
        });
        if (limit > 0 && elapsedSeconds >= limit) endByTimeLimit();
    }

    @Override
    public boolean isFinished() {
        return phase == Phase.FINISHED;
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        phase = Phase.FINISHED;
        Scoreboard scoreboard = services.server().getScoreboard();
        for (Team team : teams) {
            if (team.scoreboardTeam != null && scoreboard.getPlayerTeam(team.scoreboardTeam.getName()) != null) {
                scoreboard.removePlayerTeam(team.scoreboardTeam);
            }
        }
        if (arena != null) services.arenas().release(arena);
    }

    // ---- players -------------------------------------------------------------------------

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

    @Override
    public void removePlayer(ServerPlayer player, boolean disconnected) {
        PlayerState state = players.get(player.getUUID());
        if (state == null) return;
        if (state.alive && phase == Phase.PLAYING) {
            if (dropsItemsOnElimination()) player.getInventory().dropAll();
            broadcast(Component.literal(state.coloredName() + " §7离开了对局"));
        }
        state.alive = false;
        players.remove(player.getUUID());
        state.team.members.remove(player.getUUID());
        if (state.team.scoreboardTeam != null) {
            services.server().getScoreboard().removePlayerFromTeam(player.getScoreboardName(), state.team.scoreboardTeam);
        }
        if (players.isEmpty()) {
            phase = Phase.FINISHED;
        } else if (phase == Phase.PLAYING || phase == Phase.COUNTDOWN) {
            checkWin();
        }
    }

    @Override
    public boolean onPlayerDeath(ServerPlayer player, DamageSource source) {
        PlayerState state = players.get(player.getUUID());
        if (state == null) return false;
        if (phase == Phase.PLAYING && state.alive) {
            eliminate(player, killerOf(player, source), false);
        } else {
            // never let a participant really die (e.g. during the countdown)
            player.setHealth(player.getMaxHealth());
            if (state.spawn != null) PlayerUtil.teleport(player, arena.level(), Vec3.atBottomCenterOf(state.spawn), state.spawnYaw, 0);
        }
        return true;
    }

    @Override
    public boolean allowDamage(ServerPlayer victim, DamageSource source) {
        if (phase != Phase.PLAYING) return false;
        PlayerState victimState = players.get(victim.getUUID());
        if (victimState == null || !victimState.alive) return false;
        if (source.getEntity() instanceof ServerPlayer attacker) {
            PlayerState attackerState = players.get(attacker.getUUID());
            return attackerState == null || attackerState.team != victimState.team;
        }
        return true;
    }

    @Override
    public boolean allowBlockBreak(ServerPlayer player, BlockPos pos) {
        return phase == Phase.PLAYING;
    }

    @Override
    public boolean allowBlockPlace(ServerPlayer player, BlockPos pos) {
        return phase == Phase.PLAYING;
    }

    private void eliminate(ServerPlayer player, @Nullable ServerPlayer killer, boolean fellIntoVoid) {
        PlayerState state = players.get(player.getUUID());
        if (state == null || !state.alive) return;
        state.alive = false;

        if (!fellIntoVoid && dropsItemsOnElimination()) player.getInventory().dropAll();
        Component message;
        if (killer != null && killer != player && players.containsKey(killer.getUUID())) {
            PlayerState killerState = players.get(killer.getUUID());
            killerState.kills++;
            PlayerUtil.sound(killer, SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            message = Component.literal(state.coloredName() + " §7被 " + killerState.coloredName()
                    + (fellIntoVoid ? " §7击落虚空" : " §7击杀"));
        } else {
            message = Component.literal(state.coloredName() + (fellIntoVoid ? " §7掉入了虚空" : " §7阵亡了"));
        }
        broadcast(message);

        PlayerUtil.reset(player, GameType.SPECTATOR);
        Vec3 center = arena.center();
        PlayerUtil.teleport(player, arena.level(), new Vec3(center.x, arena.origin().getY() + arena.size().getY() + 5, center.z), 0, 60);
        PlayerUtil.title(player, Component.literal("§c§l你已阵亡"), Component.literal("§7正在观战，可使用 /bl leave 返回大厅"), 5, 50, 10);

        checkWin();
    }

    @Nullable
    private ServerPlayer killerOf(ServerPlayer victim, @Nullable DamageSource source) {
        if (source != null) {
            Entity attacker = source.getEntity();
            if (attacker instanceof ServerPlayer p) return p;
        }
        LivingEntity credit = victim.getKillCredit();
        return credit instanceof ServerPlayer p ? p : null;
    }

    // ---- win conditions ------------------------------------------------------------------

    private void checkWin() {
        if (phase != Phase.PLAYING && phase != Phase.COUNTDOWN) return;
        List<Team> alive = teams.stream().filter(this::isAlive).toList();
        if (alive.isEmpty()) {
            end(null, "所有玩家均已阵亡");
        } else if (competitive && alive.size() == 1) {
            end(alive.get(0), null);
        }
    }

    private void endByTimeLimit() {
        List<Team> alive = teams.stream().filter(this::isAlive)
                .sorted(Comparator.comparingInt(this::aliveCount).reversed()).toList();
        if (alive.isEmpty() || (alive.size() > 1 && aliveCount(alive.get(0)) == aliveCount(alive.get(1)))) {
            end(null, "时间到，平局");
        } else {
            end(alive.get(0), "时间到");
        }
    }

    private void end(@Nullable Team winner, @Nullable String reason) {
        if (phase == Phase.COUNTDOWN) removeCages();
        phase = Phase.ENDING;
        phaseTicks = teamSettings.endingSeconds() * 20;

        String winnerText = winner == null ? "§7无人获胜" : winner.coloredName() + " §6获得胜利！";
        forEachOnline(p -> {
            PlayerState state = players.get(p.getUUID());
            boolean won = winner != null && state != null && state.team == winner;
            PlayerUtil.title(p, Component.literal(won ? "§6§l胜利！" : "§c§l游戏结束"),
                    Component.literal(reason == null ? winnerText : "§7" + reason + " §8- " + winnerText), 5, 80, 20);
            PlayerUtil.sound(p, won ? SoundEvents.PLAYER_LEVELUP : SoundEvents.NOTE_BLOCK_PLING.value(), 1f, won ? 1f : 0.6f);
            if (won) {
                p.setGameMode(GameType.ADVENTURE);
                p.setHealth(p.getMaxHealth());
            }
        });

        StringBuilder summary = new StringBuilder("§8§m----------§r §b§l对局结束 §8§m----------\n");
        summary.append("§f胜者: ").append(winnerText).append('\n');
        players.values().stream().sorted(Comparator.comparingInt((PlayerState s) -> s.kills).reversed()).limit(5)
                .forEach(s -> summary.append(" ").append(s.coloredName()).append(" §7- §c").append(s.kills).append(" 击杀\n"));
        summary.append("§7").append(teamSettings.endingSeconds()).append(" 秒后返回大厅");
        broadcast(Component.literal(summary.toString()));
    }

    private boolean isAlive(Team team) {
        return aliveCount(team) > 0;
    }

    private int aliveCount(Team team) {
        int count = 0;
        for (UUID member : team.members) {
            PlayerState state = players.get(member);
            if (state != null && state.alive && online(member) != null) count++;
        }
        return count;
    }

    // ---- world setup ---------------------------------------------------------------------

    private void buildCage(BlockPos feet) {
        ServerLevel level = arena.level();
        List<BlockPos> shell = new ArrayList<>();
        shell.add(feet.below());
        shell.add(feet.above(2));
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos layer = feet.above(dy);
            shell.add(layer.north());
            shell.add(layer.south());
            shell.add(layer.east());
            shell.add(layer.west());
        }
        for (BlockPos pos : shell) {
            if (level.getBlockState(pos).isAir()) {
                level.setBlock(pos, Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
                cageBlocks.add(pos);
            }
        }
    }

    private void removeCages() {
        for (BlockPos pos : cageBlocks) {
            if (arena.level().getBlockState(pos).is(Blocks.GLASS)) {
                arena.level().setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        cageBlocks.clear();
    }

    private void setupScoreboardTeams() {
        Scoreboard scoreboard = services.server().getScoreboard();
        String prefix = "bl_" + id.toString().substring(0, 6) + "_";
        for (Team team : teams) {
            if (team.members.isEmpty()) continue;
            String name = prefix + team.index;
            PlayerTeam existing = scoreboard.getPlayerTeam(name);
            PlayerTeam playerTeam = existing != null ? existing : scoreboard.addPlayerTeam(name);
            playerTeam.setColor(team.color());
            playerTeam.setAllowFriendlyFire(false);
            playerTeam.setPlayerPrefix(Component.literal("[" + team.shortName() + "] ").withStyle(team.color()));
            team.scoreboardTeam = playerTeam;
            for (UUID member : team.members) {
                scoreboard.addPlayerToTeam(players.get(member).name, playerTeam);
            }
        }
    }

    // ---- helpers for subclasses ----------------------------------------------------------

    protected Phase phase() {
        return phase;
    }

    protected Arena arena() {
        return arena;
    }

    protected int elapsedSeconds() {
        return elapsedSeconds;
    }

    @Nullable
    protected ServerPlayer online(UUID id) {
        return services.server().getPlayerList().getPlayer(id);
    }

    protected void forEachOnline(Consumer<ServerPlayer> action) {
        for (UUID id : new ArrayList<>(players.keySet())) {
            ServerPlayer player = online(id);
            if (player != null) action.accept(player);
        }
    }

    protected void broadcast(Component message) {
        forEachOnline(p -> p.sendSystemMessage(message));
    }

    private final class Team {
        final int index;
        final List<UUID> members = new ArrayList<>();
        PlayerTeam scoreboardTeam;

        Team(int index) {
            this.index = index;
        }

        ChatFormatting color() {
            return TEAM_COLORS[index % TEAM_COLORS.length];
        }

        String name() {
            return index < TEAM_NAMES.length ? TEAM_NAMES[index] : "队伍" + (index + 1);
        }

        String shortName() {
            return name().replace("队", "");
        }

        String coloredName() {
            if (teamSettings.teamSize() == 1 && members.size() == 1) {
                PlayerState only = players.get(members.get(0));
                if (only != null) return only.coloredName();
            }
            return "§" + color().getChar() + name();
        }
    }

    private final class PlayerState {
        final UUID id;
        final String name;
        final Team team;
        boolean alive = true;
        int kills;
        BlockPos spawn;
        float spawnYaw;

        PlayerState(UUID id, String name, Team team) {
            this.id = id;
            this.name = name;
            this.team = team;
        }

        String coloredName() {
            return "§" + team.color().getChar() + name;
        }
    }
}
