package com.blockland.server.anticheat.enforce;

import com.blockland.server.anticheat.AntiCheatService;
import com.blockland.server.anticheat.track.Surroundings;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ClientInformation;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code /bl ac} (permission 2, anti-cheat spec §8): {@code status [player]}, {@code alerts}, {@code exempt <player>
 * <seconds>}, {@code reset <player>}, {@code stats}, {@code probe <pos>} (the ground test at a spot). Players are named, so those waiting in the lobby can be looked up
 * too.
 */
public final class AntiCheatCommands {
    private AntiCheatCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node(Supplier<AntiCheatService> service) {
        SuggestionProvider<CommandSourceStack> names = (ctx, builder) -> {
            AntiCheatService s = service.get();
            List<String> online = s == null ? List.of()
                    : s.players().online().stream().map(id -> s.players().name(id)).filter(n -> n != null).toList();
            return SharedSuggestionProvider.suggest(online, builder);
        };
        return Commands.literal("ac").requires(src -> src.hasPermission(2))
                .then(Commands.literal("status")
                        .executes(ctx -> {
                            ServerPlayer self = ctx.getSource().getPlayerOrException();
                            return status(ctx, service, self.getUUID());
                        })
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(names)
                                .executes(ctx -> player(ctx, service).map(id -> status(ctx, service, id)).orElse(0))))
                .then(Commands.literal("alerts").executes(ctx -> {
                    ServerPlayer self = ctx.getSource().getPlayerOrException();
                    AntiCheatService s = require(service);
                    boolean on = s.toggleAlerts(self.getUUID());
                    ctx.getSource().sendSuccess(() -> Component.translatable(on ? "blockland.anticheat.cmd.alerts_on"
                            : "blockland.anticheat.cmd.alerts_off"), false);
                    return on ? 1 : 0;
                }))
                .then(Commands.literal("exempt").then(Commands.argument("player", StringArgumentType.word()).suggests(names)
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 3600)).executes(ctx -> {
                            int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
                            return player(ctx, service).map(id -> {
                                require(service).exempt(id, seconds);
                                ctx.getSource().sendSuccess(() -> Component.translatable("blockland.anticheat.cmd.exempt",
                                        StringArgumentType.getString(ctx, "player"), seconds), true);
                                return 1;
                            }).orElse(0);
                        }))))
                // every flag of the player goes to the server log with their last moves (following a false positive)
                .then(Commands.literal("trace").then(Commands.argument("player", StringArgumentType.word()).suggests(names)
                        .executes(ctx -> player(ctx, service).map(id -> {
                            boolean on = require(service).toggleTrace(id);
                            ctx.getSource().sendSuccess(() -> Component.translatable(on ? "blockland.anticheat.cmd.trace_on"
                                    : "blockland.anticheat.cmd.trace_off", StringArgumentType.getString(ctx, "player")), true);
                            return on ? 1 : 0;
                        }).orElse(0))))
                .then(Commands.literal("reset").then(Commands.argument("player", StringArgumentType.word()).suggests(names)
                        .executes(ctx -> player(ctx, service).map(id -> {
                            require(service).reset(id);
                            ctx.getSource().sendSuccess(() -> Component.translatable("blockland.anticheat.cmd.reset",
                                    StringArgumentType.getString(ctx, "player")), true);
                            return 1;
                        }).orElse(0))))
                // the ground test the movement checks use, at a spot (a stand-in player in the sender's world)
                .then(Commands.literal("probe").then(Commands.argument("pos", Vec3Argument.vec3(false)).executes(ctx -> {
                    Vec3 at = Vec3Argument.getVec3(ctx, "pos");
                    ServerLevel level = ctx.getSource().getLevel();
                    ServerPlayer probe = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "probe"), ClientInformation.createDefault());
                    probe.setPos(at.x, at.y, at.z);
                    boolean supported = Surroundings.supported(level, probe, at.x, at.y, at.z);
                    AABB below = probe.getBoundingBox().setMinY(at.y - 0.04).setMaxY(at.y);
                    boolean plain = !level.noCollision(below);
                    String blocks = level.getBlockState(BlockPos.containing(at.x, at.y - 0.04, at.z)) + " / "
                            + level.getBlockState(BlockPos.containing(at.x, at.y, at.z));
                    ctx.getSource().sendSuccess(() -> Component.translatable("blockland.anticheat.cmd.probe",
                            String.format(java.util.Locale.ROOT, "%.3f %.3f %.3f", at.x, at.y, at.z), supported, plain, blocks), false);
                    return supported ? 1 : 0;
                })))
                .then(Commands.literal("stats").executes(ctx -> {
                    AntiCheatService.Stats stats = require(service).stats();
                    ctx.getSource().sendSuccess(() -> Component.translatable("blockland.anticheat.cmd.stats", stats.tracked(),
                            String.format(java.util.Locale.ROOT, "%.1f", stats.averageMicros()),
                            String.format(java.util.Locale.ROOT, "%.1f", stats.maxMicros()), stats.queuedReports()), false);
                    return stats.tracked();
                }));
    }

    private static int status(CommandContext<CommandSourceStack> ctx, Supplier<AntiCheatService> service, UUID id) {
        for (Component line : require(service).status(id)) ctx.getSource().sendSuccess(() -> line, false);
        return 1;
    }

    private static Optional<UUID> player(CommandContext<CommandSourceStack> ctx, Supplier<AntiCheatService> service) {
        String name = StringArgumentType.getString(ctx, "player");
        Optional<UUID> id = require(service).players().byName(name);
        if (id.isEmpty()) ctx.getSource().sendFailure(Component.translatable("blockland.anticheat.cmd.no_player", name));
        return id;
    }

    private static AntiCheatService require(Supplier<AntiCheatService> service) {
        AntiCheatService s = service.get();
        if (s == null) throw new IllegalStateException("BlockLand anti-cheat is not running");
        return s;
    }
}
