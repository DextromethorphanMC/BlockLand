package com.blockland.games.practice.bridge;

import com.blockland.games.practice.SoloPracticeGame;
import com.blockland.server.arena.Arena;
import com.blockland.server.content.model.GameModeDef;
import com.blockland.server.game.GameServices;
import com.blockland.server.game.MatchRoster;
import com.blockland.server.util.PlayerUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 搭路练习: a spawn island and a goal island across a gap of void, endless blocks of one kind. The timer starts when the
 * player leaves the spawn island (or places the first block) and stops on the goal island; falling off puts them back
 * at the start and clears their bridge, so every run starts from nothing. Only blocks inside the lane may be placed,
 * and only the player's own blocks broken.
 */
final class BridgePracticeGame extends SoloPracticeGame {
    /** Height of the islands' top surface above the arena origin. */
    private static final int ISLAND_Y = 24;
    /** Length of each island along the course, and free space around the course. */
    private static final int ISLAND = 7, MARGIN_X = 6, MARGIN_Z = 4, HEIGHT = 40;
    /** Blocks the lane reaches past the islands on each side, and how high a bridge may climb. */
    private static final int LANE_SLACK = 2, CLIMB = 12;
    /** Ticks between reaching the goal and the next run. */
    private static final int FINISH_PAUSE = 50;

    private enum Run { READY, RUNNING, DONE }

    private final BridgePracticeSettings settings;
    private final Set<BlockPos> placed = new LinkedHashSet<>();
    private Run run = Run.READY;
    private int runTicks;
    private int pauseTicks;
    private int bestTicks = -1;
    private int blocksThisRun;

    BridgePracticeGame(GameServices services, GameModeDef mode, BridgePracticeSettings settings, MatchRoster roster) {
        super(services, mode, roster, new Vec3i(settings.courseWidth() + 2 * MARGIN_X, HEIGHT,
                2 * MARGIN_Z + 2 * ISLAND + settings.gapLength()));
        this.settings = settings;
    }

    // ---- course --------------------------------------------------------------------------

    private int laneX0() {
        return MARGIN_X;
    }

    private int laneX1() {
        return MARGIN_X + settings.courseWidth();
    }

    private int spawnZ0() {
        return MARGIN_Z;
    }

    private int gapZ0() {
        return MARGIN_Z + ISLAND;
    }

    private int goalZ0() {
        return gapZ0() + settings.gapLength();
    }

    private int goalZ1() {
        return goalZ0() + ISLAND;
    }

    @Override
    protected Component gameName() {
        return Component.translatable("blockland.game.bridge_practice");
    }

    @Override
    protected void build(Arena arena) {
        island(arena, spawnZ0(), Blocks.SMOOTH_QUARTZ.defaultBlockState(), Blocks.POLISHED_ANDESITE.defaultBlockState());
        island(arena, goalZ0(), Blocks.GOLD_BLOCK.defaultBlockState(), Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
        ServerLevel level = arena.level();
        int mid = (laneX0() + laneX1()) / 2;
        // the start line along the spawn island's front edge, the goal's centre stone and its corner posts
        for (int x = laneX0(); x < laneX1(); x++) set(level, arena.toWorld(x, ISLAND_Y, gapZ0() - 1), Blocks.SEA_LANTERN.defaultBlockState());
        set(level, arena.toWorld(mid, ISLAND_Y, goalZ0() + ISLAND / 2), Blocks.EMERALD_BLOCK.defaultBlockState());
        for (int x : new int[]{laneX0(), laneX1() - 1}) {
            for (int z : new int[]{goalZ0(), goalZ1() - 1}) {
                set(level, arena.toWorld(x, ISLAND_Y + 1, z), Blocks.END_ROD.defaultBlockState());
            }
        }
        // distance posts beside the gap, well below the lane: a light every 4 blocks, brighter every 16
        for (int z = gapZ0() + 3; z < goalZ0(); z += 4) {
            boolean major = (z - gapZ0() + 1) % 16 == 0;
            BlockState light = major ? Blocks.GLOWSTONE.defaultBlockState() : Blocks.SEA_LANTERN.defaultBlockState();
            for (int x : new int[]{laneX0() - LANE_SLACK - 2, laneX1() + LANE_SLACK + 1}) {
                set(level, arena.toWorld(x, ISLAND_Y - 6, z), light);
                set(level, arena.toWorld(x, ISLAND_Y - 7, z), Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState());
            }
        }
    }

    /** An island: the top surface with a border, tapering into the void below. */
    private void island(Arena arena, int z0, BlockState top, BlockState border) {
        ServerLevel level = arena.level();
        for (int depth = 0; depth < 4; depth++) {
            int inset = depth == 0 ? 0 : depth;
            for (int x = laneX0() + inset; x < laneX1() - inset; x++) {
                for (int z = z0 + Math.min(inset, 2); z < z0 + ISLAND - Math.min(inset, 2); z++) {
                    BlockState state;
                    if (depth == 0) {
                        boolean edge = x == laneX0() || x == laneX1() - 1 || z == z0 || z == z0 + ISLAND - 1;
                        state = edge ? border : top;
                    } else {
                        state = depth == 1 ? Blocks.STONE.defaultBlockState() : Blocks.COBBLED_DEEPSLATE.defaultBlockState();
                    }
                    set(level, arena.toWorld(x, ISLAND_Y - depth, z), state);
                }
            }
        }
    }

    private static void set(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }

    @Override
    protected Vec3 spawnPoint() {
        return arena().toWorld((laneX0() + laneX1()) / 2.0, ISLAND_Y + 1, spawnZ0() + 1.5);
    }

    @Override
    protected float spawnYaw() {
        return 0; // facing +Z, towards the goal
    }

    @Override
    protected GameType playingGameMode() {
        return GameType.SURVIVAL;
    }

    @Override
    protected int voidDepth() {
        // the base class measures from the arena origin; the islands sit ISLAND_Y above it
        return settings.voidDepth() - ISLAND_Y;
    }

    /** Inside the lane: across the islands and the gap, a little to the sides, down to the void and a bit above. */
    private boolean inLane(BlockPos pos) {
        BlockPos rel = pos.subtract(arena().origin());
        return rel.getX() >= laneX0() - LANE_SLACK && rel.getX() < laneX1() + LANE_SLACK
                && rel.getY() > ISLAND_Y - settings.voidDepth() && rel.getY() <= ISLAND_Y + CLIMB
                && rel.getZ() >= spawnZ0() && rel.getZ() < goalZ1();
    }

    // ---- players -------------------------------------------------------------------------

    @Override
    protected void onPlayerReady(ServerPlayer player) {
        player.getInventory().setItem(0, new ItemStack(settings.block(), settings.block().getDefaultMaxStackSize()));
        player.getInventory().setSelectedSlot(0);
        PlayerUtil.title(player, gameName().copy().withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                Component.translatable("blockland.practice.bridge.goal_hint").withStyle(ChatFormatting.GRAY), 5, 50, 15);
        resetRun();
    }

    @Override
    protected void onRespawned(ServerPlayer player) {
        boolean wasRunning = run == Run.RUNNING;
        clearBridge();
        resetRun();
        if (wasRunning) {
            PlayerUtil.actionBar(player, Component.translatable("blockland.practice.bridge.fell").withStyle(ChatFormatting.RED));
            PlayerUtil.sound(player, SoundEvents.NOTE_BLOCK_BASS.value(), 0.8f, 0.7f);
        }
    }

    @Override
    protected void onPlayingTick(ServerPlayer player) {
        refill(player);
        if (playingTicks() % 20 == 0) {
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(5f);
        }
        switch (run) {
            case READY -> {
                if (player.getZ() - arena().origin().getZ() >= gapZ0()) startRun();
                if (playingTicks() % 10 == 0) hud(player);
            }
            case RUNNING -> {
                runTicks++;
                if (onGoal(player)) {
                    finish(player);
                } else if (runTicks % 2 == 0) {
                    hud(player);
                }
            }
            case DONE -> {
                if (--pauseTicks <= 0) {
                    clearBridge();
                    resetRun();
                    respawn(player);
                }
            }
        }
    }

    private void startRun() {
        run = Run.RUNNING;
        runTicks = 0;
    }

    private void resetRun() {
        run = Run.READY;
        runTicks = 0;
        blocksThisRun = 0;
    }

    private boolean onGoal(ServerPlayer player) {
        if (!player.onGround()) return false;
        Vec3 rel = player.position().subtract(Vec3.atLowerCornerOf(arena().origin()));
        return rel.z >= goalZ0() && rel.z < goalZ1() && rel.x >= laneX0() && rel.x < laneX1()
                && Math.abs(rel.y - (ISLAND_Y + 1)) < 0.6;
    }

    private void finish(ServerPlayer player) {
        run = Run.DONE;
        pauseTicks = FINISH_PAUSE;
        boolean record = settings.timed() && (bestTicks < 0 || runTicks < bestTicks);
        if (record) bestTicks = runTicks;
        Component subtitle;
        if (!settings.timed()) {
            subtitle = Component.translatable("blockland.practice.bridge.blocks", blocksThisRun).withStyle(ChatFormatting.GRAY);
        } else if (record) {
            subtitle = Component.translatable("blockland.practice.bridge.new_best", seconds(runTicks)).withStyle(ChatFormatting.GOLD);
        } else {
            subtitle = Component.translatable("blockland.practice.bridge.finish_time", seconds(runTicks)).withStyle(ChatFormatting.WHITE);
        }
        PlayerUtil.title(player, Component.translatable("blockland.practice.bridge.finish").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                subtitle, 0, 40, 10);
        PlayerUtil.sound(player, record ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.PLAYER_LEVELUP, 0.9f, 1f);
        hud(player);
    }

    private void hud(ServerPlayer player) {
        MutableComponent line = Component.empty();
        if (run == Run.READY) {
            line.append(Component.translatable("blockland.practice.bridge.hud.ready").withStyle(ChatFormatting.GREEN));
        } else if (settings.timed()) {
            line.append(Component.translatable("blockland.practice.bridge.hud.time", seconds(runTicks)).withStyle(ChatFormatting.YELLOW));
        } else {
            line.append(Component.translatable("blockland.practice.bridge.hud.distance", Math.max(0,
                    (int) Math.floor(player.getZ() - arena().origin().getZ()) - gapZ0()), settings.gapLength()).withStyle(ChatFormatting.YELLOW));
        }
        if (settings.timed() && bestTicks >= 0) {
            line.append(Component.literal("  |  ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.translatable("blockland.practice.bridge.hud.best", seconds(bestTicks)).withStyle(ChatFormatting.GOLD));
        }
        line.append(Component.literal("  |  ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.translatable("blockland.practice.bridge.blocks", blocksThisRun).withStyle(ChatFormatting.AQUA));
        PlayerUtil.actionBar(player, line);
    }

    private static Component seconds(int ticks) {
        return Component.translatable("blockland.practice.seconds", String.format(Locale.ROOT, "%.2f", ticks / 20.0));
    }

    /** Endless blocks: every stack of the practice block in the hotbar stays full, and the first slot never runs dry. */
    private void refill(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        int full = settings.block().getDefaultMaxStackSize();
        boolean any = false;
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.is(settings.block())) continue;
            any = true;
            if (stack.getCount() < full) stack.setCount(full);
        }
        ItemStack offhand = player.getOffhandItem();
        if (offhand.is(settings.block()) && offhand.getCount() < full) offhand.setCount(full);
        if (!any && inventory.getItem(0).isEmpty()) inventory.setItem(0, new ItemStack(settings.block(), full));
    }

    private void clearBridge() {
        ServerLevel level = arena().level();
        for (BlockPos pos : placed) {
            if (!level.getBlockState(pos).isAir()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        placed.clear();
    }

    // ---- block rules ---------------------------------------------------------------------

    @Override
    public boolean allowBlockPlace(ServerPlayer player, BlockPos pos) {
        if (run == Run.DONE || !inLane(pos)) return false;
        if (placed.add(pos.immutable())) blocksThisRun++;
        if (run == Run.READY) startRun();
        return true;
    }

    @Override
    public boolean allowBlockBreak(ServerPlayer player, BlockPos pos) {
        // the player's own block goes without a drop (the hotbar refills anyway); the vanilla break is denied
        if (placed.remove(pos)) player.level().destroyBlock(pos, false, player);
        return false;
    }
}
