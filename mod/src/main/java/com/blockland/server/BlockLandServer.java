package com.blockland.server;

import com.blockland.BlockLand;
import com.blockland.games.fastpvp.FastPvpGameType;
import com.blockland.games.skywars.SkyWarsGameType;
import com.blockland.network.BLNetwork;
import com.blockland.server.arena.ArenaService;
import com.blockland.server.command.BlockLandCommands;
import com.blockland.server.config.BlockLandConfig;
import com.blockland.server.content.ContentSnapshot;
import com.blockland.server.content.RemoteContentRepository;
import com.blockland.server.game.GameManager;
import com.blockland.server.game.GameServices;
import com.blockland.server.game.GameTypeRegistry;
import com.blockland.server.gun.GunService;
import com.blockland.server.melee.MeleeService;
import com.blockland.server.backend.BackendClient;
import com.blockland.server.kit.SessionKitLayouts;
import com.blockland.server.kit.KitService;
import com.blockland.server.lobby.LobbyService;
import com.blockland.server.lobby.LobbyWorld;
import com.blockland.server.party.PartyService;
import com.blockland.server.map.MapLoader;
import com.blockland.server.map.MapPreviewService;
import com.blockland.server.matchmaking.MatchmakingService;
import com.blockland.server.media.MediaRelay;
import com.blockland.server.player.PlayerProfiles;
import com.blockland.server.shop.ShopRelay;
import com.blockland.server.world.WorldJobScheduler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Composition root of the server side. Creates the services for a running {@link MinecraftServer}
 * and is the only place that translates Forge events into service calls.
 */
public final class BlockLandServer {
    @Nullable
    private static BlockLandServer instance;

    public final MinecraftServer server;
    public final RemoteContentRepository content;
    public final GameTypeRegistry types;
    public final WorldJobScheduler jobs;
    public final ArenaService arenas;
    public final MapLoader maps;
    public final BackendClient backend;
    public final PlayerProfiles profiles;
    public final SessionKitLayouts kitLayouts;
    public final KitService kits;
    public final ShopRelay shop;
    public final MediaRelay media;
    public final GameServices services;
    public final PartyService parties;
    public final LobbyWorld lobbyWorld;
    public final LobbyService lobby;
    public final GameManager games;
    public final MatchmakingService matchmaking;
    public final MapPreviewService previews;
    public final GunService gunService;
    public final MeleeService meleeService;
    private int tickCounter;

    private BlockLandServer(MinecraftServer server) {
        this.server = server;
        this.content = new RemoteContentRepository(BlockLandConfig.BACKEND_URL::get, BlockLandConfig.SERVER_TOKEN::get,
                FMLPaths.GAMEDIR.get().resolve("blockland"), server);
        this.types = new GameTypeRegistry()
                .register(new SkyWarsGameType())
                .register(new FastPvpGameType());
        this.jobs = new WorldJobScheduler(BlockLandConfig.BLOCK_OPERATIONS_PER_TICK::get);
        this.arenas = new ArenaService(server, jobs, BlockLandConfig.ARENA_SPACING::get, BlockLandConfig.ARENA_BASE_Y::get);
        this.maps = new MapLoader(content, server);
        this.backend = new BackendClient(BlockLandConfig.BACKEND_URL::get, BlockLandConfig.SERVER_TOKEN::get);
        this.kitLayouts = new SessionKitLayouts();
        this.kits = new KitService(server, kitLayouts);
        this.services = new GameServices(server, content, maps, arenas, jobs, kits);
        this.profiles = new PlayerProfiles(server, backend);
        this.shop = new ShopRelay(server, backend, profiles);
        this.media = new MediaRelay(server, backend);
        this.parties = new PartyService(server, BlockLandConfig.PARTY_MAX_SIZE::get);
        this.lobbyWorld = new LobbyWorld(server);
        this.lobby = new LobbyService(server, content, types, kits, profiles, parties, lobbyWorld);
        this.profiles.setChangeListener(lobby::markDirty);
        this.games = new GameManager(services, player -> lobby.sendToLobby(player, true), lobby::markDirty);
        this.matchmaking = new MatchmakingService(server, content, types, games, lobby::markDirty,
                id -> profiles.hasEntitlement(id, MatchmakingService.PRIORITY_ENTITLEMENT));
        this.lobby.bind(games, matchmaking);
        // a party that changes can no longer queue as it was
        this.parties.setListener((changed, membership) -> {
            if (membership) matchmaking.cancelFor(changed, "队伍成员变化，已取消匹配");
            lobby.markDirty();
        });
        this.previews = new MapPreviewService(services, lobby);
        this.gunService = new GunService(server);
        this.meleeService = new MeleeService(server);
        this.content.addChangeListener(this::onContentChanged);
    }

    @Nullable
    public static BlockLandServer get() {
        return instance;
    }

    /** Registers the Forge listeners. Called once from the mod constructor on both sides. */
    public static void bootstrap() {
        ServerStartedEvent.BUS.addListener(event -> start(event.getServer()));
        ServerStoppingEvent.BUS.addListener(event -> stop());
        TickEvent.ServerTickEvent.Post.BUS.addListener(event -> {
            if (instance != null) instance.tick();
        });
        RegisterCommandsEvent.BUS.addListener(event -> BlockLandCommands.register(event.getDispatcher(), BlockLandServer::get));

        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(event -> {
            if (instance != null && event.getEntity() instanceof ServerPlayer player) {
                instance.profiles.onLogin(player);
                instance.lobby.sendToLobby(player, true);
            }
        });
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(event -> {
            if (instance != null && event.getEntity() instanceof ServerPlayer player) {
                instance.matchmaking.leave(player, true);
                instance.parties.onLogout(player);
                instance.games.leave(player, true);
                instance.previews.close(player, false);
                instance.profiles.onLogout(player);
                instance.kitLayouts.forget(player.getUUID());
                instance.gunService.forget(player);
                instance.meleeService.forget(player);
            }
        });
        PlayerEvent.PlayerRespawnEvent.BUS.addListener(event -> {
            if (instance != null && event.getEntity() instanceof ServerPlayer player && !instance.games.isPlaying(player)) {
                instance.lobby.sendToLobby(player, false);
            }
        });

        ServerChatEvent.BUS.addListener(event -> {
            if (instance == null) return false;
            String muted = instance.profiles.muteMessage(event.getPlayer().getUUID());
            if (muted == null) return false;
            event.getPlayer().sendSystemMessage(Component.literal(muted));
            return true;
        });

        LivingDeathEvent.BUS.addListener(event -> instance != null
                && event.getEntity() instanceof ServerPlayer player
                && instance.games.onPlayerDeath(player, event.getSource()));
        LivingAttackEvent.BUS.addListener(event -> {
            if (instance == null || !(event.getEntity() instanceof ServerPlayer player)) return false;
            if (instance.games.isPlaying(player)) return !instance.games.allowDamage(player, event.getSource());
            return BlockLandConfig.LOBBY_PROTECT.get() && !event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY);
        });
        BlockEvent.BreakEvent.BUS.addListener(event -> {
            if (instance == null || !(event.getPlayer() instanceof ServerPlayer player)) return false;
            if (instance.games.isPlaying(player)) return !instance.games.allowBlockBreak(player, event.getPos());
            return BlockLandConfig.LOBBY_PROTECT.get() && !player.isCreative();
        });
        BlockEvent.EntityPlaceEvent.BUS.addListener(event -> {
            if (instance == null || !(event.getEntity() instanceof ServerPlayer player)) return false;
            if (instance.games.isPlaying(player)) return !instance.games.allowBlockPlace(player, event.getPos());
            return BlockLandConfig.LOBBY_PROTECT.get() && !player.isCreative();
        });
    }

    private static void start(MinecraftServer server) {
        BlockLandServer created = new BlockLandServer(server);
        instance = created;
        BLNetwork.setServerListener(new ServerPacketRouter(created.lobby, created.shop, created.media));
        BLNetwork.setGunServerListener(created.gunService);
        BLNetwork.setMeleeServerListener(created.meleeService);
        created.lobbyWorld.prepare();
        created.content.loadCache();
        created.onContentChanged(created.content.snapshot());
        created.content.refresh();
        List<String> items = BuiltInRegistries.ITEM.keySet().stream().map(ResourceLocation::toString).sorted().toList();
        created.content.publishItemRegistry(items);
        BlockLand.LOGGER.info("[BlockLand] Server services started (backend: {})", BlockLandConfig.BACKEND_URL.get());
    }

    private static void stop() {
        BlockLandServer current = instance;
        if (current == null) return;
        current.profiles.flushAll();
        current.matchmaking.dropAll();
        current.parties.clear();
        current.games.abortAll();
        current.previews.closeAll();
        current.jobs.drain();
        BLNetwork.setServerListener(null);
        BLNetwork.setGunServerListener(null);
        BLNetwork.setMeleeServerListener(null);
        current.gunService.clear();
        current.meleeService.clear();
        instance = null;
    }

    private void tick() {
        jobs.tick();
        games.tick();
        matchmaking.tick();
        parties.tick();
        gunService.tick();
        meleeService.tick();
        lobby.tick();
        profiles.tick();
        if (++tickCounter % (BlockLandConfig.SYNC_INTERVAL_SECONDS.get() * 20) == 0) content.refresh();
    }

    private void onContentChanged(ContentSnapshot snapshot) {
        BlockLand.LOGGER.info("[BlockLand] Configuration v{} active: {} modes, {} maps, type data for {}",
                snapshot.version, snapshot.modes.size(), snapshot.maps.size(), snapshot.typeData.keySet());
        maps.retain(snapshot.maps);
        types.all().forEach(type -> type.onContentChanged(services, snapshot));
        lobby.markDirty();
    }
}
