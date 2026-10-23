package com.blockland.server.map;

import com.blockland.server.arena.Arena;
import com.blockland.server.content.model.MapDef;
import com.blockland.server.game.GameServices;
import com.blockland.server.util.PlayerUtil;
import com.blockland.server.world.PasteJob;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lets admins paste a map into the arena dimension to look up coordinates for spawn points and chests
 * ({@code /bl map preview <id>}, {@code /bl map here}).
 */
public final class MapPreviewService {
    private final GameServices services;
    private final Map<UUID, Arena> previews = new HashMap<>();

    public MapPreviewService(GameServices services) {
        this.services = services;
    }

    public void open(ServerPlayer player, MapDef map) {
        close(player, false);
        player.sendSystemMessage(Component.translatable("blockland.preview.loading", map.name).withStyle(ChatFormatting.YELLOW));
        services.maps().load(map).whenComplete((blueprint, error) -> services.server().execute(() -> {
            if (error != null) {
                player.sendSystemMessage(Component.translatable("blockland.preview.load_failed", String.valueOf(error.getMessage()))
                        .withStyle(ChatFormatting.RED));
                return;
            }
            Arena arena = services.arenas().allocate(blueprint.size());
            previews.put(player.getUUID(), arena);
            services.jobs().submit(new PasteJob(arena.level(), arena.origin(), blueprint)).thenRun(() -> {
                if (previews.get(player.getUUID()) != arena || player.hasDisconnected()) return;
                player.setGameMode(GameType.CREATIVE);
                Vec3 center = arena.center();
                PlayerUtil.teleport(player, arena.level(), new Vec3(center.x, arena.origin().getY() + arena.size().getY() + 2, center.z), 0, 45);
                player.sendSystemMessage(Component.translatable("blockland.preview.ready"));
            });
        }));
    }

    /** Prints the player's position and targeted block relative to the previewed map origin. */
    public void describePosition(ServerPlayer player) {
        Arena arena = previews.get(player.getUUID());
        if (arena == null) {
            player.sendSystemMessage(Component.translatable("blockland.preview.not_previewing").withStyle(ChatFormatting.RED));
            return;
        }
        BlockPos feet = player.blockPosition().subtract(arena.origin());
        player.sendSystemMessage(Component.translatable("blockland.preview.position",
                Component.literal(String.format("x=%d y=%d z=%d", feet.getX(), feet.getY(), feet.getZ())).withStyle(ChatFormatting.WHITE),
                String.format("%.0f", player.getYRot())).withStyle(ChatFormatting.GRAY));
        HitResult hit = player.pick(20.0, 0.0F, false);
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos target = blockHit.getBlockPos().subtract(arena.origin());
            Component block = arena.level().getBlockState(blockHit.getBlockPos()).getBlock().getName();
            player.sendSystemMessage(Component.translatable("blockland.preview.target", block.copy().withStyle(ChatFormatting.WHITE),
                    target.getX(), target.getY(), target.getZ()).withStyle(ChatFormatting.GRAY));
        }
    }

    public void close(ServerPlayer player, boolean toLobby) {
        Arena arena = previews.remove(player.getUUID());
        if (arena == null) return;
        services.arenas().release(arena);
        if (toLobby) services.players().toLobby(player);
    }

    public void closeAll() {
        for (Arena arena : new ArrayList<>(previews.values())) services.arenas().release(arena);
        previews.clear();
    }

    @Nullable
    public Arena previewOf(ServerPlayer player) {
        return previews.get(player.getUUID());
    }
}
