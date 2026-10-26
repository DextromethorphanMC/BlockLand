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

    public static final ForgeConfigSpec.BooleanValue FAR_TERRAIN_ENABLED;
    public static final ForgeConfigSpec.IntValue FAR_TERRAIN_MAX_DISTANCE;
    public static final ForgeConfigSpec.IntValue FAR_TERRAIN_THREADS;
    public static final ForgeConfigSpec.IntValue FAR_TERRAIN_BYTES_PER_SECOND;
    public static final ForgeConfigSpec.BooleanValue FAR_TERRAIN_ESTIMATE;
    public static final ForgeConfigSpec.BooleanValue FAR_TERRAIN_IMPORT_SAVED;
    public static final ForgeConfigSpec.IntValue FAR_TERRAIN_GENERATE_RADIUS;
    public static final ForgeConfigSpec.IntValue FAR_TERRAIN_GENERATE_CONCURRENCY;
    public static final ForgeConfigSpec.IntValue FAR_TERRAIN_COLUMN_DISTANCE;
    public static final ForgeConfigSpec.IntValue FAR_TERRAIN_COLUMN_BYTES_PER_SECOND;

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

        // 远景地平线 (rendering spec §22): the server streams compact terrain tiles to clients that show far terrain
        BUILDER.push("farTerrain");
        FAR_TERRAIN_ENABLED = BUILDER.comment("向客户端提供远景地平线（超远距离地形）").define("enabled", true);
        FAR_TERRAIN_MAX_DISTANCE = BUILDER.comment("客户端可请求的最远距离（格）").defineInRange("maxDistance", 32768, 1024, 65536);
        FAR_TERRAIN_THREADS = BUILDER.comment("计算远景地形的后台线程数（估算、读取存档区块、写磁盘缓存）；0 = 按 CPU 核数自动").defineInRange("threads", 0, 0, 32);
        FAR_TERRAIN_BYTES_PER_SECOND = BUILDER.comment("每位玩家每秒最多发送的远景数据（字节）").defineInRange("bytesPerSecond", 393216, 16384, 16777216);
        FAR_TERRAIN_ESTIMATE = BUILDER.comment("尚未生成的区块由世界生成器估算地形（关闭则只显示已存在的区块）").define("estimate", true);
        FAR_TERRAIN_IMPORT_SAVED = BUILDER.comment("读取存档中已生成的区块（未加载时）作为真实地形").define("importSaved", true);
        FAR_TERRAIN_GENERATE_RADIUS = BUILDER.comment("在玩家周围这个距离（格）内于后台真实生成尚不存在的区块（地形、地表、洞穴、树木全部），远景在此范围内就是真实地形；0 = 不生成。"
                        + "玩家客户端的「真实远景区块」距离更远时，生成范围随之扩大到该距离（不超过 columnDistance）。会增大存档体积；"
                        + "生成 1000 区块以上的范围第一次要数小时、占用数十 GB 磁盘")
                .defineInRange("generateRadius", 1536, 0, 8192);
        FAR_TERRAIN_GENERATE_CONCURRENCY = BUILDER.comment("同时在后台生成的区块数（服务器卡顿时自动暂停）").defineInRange("generateConcurrency", 8, 1, 64);
        FAR_TERRAIN_COLUMN_DISTANCE = BUILDER.comment("客户端可请求的真实远景区块最远距离（区块，以玩家所在区块为中心）。远处的区块按距离合并成"
                        + "粗一级的单元（2×2、4×4……64×64 个区块，每个方块代表 2、4……64 格），都由真实区块生成，合并结果缓存在存档的 blockland/farchunks 下。"
                        + "后台生成会跟随玩家请求的距离：第一次生成 1000 区块以上的范围要数小时、占用数十 GB 磁盘（区块本身加合并缓存）")
                .defineInRange("columnDistance", 1024, 8, 4096);
        FAR_TERRAIN_COLUMN_BYTES_PER_SECOND = BUILDER.comment("每位玩家每秒最多发送的真实远景区块数据（字节）")
                .defineInRange("columnBytesPerSecond", 8 << 20, 65536, 268435456);
        BUILDER.pop();
    }

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private BlockLandConfig() {
    }
}
