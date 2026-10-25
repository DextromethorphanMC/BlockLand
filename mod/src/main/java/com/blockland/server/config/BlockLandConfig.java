package com.blockland.server.config;

import net.minecraftforge.common.ForgeConfigSpec;

/** config/blockland-common.toml */
public final class BlockLandConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.ConfigValue<String> BACKEND_URL;
    public static final ForgeConfigSpec.ConfigValue<String> SERVER_TOKEN;
    public static final ForgeConfigSpec.IntValue SYNC_INTERVAL_SECONDS;

    public static final ForgeConfigSpec.BooleanValue LOBBY_USE_WORLD_SPAWN;
    public static final ForgeConfigSpec.DoubleValue LOBBY_X;
    public static final ForgeConfigSpec.DoubleValue LOBBY_Y;
    public static final ForgeConfigSpec.DoubleValue LOBBY_Z;
    public static final ForgeConfigSpec.DoubleValue LOBBY_YAW;
    public static final ForgeConfigSpec.BooleanValue LOBBY_PROTECT;
    public static final ForgeConfigSpec.BooleanValue LOBBY_VOID_WORLD;
    public static final ForgeConfigSpec.IntValue PARTY_MAX_SIZE;
    public static final ForgeConfigSpec.BooleanValue SLIDE_OUTSIDE_MATCHES;

    public static final ForgeConfigSpec.IntValue ARENA_SPACING;
    public static final ForgeConfigSpec.IntValue ARENA_BASE_Y;
    public static final ForgeConfigSpec.IntValue BLOCK_OPERATIONS_PER_TICK;

    static {
        BUILDER.push("backend");
        BACKEND_URL = BUILDER.comment("BlockLand 后端地址").define("url", "http://127.0.0.1:8080");
        SERVER_TOKEN = BUILDER.comment("后端 blockland.server-token，用于服务器拉取配置").define("serverToken", "change-me-server");
        SYNC_INTERVAL_SECONDS = BUILDER.comment("从后端同步小游戏配置的间隔（秒）").defineInRange("syncIntervalSeconds", 30, 5, 3600);
        BUILDER.pop();

        BUILDER.push("lobby");
        // the lobby itself has no world (configuration phase); these only place the staging spot in the world
        LOBBY_USE_WORLD_SPAWN = BUILDER.comment("voidWorld=false 时：使用主世界出生点作为中转位置（为 false 时使用下方坐标）").define("useWorldSpawn", true);
        LOBBY_X = BUILDER.defineInRange("x", 0.5, -3.0E7, 3.0E7);
        LOBBY_Y = BUILDER.defineInRange("y", 100.0, -64, 320);
        LOBBY_Z = BUILDER.defineInRange("z", 0.5, -3.0E7, 3.0E7);
        LOBBY_YAW = BUILDER.defineInRange("yaw", 0.0, -180.0, 180.0);
        LOBBY_PROTECT = BUILDER.comment("不在对局中的世界内玩家（进入世界的管理员）不会受到伤害，非创造模式无法破坏方块").define("protect", true);
        LOBBY_VOID_WORLD = BUILDER.comment("中转位置（离开世界前、管理员进入世界时）在空的 blockland:lobby 维度中（不加载主世界区块）；为 false 时使用主世界中的位置")
                .define("voidWorld", true);
        BUILDER.pop();

        BUILDER.push("party");
        PARTY_MAX_SIZE = BUILDER.comment("组队人数上限").defineInRange("maxSize", 4, 2, 16);
        BUILDER.pop();

        BUILDER.push("movement");
        // inside a match the mode's allowSlide / allowProne / allowDive settings (backend) decide
        SLIDE_OUTSIDE_MATCHES = BUILDER.comment("不在对局中的世界内玩家（进入世界的管理员）能否滑铲、匍匐和飞扑；对局中由模式的「允许滑铲 / 匍匐 / 飞扑」决定")
                .define("slideOutsideMatches", true);
        BUILDER.pop();

        BUILDER.push("arena");
        ARENA_SPACING = BUILDER.comment("竞技场维度中每个对局槽位的 X 间隔").defineInRange("spacing", 1024, 256, 100000);
        ARENA_BASE_Y = BUILDER.comment("地图粘贴的基准 Y").defineInRange("baseY", 80, 1, 300);
        BLOCK_OPERATIONS_PER_TICK = BUILDER.comment("每 tick 最多放置/清理的方块数").defineInRange("blockOperationsPerTick", 20000, 500, 1000000);
        BUILDER.pop();
    }

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private BlockLandConfig() {
    }
}
