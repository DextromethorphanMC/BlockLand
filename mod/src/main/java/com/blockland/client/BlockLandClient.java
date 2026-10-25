package com.blockland.client;

import com.blockland.client.craft.ClientCraft;
import com.blockland.client.craft.CraftPanel;
import com.blockland.client.creature.CreatureRenderers;
import com.blockland.client.practice.PracticeTargetRenderer;
import com.blockland.client.decal.Decals;
import com.blockland.client.effect.ClientActionLock;
import com.blockland.client.font.UiFont;
import com.blockland.client.graphics.Graphics;
import com.blockland.client.gun.ClientGuns;
import com.blockland.client.infection.ClientInfection;
import com.blockland.client.input.WeaponInput;
import com.blockland.client.melee.ClientKnives;
import com.blockland.client.morph.ClientMorphs;
import com.blockland.client.movement.ClientActions;
import com.blockland.client.movement.ActionPoses;
import com.blockland.client.render.ModelCache;
import com.blockland.client.render.Textures;
import com.blockland.client.result.MatchResults;
import com.blockland.client.scope.Optics;
import com.blockland.client.screen.KitLayoutScreen;
import com.blockland.client.screen.LobbyScreen;
import com.blockland.client.settings.AimSensitivity;
import com.blockland.client.settings.ClientSettings;
import com.blockland.client.settings.SettingsAccess;
import com.blockland.client.thirdperson.ThirdPersonWeapons;
import com.blockland.client.viewmodel.Holster;
import com.blockland.client.viewmodel.ParticlePresets;
import com.blockland.client.viewmodel.ViewmodelImpulses;
import com.blockland.client.viewmodel.ViewmodelMotion;
import com.blockland.client.viewmodel.ViewmodelRenderer;
import com.blockland.network.BLNetwork;
import com.blockland.network.ClientPacketListener;
import com.blockland.network.packet.ActivityPackets;
import com.blockland.network.packet.C2SSubmitKit;
import com.blockland.network.packet.LobbyPackets;
import com.blockland.network.packet.MediaPackets;
import com.blockland.network.packet.S2CKitData;
import com.blockland.network.packet.S2CLobbyState;
import com.blockland.network.packet.S2CMatchFound;
import com.blockland.network.packet.S2CMatchResult;
import com.blockland.network.packet.ShopPackets;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerReconfigScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.SystemMessageReceivedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

/**
 * Client module: the lobby (state caches and screens) and the wiring of the independent client features (weapon
 * input, guns, knives, craft, decals, match results, morphs, infection, settings). Never loaded on a dedicated server.
 * <p>
 * On a BlockLand server the lobby is the configuration phase: no world is loaded while the player is in it, the
 * lobby screen stands in for vanilla's connection screens and keeps the connection running itself.
 */
public final class BlockLandClient {
    public static final KeyMapping OPEN_LOBBY = new KeyMapping("key.blockland.lobby", InputConstants.KEY_M, "key.categories.blockland");

    private static boolean settingsLoaded;

    private BlockLandClient() {
    }

    public static void init(BusGroup modBus) {
        // every animation runs on the render clock (the benchmark's frame comparison holds it)
        com.blockland.anim.Ticker.setClock(com.blockland.client.render.RenderClock::nanos);
        RegisterKeyMappingsEvent.getBus(modBus).addListener(event -> event.register(OPEN_LOBBY));
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> {
            if (!settingsLoaded) {
                settingsLoaded = true;
                ClientSettings.load();
            }
            while (OPEN_LOBBY.consumeClick()) backToLobby();
            tickLobbyConnection();
        });
        // back from a match the server switches to configuration again: show the lobby, not vanilla's "reconfiguring"
        ScreenEvent.Opening.BUS.addListener(event -> {
            if (event.getNewScreen() instanceof ServerReconfigScreen && ClientLobbyState.known()) event.setNewScreen(new LobbyScreen());
            return false;
        });
        // players move between the lobby and matches all the time here: vanilla's joined/left lines are only noise
        SystemMessageReceivedEvent.BUS.addListener(event -> {
            return ClientLobbyState.known() && event.getMessage().getContents() instanceof TranslatableContents text
                    && text.getKey().startsWith("multiplayer.player.");
        });
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> {
            ClientLobbyState.clear();
            ClientShopState.clear();
            ClientMedia.resetRequests();
            ClientAnnouncements.clear();
            ClientActivities.clear();
            com.blockland.client.render.LightSources.clear();
            BLNetwork.forgetClientConnection();
        });
        BLNetwork.setClientListener(new Handler());
        // retained GPU models and textures belong to the resource packs: reloaded on demand after F3+T
        RegisterClientReloadListenersEvent.getBus(modBus).addListener(event -> event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> {
                    ModelCache.clear();
                    Textures.clear();
                }));
        // every BlockLand screen and HUD draws its text with the mod's own font (Noto Sans SC as distance fields)
        UiFont.init(modBus);
        WeaponInput.init(modBus);
        Decals.init(modBus);
        com.blockland.client.impact.Impacts.init(modBus);
        // blood flying into a body leaves a stain on it (rendering spec §19): the impact module hands it to the body decals
        com.blockland.client.impact.Impacts.setBodyStains((body, at, dir, decal, size) ->
                Decals.onEntity(decal, body, at.subtract(dir.scale(0.4)), at, dir));
        // the render pipeline (render scale, upscaling, anti-aliasing, HDR post, sun and shadows, dynamic lights): first,
        // so its frame hooks and HUD composite layer come before every feature's own
        Graphics.init(modBus);
        // optimizations, frame timings and the benchmark (after the pipeline: its capture layer follows the composite)
        com.blockland.client.perf.Perf.init(modBus);
        com.blockland.client.textures.FluidTextures.init(modBus);
        com.blockland.client.fluid.WaterInteractions.init();
        Holster.init();
        ViewmodelImpulses.init(modBus);
        ParticlePresets.init(modBus);
        ClientGuns.init(modBus);
        Optics.init(modBus);
        ClientKnives.init(modBus);
        com.blockland.client.equipment.ClientEquipment.init(modBus);
        // weapons in third person, dropped and in frames as their 3D models
        ThirdPersonWeapons.init(modBus);
        ClientCraft.init(modBus);
        com.blockland.client.screen.lobby.FigureRenderer.init();
        LobbyScreen.setCraftTab(CraftPanel::lobby);
        AimSensitivity.init();
        SettingsAccess.init(modBus);
        com.blockland.client.screen.pause.PauseMenus.init();
        MatchResults.init(modBus);
        com.blockland.client.loading.MatchLoading.init();
        com.blockland.client.combat.KillFeedback.init(modBus);
        com.blockland.client.hud.KillIcon.init(modBus);
        ClientMorphs.init();
        ClientInfection.init(modBus);
        com.blockland.client.rank.ClientRank.init();
        com.blockland.client.bomb.ClientBomb.init(modBus);
        com.blockland.client.graffiti.ClientGraffiti.init(modBus);
        com.blockland.client.spectate.ClientSpectate.init(modBus);
        com.blockland.client.profile.ClientProfile.init(modBus);
        com.blockland.client.battleroyale.ClientBattleRoyale.init(modBus);
        CreatureRenderers.init(modBus);
        PracticeTargetRenderer.init(modBus);
        ClientActionLock.init(modBus);
        // what equipment does when it goes off: blasts, smoke, fire, the flash white-out and the adrenaline rush
        com.blockland.client.equipment.effect.ClientEquipmentEffects.init(modBus);
        // movement actions (slide, prone, dive): prediction and camera, third-person poses, and the weapon's poses
        // (shared viewmodel motion)
        ClientActions.init(modBus);
        ActionPoses.init();
        com.blockland.client.parachute.ClientParachute.init(modBus);
        com.blockland.client.supply.ClientSupply.init(modBus);
        com.blockland.client.armor.ClientArmor.init(modBus);
        ViewmodelMotion.setBodyMotion(ClientActions.BODY);
        com.blockland.client.dev.DevAutomation.init();
    }

    /**
     * Vanilla keeps a connection running from its connection screens; in the lobby ours are open instead, so the
     * connection is ticked here (and a lost one is reported, which shows the usual disconnect screen).
     */
    private static void tickLobbyConnection() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null || mc.getConnection() != null) return;
        if (mc.screen instanceof ConnectScreen || mc.screen instanceof ServerReconfigScreen) return;
        BLNetwork.tickClientConnection();
    }

    /** M in the world (an operator who left the lobby): back to the lobby. During a match the match decides. */
    private static void backToLobby() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null || !ClientLobbyState.known()) return;
        if (ClientLobbyState.inGame()) {
            SystemToast.addOrUpdate(mc.getToastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.translatable("blockland.lobby.title"), Component.translatable("blockland.lobby.in_match"));
            return;
        }
        mc.getConnection().sendCommand("bl lobby");
    }

    private static final class Handler implements ClientPacketListener {
        @Override
        public void onLobbyState(S2CLobbyState state) {
            String queuedBefore = ClientLobbyState.queuedModeId();
            ClientLobbyState.update(state);
            Minecraft mc = Minecraft.getInstance();
            if (!state.queuedModeId().isEmpty() && !state.queuedModeId().equals(queuedBefore) && !ClientLobbyState.isLeader()) {
                // the leader queued the party: our own saved kit arrangement goes along
                ClientKitLayouts.any(state.queuedModeId()).ifPresent(layout -> BLNetwork.sendToServer(
                        new C2SSubmitKit(state.queuedModeId(), layout.signature(), layout.slots())));
            }
            // arriving in the lobby (connected, or back from a match): normally there is no world behind it
            if (state.openScreen() && (mc.level == null || mc.screen == null) && !(mc.screen instanceof LobbyScreen)) {
                mc.setScreen(new LobbyScreen());
            }
        }

        @Override
        public void onKitData(S2CKitData kit) {
            if (Minecraft.getInstance().screen instanceof KitLayoutScreen screen && screen.modeId().equals(kit.modeId())) {
                screen.setData(kit);
            }
        }

        @Override
        public void onShopData(ShopPackets.S2CData data) {
            ClientShopState.update(data);
        }

        @Override
        public void onMedia(MediaPackets.S2CImage image) {
            ClientMedia.receive(image);
        }

        @Override
        public void onPurchaseResult(ShopPackets.S2CPurchaseResult result) {
            ClientShopState.purchaseResult(result);
        }

        @Override
        public void onMatchResult(S2CMatchResult result) {
            MatchResults.show(result);
        }

        @Override
        public void onMatchFound(S2CMatchFound match) {
            // the loading screen stays up while the map is prepared and the client loads straight into it
            com.blockland.client.loading.MatchLoading.start(match);
        }

        @Override
        public void onNotice(LobbyPackets.S2CNotice notice) {
            Minecraft mc = Minecraft.getInstance();
            Component title = Component.translatable(switch (notice.level()) {
                case INFO -> "blockland.notice.info";
                case SUCCESS -> "blockland.notice.success";
                case WARNING -> "blockland.notice.warning";
                case ERROR -> "blockland.notice.error";
            });
            SystemToast.add(mc.getToastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, notice.message());
        }

        @Override
        public void onAnnouncements(LobbyPackets.S2CAnnouncements announcements) {
            ClientAnnouncements.update(announcements);
        }

        @Override
        public void onActivities(ActivityPackets.S2CActivities activities) {
            ClientActivities.update(activities);
        }
    }
}
