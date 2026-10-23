package com.blockland.server;

import com.blockland.BlockLand;
import com.blockland.effect.ActionLocks;
import com.blockland.games.fastpvp.FastPvpGameType;
import com.blockland.games.infection.InfectionActions;
import com.blockland.games.infection.InfectionGameType;
import com.blockland.games.practice.aim.AimPracticeGameType;
import com.blockland.games.practice.bridge.BridgePracticeGameType;
import com.blockland.games.skywars.SkyWarsGameType;
import com.blockland.movement.Slides;
import com.blockland.network.BLNetwork;
import com.blockland.server.arena.ArenaService;
import com.blockland.server.backend.BackendClient;
import com.blockland.server.combat.KillTracker;
import com.blockland.server.command.BlockLandCommands;
import com.blockland.server.config.BlockLandConfig;
import com.blockland.server.content.ContentSnapshot;
import com.blockland.server.content.RemoteContentRepository;
import com.blockland.server.craft.CraftService;
import com.blockland.server.game.GameInstance;
import com.blockland.server.game.GameManager;
import com.blockland.server.game.GameServices;
import com.blockland.server.game.GameType;
import com.blockland.server.game.GameTypeRegistry;
import com.blockland.server.game.MatchResultPublisher;
import com.blockland.server.gun.GunService;
import com.blockland.server.kit.KitService;
import com.blockland.server.kit.SessionKitLayouts;
import com.blockland.server.lobby.LobbyService;
import com.blockland.server.lobby.LobbyWorld;
import com.blockland.server.map.MapLoader;
import com.blockland.server.map.MapPreviewService;
import com.blockland.server.matchmaking.MatchmakingService;
import com.blockland.server.media.MediaRelay;
import com.blockland.server.melee.MeleeService;
import com.blockland.server.equipment.EquipmentService;
import com.blockland.server.movement.SlideService;
import com.blockland.server.party.PartyService;
import com.blockland.server.player.PlayerProfiles;
import com.blockland.server.session.PlayerSessions;
import com.blockland.server.shop.ShopRelay;
import com.blockland.server.world.WorldJobScheduler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraftforge.common.util.Result;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.network.GatherLoginConfigurationTasksEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

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
    public final PlayerSessions sessions;
    public final LobbyService lobby;
    public final GameManager games;
    public final MatchmakingService matchmaking;
    public final MapPreviewService previews;
    public final GunService gunService;
    public final MeleeService meleeService;
    public final EquipmentService equipmentService;
    public final CraftService craft;
    public final SlideService slides;
    public final KillTracker kills;
    private int tickCounter;

    private BlockLandServer(MinecraftServer server) {
        this.server = server;
        this.content = new RemoteContentRepository(BlockLandConfig.BACKEND_URL::get, BlockLandConfig.SERVER_TOKEN::get,
                FMLPaths.GAMEDIR.get().resolve("blockland"), server);
        this.types = new GameTypeRegistry()
                .register(new SkyWarsGameType())
                .register(new FastPvpGameType())
                .register(new InfectionGameType())
                .register(new BridgePracticeGameType())
                .register(new AimPracticeGameType());
        this.jobs = new WorldJobScheduler(BlockLandConfig.BLOCK_OPERATIONS_PER_TICK::get);
        this.arenas = new ArenaService(server, jobs, BlockLandConfig.ARENA_SPACING::get, BlockLandConfig.ARENA_BASE_Y::get);
        this.maps = new MapLoader(content, server);
        this.backend = new BackendClient(BlockLandConfig.BACKEND_URL::get, BlockLandConfig.SERVER_TOKEN::get);
        this.lobbyWorld = new LobbyWorld(server);
        this.sessions = new PlayerSessions(server, lobbyWorld);
        this.kitLayouts = new SessionKitLayouts();
        this.kits = new KitService(server, kitLayouts);
        this.services = new GameServices(server, content, maps, arenas, jobs, kits, new MatchResultPublisher(server), sessions);
        this.profiles = new PlayerProfiles(server, backend, sessions);
        this.shop = new ShopRelay(server, backend, profiles, sessions);
        this.media = new MediaRelay(server, backend);
        this.parties = new PartyService(sessions, BlockLandConfig.PARTY_MAX_SIZE::get, profiles::allowsInvites);
        this.lobby = new LobbyService(content, types, kits, profiles, parties, sessions, sessions, lobbyWorld);
        this.profiles.setChangeListener(lobby::markDirty);
        this.games = new GameManager(services, sessions, lobby::markDirty);
        this.matchmaking = new MatchmakingService(sessions, content, types, games, lobby::markDirty,
                id -> profiles.hasEntitlement(id, MatchmakingService.PRIORITY_ENTITLEMENT));
        this.lobby.bind(games, matchmaking);
        // a party that changes can no longer queue as it was
        this.parties.setListener((changed, membership) -> {
            if (membership) matchmaking.cancelFor(changed, Component.translatable("blockland.queue.party_changed"));
            lobby.markDirty();
        });
        this.previews = new MapPreviewService(services);
        this.gunService = new GunService(server);
        this.gunService.setInfiniteAmmo(player -> {
            GameInstance game = games.gameOf(player);
            return game != null && game.infiniteAmmo(player);
        });
        this.meleeService = new MeleeService(server);
        // practice modes hand out equipment that is never used up
        this.equipmentService = new EquipmentService(server, player -> {
            com.blockland.server.game.GameInstance game = games.gameOf(player);
            return game != null && game.infiniteEquipment(player);
        });
        // in a match the mode decides (and the type, e.g. only infection humans); outside matches a config option
        this.slides = new SlideService(server, player -> games.isPlaying(player) ? games.allowSlide(player)
                : BlockLandConfig.SLIDE_OUTSIDE_MATCHES.get());
        this.craft = new CraftService(server, profiles);
        this.kills = new KillTracker(server, games);
        this.kits.setAfterApply(craft::applyBuilds);
        // the login status brings the player's read announcements and builds: show the real ones
        this.profiles.setPreferencesListener(id -> {
            if (!sessions.inLobby().contains(id)) return;
            lobby.sendAnnouncements(id);
            craft.sendBuilds(id);
        });
        this.sessions.setListener(new PlayerSessions.Listener() {
            @Override
            public void joined(UUID id, String name) {
                profiles.onJoin(id, name);
            }

            @Override
            public void enteredLobby(UUID id) {
                lobby.enteredLobby(id);
                craft.sendBuilds(id);
            }

            @Override
            public void left(UUID id, String name, @Nullable ServerPlayer entity) {
                matchmaking.leave(id, true);
                parties.onLeave(id, name);
                games.leave(id, entity, true);
                profiles.onLeave(id);
                kitLayouts.forget(id);
                craft.forget(id);
                lobby.left(id);
                lobby.markDirty();
            }
        });
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

        // every login waits in the lobby — the configuration phase, no world loaded — until a match brings it in
        GatherLoginConfigurationTasksEvent.BUS.addListener(event -> {
            if (instance != null && instance.server.isDedicatedServer()) event.addTask(instance.sessions.gateTask());
        });
        // the Forge login/logout events now mean "entered / left the world"; network joins are PlayerSessions'
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(event -> {
            if (instance != null && event.getEntity() instanceof ServerPlayer player) instance.sessions.enteredWorld(player);
        });
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(event -> {
            if (instance != null && event.getEntity() instanceof ServerPlayer player) {
                instance.previews.close(player, false);
                instance.gunService.forget(player);
                instance.meleeService.forget(player);
                instance.equipmentService.forget(player);
                instance.slides.forget(player);
                instance.kills.forget(player);
                instance.sessions.leftWorld(player);
            }
        });

        PlayerEvent.StartTracking.BUS.addListener(event -> {
            if (instance != null && event.getEntity() instanceof ServerPlayer viewer) instance.gunService.startTracking(viewer, event.getTarget());
        });

        ServerChatEvent.BUS.addListener(event -> {
            if (instance == null) return false;
            Component muted = instance.profiles.muteMessage(event.getPlayer().getUUID());
            if (muted == null) return false;
            event.getPlayer().sendSystemMessage(muted.copy().withStyle(ChatFormatting.RED));
            return true;
        });

        // kill feedback first: it must see every death, also the ones a minigame cancels below
        LivingDeathEvent.BUS.addListener(event -> {
            if (instance != null && !event.getEntity().level().isClientSide()) instance.kills.onDeath(event.getEntity(), event.getSource());
            return false;
        });
        LivingDeathEvent.BUS.addListener(event -> instance != null
                && event.getEntity() instanceof ServerPlayer player
                && instance.games.onPlayerDeath(player, event.getSource()));
        LivingAttackEvent.BUS.addListener(event -> {
            if (instance == null || !(event.getEntity() instanceof ServerPlayer player)) return false;
            if (instance.games.isPlaying(player)) return !instance.games.allowDamage(player, event.getSource());
            return BlockLandConfig.LOBBY_PROTECT.get() && !event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY);
        });
        // locked hands (粘液缠身): no vanilla attack, item use or block interaction either
        AttackEntityEvent.BUS.addListener(event -> {
            return ActionLocks.isLocked(event.getEntity());
        });
        PlayerInteractEvent.RightClickItem.BUS.addListener(event -> {
            return ActionLocks.isLocked(event.getEntity());
        });
        PlayerInteractEvent.RightClickBlock.BUS.addListener(event -> {
            return ActionLocks.isLocked(event.getEntity());
        });
        PlayerInteractEvent.LeftClickBlock.BUS.addListener(event -> {
            return ActionLocks.isLocked(event.getEntity());
        });
        PlayerInteractEvent.EntityInteract.BUS.addListener(event -> {
            return ActionLocks.isLocked(event.getEntity());
        });
        PlayerInteractEvent.EntityInteractSpecific.BUS.addListener(event -> {
            return ActionLocks.isLocked(event.getEntity());
        });
        LivingHurtEvent.BUS.addListener(event -> {
            if (instance != null && event.getEntity() instanceof ServerPlayer player && instance.games.isPlaying(player)) {
                event.setAmount(instance.games.modifyDamage(player, event.getSource(), event.getAmount()));
            }
            return false;
        });
        // Forge fires (not posts) the break event and only honours a DENY result: cancelling alone is ignored
        BlockEvent.BreakEvent.BUS.addListener(event -> {
            if (instance == null || !(event.getPlayer() instanceof ServerPlayer player)) return;
            boolean allowed = instance.games.isPlaying(player) ? instance.games.allowBlockBreak(player, event.getPos())
                    : !BlockLandConfig.LOBBY_PROTECT.get() || player.isCreative();
            if (!allowed) event.setResult(Result.DENY);
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
        BLNetwork.setSenderResolver(created.sessions::idOf);
        BLNetwork.setConnectionResolver(created.sessions::connectionOf);
        BLNetwork.setGunServerListener(created.gunService);
        BLNetwork.setMeleeServerListener(created.meleeService);
        BLNetwork.setEquipmentServerListener(created.equipmentService);
        BLNetwork.setCraftServerListener(created.craft);
        BLNetwork.setMovementServerListener(created.slides);
        BLNetwork.setCombatServerListener(created.kills);
        Slides.setServerView(created.slides);
        BLNetwork.setInfectionServerListener(new InfectionActions(created.games::gameOf));
        created.lobbyWorld.prepare();
        created.games.removeStaleTeams();
        created.content.loadCache();
        created.onContentChanged(created.content.snapshot());
        created.content.refresh();
        List<String> items = BuiltInRegistries.ITEM.keySet().stream().map(ResourceLocation::toString).sorted().toList();
        created.content.publishItemRegistry(items);
        for (GameType type : created.types.all()) {
            com.google.gson.JsonElement registry = type.registryData();
            if (registry != null) created.content.publishTypeRegistry(type.id(), registry);
        }
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
        current.sessions.clear();
        BLNetwork.setServerListener(null);
        BLNetwork.setSenderResolver(null);
        BLNetwork.setConnectionResolver(null);
        BLNetwork.setGunServerListener(null);
        BLNetwork.setMeleeServerListener(null);
        BLNetwork.setEquipmentServerListener(null);
        BLNetwork.setCraftServerListener(null);
        current.craft.clear();
        BLNetwork.setMovementServerListener(null);
        BLNetwork.setCombatServerListener(null);
        current.kills.clear();
        Slides.setServerView(null);
        current.slides.clear();
        BLNetwork.setInfectionServerListener(null);
        current.gunService.clear();
        current.meleeService.clear();
        current.equipmentService.clear();
        instance = null;
    }

    private void tick() {
        sessions.tick();
        jobs.tick();
        games.tick();
        matchmaking.tick();
        parties.tick();
        gunService.tick();
        meleeService.tick();
        equipmentService.tick();
        slides.tick();
        kills.tick();
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
        lobby.announcementsChanged();
    }
}
