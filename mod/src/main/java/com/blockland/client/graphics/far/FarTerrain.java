package com.blockland.client.graphics.far;

import com.blockland.client.graphics.Graphics;
import com.blockland.client.graphics.GraphicsSettings;
import com.blockland.client.graphics.GraphicsStatus;
import com.blockland.client.graphics.frame.FrameContext;
import com.blockland.client.graphics.frame.FrameStage;
import com.blockland.client.graphics.frame.LevelPasses;
import com.blockland.client.perf.Optimization;
import com.blockland.network.BLNetwork;
import com.blockland.network.FarTerrainPacketListeners;
import com.blockland.network.packet.FarChunkPackets;
import com.blockland.network.packet.FarTerrainPackets;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.listener.Priority;

import javax.annotation.Nullable;

/**
 * Far terrain (远景地平线, rendering spec §22): distant terrain streamed from the server, cached, meshed into block columns
 * and drawn behind the vanilla terrain. Wired once from {@code Graphics.init}: the server's packets, a frame stage (the
 * selection, requests and uploads at frame start), the pass before vanilla's terrain ({@link LevelPasses}) and the fog
 * (vanilla's render-distance fade moves out to the far distance while it is drawn). Talks to the rest of the pipeline only
 * through {@link FrameContext#farDepth} and {@link GraphicsStatus#farTerrainReach}.
 */
public final class FarTerrain {
    private static final TileColors colors = new TileColors();
    private static final TileStore store = new TileStore(colors);
    private static final Selection selection = new Selection();
    private static final FarRenderer renderer = new FarRenderer();
    private static final RegionRenderer regions = new RegionRenderer();
    private static final ColumnStore columns = new ColumnStore(regions);

    @Nullable
    private static FrameContext frame;
    @Nullable
    private static ClientLevel lastLevel;
    /** Drawn this frame (decided at frame start), how far it reaches, the planes of its projection. */
    private static boolean active;
    private static float reach, near, far;
    private static float millis;

    private FarTerrain() {
    }

    public static void init(BusGroup modBus) {
        BLNetwork.setFarTerrainClientListener(new FarTerrainPacketListeners.Client() {
            @Override
            public void onHello(FarTerrainPackets.S2CHello hello) {
                store.hello(hello);
                columns.hello(hello);
                Graphics.status().farServerDistance = store.serverDistance();
            }

            @Override
            public void onTiles(FarTerrainPackets.S2CTiles tiles) {
                store.answers(tiles);
            }

            @Override
            public void onColumns(FarChunkPackets.S2CColumns answers) {
                columns.answers(answers, Minecraft.getInstance().level);
            }
        });
        Graphics.addStage(new Stage());
        LevelPasses.addBeforeTerrain(FarTerrain::draw);
        // before the pipeline's frame start: nothing of last frame's decision lingers when the pipeline does not run
        TickEvent.RenderTickEvent.Pre.BUS.addListener(Priority.HIGH, event -> {
            active = false;
            Graphics.status().farTerrainReach = 0;
        });
        ViewportEvent.RenderFog.BUS.addListener(Priority.LOW, FarTerrain::onFog);
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> {
            store.forgetSession();
            columns.forgetSession();
            renderer.close();
            lastLevel = null;
            Graphics.status().farServerDistance = 0;
        });
        RegisterClientReloadListenersEvent.getBus(modBus).addListener(event -> event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> {
                    store.recolour();
                    columns.remesh();
                }));
    }

    /** Frame start: what is wanted and drawn, requests, meshes, uploads, the coverage mask. */
    private static final class Stage implements FrameStage {
        private boolean wasOn;
        /** The meshing switches the real far chunks were meshed with (a change meshes them all again). */
        private boolean meshedGroups = Optimization.FACE_GROUPS.on(), meshedHidden = Optimization.FAR_HIDDEN_GEOMETRY.on();

        @Override
        public void beginFrame(FrameContext context) {
            frame = context;
            context.farDepth = null;
            Minecraft mc = Minecraft.getInstance();
            GraphicsSettings settings = Graphics.settings();
            GraphicsStatus status = Graphics.status();
            ClientLevel level = mc.level;
            if (level != lastLevel) {
                // another level: what we hold belongs to the old one (the server's hello for the new one follows)
                store.clear(false);
                columns.clear(false);
                lastLevel = level;
            }
            String reason = null;
            if (!settings.farTerrain) reason = "blockland.graphics.status.far.off";
            else if (level == null) reason = "blockland.graphics.status.no_world";
            else if (!store.enabled()) reason = "blockland.graphics.status.far.server";
            status.farTerrainReason = reason;
            if (reason != null) {
                if (wasOn) {
                    store.clear(true);
                    columns.clear(true);
                    renderer.close();
                    }
                wasOn = false;
                status.farTilesDrawn = status.farTilesWanted = status.farTilesHeld = 0;
                status.farCells = status.farGpuBytes = 0;
                status.farChunksDrawn = status.farChunksPlaced = status.farChunksHeld = status.farChunksPending = 0;
                status.farChunkGpuBytes = 0;
                return;
            }
            wasOn = true;
            long start = System.nanoTime();
            long now = System.currentTimeMillis();
            store.beginFrame();
            Camera camera = mc.gameRenderer.getMainCamera();
            Vec3 pos = camera.getPosition();
            int renderDistance = mc.options.getEffectiveRenderDistance();
            int camChunkX = Math.floorDiv((int) Math.floor(pos.x), 16), camChunkZ = Math.floorDiv((int) Math.floor(pos.z), 16);
            reach = Math.min(settings.farTerrainDistance, store.serverDistance());
            // the real far chunks (§23) out to their distance, the tiles beyond (and in their gaps while they load)
            int ring = 0;
            if (settings.farChunks && columns.enabled()) {
                ring = Math.min(settings.farChunkDistance, (int) Math.ceil(reach / 16));
                if (ring <= renderDistance) ring = 0;
            }
            if (Optimization.FACE_GROUPS.on() != meshedGroups || Optimization.FAR_HIDDEN_GEOMETRY.on() != meshedHidden) {
                meshedGroups = Optimization.FACE_GROUPS.on();
                meshedHidden = Optimization.FAR_HIDDEN_GEOMETRY.on();
                columns.remesh();
            }
            if (ring > 0) {
                columns.beginFrame(now);
                columns.frame(level, camChunkX, camChunkZ, renderDistance, settings.farChunkFullDetail, ring, now);
            } else if (regions.columns() > 0 || columns.held() > 0) {
                columns.clear(true);
            }
            // tiles reaching into the real far chunks' area are masked (they fill only the chunks no unit holds)
            int covered = Math.max(renderDistance, ring);
            double inner = Math.max(0, (renderDistance - 1.5) * 16), outer = (covered + 1.5) * 16;
            double full = Math.max(renderDistance, settings.farTerrainFullDetail) * 16.0;
            selection.run(store, pos.x, pos.z, reach, settings.farTerrainQualityMode().detail, inner, outer, full, now);
            store.schedule(level, now);
            renderer.mask.update(level, camChunkX, camChunkZ, renderDistance, columns, columns.coverageChanged);
            columns.coverageChanged = false;
            near = Math.max(8, Math.min(256, (renderDistance - 2) * 8f));
            far = reach * 1.5f + 4096;
            active = true;
            status.farTerrainReach = reach;
            status.farTilesWanted = selection.wanted;
            status.farTilesHeld = store.held();
            status.farTilesDrawn = renderer.drawn;
            status.farCells = renderer.cells;
            status.farGpuBytes = store.gpuBytes();
            status.farChunksDrawn = ring > 0 ? renderer.chunksDrawn : 0;
            status.farChunksPlaced = columns.placedCount();
            status.farChunksHeld = columns.held();
            status.farChunksPending = columns.pending();
            status.farChunkGpuBytes = regions.gpuBytes();
            millis = millis * 0.9f + (System.nanoTime() - start) / 1e6f * 0.1f;
            status.farMillis = millis;
        }
    }

    /** The pass before vanilla's terrain (main view and scope pictures). */
    private static void draw(LevelPasses.View view) {
        if (!active || frame == null) return;
        GpuTextureView depth = renderer.draw(view, selection.draws, regions.columns() > 0 ? regions : null, near, far);
        if (view.mainView()) {
            frame.farDepth = depth;
            frame.farNear = near;
            frame.farFar = far;
        }
    }

    /**
     * While far terrain is drawn: vanilla's render-distance fade moves out to the far distance (vanilla's terrain no longer
     * fades into the sky where the far terrain carries on) and the environmental fog stretches by far / 1024 (vanilla's
     * clear-weather fog ends at 1024 blocks). Fog that belongs to the game (water, lava, blindness) stays.
     */
    private static boolean onFog(ViewportEvent.RenderFog event) {
        if (!active || reach <= 0 || event.getType() != FogType.ATMOSPHERIC) return false;
        if (event.getCamera().getEntity() instanceof LivingEntity living
                && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS))) return false;
        FogData data = event.getData();
        boolean fog = Graphics.settings().fog;
        data.renderDistanceEnd = reach;
        data.renderDistanceStart = reach * (fog ? 0.9f : 0.96f);
        if (data.environmentalEnd < Float.MAX_VALUE / 2) {
            // vanilla's clear-weather fog (0 … 1024 blocks) stretched over the far terrain's second half, so the land stays
            // clear out to half the far distance (rain still pulls both in)
            float stretch = reach / 1024f;
            data.environmentalStart = reach * 0.5f + data.environmentalStart * stretch;
            data.environmentalEnd = Math.max(data.environmentalStart + 1, data.environmentalEnd * stretch);
        }
        return false;
    }
}
