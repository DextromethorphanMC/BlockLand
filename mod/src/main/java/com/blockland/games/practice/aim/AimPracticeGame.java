package com.blockland.games.practice.aim;

import com.blockland.BlockLand;
import com.blockland.entity.BLEntities;
import com.blockland.entity.PracticeTarget;
import com.blockland.games.practice.SoloPracticeGame;
import com.blockland.gun.GunCatalog;
import com.blockland.gun.GunContent;
import com.blockland.gun.GunData;
import com.blockland.gun.GunState;
import com.blockland.server.arena.Arena;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.game.GameServices;
import com.blockland.server.game.MatchBoard;
import com.blockland.server.game.MatchRoster;
import com.blockland.server.util.PlayerUtil;
import com.mojang.math.Transformation;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 枪械瞄准练习: a shooting range built in the arena slot — a firing line, lanes at 10 to 64 m with cover, a back wall —
 * with fake players ({@link PracticeTarget}) strafing, peeking out of cover, crouch-spamming or standing still. The
 * player gets every gun (with their 工匠 builds) and never runs out of reserve ammo: reloads still play, they just take
 * nothing. A killed target falls and a new one steps into its lane after the mode's respawn time. Shots, hits, headshots,
 * kills and kills per minute show in the action bar.
 */
final class AimPracticeGame extends SoloPracticeGame implements PracticeTarget.Listener {
    /** Course size (x across, z down range), the firing line and the lanes' distances from it. */
    private static final int WIDTH = 56, LENGTH = 90, HEIGHT = 24, FIRING_LINE = 8, BACK_WALL = 80;
    private static final int[] LANES = {10, 18, 27, 38, 50, 64};
    /** Reserve ammo handed out per ammo type: never used up, only shown (the HUD and client reloads count it). */
    private static final int AMMO_STACKS = 3;
    /** Targets face the firing line (down range is +Z). */
    private static final float FACE = 180;

    /** A place on the range for one target: lane distance, centre across, how far it moves, its drill, its cover. */
    private record Slot(int lane, double x, double halfWidth, PracticeTarget.Drill drill, double peek, Cover cover) {
    }

    private enum Cover { NONE, TALL, LOW }

    private static final List<Slot> SLOTS = List.of(
            new Slot(10, 20, 4, PracticeTarget.Drill.STRAFE, 0, Cover.NONE),
            new Slot(10, 36, 4, PracticeTarget.Drill.STRAFE, 0, Cover.NONE),
            new Slot(18, 14, 0, PracticeTarget.Drill.PEEK, 2.4, Cover.TALL),
            new Slot(18, 42, 0, PracticeTarget.Drill.PEEK, -2.4, Cover.TALL),
            new Slot(27, 28, 3, PracticeTarget.Drill.CROUCH, 0, Cover.LOW),
            new Slot(38, 28, 9, PracticeTarget.Drill.STRAFE, 0, Cover.NONE),
            new Slot(50, 20, 0, PracticeTarget.Drill.PEEK, 2.4, Cover.TALL),
            new Slot(64, 34, 0, PracticeTarget.Drill.STILL, 0, Cover.NONE),
            new Slot(50, 38, 5, PracticeTarget.Drill.STRAFE, 0, Cover.NONE),
            new Slot(27, 12, 0, PracticeTarget.Drill.STILL, 0, Cover.NONE),
            new Slot(38, 10, 3, PracticeTarget.Drill.CROUCH, 0, Cover.NONE),
            new Slot(64, 20, 5, PracticeTarget.Drill.STRAFE, 0, Cover.NONE));

    private final AimPracticeSettings settings;
    private final RandomSource random = RandomSource.create();
    private final List<Slot> slots = new ArrayList<>();
    private final List<Lane> lanes = new ArrayList<>();
    private final List<Entity> labels = new ArrayList<>();
    private int targetNumber;

    // the player's stats
    private int shots, hits, headshots, kills;
    private float damage;
    private long lastHitTick = -1;
    private int lastSlot = -1;
    @Nullable
    private Item lastGun;
    private int lastAmmo;

    /** A slot in use: its current target and when the next one steps in. */
    private static final class Lane {
        final Slot slot;
        @Nullable
        PracticeTarget target;
        int respawnAt = -1;

        Lane(Slot slot) {
            this.slot = slot;
        }
    }

    AimPracticeGame(GameServices services, GameModeDef mode, AimPracticeSettings settings, MatchRoster roster) {
        super(services, mode, roster, new Vec3i(WIDTH, HEIGHT, LENGTH));
        this.settings = settings;
        slots.addAll(SLOTS.subList(0, Math.min(SLOTS.size(), settings.targetCount())));
        // more targets than prepared places: extra strafers spread over the open lanes
        for (int i = SLOTS.size(); i < settings.targetCount(); i++) {
            slots.add(new Slot(LANES[i % LANES.length], 8 + random.nextInt(WIDTH - 16), 3, PracticeTarget.Drill.STRAFE, 0, Cover.NONE));
        }
    }

    @Override
    protected Component gameName() {
        return Component.translatable("blockland.game.aim_practice");
    }

    @Override
    public boolean infiniteAmmo(ServerPlayer player) {
        return true;
    }

    @Override
    public boolean infiniteEquipment(ServerPlayer player) {
        return true;
    }

    // ---- course --------------------------------------------------------------------------

    @Override
    protected Vec3 spawnPoint() {
        return arena().toWorld(WIDTH / 2.0, 1, FIRING_LINE - 3.5);
    }

    @Override
    protected void build(Arena arena) {
        ServerLevel level = arena.level();
        BlockState floor = Blocks.GRAY_CONCRETE.defaultBlockState(), line = Blocks.WHITE_CONCRETE.defaultBlockState();
        BlockState light = Blocks.SEA_LANTERN.defaultBlockState(), wall = Blocks.STONE_BRICKS.defaultBlockState();
        for (int x = 0; x < WIDTH; x++) {
            for (int z = 0; z <= BACK_WALL + 1; z++) {
                BlockState block = floor;
                if (z == FIRING_LINE) block = Blocks.YELLOW_CONCRETE.defaultBlockState();
                else if (isLaneLine(z)) block = line;
                else if (x % 8 == 4 && z % 8 == 4) block = light;
                set(level, arena.toWorld(x, 0, z), block);
            }
        }
        // side walls, the wall behind the firing booth, and a tall back wall to stop the eye (and the shots)
        for (int z = 0; z <= BACK_WALL + 1; z++) {
            for (int y = 1; y <= 5; y++) {
                BlockState block = y == 5 && z % 6 == 3 ? light : wall;
                set(level, arena.toWorld(0, y, z), block);
                set(level, arena.toWorld(WIDTH - 1, y, z), block);
            }
        }
        for (int x = 0; x < WIDTH; x++) {
            for (int y = 1; y <= 5; y++) set(level, arena.toWorld(x, y, 0), wall);
            for (int y = 1; y <= 14; y++) {
                BlockState block = y == 14 ? Blocks.GLOWSTONE.defaultBlockState()
                        : (x + y) % 2 == 0 ? Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState() : Blocks.BLACKSTONE.defaultBlockState();
                set(level, arena.toWorld(x, y, BACK_WALL), block);
                set(level, arena.toWorld(x, y, BACK_WALL + 1), Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
            }
        }
        // cover: a tall wall to peek around, a low wall to crouch behind (one block in front of the target)
        for (Slot slot : slots) {
            int z = FIRING_LINE + slot.lane() - 1;
            int x0 = (int) Math.floor(slot.x());
            switch (slot.cover()) {
                case TALL -> {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int y = 1; y <= 2; y++) set(level, arena.toWorld(x0 + dx, y, z), wall);
                    }
                }
                case LOW -> {
                    int reach = (int) Math.ceil(slot.halfWidth()) + 1;
                    for (int dx = -reach; dx <= reach; dx++) set(level, arena.toWorld(x0 + dx, 1, z), Blocks.SMOOTH_STONE.defaultBlockState());
                }
                case NONE -> {
                }
            }
        }
    }

    private static boolean isLaneLine(int z) {
        for (int lane : LANES) if (z == FIRING_LINE + lane + 1) return true;
        return false;
    }

    private static void set(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }

    // ---- player --------------------------------------------------------------------------

    @Override
    protected void onPlayerReady(ServerPlayer player) {
        services.kits().apply(player, mode);
        for (GunData.Gun gun : GunCatalog.get().guns()) {
            Item item = BuiltInRegistries.ITEM.getValue(BlockLand.id(gun.id));
            if (item != Items.AIR) player.getInventory().add(new ItemStack(item));
        }
        for (GunData.Ammo ammo : GunCatalog.get().ammoTypes()) {
            Item item = GunContent.ammoItem(ammo.id);
            if (item == null) continue;
            for (int i = 0; i < AMMO_STACKS; i++) player.getInventory().add(new ItemStack(item, item.getDefaultMaxStackSize()));
        }
        services.kits().refit(player);
        player.getInventory().setSelectedSlot(0);
        PlayerUtil.title(player, gameName().copy().withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                Component.translatable("blockland.practice.aim.subtitle").withStyle(ChatFormatting.GRAY), 5, 50, 15);
        player.sendSystemMessage(Component.translatable("blockland.practice.aim.welcome").withStyle(ChatFormatting.GRAY));
        if (lanes.isEmpty()) {
            for (Slot slot : slots) lanes.add(new Lane(slot));
            for (Lane lane : lanes) spawn(lane);
            placeLabels();
        }
    }

    @Override
    protected void onPlayingTick(ServerPlayer player) {
        countShots(player);
        for (Lane lane : lanes) {
            PracticeTarget target = lane.target;
            if (target != null && (target.isRemoved() || target.isDeadOrDying())) {
                lane.target = null;
                lane.respawnAt = playingTicks() + Math.max(10, Math.round(settings.respawnSeconds() * 20));
            }
            if (lane.target == null && playingTicks() >= lane.respawnAt) spawn(lane);
        }
        if (playingTicks() % 10 == 0) PlayerUtil.actionBar(player, hud());
    }

    /** Rounds fired: the held gun's magazine going down (reloads only ever raise it). */
    private void countShots(ServerPlayer player) {
        int slot = player.getInventory().getSelectedSlot();
        ItemStack stack = player.getMainHandItem();
        GunState state = GunContent.stateOf(stack);
        if (state == null) {
            lastGun = null;
            return;
        }
        if (slot == lastSlot && stack.getItem() == lastGun && state.ammo() < lastAmmo) shots += lastAmmo - state.ammo();
        lastSlot = slot;
        lastGun = stack.getItem();
        lastAmmo = state.ammo();
    }

    @Override
    public void onHit(PracticeTarget target, ServerPlayer attacker, float amount, boolean headshot, boolean killed) {
        if (!attacker.getUUID().equals(playerId())) return;
        damage += amount;
        // a round's pellets (or a burst landing in one tick) count as one hit
        long tick = target.level().getGameTime();
        if (tick != lastHitTick) {
            hits++;
            if (headshot) headshots++;
            lastHitTick = tick;
        }
        if (killed) {
            kills++;
            PlayerUtil.sound(attacker, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.6f, headshot ? 1.6f : 1.2f);
        }
    }

    private Component hud() {
        // a hit can land in the same tick as the shot counted after it
        int fired = Math.max(shots, hits);
        float minutes = Math.max(1 / 6f, playingTicks() / 1200f);
        return Component.translatable("blockland.practice.aim.hud",
                Component.literal(String.valueOf(fired)).withStyle(ChatFormatting.WHITE),
                Component.literal(percent(hits, fired)).withStyle(ChatFormatting.GREEN),
                Component.literal(percent(headshots, hits)).withStyle(ChatFormatting.GOLD),
                Component.literal(String.valueOf(kills)).withStyle(ChatFormatting.RED),
                Component.literal(String.format(Locale.ROOT, "%.1f", kills / minutes)).withStyle(ChatFormatting.AQUA),
                Component.literal(String.valueOf(Math.round(damage))).withStyle(ChatFormatting.WHITE))
                .withStyle(ChatFormatting.GRAY);
    }

    /** The Tab board: the drill's stats as columns, and how many targets are standing. */
    @Override
    public MatchBoard board(MatchBoard.Stats stats) {
        int fired = Math.max(shots, hits);
        float minutes = Math.max(1 / 6f, playingTicks() / 1200f);
        long standing = lanes.stream().filter(l -> l.target != null && l.target.isAlive()).count();
        MatchBoard.Row row = new MatchBoard.Row(playerId(), stats.name(playerId()), kills, 0, List.of(
                Component.literal(String.valueOf(fired)),
                Component.literal(percent(hits, fired)),
                Component.literal(percent(headshots, hits)),
                Component.literal(String.format(Locale.ROOT, "%.1f", kills / minutes)),
                Component.literal(String.valueOf(Math.round(damage)))), MatchBoard.State.ALIVE);
        return new MatchBoard(gameName(), Component.literal(mode.title == null ? "" : mode.title),
                Component.translatable("blockland.arena.hud.time_elapsed", PlayerUtil.formatTime(playingTicks() / 20)),
                List.of(Component.translatable("blockland.practice.aim.column.shots"),
                        Component.translatable("blockland.practice.aim.column.accuracy"),
                        Component.translatable("blockland.practice.aim.column.headshots"),
                        Component.translatable("blockland.practice.aim.column.kpm"),
                        Component.translatable("blockland.practice.aim.column.damage")),
                List.of(new MatchBoard.Team(Component.translatable("blockland.board.players"), 0xFFFFA726,
                        Component.translatable("blockland.practice.aim.targets_standing", standing, lanes.size()), List.of(row))));
    }

    private static String percent(int part, int whole) {
        return whole <= 0 ? "-" : Math.round(100f * part / whole) + "%";
    }

    // ---- targets -------------------------------------------------------------------------

    private void spawn(Lane lane) {
        Slot slot = lane.slot;
        ServerLevel level = arena().level();
        PracticeTarget target = BLEntities.PRACTICE_TARGET.get().create(level, EntitySpawnReason.EVENT);
        if (target == null) return;
        PracticeTarget.Drill drill = settings.moving() ? slot.drill() : PracticeTarget.Drill.STILL;
        // a still target behind cover stands where it can be seen
        double x = slot.x() + (!settings.moving() && slot.drill() == PracticeTarget.Drill.PEEK ? slot.peek() : 0);
        Vec3 home = arena().toWorld(x, 1, FIRING_LINE + slot.lane() + 0.5);
        target.setPos(home.x, home.y, home.z);
        target.setDrill(drill, home, slot.halfWidth(), slot.peek(), FACE, settings.targetHealth());
        target.setSkin(random.nextInt(64));
        target.setCustomName(Component.translatable("blockland.practice.target_name", ++targetNumber));
        target.setCustomNameVisible(true);
        if (settings.armor()) {
            target.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            target.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            target.setDropChance(EquipmentSlot.HEAD, 0);
            target.setDropChance(EquipmentSlot.CHEST, 0);
        }
        target.setListener(this);
        level.addFreshEntity(target);
        lane.target = target;
        lane.respawnAt = -1;
    }

    /** Distance signs over the lane lines, against the left wall. */
    private void placeLabels() {
        ServerLevel level = arena().level();
        for (int lane : LANES) {
            Vec3 at = arena().toWorld(2.5, 2.2, FIRING_LINE + lane + 1.5);
            Entity label = textDisplay(level, Component.translatable("blockland.practice.aim.distance", lane).withStyle(ChatFormatting.YELLOW));
            if (label == null) continue;
            label.setPos(at.x, at.y, at.z);
            level.addFreshEntity(label);
            labels.add(label);
        }
    }

    /** A text display facing the viewer (its text setters are private: built from its saved form). */
    @Nullable
    private static Entity textDisplay(ServerLevel level, Component text) {
        try {
            CompoundTag tag = new CompoundTag();
            tag.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.TEXT_DISPLAY).toString());
            var ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            tag.put("text", ComponentSerialization.CODEC.encodeStart(ops, text).getOrThrow());
            tag.putString("billboard", "center");
            tag.putInt("background", 0x66000000);
            tag.putBoolean("shadow", true);
            tag.put("transformation", Transformation.EXTENDED_CODEC.encodeStart(NbtOps.INSTANCE,
                    new Transformation(null, null, new Vector3f(2.5f, 2.5f, 2.5f), null)).getOrThrow());
            return EntityType.create(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag), level,
                    EntitySpawnReason.EVENT).orElse(null);
        } catch (RuntimeException e) {
            BlockLand.LOGGER.warn("[BlockLand] Practice range label failed: {}", e.toString());
            return null;
        }
    }

    @Override
    protected void onDispose() {
        for (Lane lane : lanes) {
            if (lane.target != null) lane.target.discard();
        }
        lanes.clear();
        labels.forEach(Entity::discard);
        labels.clear();
    }
}
