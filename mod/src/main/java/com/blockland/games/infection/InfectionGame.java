package com.blockland.games.infection;

import com.blockland.BlockLand;
import com.blockland.games.common.ArenaLoader;
import com.blockland.games.common.MatchText;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.InfectionPackets;
import com.blockland.server.arena.Arena;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.content.model.MapDef;
import com.blockland.server.game.GameInstance;
import com.blockland.server.game.GameManager;
import com.blockland.server.game.GameServices;
import com.blockland.server.game.MatchBoard;
import com.blockland.server.game.MatchResult;
import com.blockland.server.game.MatchRoster;
import com.blockland.server.morph.Morphs;
import com.blockland.server.util.PlayerUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
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
 * One 枪战感染 match: LOADING → COUNTDOWN → (PLAYING → ROUND_BREAK)× → ENDING, a best-of series of rounds.
 * Every round everyone starts as a human with the mode's kit at a human spawn; after the infection delay
 * {@code startMonsters} random players turn into mother monsters. A human who dies (in any way) is infected: they
 * pick a monster in a panel (the first one after {@code selectSeconds}) and fight on with its model, health and
 * skill. Killed monsters come back at a monster spawn after {@code monsterRespawnSeconds}. A round goes to the humans
 * when any of them is still standing at the end of the survival time, to the monsters when nobody is left to
 * infect; the side with more round wins takes the match (it ends as soon as it is decided).
 */
final class InfectionGame implements GameInstance {
    private static final int DEFAULT_SURVIVE_SECONDS = 180;
    /** Nothing is ever built or broken here: adventure mode stops clients from even starting to mine. */
    private static final GameType PLAY_MODE = GameType.ADVENTURE;
    private static final int DEFAULT_VOID_Y = -10;
    private static final ResourceLocation HEALTH = BlockLand.id("infection_health");
    private static final ResourceLocation SPEED = BlockLand.id("infection_speed");
    private static final ResourceLocation KNOCKBACK = BlockLand.id("infection_knockback");
    private static final int HUMAN_COLOR = 0xFF55C8FF;
    private static final int MONSTER_COLOR = 0xFF7CDB5A;
    private static final int MOTHER_COLOR = 0xFFE0463C;

    private enum Phase { LOADING, COUNTDOWN, PLAYING, ROUND_BREAK, ENDING, FINISHED }

    private enum Side {
        HUMAN("blockland.infection.side.human", ChatFormatting.AQUA), MONSTER("blockland.infection.side.monster", ChatFormatting.DARK_GREEN);

        final String key;
        final ChatFormatting color;

        Side(String key, ChatFormatting color) {
            this.key = key;
            this.color = color;
        }

        /** "人类" / "怪物" in the side's colour. */
        Component label() {
            return Component.translatable(key).withStyle(color);
        }
    }

    private final GameServices services;
    private final GameModeDef mode;
    private final InfectionSettings settings;
    private final MapDef map;
    private final List<MonsterCatalog.Monster> offered;
    private final RandomSource random = RandomSource.create();
    private final UUID id = UUID.randomUUID();
    private final Map<UUID, Fighter> players = new LinkedHashMap<>();
    private final List<Spawn> humanSpawns = new ArrayList<>();
    private final List<Spawn> monsterSpawns = new ArrayList<>();

    private Phase phase = Phase.LOADING;
    private Arena arena;
    private int phaseTicks;
    /** Seconds into the current round, and in the whole match. */
    private int roundSeconds, matchSeconds;
    private int round;
    private int humanRounds, monsterRounds;
    private boolean mothersChosen;
    private boolean disposed;
    private PlayerTeam humanTeam;
    private PlayerTeam monsterTeam;

    private record Spawn(BlockPos pos, float yaw) {
    }

    private static final class Fighter {
        final UUID id;
        final String name;
        Side side = Side.HUMAN;
        boolean mother;
        @Nullable
        MonsterCatalog.Monster monster;
        /** Server tick until which the monster may still choose (-1 = not choosing). */
        int selectUntil = -1;
        /** Server tick of a killed monster's return (-1 = alive). */
        int respawnAt = -1;
        int skillReadyAt;
        int kills;
        int infections;
        /** Rounds this player finished on the winning side. */
        int roundWins;

        Fighter(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        boolean active() {
            return selectUntil < 0 && respawnAt < 0;
        }
    }

    InfectionGame(GameServices services, GameModeDef mode, InfectionSettings settings, MapDef map, MatchRoster roster) {
        this.services = services;
        this.mode = mode;
        this.settings = settings;
        this.map = map;
        this.offered = MonsterCatalog.get().offered(settings.monsters());
        for (MatchRoster.Member member : roster.players()) players.put(member.id(), new Fighter(member.id(), member.name()));
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    @Override
    public void start() {
        forEachOnline(p -> {
            p.sendSystemMessage(Component.empty()
                    .append(Component.translatable("blockland.game.infection").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                    .append(Component.literal(" » ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(mode.title).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" | ").withStyle(ChatFormatting.GRAY))
                    .append(Component.translatable("blockland.arena.intro_map", Component.literal(map.name).withStyle(ChatFormatting.YELLOW))
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(" | ").withStyle(ChatFormatting.GRAY))
                    .append(Component.translatable("blockland.infection.intro_rounds", settings.rounds(), settings.winsNeeded())
                            .withStyle(ChatFormatting.GRAY)));
            PlayerUtil.actionBar(p, MatchText.loadingMap());
        });
        ArenaLoader.load(services, map, () -> disposed, (allocated, blueprint) -> arena = allocated, pasted -> onPasted(), problem -> {
            broadcast(problem.copy().withStyle(ChatFormatting.RED));
            phase = Phase.FINISHED;
        });
    }

    private void onPasted() {
        setupTeams();
        InfectionSpawns spawns = InfectionSpawns.of(map);
        for (InfectionSpawns.Point point : spawns.humans()) humanSpawns.add(spawn(point));
        for (InfectionSpawns.Point point : spawns.monsters()) monsterSpawns.add(spawn(point));
        placeHumans();
        forEachOnline(p -> p.setGameMode(GameType.ADVENTURE));
        phase = Phase.COUNTDOWN;
        phaseTicks = settings.countdownSeconds() * 20;
        // the players waited in the lobby while the map loaded: now they load straight into the arena
        for (UUID id : List.copyOf(players.keySet())) {
            if (online(id) == null) services.players().bring(id, this::arrive);
        }
    }

    /** A player loaded in from the lobby once the map was ready: a human at a human spawn, seeing the monsters there are. */
    private void arrive(ServerPlayer player) {
        Fighter fighter = players.get(player.getUUID());
        if (fighter == null || disposed || phase == Phase.ENDING || phase == Phase.FINISHED) {
            services.players().toLobby(player);
            return;
        }
        placeHuman(player, fighter, humanSpawns.get(random.nextInt(humanSpawns.size())), onlinePlayers());
        for (Fighter other : players.values()) {
            if (other.side == Side.MONSTER && other.monster != null) Morphs.show(other.id, other.monster.entity, List.of(player));
        }
    }

    private Spawn spawn(InfectionSpawns.Point point) {
        return new Spawn(arena.toWorld((int) Math.floor(point.x()), (int) Math.floor(point.y()), (int) Math.floor(point.z())), point.yaw());
    }

    /** Everyone back to human at the human spawns (shuffled), with a fresh kit. */
    private void placeHumans() {
        List<Spawn> order = new ArrayList<>(humanSpawns);
        Collections.shuffle(order, new java.util.Random(random.nextLong()));
        List<ServerPlayer> online = onlinePlayers();
        int slot = 0;
        for (Fighter fighter : players.values()) {
            ServerPlayer player = online(fighter.id);
            if (player == null) continue;
            placeHuman(player, fighter, order.get(slot++ % order.size()), online);
        }
    }

    private void placeHuman(ServerPlayer player, Fighter fighter, Spawn spawn, List<ServerPlayer> viewers) {
        if (fighter.side == Side.MONSTER || fighter.monster != null) Morphs.show(fighter.id, null, viewers);
        clearMonster(player);
        fighter.side = Side.HUMAN;
        fighter.mother = false;
        fighter.monster = null;
        fighter.selectUntil = -1;
        fighter.respawnAt = -1;
        fighter.skillReadyAt = 0;
        PlayerUtil.reset(player, PLAY_MODE);
        PlayerUtil.teleport(player, arena.level(), Vec3.atBottomCenterOf(spawn.pos), spawn.yaw, 0);
        services.kits().apply(player, mode);
        joinTeam(player, Side.HUMAN);
        sendRole(player, fighter);
    }

    @Override
    public void tick() {
        switch (phase) {
            case LOADING -> {
                if (++phaseTicks % 20 == 0) forEachOnline(p -> PlayerUtil.actionBar(p, MatchText.loadingMap()));
            }
            case COUNTDOWN -> tickCountdown();
            case PLAYING -> tickPlaying();
            case ROUND_BREAK -> tickBreak();
            case ENDING -> {
                if (--phaseTicks <= 0) phase = Phase.FINISHED;
            }
            case FINISHED -> {
            }
        }
    }

    private void tickCountdown() {
        if (phaseTicks % 20 == 0 && phaseTicks > 0) {
            int seconds = phaseTicks / 20;
            ChatFormatting color = seconds <= 3 ? ChatFormatting.RED : seconds <= 5 ? ChatFormatting.GOLD : ChatFormatting.GREEN;
            forEachOnline(p -> {
                PlayerUtil.title(p, Component.literal(String.valueOf(seconds)).withStyle(color, ChatFormatting.BOLD),
                        Component.translatable("blockland.infection.countdown_sub").withStyle(ChatFormatting.GRAY), 0, 25, 0);
                if (seconds <= 5) PlayerUtil.sound(p, SoundEvents.NOTE_BLOCK_HAT.value(), 1f, 1f);
            });
        }
        if (--phaseTicks > 0) return;
        beginRound();
    }

    private void beginRound() {
        round++;
        mothersChosen = false;
        roundSeconds = 0;
        phaseTicks = 0;
        phase = Phase.PLAYING;
        forEachOnline(p -> {
            p.setGameMode(PLAY_MODE);
            PlayerUtil.title(p, Component.translatable("blockland.infection.round_title", round).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                    Component.translatable("blockland.infection.round_sub", settings.infectionDelaySeconds()).withStyle(ChatFormatting.GRAY),
                    0, 40, 10);
            PlayerUtil.sound(p, SoundEvents.PLAYER_LEVELUP, 1f, 1f);
        });
    }

    private void tickPlaying() {
        int now = services.server().getTickCount();
        double voidY = arena.origin().getY() + (map.voidY == null ? DEFAULT_VOID_Y : map.voidY);
        for (Fighter fighter : new ArrayList<>(players.values())) {
            ServerPlayer player = online(fighter.id);
            if (player == null) continue;
            if (fighter.respawnAt >= 0) {
                if (now >= fighter.respawnAt) respawnMonster(player, fighter);
                continue;
            }
            if (player.level() != arena.level() || player.getY() < voidY) {
                died(player, null, true);
                if (phase != Phase.PLAYING) return;
                continue;
            }
            if (fighter.selectUntil >= 0 && now >= fighter.selectUntil) becomeMonster(player, fighter, offered.get(0));
        }
        if (phase != Phase.PLAYING || ++phaseTicks % 20 != 0) return;
        roundSeconds++;
        matchSeconds++;
        if (!mothersChosen && roundSeconds >= settings.infectionDelaySeconds()) chooseMothers();
        if (phase != Phase.PLAYING) return;
        int limit = surviveSeconds();
        int humans = count(Side.HUMAN), monsters = count(Side.MONSTER);
        Component score = Component.empty()
                .append(Component.translatable("blockland.infection.hud.round", round, settings.rounds()).withStyle(ChatFormatting.GRAY))
                .append(" ")
                .append(Component.literal(String.valueOf(humanRounds)).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(":").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.valueOf(monsterRounds)).withStyle(ChatFormatting.DARK_GREEN));
        forEachOnline(p -> {
            Fighter fighter = players.get(p.getUUID());
            Component status;
            if (!mothersChosen) {
                status = Component.translatable("blockland.infection.hud.mother_in", settings.infectionDelaySeconds() - roundSeconds)
                        .withStyle(ChatFormatting.YELLOW);
            } else if (fighter != null && fighter.respawnAt >= 0) {
                status = Component.translatable("blockland.infection.hud.respawn_in", Math.max(1, (fighter.respawnAt - now + 19) / 20))
                        .withStyle(ChatFormatting.RED);
            } else {
                status = Component.translatable("blockland.arena.hud.time_left", PlayerUtil.formatTime(limit - roundSeconds))
                        .withStyle(ChatFormatting.YELLOW);
            }
            PlayerUtil.actionBar(p, Component.empty().append(score)
                    .append(MatchText.SEPARATOR)
                    .append(Component.translatable("blockland.infection.hud.humans", humans).withStyle(ChatFormatting.AQUA))
                    .append(MatchText.SEPARATOR)
                    .append(Component.translatable("blockland.infection.hud.monsters", monsters).withStyle(ChatFormatting.DARK_GREEN))
                    .append(MatchText.SEPARATOR)
                    .append(status));
        });
        if (mothersChosen && roundSeconds >= limit) endRound(Side.HUMAN, Component.translatable("blockland.infection.reason.held_out"));
    }

    private void tickBreak() {
        if (phaseTicks % 20 == 0 && phaseTicks > 0) {
            int seconds = phaseTicks / 20;
            Component bar = Component.empty()
                    .append(Component.translatable("blockland.infection.hud.score_label").withStyle(ChatFormatting.GRAY))
                    .append(" ")
                    .append(seriesScore())
                    .append(MatchText.SEPARATOR)
                    .append(Component.translatable("blockland.infection.hud.next_round", round + 1, seconds).withStyle(ChatFormatting.YELLOW));
            forEachOnline(p -> PlayerUtil.actionBar(p, bar));
        }
        if (--phaseTicks > 0) return;
        placeHumans();
        beginRound();
    }

    private int surviveSeconds() {
        int limit = mode.timeLimitOrZero();
        return limit > 0 ? limit : DEFAULT_SURVIVE_SECONDS;
    }

    @Override
    public boolean isFinished() {
        return phase == Phase.FINISHED;
    }

    @Override
    public boolean isEnding() {
        return phase == Phase.ENDING;
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        phase = Phase.FINISHED;
        List<ServerPlayer> online = onlinePlayers();
        for (Fighter fighter : players.values()) {
            if (fighter.monster != null || fighter.side == Side.MONSTER) Morphs.show(fighter.id, null, online);
            ServerPlayer player = online(fighter.id);
            if (player != null) {
                clearMonster(player);
                BLNetwork.sendTo(player, InfectionPackets.S2CRole.NONE);
            }
        }
        Scoreboard scoreboard = services.server().getScoreboard();
        for (PlayerTeam team : new PlayerTeam[]{humanTeam, monsterTeam}) {
            if (team != null && scoreboard.getPlayerTeam(team.getName()) != null) scoreboard.removePlayerTeam(team);
        }
        if (arena != null) services.arenas().release(arena);
    }

    // ---- infection --------------------------------------------------------------------------------

    /** Random humans become the round's first monsters: {@code startMonsters} of them, always leaving a human. */
    private void chooseMothers() {
        List<ServerPlayer> humans = new ArrayList<>();
        for (Fighter fighter : players.values()) {
            ServerPlayer player = online(fighter.id);
            if (player != null && fighter.side == Side.HUMAN) humans.add(player);
        }
        mothersChosen = true;
        if (humans.size() < 2) {
            finish(Component.translatable("blockland.infection.reason.not_enough"));
            return;
        }
        int count = Mth.clamp(settings.startMonsters(), 1, humans.size() - 1);
        Collections.shuffle(humans, new java.util.Random(random.nextLong()));
        for (ServerPlayer mother : humans.subList(0, count)) infect(mother, null, true);
        forEachOnline(p -> {
            PlayerUtil.title(p, Component.translatable("blockland.infection.mothers_title").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD),
                    Component.translatable("blockland.infection.mothers_sub", count).withStyle(ChatFormatting.GRAY), 5, 40, 10);
            PlayerUtil.sound(p, SoundEvents.WITHER_SPAWN, 0.6f, 1.2f);
        });
    }

    /** A human turns into a monster where they stand and gets to pick which monster. */
    private void infect(ServerPlayer player, @Nullable ServerPlayer by, boolean mother) {
        Fighter fighter = players.get(player.getUUID());
        if (fighter == null || fighter.side != Side.HUMAN) return;
        fighter.side = Side.MONSTER;
        fighter.mother = mother;
        Fighter infector = by == null ? null : players.get(by.getUUID());
        if (infector != null && infector.side == Side.MONSTER) infector.infections++;

        PlayerUtil.reset(player, PLAY_MODE);
        joinTeam(player, Side.MONSTER);
        // until the choice is made the default monster stands there, harmless and untouchable
        fighter.monster = offered.get(0);
        fighter.selectUntil = services.server().getTickCount() + settings.selectSeconds() * 20;
        Morphs.show(fighter.id, fighter.monster.entity, onlinePlayers());
        BLNetwork.sendTo(player, new InfectionPackets.S2CSelect(settings.selectSeconds(), mother,
                mother ? settings.motherHealthMultiplier() : 1f, offered.stream().map(InfectionGame::option).toList()));

        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ZOMBIE_INFECT, SoundSource.PLAYERS, 1.2f, 0.9f);
        player.level().sendParticles(ParticleTypes.SQUID_INK, player.getX(), player.getY() + 1, player.getZ(), 24, 0.3, 0.6, 0.3, 0.02);
        PlayerUtil.title(player, mother ? Component.translatable("blockland.infection.you_are_mother").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)
                        : Component.translatable("blockland.infection.you_are_infected").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                Component.translatable("blockland.infection.pick_monster").withStyle(ChatFormatting.GRAY), 0, 40, 10);
        if (!mother) {
            Component victim = Component.literal(fighter.name).withStyle(ChatFormatting.AQUA);
            broadcast(infector != null
                    ? Component.translatable("blockland.infection.infected_by", victim, Component.literal(infector.name).withStyle(ChatFormatting.DARK_GREEN))
                            .withStyle(ChatFormatting.GRAY)
                    : Component.translatable("blockland.infection.infected", victim).withStyle(ChatFormatting.GRAY));
        }
        checkRound();
    }

    private static InfectionPackets.MonsterOption option(MonsterCatalog.Monster monster) {
        return new InfectionPackets.MonsterOption(monster.id, monster.name, monster.description, monster.entity, monster.health,
                monster.skill.name, monster.skill.description, monster.skill.cooldown);
    }

    /** The infected player chose (or the selection timed out): the monster takes over. */
    private void becomeMonster(ServerPlayer player, Fighter fighter, MonsterCatalog.Monster monster) {
        fighter.selectUntil = -1;
        fighter.monster = monster;
        fighter.skillReadyAt = 0;
        applyMonster(player, fighter);
        Morphs.show(fighter.id, monster.entity, onlinePlayers());
        sendRole(player, fighter);
        PlayerUtil.actionBar(player, Component.empty()
                .append(Component.translatable("blockland.infection.now_monster", monster.name).withStyle(ChatFormatting.DARK_GREEN))
                .append(Component.literal(" · ").withStyle(ChatFormatting.GRAY))
                .append(Component.translatable("blockland.infection.monster_hint", monster.skill.name).withStyle(ChatFormatting.GRAY)));
    }

    /** The monster panel's choice; only accepted while that player is choosing, and only among the offered monsters. */
    void onSelect(ServerPlayer player, String monsterId) {
        Fighter fighter = players.get(player.getUUID());
        if (fighter == null || fighter.selectUntil < 0 || phase != Phase.PLAYING) return;
        MonsterCatalog.Monster chosen = offered.stream().filter(m -> m.id.equals(monsterId)).findFirst().orElse(offered.get(0));
        becomeMonster(player, fighter, chosen);
    }

    /** The skill key; the server checks the monster, its state and the cooldown. */
    void onSkill(ServerPlayer player) {
        Fighter fighter = players.get(player.getUUID());
        if (fighter == null || fighter.side != Side.MONSTER || fighter.monster == null || !fighter.active() || phase != Phase.PLAYING) return;
        int now = services.server().getTickCount();
        if (now < fighter.skillReadyAt) return;
        MonsterCatalog.Skill skill = fighter.monster.skill;
        if (!MonsterSkills.use(player, skill)) return;
        fighter.skillReadyAt = now + Math.round(skill.cooldown * 20);
        BLNetwork.sendTo(player, new InfectionPackets.S2CSkill(skill.cooldown, skill.activeSeconds()));
    }

    /** Health, speed and knockback resistance of the monster (mothers are tougher). Left-click damage is the mode's. */
    private void applyMonster(ServerPlayer player, Fighter fighter) {
        MonsterCatalog.Monster monster = fighter.monster;
        float health = monster.health * (fighter.mother ? settings.motherHealthMultiplier() : 1f);
        modifier(player, Attributes.MAX_HEALTH, HEALTH, health - 20, AttributeModifier.Operation.ADD_VALUE);
        modifier(player, Attributes.MOVEMENT_SPEED, SPEED, monster.speed - 1, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        modifier(player, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK, monster.knockbackResistance, AttributeModifier.Operation.ADD_VALUE);
        player.setHealth(player.getMaxHealth());
    }

    private static void modifier(ServerPlayer player, Holder<Attribute> attribute, ResourceLocation id, double amount, AttributeModifier.Operation operation) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null) instance.addOrUpdateTransientModifier(new AttributeModifier(id, amount, operation));
    }

    private static void clearMonster(ServerPlayer player) {
        for (Holder<Attribute> attribute : List.of(Attributes.MAX_HEALTH, Attributes.MOVEMENT_SPEED, Attributes.KNOCKBACK_RESISTANCE)) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance == null) continue;
            instance.removeModifier(HEALTH);
            instance.removeModifier(SPEED);
            instance.removeModifier(KNOCKBACK);
        }
        if (player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
    }

    // ---- deaths -----------------------------------------------------------------------------------

    @Override
    public boolean onPlayerDeath(ServerPlayer player, DamageSource source) {
        Fighter fighter = players.get(player.getUUID());
        if (fighter == null) return false;
        if (phase == Phase.PLAYING) {
            died(player, killerOf(player, source), false);
        } else {
            // never let a participant really die outside the fight
            player.setHealth(player.getMaxHealth());
        }
        return true;
    }

    private void died(ServerPlayer player, @Nullable ServerPlayer killer, boolean fell) {
        Fighter fighter = players.get(player.getUUID());
        if (fighter == null) return;
        if (fighter.side == Side.HUMAN) {
            if (!mothersChosen) {
                // before the outbreak nobody is infected: back to a spawn
                player.setHealth(player.getMaxHealth());
                Spawn spawn = humanSpawns.get(random.nextInt(humanSpawns.size()));
                PlayerUtil.teleport(player, arena.level(), Vec3.atBottomCenterOf(spawn.pos), spawn.yaw, 0);
                return;
            }
            if (fell) {
                Spawn spawn = farthestMonsterSpawn();
                PlayerUtil.teleport(player, arena.level(), Vec3.atBottomCenterOf(spawn.pos), spawn.yaw, 0);
            }
            ServerPlayer infector = killer != null && players.containsKey(killer.getUUID()) && players.get(killer.getUUID()).side == Side.MONSTER ? killer : null;
            infect(player, infector, false);
            return;
        }
        // a monster went down: it comes back after a while
        Fighter killerState = killer == null ? null : players.get(killer.getUUID());
        if (killerState != null && killerState.side == Side.HUMAN) {
            killerState.kills++;
            PlayerUtil.sound(killer, SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            broadcast(Component.translatable("blockland.arena.killed_by", Component.literal(fighter.name).withStyle(ChatFormatting.DARK_GREEN),
                    Component.literal(killerState.name).withStyle(ChatFormatting.AQUA)).withStyle(ChatFormatting.GRAY));
        }
        fighter.selectUntil = -1;
        fighter.respawnAt = services.server().getTickCount() + settings.monsterRespawnSeconds() * 20;
        player.setHealth(player.getMaxHealth());
        player.setGameMode(GameType.SPECTATOR);
        if (fell) {
            Spawn spawn = farthestMonsterSpawn();
            PlayerUtil.teleport(player, arena.level(), Vec3.atBottomCenterOf(spawn.pos).add(0, 3, 0), spawn.yaw, 30);
        }
        PlayerUtil.title(player, Component.translatable("blockland.infection.you_were_killed").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                Component.translatable("blockland.infection.hud.respawn_in", settings.monsterRespawnSeconds()).withStyle(ChatFormatting.GRAY), 0, 30, 10);
    }

    private void respawnMonster(ServerPlayer player, Fighter fighter) {
        fighter.respawnAt = -1;
        Spawn spawn = farthestMonsterSpawn();
        PlayerUtil.reset(player, PLAY_MODE);
        PlayerUtil.teleport(player, arena.level(), Vec3.atBottomCenterOf(spawn.pos), spawn.yaw, 0);
        if (fighter.monster == null) fighter.monster = offered.get(0);
        applyMonster(player, fighter);
        Morphs.show(fighter.id, fighter.monster.entity, onlinePlayers());
        sendRole(player, fighter);
    }

    /** The monster spawn farthest from every standing human. */
    private Spawn farthestMonsterSpawn() {
        Spawn best = monsterSpawns.get(0);
        double bestDistance = -1;
        for (Spawn spawn : monsterSpawns) {
            double nearest = Double.MAX_VALUE;
            for (Fighter fighter : players.values()) {
                ServerPlayer human = online(fighter.id);
                if (human == null || fighter.side != Side.HUMAN) continue;
                nearest = Math.min(nearest, human.distanceToSqr(Vec3.atBottomCenterOf(spawn.pos)));
            }
            if (nearest > bestDistance) {
                bestDistance = nearest;
                best = spawn;
            }
        }
        return best;
    }

    @Nullable
    private ServerPlayer killerOf(ServerPlayer victim, @Nullable DamageSource source) {
        if (source != null && source.getEntity() instanceof ServerPlayer p) return p;
        LivingEntity credit = victim.getKillCredit();
        return credit instanceof ServerPlayer p ? p : null;
    }

    // ---- rules ------------------------------------------------------------------------------------

    @Override
    public boolean allowDamage(ServerPlayer victim, DamageSource source) {
        if (phase != Phase.PLAYING) return false;
        Fighter target = players.get(victim.getUUID());
        if (target == null || !target.active()) return false;
        Entity attacker = source.getEntity();
        if (attacker instanceof ServerPlayer player) {
            Fighter from = players.get(player.getUUID());
            return from != null && from.active() && from.side != target.side;
        }
        return true;
    }

    /** Every monster's left click hits for the same damage, whatever the monster. */
    @Override
    public float modifyDamage(ServerPlayer victim, DamageSource source, float amount) {
        if (source.getDirectEntity() instanceof ServerPlayer attacker && source.is(DamageTypes.PLAYER_ATTACK)) {
            Fighter from = players.get(attacker.getUUID());
            if (from != null && from.side == Side.MONSTER) return settings.monsterDamage();
        }
        return amount;
    }

    @Override
    public boolean allowBlockBreak(ServerPlayer player, BlockPos pos) {
        return false;
    }

    @Override
    public boolean allowBlockPlace(ServerPlayer player, BlockPos pos) {
        return false;
    }

    /** Only humans slide (when the mode allows it): monsters run on their own model and skills. */
    @Override
    public boolean allowSlide(ServerPlayer player) {
        Fighter fighter = players.get(player.getUUID());
        return GameInstance.super.allowSlide(player) && fighter != null && fighter.side == Side.HUMAN;
    }

    // ---- players ----------------------------------------------------------------------------------

    @Override
    public MapDef map() {
        return map;
    }

    /** A downed monster watches its own side first (the humans' moves are no secret to it either way). */
    @Override
    public List<UUID> spectateTargets(UUID viewer) {
        Fighter self = players.get(viewer);
        List<UUID> targets = new ArrayList<>(players.keySet());
        targets.remove(viewer);
        if (self != null) targets.sort(java.util.Comparator.comparing(id -> players.get(id).side != self.side));
        return targets;
    }

    /** A monster waiting to come back: its respawn countdown. */
    @Override
    public int respawnTicks(UUID viewer) {
        Fighter fighter = players.get(viewer);
        return fighter == null || fighter.respawnAt < 0 ? -1 : Math.max(0, fighter.respawnAt - services.server().getTickCount());
    }

    @Nullable
    @Override
    public net.minecraft.world.phys.AABB spectateBounds() {
        return arena == null ? null : net.minecraft.world.phys.AABB.of(arena.cleanupBox(8));
    }

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
    public void removePlayer(UUID id, @Nullable ServerPlayer player, boolean disconnected) {
        Fighter fighter = players.remove(id);
        if (fighter == null) return;
        if (phase == Phase.PLAYING || phase == Phase.ROUND_BREAK) {
            broadcast(Component.translatable("blockland.arena.player_left", fighter.name).withStyle(ChatFormatting.GRAY));
        }
        List<ServerPlayer> viewers = onlinePlayers();
        if (player != null) {
            clearMonster(player);
            viewers.add(player);
            // the leaving player must no longer see the others as monsters either
            for (Fighter other : players.values()) if (other.side == Side.MONSTER) Morphs.show(other.id, null, List.of(player));
            BLNetwork.sendTo(player, InfectionPackets.S2CRole.NONE);
        }
        if (fighter.side == Side.MONSTER) Morphs.show(fighter.id, null, viewers);
        PlayerTeam team = fighter.side == Side.HUMAN ? humanTeam : monsterTeam;
        Scoreboard scoreboard = services.server().getScoreboard();
        if (team != null && scoreboard.getPlayersTeam(fighter.name) == team) scoreboard.removePlayerFromTeam(fighter.name, team);
        if (players.isEmpty()) {
            phase = Phase.FINISHED;
        } else if (phase == Phase.PLAYING || phase == Phase.ROUND_BREAK) {
            if (onlinePlayers().size() < 2) finish(Component.translatable("blockland.infection.reason.not_enough"));
            else checkRound();
        }
    }

    // ---- the Tab board ---------------------------------------------------------------------------------

    /** Humans and monsters with kills, deaths and infections; the round score and what the round waits for. */
    @Override
    public MatchBoard board(MatchBoard.Stats stats) {
        Component status = switch (phase) {
            case LOADING, COUNTDOWN -> Component.translatable("blockland.board.status.starting");
            case PLAYING, ROUND_BREAK -> Component.empty()
                    .append(Component.translatable("blockland.infection.hud.round", round, settings.rounds()))
                    .append(" ").append(Component.literal(String.valueOf(humanRounds)).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(":")).append(Component.literal(String.valueOf(monsterRounds)).withStyle(ChatFormatting.DARK_GREEN))
                    .append(phase == Phase.PLAYING && mothersChosen ? Component.literal("  ").append(Component.translatable(
                            "blockland.arena.hud.time_left", PlayerUtil.formatTime(Math.max(0, surviveSeconds() - roundSeconds)))) : Component.empty());
            case ENDING, FINISHED -> Component.translatable("blockland.board.status.ended");
        };
        List<MatchBoard.Team> teams = new ArrayList<>();
        for (Side side : Side.values()) {
            List<MatchBoard.Row> rows = new ArrayList<>();
            for (Fighter f : players.values()) {
                if (f.side != side) continue;
                rows.add(new MatchBoard.Row(f.id, f.name, f.kills, stats.deaths(f.id), List.of(Component.literal(String.valueOf(f.infections))),
                        f.respawnAt >= 0 ? MatchBoard.State.DEAD : null));
            }
            rows.sort(Comparator.comparingInt((MatchBoard.Row r) -> -r.kills()));
            Integer rgb = side.color.getColor();
            teams.add(new MatchBoard.Team(Component.translatable(side.key), 0xFF000000 | (rgb == null ? 0xFFFFFF : rgb),
                    Component.translatable("blockland.board.count", count(side)), rows));
        }
        return new MatchBoard(Component.translatable("blockland.game.infection"), Component.literal(mode.title + " · " + map.name), status,
                List.of(Component.translatable("blockland.result.column.infections")), teams);
    }

    // ---- rounds and the match result --------------------------------------------------------------

    private void checkRound() {
        if (phase != Phase.PLAYING || !mothersChosen) return;
        int humans = count(Side.HUMAN), monsters = count(Side.MONSTER);
        if (humans == 0) {
            endRound(Side.MONSTER, Component.translatable("blockland.infection.reason.all_infected"));
        } else if (monsters == 0) {
            // every monster left: the outbreak starts again among the survivors, or the last one takes the round
            if (humans >= 2) chooseMothers();
            else endRound(Side.HUMAN, Component.translatable("blockland.infection.reason.monsters_left"));
        }
    }

    private int count(Side side) {
        int count = 0;
        for (Fighter fighter : players.values()) if (fighter.side == side && online(fighter.id) != null) count++;
        return count;
    }

    private void endRound(Side winner, Component reason) {
        if (winner == Side.HUMAN) humanRounds++;
        else monsterRounds++;
        // everyone on the winning side at the end of the round gets the round (infected players count as monsters)
        for (Fighter fighter : players.values()) if (fighter.side == winner && online(fighter.id) != null) fighter.roundWins++;
        Component score = seriesScore();
        boolean decided = humanRounds >= settings.winsNeeded() || monsterRounds >= settings.winsNeeded() || round >= settings.rounds();
        forEachOnline(p -> {
            Fighter fighter = players.get(p.getUUID());
            boolean won = fighter != null && fighter.side == winner;
            PlayerUtil.title(p, Component.translatable("blockland.infection.side_won_round", winner.label(), round)
                            .withStyle(winner.color, ChatFormatting.BOLD),
                    Component.empty().append(reason.copy().withStyle(ChatFormatting.GRAY))
                            .append(Component.literal(" - ").withStyle(ChatFormatting.DARK_GRAY)).append(score), 5, 50, 10);
            PlayerUtil.sound(p, won ? SoundEvents.PLAYER_LEVELUP : SoundEvents.NOTE_BLOCK_PLING.value(), 1f, won ? 1.2f : 0.7f);
            // monsters waiting to respawn stay spectators (not dropped mid-air) until the next round places everyone
            if (!p.isSpectator()) p.setGameMode(GameType.ADVENTURE);
        });
        broadcast(Component.translatable("blockland.infection.round_result", round, winner.label(), reason).withStyle(ChatFormatting.GRAY)
                .append(MatchText.SEPARATOR).append(score));
        if (decided) {
            finish(null);
        } else {
            phase = Phase.ROUND_BREAK;
            phaseTicks = settings.roundBreakSeconds() * 20;
        }
    }

    /** The series is over (decided, all rounds played, or not enough players). */
    private void finish(@Nullable Component cutShort) {
        phase = Phase.ENDING;
        phaseTicks = settings.endingSeconds() * 20;
        Side winner = humanRounds > monsterRounds ? Side.HUMAN : monsterRounds > humanRounds ? Side.MONSTER : null;
        Component headline = winner == null ? Component.translatable("blockland.infection.result.draw")
                : Component.translatable("blockland.infection.result.side_won", Component.translatable(winner.key));
        MutableComponent reason = Component.translatable("blockland.infection.result.reason", humanRounds, monsterRounds, round);
        if (cutShort != null) reason.append(" · ").append(cutShort);
        forEachOnline(p -> {
            p.setGameMode(GameType.ADVENTURE);
            PlayerUtil.title(p, winner == null ? Component.translatable("blockland.infection.result.draw_title").withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD)
                            : Component.translatable("blockland.infection.result.side_won", Component.translatable(winner.key))
                            .withStyle(winner.color, ChatFormatting.BOLD),
                    reason.copy().withStyle(ChatFormatting.GRAY), 5, 80, 20);
        });
        broadcast(MatchText.summaryHeader(ChatFormatting.GREEN)
                .append(headline.copy().withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" (").withStyle(ChatFormatting.GRAY))
                .append(reason.copy().withStyle(ChatFormatting.GRAY))
                .append(Component.literal(")").withStyle(ChatFormatting.GRAY))
                .append("\n")
                .append(Component.translatable("blockland.arena.summary.back_in", settings.endingSeconds()).withStyle(ChatFormatting.GRAY)));
        services.results().publish(result(headline, reason), settings.endingSeconds());
    }

    /** "人类 2 : 1 怪物" in the sides' colours. */
    private Component seriesScore() {
        return Component.empty()
                .append(Component.translatable("blockland.infection.hud.humans", humanRounds).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" : ").withStyle(ChatFormatting.GRAY))
                .append(Component.translatable("blockland.infection.hud.monsters_trailing", monsterRounds).withStyle(ChatFormatting.DARK_GREEN));
    }

    /**
     * The results panel: per player the rounds they won, kills and infections. A player won the match when they were
     * on the winning side in most of the rounds played. Best players first; the top scorer among the winners is MVP.
     */
    private MatchResult result(Component headline, Component reason) {
        int played = Math.max(1, round);
        Comparator<Fighter> byScore = Comparator.comparingInt((Fighter f) -> -(f.roundWins * 3 + f.kills + f.infections));
        List<Fighter> ordered = players.values().stream().sorted(byScore).toList();
        Fighter mvp = ordered.stream().filter(f -> outcome(f, played) == MatchResult.Outcome.VICTORY && f.kills + f.infections > 0)
                .findFirst().orElse(null);
        List<MatchResult.Row> rows = new ArrayList<>();
        for (Fighter f : ordered) {
            Component side = Component.translatable(f.side == Side.HUMAN ? "blockland.infection.side.human"
                    : f.mother ? "blockland.infection.side.mother" : "blockland.infection.side.monster");
            int color = f.side == Side.HUMAN ? HUMAN_COLOR : f.mother ? MOTHER_COLOR : MONSTER_COLOR;
            rows.add(new MatchResult.Row(f.id, f.name, side, color,
                    List.of(Component.literal(f.roundWins + "/" + played), Component.literal(String.valueOf(f.kills)),
                            Component.literal(String.valueOf(f.infections))), outcome(f, played), f == mvp));
        }
        return new MatchResult(mode.id, mode.title, Component.translatable("blockland.game.infection"), headline, reason, matchSeconds,
                List.of(Component.translatable("blockland.result.column.round_wins"), Component.translatable("blockland.result.column.kills"),
                        Component.translatable("blockland.result.column.infections")), rows);
    }

    private static MatchResult.Outcome outcome(Fighter f, int played) {
        int twice = f.roundWins * 2;
        return twice > played ? MatchResult.Outcome.VICTORY : twice == played ? MatchResult.Outcome.DRAW : MatchResult.Outcome.DEFEAT;
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private void setupTeams() {
        Scoreboard scoreboard = services.server().getScoreboard();
        String prefix = GameManager.MATCH_TEAM_PREFIX + id.toString().substring(0, 6) + "_";
        humanTeam = team(scoreboard, prefix + "h", Side.HUMAN);
        monsterTeam = team(scoreboard, prefix + "m", Side.MONSTER);
    }

    private static PlayerTeam team(Scoreboard scoreboard, String name, Side side) {
        PlayerTeam existing = scoreboard.getPlayerTeam(name);
        PlayerTeam team = existing != null ? existing : scoreboard.addPlayerTeam(name);
        team.setColor(side.color);
        team.setAllowFriendlyFire(false);
        team.setPlayerPrefix(Component.literal("[").append(Component.translatable(side.key)).append("] ").withStyle(side.color));
        return team;
    }

    private void joinTeam(ServerPlayer player, Side side) {
        PlayerTeam team = side == Side.HUMAN ? humanTeam : monsterTeam;
        if (team != null) services.server().getScoreboard().addPlayerToTeam(player.getScoreboardName(), team);
    }

    private void sendRole(ServerPlayer player, Fighter fighter) {
        if (fighter.side == Side.HUMAN || fighter.monster == null) {
            BLNetwork.sendTo(player, new InfectionPackets.S2CRole(InfectionPackets.Role.HUMAN, false, "", "", 0, ""));
        } else {
            MonsterCatalog.Skill skill = fighter.monster.skill;
            BLNetwork.sendTo(player, new InfectionPackets.S2CRole(InfectionPackets.Role.MONSTER, fighter.mother, fighter.monster.name, skill.name, skill.cooldown,
                    fighter.monster.id));
        }
    }

    @Nullable
    private ServerPlayer online(UUID id) {
        return services.server().getPlayerList().getPlayer(id);
    }

    private List<ServerPlayer> onlinePlayers() {
        List<ServerPlayer> online = new ArrayList<>();
        for (UUID id : players.keySet()) {
            ServerPlayer player = online(id);
            if (player != null) online.add(player);
        }
        return online;
    }

    private void forEachOnline(Consumer<ServerPlayer> action) {
        onlinePlayers().forEach(action);
    }

    private void broadcast(Component message) {
        forEachOnline(p -> p.sendSystemMessage(message));
    }
}
