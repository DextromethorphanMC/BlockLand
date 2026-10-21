package com.blockland.server.game;

import com.blockland.server.arena.ArenaService;
import com.blockland.server.content.ContentRepository;
import com.blockland.server.kit.KitService;
import com.blockland.server.map.MapLoader;
import com.blockland.server.world.WorldJobScheduler;
import net.minecraft.server.MinecraftServer;

/** Infrastructure handed to game types. Games never reach for globals. */
public record GameServices(
        MinecraftServer server,
        ContentRepository content,
        MapLoader maps,
        ArenaService arenas,
        WorldJobScheduler jobs,
        KitService kits
) {
}
