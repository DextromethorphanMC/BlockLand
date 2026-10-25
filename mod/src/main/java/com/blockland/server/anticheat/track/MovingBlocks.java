package com.blockland.server.anticheat.track;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;

/**
 * Pistons that moved lately (anti-cheat spec §6). The blocks a piston pushes or pulls carry the players in their way
 * along, and a slime block among them launches them — on the server and on their client alike, faster than any physics
 * the predictor knows. A player near a piston that moved within their latency window is not judged on either axis for
 * those ticks; the next judged move carries on from where the push left them. A handful of entries; server thread only.
 */
public final class MovingBlocks {
    /** A piston moves blocks up to 12 away from its face: anything within this many blocks of it may have been pushed. */
    private static final int REACH = 14;
    /** Ticks a push lasts (the moving blocks' two steps), plus one. */
    private static final int PUSH_TICKS = 4;
    /** Entries older than this are dropped (longer than any latency window). */
    private static final int KEEP_TICKS = 100;
    private static final int MAX_MOVES = 256;

    private record Move(ResourceKey<Level> level, BlockPos pos, int tick) {
    }

    private final ArrayDeque<Move> moves = new ArrayDeque<>();

    /** A piston at {@code pos} started to extend or retract. */
    public void moved(Level level, BlockPos pos, int tick) {
        prune(tick);
        if (moves.size() >= MAX_MOVES) moves.removeFirst();
        moves.addLast(new Move(level.dimension(), pos.immutable(), tick));
    }

    /** Whether a piston moved within reach of {@code x, y, z} in the last {@code windowTicks} (plus the push itself). */
    public boolean near(Level level, double x, double y, double z, int tick, int windowTicks) {
        if (moves.isEmpty()) return false;
        prune(tick);
        for (Move move : moves) {
            if (tick - move.tick > windowTicks + PUSH_TICKS || move.level != level.dimension()) continue;
            BlockPos p = move.pos;
            if (Math.abs(p.getX() + 0.5 - x) <= REACH && Math.abs(p.getY() + 0.5 - y) <= REACH && Math.abs(p.getZ() + 0.5 - z) <= REACH) return true;
        }
        return false;
    }

    public void clear() {
        moves.clear();
    }

    private void prune(int tick) {
        while (!moves.isEmpty() && tick - moves.peekFirst().tick > KEEP_TICKS) moves.removeFirst();
    }
}
