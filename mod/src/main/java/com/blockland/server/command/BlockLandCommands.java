package com.blockland.server.command;

import com.blockland.server.BlockLandServer;
import com.blockland.server.config.BlockLandConfig;
import com.blockland.server.content.ContentSnapshot;
import com.blockland.server.content.model.MapDef;
import com.blockland.server.game.GameInstance;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.function.Supplier;

/** {@code /bl} (alias {@code /blockland}). */
public final class BlockLandCommands {
    private BlockLandCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<BlockLandServer> server) {
        SuggestionProvider<CommandSourceStack> modeIds = (ctx, builder) -> {
            BlockLandServer s = server.get();
            return SharedSuggestionProvider.suggest(s == null ? List.of() : s.content.snapshot().modes.stream().map(m -> m.id).toList(), builder);
        };
        SuggestionProvider<CommandSourceStack> mapIds = (ctx, builder) -> {
            BlockLandServer s = server.get();
            return SharedSuggestionProvider.suggest(s == null ? List.of() : s.content.snapshot().maps.stream().map(m -> m.id).toList(), builder);
        };

        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("bl")
                // the lobby has no world: a player standing in the world (an operator) goes back to it
                .then(Commands.literal("lobby").executes(ctx -> run(ctx, server, (s, p) -> {
                    if (s.games.isPlaying(p)) s.games.leave(p, false);
                    else s.sessions.toLobby(p);
                    return 1;
                })))
                .then(Commands.literal("join").then(Commands.argument("mode", StringArgumentType.word()).suggests(modeIds)
                        .executes(ctx -> run(ctx, server, (s, p) -> {
                            s.lobby.joinQueue(p.getUUID(), StringArgumentType.getString(ctx, "mode"), "", "", List.of());
                            return s.matchmaking.ticketOf(p.getUUID()) != null ? 1 : 0;
                        }))))
                .then(Commands.literal("leave").executes(ctx -> run(ctx, server, (s, p) -> {
                    if (s.games.isPlaying(p)) {
                        s.games.leave(p, false);
                    } else {
                        s.matchmaking.leave(p.getUUID(), true);
                        s.sessions.toLobby(p);
                    }
                    return 1;
                })))
                .then(Commands.literal("reload").requires(src -> src.hasPermission(2)).executes(ctx -> {
                    BlockLandServer s = require(ctx, server);
                    CommandSourceStack source = ctx.getSource();
                    s.content.refresh().thenAccept(changed -> s.server.execute(() -> source.sendSuccess(() -> Component.literal(
                            changed ? "§a配置已更新到 v" + s.content.snapshot().version : "§7配置无变化 (v" + s.content.snapshot().version + ")"), false)));
                    return 1;
                }))
                .then(Commands.literal("status").requires(src -> src.hasPermission(2)).executes(ctx -> {
                    BlockLandServer s = require(ctx, server);
                    ContentSnapshot snapshot = s.content.snapshot();
                    ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                            "§b[BlockLand] §7后端 §f%s §7| 配置 v%d | 模式 %d | 地图 %d | 进行中对局 %d | 已用竞技场 %d",
                            BlockLandConfig.BACKEND_URL.get(), snapshot.version, snapshot.modes.size(), snapshot.maps.size(),
                            s.games.games().size(), s.arenas.activeSlots())), false);
                    return 1;
                }))
                .then(Commands.literal("start").requires(src -> src.hasPermission(2))
                        .then(Commands.argument("mode", StringArgumentType.word()).suggests(modeIds).executes(ctx -> {
                            BlockLandServer s = require(ctx, server);
                            Component error = s.matchmaking.forceStart(StringArgumentType.getString(ctx, "mode"));
                            if (error != null) ctx.getSource().sendFailure(error);
                            else ctx.getSource().sendSuccess(() -> Component.literal("§a已强制开始对局"), true);
                            return error == null ? 1 : 0;
                        })))
                .then(Commands.literal("games").requires(src -> src.hasPermission(2)).executes(ctx -> {
                    BlockLandServer s = require(ctx, server);
                    List<GameInstance> games = s.games.games();
                    if (games.isEmpty()) ctx.getSource().sendSuccess(() -> Component.literal("§7当前没有进行中的对局"), false);
                    for (int i = 0; i < games.size(); i++) {
                        GameInstance game = games.get(i);
                        int index = i;
                        ctx.getSource().sendSuccess(() -> Component.literal("§7#" + index + " §f" + game.mode().title
                                + " §7玩家 " + game.participants().size()), false);
                    }
                    return games.size();
                }))
                .then(Commands.literal("queue").requires(src -> src.hasPermission(2))
                        .then(Commands.argument("mode", StringArgumentType.word()).suggests(modeIds).executes(ctx -> {
                            BlockLandServer s = require(ctx, server);
                            String modeId = StringArgumentType.getString(ctx, "mode");
                            List<java.util.UUID> order = s.matchmaking.order(modeId);
                            StringBuilder text = new StringBuilder("§b" + modeId + " §7队列（" + order.size() + "，按开局顺序）:");
                            for (int i = 0; i < order.size(); i++) {
                                String name = s.sessions.name(order.get(i));
                                text.append("\n §7").append(i + 1).append(". ")
                                        .append(s.matchmaking.hasPriority(order.get(i)) ? "§6★ " : "§f")
                                        .append(name == null ? order.get(i).toString() : name);
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal(text.toString()), false);
                            return order.size();
                        })))
                .then(Commands.literal("stopall").requires(src -> src.hasPermission(2)).executes(ctx -> {
                    BlockLandServer s = require(ctx, server);
                    int count = s.games.games().size();
                    s.games.abortAll();
                    ctx.getSource().sendSuccess(() -> Component.literal("§e已中止 " + count + " 场对局"), true);
                    return count;
                }))
                .then(Commands.literal("map").requires(src -> src.hasPermission(2))
                        .then(Commands.literal("preview").then(Commands.argument("map", StringArgumentType.word()).suggests(mapIds)
                                .executes(ctx -> run(ctx, server, (s, p) -> {
                                    String id = StringArgumentType.getString(ctx, "map");
                                    MapDef map = s.content.snapshot().map(id).orElse(null);
                                    if (map == null) {
                                        p.sendSystemMessage(Component.literal("§c地图不存在: " + id));
                                        return 0;
                                    }
                                    if (s.games.isPlaying(p)) {
                                        p.sendSystemMessage(Component.literal("§c对局中无法预览地图"));
                                        return 0;
                                    }
                                    s.matchmaking.leave(p.getUUID(), true);
                                    s.previews.open(p, map);
                                    return 1;
                                }))))
                        .then(Commands.literal("here").executes(ctx -> run(ctx, server, (s, p) -> {
                            s.previews.describePosition(p);
                            return 1;
                        })))
                        .then(Commands.literal("close").executes(ctx -> run(ctx, server, (s, p) -> {
                            s.previews.close(p, true);
                            return 1;
                        }))));

        dispatcher.register(root);
        dispatcher.register(Commands.literal("blockland").redirect(dispatcher.getRoot().getChild("bl")));
    }

    private interface PlayerAction {
        int run(BlockLandServer server, ServerPlayer player) throws CommandSyntaxException;
    }

    private static int run(CommandContext<CommandSourceStack> ctx, Supplier<BlockLandServer> server, PlayerAction action)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        return action.run(require(ctx, server), player);
    }

    private static BlockLandServer require(CommandContext<CommandSourceStack> ctx, Supplier<BlockLandServer> server) {
        BlockLandServer s = server.get();
        if (s == null) throw new IllegalStateException("BlockLand server services are not running");
        return s;
    }
}
