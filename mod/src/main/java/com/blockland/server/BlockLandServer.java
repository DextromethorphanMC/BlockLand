package com.blockland.server;

import com.blockland.BlockLand;
import com.blockland.bomb.BombItem;
import com.blockland.effect.ActionLocks;
import com.blockland.games.bomb.BombActions;
import com.blockland.games.bomb.BombGameType;
import com.blockland.games.fastpvp.FastPvpGameType;
import com.blockland.games.infection.InfectionActions;
import com.blockland.games.infection.InfectionGameType;
import com.blockland.games.practice.aim.AimPracticeGameType;
import com.blockland.games.practice.bridge.BridgePracticeGameType;
import com.blockland.games.skywars.SkyWarsGameType;
import com.blockland.games.world.WorldGameType;
import com.blockland.movement.action.BodyActions;
import com.blockland.network.BLNetwork;
import com.blockland.server.arena.ArenaService;
import com.blockland.server.backend.BackendClient;
import com.blockland.server.combat.HitHistory;
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
import com.blockland.server.movement.ActionService;
import com.blockland.server.parachute.ParachuteService;
import com.blockland.parachute.Parachutes;
import com.blockland.server.party.PartyService;
import com.blockland.server.player.PlayerProfiles;
import com.blockland.server.inventory.InventoryCatalog;
import com.blockland.server.inventory.InventoryService;
import com.blockland.server.profile.CardItems;
import com.blockland.server.profile.Cosmetics;
import com.blockland.server.profile.CosmeticsService;
import com.blockland.server.profile.OwnedCosmetics;
import com.blockland.server.profile.ProfileService;
import com.blockland.server.ranked.RankedService;
import com.blockland.server.ranked.Ranks;
import com.blockland.server.anticheat.AntiCheatService;
import com.blockland.server.anticheat.api.Suspicion;
import com.blockland.server.anticheat.net.NetHooks;
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
import net.minecraftforge.event.entity.living.LivingFallEvent;
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
    public final HitHistory hitHistory = new HitHistory();
    public final GunService gunService;
    public final MeleeService meleeService;
    public final EquipmentService equipmentService;
    public final CraftService craft;
    public final ActionService actions;
    public final ParachuteService parachutes;
    public final com.blockland.server.supply.AmmoCrateService supply;
    public final com.blockland.server.armor.ArmorService armor;
    public final com.blockland.server.vehicle.VehicleService vehicles;
    public final com.blockland.server.graffiti.GraffitiService graffiti;
    public final KillTracker kills;
    public final com.blockland.server.combat.ProjectileHits projectileHits;
    public final com.blockland.server.spectate.SpectateService spectate;
    public final CosmeticsService cosmetics;
    public final InventoryService inventory;
    public final ProfileService profilePages;
    public final RankedService ranked;
    public final AntiCheatService antiCheat;
    public final com.blockland.server.farterrain.FarTerrainService farTerrain;
    private int tickCounter;

    private BlockLandServer(MinecraftServer server) {
        this.server = server;
        this.content = new RemoteContentRepository(BlockLandConfig.BACKEND_URL::get, BlockLandConfig.SERVER_TOKEN::get,
                FMLPaths.GAMEDIR.get().resolve("blockland"), server);
        this.types = new GameTypeRegistry()
                .register(new SkyWarsGameType())
                .register(new FastPvpGameType())
                .register(new InfectionGameType())
                .register(new BombGameType())
                .register(new WorldGameType())
                .register(new com.blockland.games.battleroyale.BattleRoyaleGameType())
                .register(new com.blockland.games.pillars.LuckyPillarsGameType())
                .register(new com.blockland.games.uhc.UhcGameType())
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
        // items a type hands to some players only (the bomb) are arranged with the kit
        this.kits.setRoleItems(mode -> types.get(mode.type).map(type -> type.roleItems(mode)).orElse(List.of()));
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
        this.gunService.setHistory(hitHistory);
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
        // movement actions (slide, prone, dive): in a match the mode decides (and the type, e.g. only infection humans);
        // outside matches a config option
        this.actions = new ActionService(server, (player, action) -> games.isPlaying(player) ? games.allowAction(player, action)
                : BlockLandConfig.SLIDE_OUTSIDE_MATCHES.get());
        this.parachutes = new ParachuteService(server);
        this.supply = new com.blockland.server.supply.AmmoCrateService(server);
        this.armor = new com.blockland.server.armor.ArmorService(server);
        // vehicles (载具): in a match the mode's allowVehicles decides, outside matches a config option
        this.vehicles = new com.blockland.server.vehicle.VehicleService(player -> {
            if (!games.isPlaying(player)) return BlockLandConfig.VEHICLES_OUTSIDE_MATCHES.get();
            GameInstance game = games.gameOf(player);
            return game != null && game.mode().flag("allowVehicles", false);
        });
        // wall graffiti: only in a match whose mode allows it; owned = free or an entitlement
        this.graffiti = new com.blockland.server.graffiti.GraffitiService(server, player -> {
            GameInstance game = games.gameOf(player);
            return game != null && game.mode().flag("allowGraffiti", true);
        }, profiles::hasEntitlement);
        this.arenas.setReleaseListener(graffiti::clearArea);
        // skins that must be owned are entitlements (skin:<weapon>/<skin>); a status that arrives may unlock or expire one
        this.craft = new CraftService(server, profiles, profiles::hasEntitlement);
        this.profiles.setEntitlementListener(craft::entitlementsChanged);
        this.kills = new KillTracker(server, games);
        // snowballs, eggs and fishing bobbers knock players back in modes that turn it on (projectileKnockback)
        this.projectileHits = new com.blockland.server.combat.ProjectileHits((player, source) -> {
            GameInstance game = games.gameOf(player);
            return game != null && game.mode().flag("projectileKnockback", false) && games.allowDamage(player, source);
        });
        this.spectate = new com.blockland.server.spectate.SpectateService(server, games, profiles);
        // 库存 / 个人主页: what players own (entitlements + free items) and wear (cosmetics slots)
        this.cosmetics = new CosmeticsService(server, backend);
        this.inventory = new InventoryService(backend, profiles, cosmetics);
        this.profilePages = new ProfileService(server, sessions, profiles, cosmetics, inventory);
        this.cosmetics.setChangeListener(profilePages::refresh);
        // 排位: one ladder for every ranked mode; matchmaking by score, results (and leavers) reported to the backend
        this.ranked = new RankedService(server, backend, content, types, games::gameOf);
        this.matchmaking.setScoreSource(ranked::matchScore);
        this.games.setAbandonListener(ranked::abandoned);
        this.services.results().addListener(ranked::published);
        this.kits.setAfterApply(craft::applyBuilds);
        // 反作弊: movement, packets and combat judged by the rules of the player's mode (anti-cheat spec); it knows the
        // matches only through these functions, the modules' own mechanics only through their allowances
        this.antiCheat = new AntiCheatService(server, backend, sessions, content::snapshot,
                id -> {
                    GameInstance game = games.gameOf(id);
                    return game == null ? null : game.mode();
                },
                id -> {
                    GameInstance game = games.gameOf(id);
                    return game == null || game.map() == null ? null : game.map().id;
                },
                hitHistory::positionAgo);
        this.antiCheat.addAllowance(new com.blockland.server.movement.ActionAllowance(actions));
        // 远景地平线: terrain tiles for the clients' far terrain (rendering spec §22); a player in an arena match only sees
        // their own arena, never the neighbouring matches (nor a match in a shared generated world, e.g. UHC, beyond its
        // bounds); pasted and cleared arenas are captured again
        this.farTerrain = new com.blockland.server.farterrain.FarTerrainService(server, () -> new com.blockland.server.farterrain.FarTerrainSettings(
                BlockLandConfig.FAR_TERRAIN_ENABLED.get(), BlockLandConfig.FAR_TERRAIN_MAX_DISTANCE.get(), BlockLandConfig.FAR_TERRAIN_THREADS.get(),
                BlockLandConfig.FAR_TERRAIN_BYTES_PER_SECOND.get(), BlockLandConfig.FAR_TERRAIN_ESTIMATE.get(),
                BlockLandConfig.FAR_TERRAIN_IMPORT_SAVED.get(), BlockLandConfig.FAR_TERRAIN_GENERATE_RADIUS.get(),
                BlockLandConfig.FAR_TERRAIN_GENERATE_CONCURRENCY.get(), BlockLandConfig.FAR_TERRAIN_COLUMN_DISTANCE.get(),
                BlockLandConfig.FAR_TERRAIN_COLUMN_BYTES_PER_SECOND.get()));
        this.farTerrain.setViewLimit(player -> {
            boolean arena = player.level().dimension() == ArenaService.ARENA_DIMENSION;
            GameInstance game = games.gameOf(player);
            if (game == null) return null;
            net.minecraft.world.phys.AABB bounds = game.spectateBounds();
            if (bounds == null) {
                // a match whose arena is not there yet sees nothing (a box far away); other worlds are open
                return arena ? new net.minecraft.world.level.levelgen.structure.BoundingBox(29_000_000, 0, 29_000_000, 29_000_000, 0, 29_000_000) : null;
            }
            return new net.minecraft.world.level.levelgen.structure.BoundingBox((int) Math.floor(bounds.minX), (int) Math.floor(bounds.minY),
                    (int) Math.floor(bounds.minZ), (int) Math.ceil(bounds.maxX) - 1, (int) Math.ceil(bounds.maxY) - 1, (int) Math.ceil(bounds.maxZ) - 1);
        });
        this.jobs.addCompletionListener(farTerrain::areaChanged);
        this.antiCheat.addAllowance(new com.blockland.server.parachute.ParachuteAllowance());
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
                cosmetics.onJoin(id);
            }

            @Override
            public void enteredLobby(UUID id) {
                lobby.enteredLobby(id);
                ranked.enteredLobby(id);
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
                cosmetics.forget(id);
                ranked.forget(id);
                antiCheat.forget(id);
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
                instance.actions.forget(player);
                instance.parachutes.forget(player);
                instance.supply.forget(player);
                instance.armor.forget(player);
                instance.graffiti.forget(player);
                instance.kills.forget(player);
                instance.spectate.forget(player);
                instance.antiCheat.leftWorld(player);
                instance.farTerrain.forget(player);
                instance.sessions.leftWorld(player);
            }
        });

        PlayerEvent.StartTracking.BUS.addListener(event -> {
            if (instance != null && event.getEntity() instanceof ServerPlayer viewer) instance.gunService.startTracking(viewer, event.getTarget());
        });
        // far terrain captures full chunks as they load, change (are saved) and unload
        net.minecraftforge.event.level.ChunkEvent.Load.BUS.addListener(event -> {
            if (instance != null && event.getLevel() instanceof net.minecraft.server.level.ServerLevel level
                    && event.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk chunk) {
                instance.farTerrain.chunkLoaded(level, chunk.getPos());
            }
        });
        net.minecraftforge.event.level.ChunkDataEvent.Save.BUS.addListener(event -> {
            if (instance != null && event.getLevel() instanceof net.minecraft.server.level.ServerLevel level
                    && event.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk chunk) {
                instance.farTerrain.chunkSaved(level, chunk.getPos());
            }
        });
        net.minecraftforge.event.level.ChunkEvent.Unload.BUS.addListener(event -> {
            if (instance != null && event.getLevel() instanceof net.minecraft.server.level.ServerLevel level
                    && event.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk chunk) {
                instance.farTerrain.chunkUnloaded(level, chunk);
            }
        });
        PlayerEvent.StartTracking.BUS.addListener(event -> {
            if (instance != null && event.getEntity() instanceof ServerPlayer viewer) instance.parachutes.startTracking(viewer, event.getTarget());
        });
        // an open canopy lands softly
        LivingFallEvent.BUS.addListener(event -> instance != null && event.getEntity() instanceof ServerPlayer player
                && instance.parachutes.cancelsFall(player));

        ServerChatEvent.BUS.addListener(event -> {
            if (instance == null) return false;
            Component muted = instance.profiles.muteMessage(event.getPlayer().getUUID());
            if (muted == null) return false;
            event.getPlayer().sendSystemMessage(muted.copy().withStyle(ChatFormatting.RED));
            return true;
        });

        // kill feedback first: it must see every death, also the ones a minigame cancels below
        LivingDeathEvent.BUS.addListener(event -> {
            if (instance != null && !event.getEntity().level().isClientSide()) {
                instance.kills.onDeath(event.getEntity(), event.getSource());
                instance.spectate.onDeath(event.getEntity(), event.getSource());
            }
            return false;
        });
        LivingDeathEvent.BUS.addListener(event -> instance != null
                && event.getEntity() instanceof ServerPlayer player
                && instance.games.onPlayerDeath(player, event.getSource()));
        // body armour soaks what it covers, once per hurt, before vanilla's own armour
        net.minecraftforge.event.entity.living.LivingHurtEvent.BUS.addListener(event -> {
            if (instance != null && !event.getEntity().level().isClientSide()) {
                event.setAmount(instance.armor.onHurt(event.getEntity(), event.getSource(), event.getAmount()));
            }
            return false;
        });
        // riders inside a vehicle's hull are out of reach of rounds and blades (vehicle spec §6)
        LivingAttackEvent.BUS.addListener(event -> instance != null && !event.getEntity().level().isClientSide()
                && instance.vehicles.shelters(event.getEntity(), event.getSource()));
        LivingAttackEvent.BUS.addListener(event -> {
            if (instance == null || !(event.getEntity() instanceof ServerPlayer player)) return false;
            if (instance.games.isPlaying(player)) return !instance.games.allowDamage(player, event.getSource());
            return BlockLandConfig.LOBBY_PROTECT.get() && !event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY);
        });
        net.minecraftforge.event.entity.ProjectileImpactEvent.BUS.addListener(event -> {
            if (instance != null && !event.getProjectile().level().isClientSide()) {
                instance.projectileHits.onImpact(event.getProjectile(), event.getRayTraceResult());
            }
        });
        // locked hands (粘液缠身): no vanilla attack, item use or block interaction either
        AttackEntityEvent.BUS.addListener(event -> {
            return ActionLocks.isLocked(event.getEntity());
        });
        // the anti-cheat judges vanilla melee attacks (reach, angle, several targets at once); a cancelled one does nothing
        AttackEntityEvent.BUS.addListener(event -> instance != null && event.getEntity() instanceof ServerPlayer player
                && instance.antiCheat.onAttack(player, event.getTarget()));
        // pistons carry players along (and slime blocks launch them): the anti-cheat leaves those moves alone
        net.minecraftforge.event.level.PistonEvent.Post.BUS.addListener(event -> {
            if (instance != null && event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
                instance.antiCheat.onPiston(level, event.getPos());
            }
        });
        // …and the face a block is placed against
        PlayerInteractEvent.RightClickBlock.BUS.addListener(event -> {
            if (instance != null && event.getEntity() instanceof ServerPlayer player
                    && event.getItemStack().getItem() instanceof net.minecraft.world.item.BlockItem) {
                instance.antiCheat.onPlace(player, event.getHitVec());
            }
            return false;
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
        BLNetwork.setMovementServerListener(created.actions);
        BLNetwork.setCombatServerListener(created.kills);
        InventoryCatalog.register(new CardItems());
        InventoryCatalog.register(new com.blockland.server.graffiti.GraffitiItems());
        InventoryCatalog.register(new com.blockland.server.craft.SkinItems());
        // a product that is an inventory item shows that item's icon when it has no image of its own
        created.shop.setItemIcons(key -> {
            InventoryCatalog.ItemType type = InventoryCatalog.typeOf(key);
            InventoryCatalog.Display display = type == null ? null : type.display(key);
            return display == null ? "" : display.icon();
        });
        BLNetwork.setGraffitiServerListener(created.graffiti);
        BLNetwork.setSpectateServerListener(created.spectate);
        BLNetwork.setProfileServerListener(created.profilePages);
        Cosmetics.setStore(new OwnedCosmetics(created.cosmetics, created.inventory));
        // after every module registered its item types at start-up
        server.execute(created.inventory::publishRegistry);
        BodyActions.setServerView(created.actions);
        Ranks.setView(created.ranked);
        BLNetwork.setParachuteServerListener(created.parachutes);
        BLNetwork.setSupplyServerListener(created.supply);
        BLNetwork.setArmorServerListener(created.armor);
        BLNetwork.setVehicleServerListener(created.vehicles);
        com.blockland.vehicle.Vehicles.setServer(created.vehicles);
        BLNetwork.setFarTerrainServerListener(created.farTerrain);
        NetHooks.setListener(created.antiCheat);
        Suspicion.setSink(created.antiCheat);
        BLNetwork.setInboundGuard(created.antiCheat::admit);
        created.antiCheat.publishRegistry();
        Parachutes.setServerView(created.parachutes);
        BLNetwork.setInfectionServerListener(new InfectionActions(created.games::gameOf));
        BombActions bombActions = new BombActions(created.games::gameOf);
        BLNetwork.setBombServerListener(bombActions);
        BombItem.setDropHandler(bombActions);
        BLNetwork.setBattleRoyaleServerListener(new com.blockland.games.battleroyale.BattleRoyaleActions(created.games::gameOf));
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
        BLNetwork.setFarTerrainServerListener(null);
        current.farTerrain.stop();
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
        BLNetwork.setProfileServerListener(null);
        Cosmetics.setStore(null);
        current.cosmetics.clear();
        BodyActions.setServerView(null);
        Ranks.setView(null);
        current.ranked.clear();
        current.actions.clear();
        BLNetwork.setParachuteServerListener(null);
        Parachutes.setServerView(null);
        current.parachutes.clear();
        BLNetwork.setSupplyServerListener(null);
        current.supply.clear();
        BLNetwork.setArmorServerListener(null);
        current.armor.clear();
        BLNetwork.setVehicleServerListener(null);
        com.blockland.vehicle.Vehicles.setServer(null);
        BLNetwork.setGraffitiServerListener(null);
        BLNetwork.setSpectateServerListener(null);
        current.spectate.clear();
        current.graffiti.clear();
        BLNetwork.setInfectionServerListener(null);
        BLNetwork.setBombServerListener(null);
        BombItem.setDropHandler(null);
        BLNetwork.setBattleRoyaleServerListener(null);
        current.gunService.clear();
        current.hitHistory.clear();
        current.meleeService.clear();
        current.equipmentService.clear();
        NetHooks.setListener(null);
        Suspicion.setSink(null);
        BLNetwork.setInboundGuard(null);
        current.antiCheat.clear();
        instance = null;
    }

    private void tick() {
        hitHistory.tick(server);
        sessions.tick();
        jobs.tick();
        games.tick();
        matchmaking.tick();
        parties.tick();
        gunService.tick();
        meleeService.tick();
        equipmentService.tick();
        actions.tick();
        parachutes.tick();
        supply.tick();
        armor.tick();
        graffiti.tick();
        farTerrain.tick();
        kills.tick();
        spectate.tick();
        // after the worlds ticked: the velocities of this tick are sent, the pings that acknowledge them follow
        antiCheat.tick();
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
        lobby.activitiesChanged();
    }
}
