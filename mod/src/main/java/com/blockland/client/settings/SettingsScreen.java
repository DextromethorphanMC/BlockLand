package com.blockland.client.settings;

import com.blockland.anim.Anim;
import com.blockland.anim.AnimValue;
import com.blockland.anim.ColorMath;
import com.blockland.anim.Ease;
import com.blockland.anim.Spring;
import com.blockland.anim.Ticker;
import com.blockland.client.ClientLobbyState;
import com.blockland.client.font.UiFont;
import com.blockland.client.graphics.Graphics;
import com.blockland.client.graphics.GraphicsSettings;
import com.blockland.client.graphics.GraphicsStatus;
import com.blockland.client.hud.Crosshair;
import com.blockland.client.hud.HitMarker;
import com.blockland.client.impact.ImpactSettings;
import com.blockland.client.scope.ScopeQuality;
import com.blockland.client.ui.Draw;
import com.blockland.network.BLNetwork;
import com.blockland.network.packet.LobbyPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static com.blockland.client.screen.lobby.LobbyTheme.*;
import static com.blockland.client.ui.Tr.tr;

/**
 * The lobby's settings page (设置): 通用 (language), 画质 (the render pipeline: presets, resolution and anti-aliasing,
 * lighting, screen-space effects, camera and colour, and what the pipeline is doing right now), 枪械 (look, aim-down-sights
 * and per-magnification scope sensitivity, crosshair and hit marker style, with a live preview) and 隐私 (party
 * invitations). Client preferences are saved on this computer as they change; the privacy choice is sent to the server,
 * which stores and enforces it. Graphics changes apply live; the world lighting switch (a resource reload) applies when
 * the page closes.
 */
public final class SettingsScreen extends Screen {
    private static final int PAD = 16;
    private static final int ROW_H = 22;
    private static final int[] SWATCHES = {0xFFFFFF, 0x4CFF6A, 0x4CE6FF, 0xFFE34C, 0xFF4C4C, 0xFF4CF5, 0xFFA235, 0x8C7CFF};
    private static final String[][] LANGUAGES = {{"zh_cn", "简体中文"}, {"en_us", "English"}};
    /** How much a row fades while it has no effect (pipeline off). */
    private static final float DIMMED = 0.45f;

    private enum Tab {
        GENERAL("blockland.settings.tab.general"), GRAPHICS("blockland.settings.tab.graphics"),
        WEAPONS("blockland.settings.tab.weapons"), PRIVACY("blockland.settings.tab.privacy");

        final String key;

        Tab(String key) {
            this.key = key;
        }
    }

    private static Tab tab = Tab.WEAPONS;

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private final Map<String, Spring> hover = new HashMap<>();
    private final AnimValue intro = new AnimValue(0);
    private final Spring tabX = new Spring(0).config(260, 24);
    private final Spring tabW = new Spring(0).config(260, 24);
    private float scroll;
    private int contentTop, contentBottom, contentHeight;
    private int columnX, columnW;
    private Row dragging;
    /** Which colour the RGB sliders edit. */
    private ColorTarget colorTarget = ColorTarget.CROSSHAIR;
    private double nextPreviewHit;
    private int previewKind;
    private boolean committed;

    private enum ColorTarget {
        CROSSHAIR("blockland.settings.crosshair.color"), HIT("blockland.settings.hitmarker.hit_color"),
        CRITICAL("blockland.settings.hitmarker.critical_color"), KILL("blockland.settings.hitmarker.kill_color");

        final String key;

        ColorTarget(String key) {
            this.key = key;
        }
    }

    public SettingsScreen(Screen parent) {
        super(Component.translatable("blockland.settings.title"));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        Anim.to(intro, 1, 0.4).ease(Ease.QUART_OUT);
        rebuild();
    }

    @Override
    public void onClose() {
        commit();
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        // left some other way (a disconnect screen replaced it): the settings still apply
        commit();
    }

    /** Applies the expensive graphics changes (resource reload for world lighting) and saves the file, once. */
    private void commit() {
        if (committed) return;
        committed = true;
        Graphics.settingsCommitted();
        ClientSettings.save();
    }

    // ---- rows ---------------------------------------------------------------------------------------------

    private void rebuild() {
        rows.clear();
        ClientSettings.Data data = ClientSettings.get();
        switch (tab) {
            case GENERAL -> {
                rows.add(new Header("blockland.settings.section.language"));
                rows.add(new Choice("blockland.settings.language", () -> {
                    String code = Minecraft.getInstance().options.languageCode;
                    return code.equals("en_us") ? 1 : 0;
                }, index -> setLanguage(LANGUAGES[index][0]), LANGUAGES[0][1], LANGUAGES[1][1]));
                rows.add(new Note("blockland.settings.language_note"));
            }
            case GRAPHICS -> graphicsRows(data.graphics);
            case WEAPONS -> {
                rows.add(new Header("blockland.settings.section.sensitivity"));
                rows.add(new Slider("blockland.settings.look_sensitivity", 0, 1, 0.005,
                        () -> Minecraft.getInstance().options.sensitivity().get(),
                        v -> {
                            Minecraft.getInstance().options.sensitivity().set(v);
                            Minecraft.getInstance().options.save();
                        },
                        v -> Math.round(v * 200) + "%"));
                rows.add(new Slider("blockland.settings.ads_sensitivity", ScopeSensitivity.MIN, ScopeSensitivity.MAX, 0.01,
                        () -> data.adsSensitivity, v -> data.adsSensitivity = (float) v, v -> String.format(Locale.ROOT, "%.2f×", v)));
                // one multiplier per magnification class, on top of the physical slow-down by the power
                for (int i = 0; i < ScopeSensitivity.CLASSES.length; i++) {
                    float power = ScopeSensitivity.CLASSES[i];
                    boolean last = i == ScopeSensitivity.CLASSES.length - 1;
                    rows.add(new Slider(last ? "blockland.settings.scope_sensitivity.class_up" : "blockland.settings.scope_sensitivity.class",
                            new Object[]{Math.round(power)}, ScopeSensitivity.MIN, ScopeSensitivity.MAX, 0.01,
                            () -> ScopeSensitivity.get(power), v -> ScopeSensitivity.set(power, (float) v),
                            v -> String.format(Locale.ROOT, "%.2f×", v)));
                }
                rows.add(new Note(() -> tr("blockland.settings.scope_sensitivity.note")));
                String scopes = ScopeSensitivity.scopesInGame();
                if (!scopes.isEmpty()) rows.add(new Note(() -> tr("blockland.settings.scope_sensitivity.scopes", scopes)));

                rows.add(new Header("blockland.settings.section.scope"));
                rows.add(new Choice("blockland.settings.scope.quality", () -> ScopeQuality.of(data.scopeQuality).ordinal(),
                        i -> data.scopeQuality = ScopeQuality.values()[i].id(),
                        tr("blockland.settings.scope.quality.off"), tr("blockland.settings.scope.quality.low"),
                        tr("blockland.settings.scope.quality.medium"), tr("blockland.settings.scope.quality.high")));
                rows.add(new Toggle("blockland.settings.scope.blur_outside", () -> data.scopeBlurOutside, v -> data.scopeBlurOutside = v));
                rows.add(new Toggle("blockland.settings.scope.dim_outside", () -> data.scopeDimOutside, v -> data.scopeDimOutside = v));
                rows.add(new Note("blockland.settings.scope.note"));

                rows.add(new Header("blockland.settings.section.camera"));
                rows.add(new Slider("blockland.settings.camera.impact", 0, 1.5, 0.05,
                        () -> data.cameraImpact, v -> data.cameraImpact = (float) v, v -> Math.round(v * 100) + "%"));
                rows.add(new Slider("blockland.settings.camera.motion", 0, 1.5, 0.05,
                        () -> data.cameraMotion, v -> data.cameraMotion = (float) v, v -> Math.round(v * 100) + "%"));
                rows.add(new Note("blockland.settings.camera.note"));

                Crosshair.Style c = data.crosshair;
                rows.add(new Header("blockland.settings.section.crosshair"));
                rows.add(new Choice("blockland.settings.crosshair.shape", () -> Crosshair.Shape.of(c.shape).ordinal(),
                        i -> c.shape = Crosshair.Shape.values()[i].id(),
                        tr("blockland.settings.crosshair.shape.cross"), tr("blockland.settings.crosshair.shape.cross_dot"),
                        tr("blockland.settings.crosshair.shape.t"), tr("blockland.settings.crosshair.shape.dot"),
                        tr("blockland.settings.crosshair.shape.circle")));
                rows.add(new Swatches(ColorTarget.CROSSHAIR, () -> c.color, v -> c.color = v));
                rows.add(new Slider("blockland.settings.crosshair.opacity", 0.1, 1, 0.05, () -> c.opacity, v -> c.opacity = (float) v,
                        v -> Math.round(v * 100) + "%"));
                rows.add(new Slider("blockland.settings.crosshair.length", 1, 14, 1, () -> c.length, v -> c.length = (int) Math.round(v),
                        v -> String.valueOf(Math.round(v))));
                rows.add(new Slider("blockland.settings.crosshair.thickness", 1, 4, 1, () -> c.thickness, v -> c.thickness = (int) Math.round(v),
                        v -> String.valueOf(Math.round(v))));
                rows.add(new Slider("blockland.settings.crosshair.gap", 0, 12, 1, () -> c.gap, v -> c.gap = (int) Math.round(v),
                        v -> String.valueOf(Math.round(v))));
                rows.add(new Toggle("blockland.settings.crosshair.outline", () -> c.outline, v -> c.outline = v));
                rows.add(new Toggle("blockland.settings.crosshair.dynamic", () -> c.dynamic, v -> c.dynamic = v));

                HitMarker.Style h = data.hitmarker;
                rows.add(new Header("blockland.settings.section.hitmarker"));
                rows.add(new Choice("blockland.settings.hitmarker.shape", () -> HitMarker.Shape.of(h.shape).ordinal(),
                        i -> h.shape = HitMarker.Shape.values()[i].id(),
                        tr("blockland.settings.hitmarker.shape.x"), tr("blockland.settings.hitmarker.shape.cross"),
                        tr("blockland.settings.hitmarker.shape.ring")));
                rows.add(new Swatches(ColorTarget.HIT, () -> h.hitColor, v -> h.hitColor = v));
                rows.add(new Swatches(ColorTarget.CRITICAL, () -> h.criticalColor, v -> h.criticalColor = v));
                rows.add(new Swatches(ColorTarget.KILL, () -> h.killColor, v -> h.killColor = v));
                rows.add(new Slider("blockland.settings.hitmarker.size", 0.5, 2, 0.05, () -> h.size, v -> h.size = (float) v,
                        v -> Math.round(v * 100) + "%"));
                rows.add(new Slider("blockland.settings.hitmarker.thickness", 1, 3, 1, () -> h.thickness, v -> h.thickness = (int) Math.round(v),
                        v -> String.valueOf(Math.round(v))));
                rows.add(new Slider("blockland.settings.hitmarker.duration", 0.5, 2, 0.05, () -> h.duration, v -> h.duration = (float) v,
                        v -> Math.round(v * 100) + "%"));

                rows.add(new Header("blockland.settings.section.rgb"));
                rows.add(new Slider("blockland.settings.rgb.r", 0, 255, 1, () -> channel(16), v -> setChannel(16, v), v -> String.valueOf(Math.round(v))));
                rows.add(new Slider("blockland.settings.rgb.g", 0, 255, 1, () -> channel(8), v -> setChannel(8, v), v -> String.valueOf(Math.round(v))));
                rows.add(new Slider("blockland.settings.rgb.b", 0, 255, 1, () -> channel(0), v -> setChannel(0, v), v -> String.valueOf(Math.round(v))));
                rows.add(new Action("blockland.settings.reset_weapons", () -> {
                    data.adsSensitivity = 1;
                    data.scopeSensitivities = ScopeSensitivity.defaults();
                    data.scopeQuality = ScopeQuality.MEDIUM.id();
                    data.scopeBlurOutside = true;
                    data.scopeDimOutside = false;
                    data.cameraImpact = 1;
                    data.cameraMotion = 1;
                    data.crosshair = new Crosshair.Style();
                    data.hitmarker = new HitMarker.Style();
                    ClientSettings.save();
                    rebuild();
                }));
            }
            case PRIVACY -> {
                rows.add(new Header("blockland.settings.section.party"));
                rows.add(new Toggle("blockland.settings.allow_invites", ClientLobbyState::allowInvites,
                        v -> BLNetwork.sendToServer(new LobbyPackets.C2SSettings(v))));
                rows.add(new Note("blockland.settings.allow_invites_note"));
            }
        }
    }

    /** The 画质 tab: every {@link GraphicsSettings} value, grouped, then what the pipeline is doing. */
    private void graphicsRows(GraphicsSettings g) {
        String gfx = "blockland.settings.graphics.";
        // ---- master switch and preset
        rows.add(new Header(gfx + "section.master"));
        rows.add(live(new Toggle(gfx + "enabled", () -> g.enabled, v -> g.enabled = v)));
        rows.add(new Note(gfx + "enabled_note"));
        rows.add(new Choice(gfx + "preset", () -> g.presetMode().ordinal(), i -> {
            GraphicsSettings.Preset preset = GraphicsSettings.Preset.values()[i];
            if (preset == GraphicsSettings.Preset.CUSTOM) g.preset = GraphicsSettings.id(preset);
            else g.apply(preset);
        }, tr(gfx + "preset.low"), tr(gfx + "preset.medium"), tr(gfx + "preset.high"), tr(gfx + "preset.ultra"),
                tr(gfx + "preset.custom")).then(() -> {
            Graphics.settingsChanged();
            rebuild();
        }).dim(this::graphicsDimmed));

        // ---- resolution and anti-aliasing
        rows.add(new Header(gfx + "section.resolution"));
        rows.add(custom(new Choice(gfx + "upscaler", () -> g.upscalerMode().ordinal(),
                i -> g.upscaler = GraphicsSettings.id(GraphicsSettings.Upscaler.values()[i]),
                tr(gfx + "upscaler.none"), tr(gfx + "upscaler.taau"), tr(gfx + "upscaler.dlss"), tr(gfx + "upscaler.dlaa")))
                .then(this::rebuild));
        GraphicsSettings.Upscaler upscaler = g.upscalerMode();
        if (upscaler == GraphicsSettings.Upscaler.TAAU) {
            rows.add(custom(new Choice(gfx + "upscale_quality", () -> g.upscaleQualityMode().ordinal(),
                    i -> g.upscaleQuality = GraphicsSettings.id(GraphicsSettings.UpscaleQuality.values()[i]),
                    tr(gfx + "upscale_quality.native"), tr(gfx + "upscale_quality.quality"), tr(gfx + "upscale_quality.balanced"),
                    tr(gfx + "upscale_quality.performance"), tr(gfx + "upscale_quality.ultra_performance"))));
            rows.add(new Note(() -> tr(gfx + "upscale_quality.note", Math.round(g.upscaleQualityMode().scale * 100))));
        } else if (upscaler == GraphicsSettings.Upscaler.DLSS) {
            // DLSS Super Resolution's quality modes (native resolution is the DLAA choice above)
            rows.add(custom(new Choice(gfx + "dlss_quality", () -> Math.max(0, g.upscaleQualityMode().ordinal() - 1),
                    i -> g.upscaleQuality = GraphicsSettings.id(GraphicsSettings.UpscaleQuality.values()[i + 1]),
                    tr(gfx + "upscale_quality.quality"), tr(gfx + "upscale_quality.balanced"),
                    tr(gfx + "upscale_quality.performance"), tr(gfx + "upscale_quality.ultra_performance"))));
            rows.add(new Note(() -> tr(gfx + "upscale_quality.note", Math.round(g.upscaleQualityMode().scale * 100))));
        } else if (upscaler == GraphicsSettings.Upscaler.NONE) {
            rows.add(custom(new Choice(gfx + "antialiasing", () -> g.antialiasingMode().ordinal(),
                    i -> g.antialiasing = GraphicsSettings.id(GraphicsSettings.AntiAliasing.values()[i]),
                    tr(gfx + "antialiasing.off"), tr(gfx + "antialiasing.fxaa"), tr(gfx + "antialiasing.taa"))));
        }
        if (g.dlssChosen()) {
            rows.add(new Note(this::dlssLine));
            rows.add(custom(new Choice(gfx + "dlss_preset", () -> g.dlssPresetMode().ordinal(),
                    i -> g.dlssPreset = GraphicsSettings.id(GraphicsSettings.DlssPreset.values()[i]),
                    tr(gfx + "dlss_preset.default"), "J", "K", "L", "M")));
            rows.add(new Note(() -> tr(gfx + "dlss_preset.note")));
            rows.add(custom(new Slider(gfx + "dlss_sharpness", 0, 1, 0.05, () -> g.dlssSharpness, v -> g.dlssSharpness = (float) v,
                    SettingsScreen::percentOrOff)));
        } else {
            rows.add(custom(new Slider(gfx + "sharpness", 0, 1, 0.05, () -> g.sharpness, v -> g.sharpness = (float) v, SettingsScreen::percent)));
        }
        // DLSS Frame Generation works with any upscaler: 2× is Frame Generation, 3× and more Multi Frame Generation
        rows.add(custom(new Choice(gfx + "dlss_frame_generation", () -> g.frameGeneration - 1, i -> g.frameGeneration = i + 1,
                tr(gfx + "dlss_frame_generation.off"), "2×", "3×", "4×", "5×", "6×")).then(this::rebuild));
        rows.add(new Note(this::frameGenLine));
        if (g.frameGeneration > 1) {
            rows.add(custom(new Choice(gfx + "reflex", () -> g.reflexBoost() ? 1 : 0, i -> g.reflex = i == 1 ? "boost" : "on",
                    tr(gfx + "reflex.on"), tr(gfx + "reflex.boost"))));
            rows.add(new Note(() -> tr(gfx + "dlss_frame_generation.note")));
        }
        rows.add(new Info(gfx + "dlss_ray_reconstruction", this::rayReconstructionLine,
                () -> Graphics.status().rayReconstructionActive ? OK : MUTED));

        // ---- DLSS 5 neural rendering (RTX Remix's options)
        rows.add(new Header(gfx + "section.neural"));
        rows.add(custom(new Toggle(gfx + "neural", () -> g.neuralRendering, v -> g.neuralRendering = v)).then(this::rebuild));
        rows.add(new Note(this::neuralLine));
        if (g.neuralRendering) {
            rows.add(custom(new Choice(gfx + "neural_model", () -> g.neuralModel, i -> g.neuralModel = i,
                    tr(gfx + "neural_model.a"), tr(gfx + "neural_model.b"), tr(gfx + "neural_model.c"))));
            rows.add(new Note(() -> tr(gfx + "neural_model.note")));
            rows.add(custom(new Slider(gfx + "neural_intensity", 0, 1, 0.05, () -> g.neuralIntensity, v -> g.neuralIntensity = (float) v,
                    SettingsScreen::percentOrOff)));
            rows.add(custom(new Slider(gfx + "neural_structure", 0, 1, 0.05, () -> g.neuralStructure, v -> g.neuralStructure = (float) v,
                    SettingsScreen::percentOrOff)));
            rows.add(new Note(() -> tr(gfx + "neural_structure.note")));
            rows.add(custom(new Slider(gfx + "neural_tone", 0, 1, 0.05, () -> g.neuralTone, v -> g.neuralTone = (float) v,
                    SettingsScreen::percentOrOff)));
            rows.add(new Note(() -> tr(gfx + "neural_tone.note")));
            rows.add(custom(new Toggle(gfx + "neural_auto_mask", () -> g.neuralAutoMask, v -> g.neuralAutoMask = v)).then(this::rebuild));
            if (g.neuralAutoMask) {
                rows.add(custom(new Slider(gfx + "neural_characters", 0, 1, 0.05, () -> g.neuralCharacters,
                        v -> g.neuralCharacters = (float) v, SettingsScreen::percentOrOff)));
            }
            rows.add(custom(new Toggle(gfx + "neural_highlights", () -> g.neuralHighlightRecovery, v -> g.neuralHighlightRecovery = v)));
            rows.add(new Note(() -> tr(gfx + "neural.note")));
        }

        // ---- lighting
        rows.add(new Header(gfx + "section.lighting"));
        rows.add(custom(new Toggle(gfx + "world_lighting", () -> g.worldLighting, v -> g.worldLighting = v)));
        rows.add(new Note(() -> tr(gfx + "world_lighting.note")));
        rows.add(custom(new Choice(gfx + "shadows", () -> g.shadowQuality().ordinal(),
                i -> g.shadows = GraphicsSettings.id(GraphicsSettings.ShadowQuality.values()[i]),
                tr(gfx + "quality.off"), tr(gfx + "quality.low"), tr(gfx + "quality.medium"), tr(gfx + "quality.high"),
                tr(gfx + "quality.ultra"))));
        rows.add(custom(new Slider(gfx + "shadow_distance", 16, 256, 8, () -> g.shadowDistance, v -> g.shadowDistance = (float) v,
                v -> tr(gfx + "blocks", Math.round(v)))));
        rows.add(custom(new Slider(gfx + "shadow_softness", 0.25, 3, 0.05, () -> g.shadowSoftness, v -> g.shadowSoftness = (float) v,
                v -> String.format(Locale.ROOT, "%.2f×", v))));
        rows.add(custom(new Slider(gfx + "sun_strength", 0, 2, 0.05, () -> g.sunStrength, v -> g.sunStrength = (float) v, SettingsScreen::percent)));
        // ---- light colours (with world lighting; the ray tracer and the clouds take them too)
        rows.add(custom(new Slider(gfx + "sun_temperature", -1, 1, 0.05, () -> g.sunTemperature, v -> g.sunTemperature = (float) v,
                SettingsScreen::temperature)));
        rows.add(custom(new Slider(gfx + "moon_temperature", -1, 1, 0.05, () -> g.moonTemperature, v -> g.moonTemperature = (float) v,
                SettingsScreen::temperature)));
        rows.add(custom(new Slider(gfx + "block_light_temperature", -1, 1, 0.05, () -> g.blockLightTemperature,
                v -> g.blockLightTemperature = (float) v, SettingsScreen::temperature)));
        rows.add(custom(new Slider(gfx + "block_light_strength", 0, 2, 0.05, () -> g.blockLightStrength,
                v -> g.blockLightStrength = (float) v, SettingsScreen::percent)));
        rows.add(new Note(() -> tr(gfx + "light_colors.note")));
        rows.add(custom(new Toggle(gfx + "dynamic_lights", () -> g.dynamicLights, v -> g.dynamicLights = v)));
        rows.add(custom(new Toggle(gfx + "held_item_lights", () -> g.heldItemLights, v -> g.heldItemLights = v)));
        rows.add(custom(new Slider(gfx + "emission", 0, 2, 0.05, () -> g.emission, v -> g.emission = (float) v, SettingsScreen::percent)));
        rows.add(custom(new Toggle(gfx + "water_reflections", () -> g.waterReflections, v -> g.waterReflections = v)));
        rows.add(custom(new Toggle(gfx + "water_refraction", () -> g.waterRefraction, v -> g.waterRefraction = v)));
        rows.add(new Note(this::fabulousLine));
        if (!fabulous() && fabulousPossible()) {
            rows.add(new Action(gfx + "use_fabulous", () -> {
                Minecraft mc = Minecraft.getInstance();
                mc.options.graphicsMode().set(net.minecraft.client.GraphicsStatus.FABULOUS);
                mc.options.save();
                if (mc.level != null) mc.levelRenderer.allChanged();
                rebuild();
            }));
        }
        rows.add(custom(new Toggle(gfx + "weapon_lighting", () -> g.weaponWorldLighting, v -> g.weaponWorldLighting = v)));

        // ---- screen-space effects
        // ---- ray tracing
        rows.add(new Header(gfx + "section.ray_tracing"));
        rows.add(custom(new Toggle(gfx + "ray_tracing", () -> g.rayTracing, v -> g.rayTracing = v)).then(this::rebuild));
        rows.add(new Note(() -> tr(gfx + "ray_tracing.note")));
        if (g.rayTracing) {
            rows.add(custom(new Choice(gfx + "ray_quality", () -> g.rayQualityMode().ordinal(),
                    i -> g.rayQuality = GraphicsSettings.id(GraphicsSettings.RayQuality.values()[i]),
                    tr(gfx + "quality.low"), tr(gfx + "quality.medium"), tr(gfx + "quality.high"), tr(gfx + "quality.ultra"))));
            rows.add(new Note(() -> tr(gfx + "ray_quality.note")));
            rows.add(custom(new Slider(gfx + "ray_distance", 16, 64, 4, () -> g.rayDistance, v -> g.rayDistance = (float) v,
                    v -> tr(gfx + "blocks", Math.round(v)))));
            rows.add(custom(new Slider(gfx + "ray_gi", 0, 2, 0.05, () -> g.rayGlobalIllumination,
                    v -> g.rayGlobalIllumination = (float) v, SettingsScreen::percentOrOff)));
            rows.add(new Note(() -> tr(gfx + "ray_gi.note")));
            rows.add(custom(new Slider(gfx + "ray_ao", 0, 1, 0.05, () -> g.rayAmbientOcclusion,
                    v -> g.rayAmbientOcclusion = (float) v, SettingsScreen::percentOrOff)));
            rows.add(custom(new Slider(gfx + "ray_emission", 0, 2, 0.05, () -> g.rayEmission, v -> g.rayEmission = (float) v,
                    SettingsScreen::percentOrOff)));
            rows.add(custom(new Toggle(gfx + "ray_sun_bounce", () -> g.raySunBounce, v -> g.raySunBounce = v)));
            if (g.dlssChosen()) {
                rows.add(custom(new Choice(gfx + "ray_denoiser", () -> g.rayDenoiserMode().ordinal(),
                        i -> g.rayDenoiser = GraphicsSettings.id(GraphicsSettings.RayDenoiser.values()[i]),
                        tr(gfx + "ray_denoiser.blockland"), tr(gfx + "ray_denoiser.dlss_rr"))));
            }
            rows.add(new Note(this::rayDenoiserLine));
        }

        // ---- fog and sky
        rows.add(new Header(gfx + "section.sky"));
        rows.add(custom(new Toggle(gfx + "fog", () -> g.fog, v -> g.fog = v)).then(this::rebuild));
        rows.add(new Note(() -> tr(g.fog ? gfx + "fog.note" : gfx + "fog.off_note")));
        if (g.fog) {
            rows.add(custom(new Slider(gfx + "fog_density", 0, 2, 0.05, () -> g.fogDensity, v -> g.fogDensity = (float) v,
                    SettingsScreen::percentOrOff)));
            rows.add(custom(new Choice(gfx + "fog_quality", () -> g.fogQualityMode().ordinal(),
                    i -> g.fogQuality = GraphicsSettings.id(GraphicsSettings.FogQuality.values()[i]),
                    tr(gfx + "quality.low"), tr(gfx + "quality.medium"), tr(gfx + "quality.high"))));
        }
        rows.add(custom(new Toggle(gfx + "volumetric_clouds", () -> g.volumetricClouds, v -> g.volumetricClouds = v)).then(this::rebuild));
        rows.add(new Note(this::cloudsLine));
        if (g.volumetricClouds) {
            rows.add(custom(new Choice(gfx + "cloud_quality", () -> g.cloudQualityMode().ordinal(),
                    i -> g.cloudQuality = GraphicsSettings.id(GraphicsSettings.CloudQuality.values()[i]),
                    tr(gfx + "quality.low"), tr(gfx + "quality.medium"), tr(gfx + "quality.high"))));
            rows.add(custom(new Slider(gfx + "cloud_coverage", 0, 1, 0.05, () -> g.cloudCoverage, v -> g.cloudCoverage = (float) v,
                    SettingsScreen::percent)));
        }

        // ---- rain and water
        rows.add(new Header(gfx + "section.water"));
        rows.add(custom(new Slider(gfx + "rain_wetness", 0, 1, 0.05, () -> g.rainWetness, v -> g.rainWetness = (float) v,
                SettingsScreen::percentOrOff)));
        rows.add(new Note(() -> tr(gfx + "rain_wetness.note")));
        rows.add(custom(new Choice(gfx + "reflections", () -> g.reflectionQualityMode().ordinal(),
                i -> g.reflections = GraphicsSettings.id(GraphicsSettings.ReflectionQuality.values()[i]),
                tr(gfx + "quality.off"), tr(gfx + "quality.low"), tr(gfx + "quality.high"))));
        rows.add(new Note(() -> tr(gfx + "reflections.note")));
        rows.add(custom(new Toggle(gfx + "water_ripples", () -> g.waterRipples, v -> g.waterRipples = v)));
        rows.add(new Note(() -> tr(gfx + "water_ripples.note")));
        rows.add(custom(new Toggle(gfx + "soaking", () -> g.soaking, v -> g.soaking = v)));
        rows.add(new Note(() -> tr(gfx + "soaking.note")));
        rows.add(custom(new Toggle(gfx + "fluid_textures", () -> g.fluidTextures, v -> g.fluidTextures = v)));
        rows.add(new Note(() -> tr(gfx + "fluid_textures.note")));

        // ---- wind
        rows.add(new Header(gfx + "section.wind"));
        rows.add(custom(new Toggle(gfx + "wind", () -> g.wind, v -> g.wind = v)).then(this::rebuild));
        if (g.wind) {
            rows.add(custom(new Slider(gfx + "wind_strength", 0, 2, 0.05, () -> g.windStrength, v -> g.windStrength = (float) v,
                    SettingsScreen::percentOrOff)));
        }
        rows.add(new Note(() -> tr(gfx + "wind.note")));

        rows.add(new Header(gfx + "section.screen_space"));
        rows.add(custom(new Slider(gfx + "ambient_occlusion", 0, 1, 0.05, () -> g.ambientOcclusion, v -> g.ambientOcclusion = (float) v,
                SettingsScreen::percentOrOff)));
        rows.add(custom(new Slider(gfx + "volumetric_light", 0, 1, 0.05, () -> g.volumetricLight, v -> g.volumetricLight = (float) v,
                SettingsScreen::percentOrOff)));

        // ---- camera and colour
        rows.add(new Header(gfx + "section.camera"));
        rows.add(custom(new Slider(gfx + "bloom", 0, 1, 0.05, () -> g.bloom, v -> g.bloom = (float) v, SettingsScreen::percentOrOff)));
        rows.add(custom(new Toggle(gfx + "auto_exposure", () -> g.autoExposure, v -> g.autoExposure = v)));
        rows.add(custom(new Slider(gfx + "exposure", -2, 2, 0.1, () -> g.exposure, v -> g.exposure = (float) v,
                v -> String.format(Locale.ROOT, "%+.1f EV", v))));
        rows.add(custom(new Choice(gfx + "tonemap", () -> g.tonemapMode().ordinal(),
                i -> g.tonemap = GraphicsSettings.id(GraphicsSettings.Tonemap.values()[i]),
                tr(gfx + "tonemap.vanilla"), tr(gfx + "tonemap.filmic"), tr(gfx + "tonemap.aces"))));
        rows.add(custom(new Slider(gfx + "motion_blur", 0, 1, 1 / 24.0, () -> g.motionBlur, v -> g.motionBlur = (float) v,
                SettingsScreen::shutterOrOff)));
        rows.add(custom(new Slider(gfx + "motion_blur_viewmodel", 0, 1, 1 / 24.0, () -> g.motionBlurViewmodel,
                v -> g.motionBlurViewmodel = (float) v, SettingsScreen::shutterOrOff)));
        rows.add(custom(new Choice(gfx + "motion_blur_quality", () -> g.motionBlurQualityMode().ordinal(),
                i -> g.motionBlurQuality = GraphicsSettings.id(GraphicsSettings.MotionBlurQuality.values()[i]),
                tr(gfx + "quality.low"), tr(gfx + "quality.medium"), tr(gfx + "quality.high"))));
        rows.add(new Note(() -> tr(gfx + "motion_blur.note")));
        rows.add(custom(new Slider(gfx + "vignette", 0, 1, 0.05, () -> g.vignette, v -> g.vignette = (float) v, SettingsScreen::percentOrOff)));
        rows.add(custom(new Slider(gfx + "chromatic_aberration", 0, 1, 0.05, () -> g.chromaticAberration,
                v -> g.chromaticAberration = (float) v, SettingsScreen::percentOrOff)));
        rows.add(custom(new Slider(gfx + "film_grain", 0, 1, 0.05, () -> g.filmGrain, v -> g.filmGrain = (float) v, SettingsScreen::percentOrOff)));
        rows.add(custom(new Slider(gfx + "saturation", 0, 2, 0.05, () -> g.saturation, v -> g.saturation = (float) v, SettingsScreen::percent)));
        rows.add(custom(new Slider(gfx + "contrast", 0.5, 1.5, 0.05, () -> g.contrast, v -> g.contrast = (float) v, SettingsScreen::percent)));
        rows.add(custom(new Slider(gfx + "temperature", -1, 1, 0.05, () -> g.temperature, v -> g.temperature = (float) v,
                SettingsScreen::temperature)));

        // ---- bullet impacts (their own settings, not part of the presets)
        ImpactSettings fx = ClientSettings.get().impacts;
        rows.add(new Header("blockland.settings.section.impacts"));
        rows.add(new Choice("blockland.settings.impacts.quality", () -> fx.qualityLevel().ordinal(),
                i -> fx.quality = ImpactSettings.Quality.values()[i].id(),
                tr("blockland.settings.impacts.quality.off"), tr("blockland.settings.impacts.quality.low"),
                tr("blockland.settings.impacts.quality.medium"), tr("blockland.settings.impacts.quality.high"),
                tr("blockland.settings.impacts.quality.ultra")));
        rows.add(new Toggle("blockland.settings.impacts.dust", () -> fx.dust, v -> fx.dust = v));
        rows.add(new Toggle("blockland.settings.impacts.second_collisions", () -> fx.secondCollisions, v -> fx.secondCollisions = v));
        rows.add(new Toggle("blockland.settings.impacts.environment", () -> fx.environment, v -> fx.environment = v));
        rows.add(new Slider("blockland.settings.impacts.blood", 0, 2, 0.05, () -> fx.blood, v -> fx.blood = (float) v,
                SettingsScreen::percentOrOff));
        rows.add(new Toggle("blockland.settings.impacts.blood_in_water", () -> fx.bloodInWater, v -> fx.bloodInWater = v));
        rows.add(new Slider("blockland.settings.impacts.blood_stains", 0, 2, 0.05, () -> fx.bloodStains, v -> fx.bloodStains = (float) v,
                SettingsScreen::percentOrOff));
        rows.add(new Toggle("blockland.settings.impacts.fluid_physics", () -> fx.fluidPhysics, v -> fx.fluidPhysics = v));
        rows.add(new Note("blockland.settings.impacts.note"));

        // ---- what the pipeline is doing
        rows.add(new Header(gfx + "section.status"));
        GraphicsStatus status = Graphics.status();
        rows.add(new Info(gfx + "status.pipeline", this::pipelineLine, () -> status.running ? OK : MUTED));
        rows.add(new Info(gfx + "status.resolution", () -> status.running && status.outputWidth > 0
                ? status.internalWidth + "×" + status.internalHeight + " → " + status.outputWidth + "×" + status.outputHeight : "—",
                () -> TEXT));
        rows.add(new Info(gfx + "status.aa", () -> status.running ? aaName(status.activeAa) : "—", () -> TEXT));
        rows.add(new Info(gfx + "status.dlss", () -> status.dlssAvailable ? join(tr(gfx + "status.available"), status.dlssDetail)
                : join(reason(status.dlssReason, gfx + "status.unavailable"), status.dlssDetail), () -> status.dlssAvailable ? OK : MUTED));
        rows.add(new Info(gfx + "status.ray_tracing", this::rayTracingStatus,
                () -> status.rayTracingActive ? OK : MUTED));
        rows.add(new Info(gfx + "status.neural", this::neuralStatus,
                () -> status.neuralActive ? OK : status.neuralAvailable ? TEXT : MUTED));
        rows.add(new Info(gfx + "status.frame_generation", this::frameGenStatus,
                () -> status.frameGenActive ? OK : status.frameGenAvailable ? TEXT : MUTED));
        rows.add(new Info(gfx + "status.world_lighting", this::worldLightingLine, () -> status.worldLightingActive ? OK : MUTED));
        rows.add(new Info(gfx + "status.renderer", () -> status.renderer == null || status.renderer.isEmpty() ? "—" : status.renderer,
                () -> MUTED));
        rows.add(live(new Toggle(gfx + "show_stats", () -> g.showStats, v -> g.showStats = v)));
        rows.add(new Action(gfx + "reset", () -> {
            ClientSettings.get().graphics = new GraphicsSettings();
            ClientSettings.get().impacts = new ImpactSettings();
            Graphics.settingsChanged();
            ClientSettings.save();
            rebuild();
        }));
    }

    /** A graphics row whose change makes the preset 自定义. */
    private <R extends Row> R custom(R row) {
        row.dim(this::graphicsDimmed).then(() -> {
            GraphicsSettings g = ClientSettings.get().graphics;
            g.preset = GraphicsSettings.id(GraphicsSettings.Preset.CUSTOM);
            Graphics.settingsChanged();
        });
        return row;
    }

    /** A graphics row that applies live without touching the preset (master switch, statistics). */
    private static <R extends Row> R live(R row) {
        row.then(Graphics::settingsChanged);
        return row;
    }

    private boolean graphicsDimmed() {
        return !ClientSettings.get().graphics.enabled;
    }

    private String pipelineLine() {
        GraphicsStatus status = Graphics.status();
        String gfx = "blockland.settings.graphics.";
        if (status.running) return tr(gfx + "status.running");
        if (!ClientSettings.get().graphics.enabled) return tr(gfx + "status.disabled");
        return reason(status.blockedReason, gfx + "status.idle");
    }

    private String worldLightingLine() {
        GraphicsStatus status = Graphics.status();
        String gfx = "blockland.settings.graphics.";
        String line = status.worldLightingActive
                ? tr(gfx + "status.world_lighting.on", status.shadowCascades, status.dynamicLightCount)
                : reason(status.worldLightingReason, gfx + "status.off");
        return status.reloadPending ? line + " " + tr(gfx + "status.reload_pending") : line;
    }

    private String dlssLine() {
        GraphicsStatus status = Graphics.status();
        String gfx = "blockland.settings.graphics.";
        if (status.dlssAvailable) return tr(gfx + "dlss.available", status.dlssDetail);
        return tr(gfx + "dlss.unavailable", join(reason(status.dlssReason, gfx + "status.unavailable"), status.dlssDetail));
    }

    /** The Ray Reconstruction row of the resolution section: off, on, or what it waits for. */
    private String rayReconstructionLine() {
        GraphicsStatus status = Graphics.status();
        GraphicsSettings g = Graphics.settings();
        String gfx = "blockland.settings.graphics.";
        if (status.rayReconstructionActive) return tr(gfx + "dlss_ray_reconstruction.active");
        if (!g.rayTracing) return tr(gfx + "dlss_ray_reconstruction.needs_rt");
        if (!g.dlssChosen()) return tr(gfx + "dlss_ray_reconstruction.needs_dlss");
        if (!status.rayReconstructionAvailable) {
            return reason(status.rayReconstructionReason != null ? status.rayReconstructionReason : status.dlssReason, gfx + "status.unavailable");
        }
        if (g.rayDenoiserMode() != GraphicsSettings.RayDenoiser.DLSS_RR) return tr(gfx + "dlss_ray_reconstruction.chosen_blockland");
        return tr(gfx + "dlss_ray_reconstruction.ready");
    }

    /** Under the ray tracing denoiser: which one denoises now. */
    private String rayDenoiserLine() {
        GraphicsSettings g = Graphics.settings();
        String gfx = "blockland.settings.graphics.";
        if (!g.dlssChosen()) return tr(gfx + "ray_denoiser.needs_dlss");
        return rayReconstructionLine();
    }

    /** What the volumetric clouds are doing (or what they are, while off). */
    private String cloudsLine() {
        GraphicsStatus status = Graphics.status();
        String gfx = "blockland.settings.graphics.";
        if (!Graphics.settings().volumetricClouds) return tr(gfx + "volumetric_clouds.note");
        if (status.cloudsReason != null) return tr(status.cloudsReason);
        return tr(status.cloudsActive ? gfx + "volumetric_clouds.active" : gfx + "volumetric_clouds.waiting");
    }

    private String rayTracingStatus() {
        GraphicsStatus status = Graphics.status();
        String gfx = "blockland.settings.graphics.";
        if (status.rayTracingReason != null) return tr(status.rayTracingReason);
        if (!status.rayTracingActive) return tr(gfx + "status.ray_tracing.off");
        return tr(gfx + "status.ray_tracing.active", status.rayVolume, Math.round(status.rayVoxelsReady * 100),
                tr(status.rayReconstructionActive ? gfx + "ray_denoiser.dlss_rr" : gfx + "ray_denoiser.blockland"));
    }

    /** Under the DLSS 5 switch: whether it can run here. */
    private String neuralLine() {
        GraphicsStatus status = Graphics.status();
        String gfx = "blockland.settings.graphics.";
        if (!status.neuralAvailable) {
            return tr(gfx + "neural.unavailable", join(reason(status.neuralReason, gfx + "status.unavailable"), status.neuralDetail));
        }
        return tr(gfx + "neural.available");
    }

    private String neuralStatus() {
        GraphicsStatus status = Graphics.status();
        String gfx = "blockland.settings.graphics.";
        if (!status.neuralAvailable) return join(reason(status.neuralReason, gfx + "status.unavailable"), status.neuralDetail);
        return tr(status.neuralActive ? gfx + "status.neural.active" : gfx + "status.neural.off");
    }

    /** Under the frame generation choice: whether it can run here and how far it goes. */
    private String frameGenLine() {
        GraphicsStatus status = Graphics.status();
        String gfx = "blockland.settings.graphics.";
        if (!status.frameGenAvailable) {
            return tr(gfx + "dlss_frame_generation.unavailable", join(reason(status.frameGenReason, gfx + "status.unavailable"),
                    status.frameGenDetail));
        }
        if (status.frameGenMax > 0) return tr(gfx + "dlss_frame_generation.available_max", status.frameGenMax);
        return tr(gfx + "dlss_frame_generation.available");
    }

    /** The status line: off, paused, or generating with how many frames reach the screen. */
    private String frameGenStatus() {
        GraphicsStatus status = Graphics.status();
        String gfx = "blockland.settings.graphics.";
        if (!status.frameGenAvailable) return join(reason(status.frameGenReason, gfx + "status.unavailable"), status.frameGenDetail);
        if (status.frameGenActive) return tr(gfx + "status.frame_generation.active", String.format("%.1f", status.frameGenPresented));
        if (status.frameGenPresenting) return tr(gfx + "status.frame_generation.paused");
        return tr(gfx + "status.frame_generation.off");
    }

    private String fabulousLine() {
        String gfx = "blockland.settings.graphics.";
        if (fabulous()) return tr(gfx + "fabulous.on");
        return tr(fabulousPossible() ? gfx + "fabulous.off" : gfx + "fabulous.unavailable");
    }

    private static boolean fabulous() {
        return Minecraft.getInstance().options.graphicsMode().get() == net.minecraft.client.GraphicsStatus.FABULOUS;
    }

    /** Vanilla offers 极佳 here: not blocked for this GPU. */
    private static boolean fabulousPossible() {
        return !Minecraft.getInstance().getGpuWarnlistManager().isSkippingFabulous();
    }

    /** A status reason key in the player's language (the raw key when the language lacks it), or the fallback's text. */
    private static String reason(@Nullable String key, String fallback) {
        return tr(key == null || key.isEmpty() ? fallback : key);
    }

    private static String aaName(@Nullable String id) {
        return switch (id == null ? "" : id) {
            case "fxaa" -> "FXAA";
            case "taa" -> "TAA";
            case "taau" -> tr("blockland.settings.graphics.upscaler.taau");
            case "dlss" -> "NVIDIA DLSS";
            default -> tr("blockland.settings.graphics.antialiasing.off");
        };
    }

    private static String join(String a, String detail) {
        return detail == null || detail.isEmpty() ? a : a + " · " + detail;
    }

    /** "中性", "偏冷 40%", "偏暖 25%". */
    private static String temperature(double v) {
        String gfx = "blockland.settings.graphics.";
        return Math.abs(v) < 0.025 ? tr(gfx + "temperature.neutral")
                : tr(v < 0 ? gfx + "temperature.cool" : gfx + "temperature.warm", Math.round(Math.abs(v) * 100));
    }

    private static String percent(double v) {
        return Math.round(v * 100) + "%";
    }

    /** A shutter as its angle (360° = 1/30 s), or 关. */
    private static String shutterOrOff(double v) {
        return v < 0.005 ? tr("blockland.settings.off") : Math.round(v * 360) + "°";
    }

    private static String percentOrOff(double v) {
        return v < 0.005 ? tr("blockland.settings.off") : percent(v);
    }

    private int colorOf(ColorTarget target) {
        ClientSettings.Data data = ClientSettings.get();
        return switch (target) {
            case CROSSHAIR -> data.crosshair.color;
            case HIT -> data.hitmarker.hitColor;
            case CRITICAL -> data.hitmarker.criticalColor;
            case KILL -> data.hitmarker.killColor;
        };
    }

    private void setColor(ColorTarget target, int rgb) {
        ClientSettings.Data data = ClientSettings.get();
        switch (target) {
            case CROSSHAIR -> data.crosshair.color = rgb;
            case HIT -> data.hitmarker.hitColor = rgb;
            case CRITICAL -> data.hitmarker.criticalColor = rgb;
            case KILL -> data.hitmarker.killColor = rgb;
        }
    }

    private double channel(int shift) {
        return colorOf(colorTarget) >> shift & 0xFF;
    }

    private void setChannel(int shift, double value) {
        int rgb = colorOf(colorTarget);
        int v = (int) Math.round(Math.max(0, Math.min(255, value)));
        setColor(colorTarget, rgb & ~(0xFF << shift) | v << shift);
    }

    private void setLanguage(String code) {
        Minecraft mc = Minecraft.getInstance();
        if (code.equals(mc.options.languageCode)) return;
        mc.options.languageCode = code;
        mc.getLanguageManager().setSelected(code);
        mc.options.save();
        // the resource reload brings the new language to every screen, the lobby included
        mc.reloadResourcePacks();
    }

    // ---- rendering -------------------------------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Ticker.global().tick();
        g.fillGradient(0, 0, width, height, BG_TOP, BG_BOTTOM);
        Draw.glow(g, width * 0.7f, height * 0.4f, width * 0.45f, height * 0.5f, 0x223A6EA5);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        float a = intro.get();
        // header
        Draw.text(g,tr("blockland.settings.title"), PAD, 12, 1.6f, ColorMath.fade(TEXT, a), true);
        String hint = tr("blockland.settings.hint");
        Draw.text(g,hint, width - PAD - UiFont.width(hint), 16, 1f, ColorMath.fade(DIM, a), false);

        // tabs
        int tx = PAD, ty = 38;
        for (Tab t : Tab.values()) {
            String label = tr(t.key);
            int w = UiFont.width(label) + 16;
            boolean over = inside(mouseX, mouseY, tx, ty, w, 18);
            float hv = spring("tab" + t, over || t == tab);
            Draw.text(g,label, tx + 8, ty + 5 - hv, 1f, ColorMath.fade(ColorMath.mix(MUTED, TEXT, hv), a), false);
            if (t == tab) {
                tabX.setTarget(tx);
                tabW.setTarget(w);
                if (tabW.get() == 0) {
                    tabX.set(tx);
                    tabW.set(w);
                }
            }
            tx += w + 4;
        }
        g.fill(Math.round(tabX.get()), ty + 18, Math.round(tabX.get() + tabW.get()), ty + 20, ColorMath.fade(ACCENT, a));
        g.fill(PAD, ty + 20, width - PAD, ty + 21, ColorMath.fade(LINE, a));

        // rows (scrollable), with the preview on the right for the weapons tab
        boolean preview = tab == Tab.WEAPONS && width > 520;
        columnX = PAD;
        columnW = preview ? Math.min(420, (int) (width * 0.56f)) : Math.min(560, width - PAD * 2);
        contentTop = ty + 30;
        contentBottom = height - PAD;
        contentHeight = 0;
        for (Row row : rows) contentHeight += row.height();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - (contentBottom - contentTop))));
        g.enableScissor(columnX - 4, contentTop, columnX + columnW + 4, contentBottom);
        int y = contentTop - Math.round(scroll);
        for (Row row : rows) {
            int h = row.height();
            float ra = row.dimmed.getAsBoolean() ? a * DIMMED : a;
            if (y + h > contentTop && y < contentBottom) row.render(g, mouseX, mouseY, columnX, y, columnW, ra);
            row.y = y;
            y += h;
        }
        g.disableScissor();
        if (contentHeight > contentBottom - contentTop) {
            int view = contentBottom - contentTop;
            int barH = Math.max(16, view * view / contentHeight);
            int barY = contentTop + Math.round((view - barH) * scroll / Math.max(1, contentHeight - view));
            g.fill(columnX + columnW + 6, barY, columnX + columnW + 8, barY + barH, ColorMath.fade(0x55FFFFFF, a));
        }
        if (preview) renderPreview(g, mouseX, mouseY, columnX + columnW + 24, contentTop, width - PAD - (columnX + columnW + 24), a);
    }

    /** A dim scene with the crosshair in the middle; a hit marker plays now and then (hit, critical, kill in turn). */
    private void renderPreview(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, float a) {
        int h = Math.min(w, contentBottom - y);
        Draw.rect(g, x, y, w, h, ColorMath.fade(0xFF0A0F17, a));
        g.fillGradient(x, y, x + w, y + h, ColorMath.fade(0xFF5B7C99, a * 0.7f), ColorMath.fade(0xFF2E3B2C, a * 0.7f));
        Draw.glow(g, x + w * 0.3f, y + h * 0.7f, w * 0.4f, h * 0.2f, ColorMath.fade(0x44000000, a));
        Draw.outline(g, x, y, w, h, ColorMath.fade(LINE, a));
        String label = tr("blockland.settings.preview");
        Draw.text(g,label, x + 6, y + 6, 1f, ColorMath.fade(MUTED, a), false);
        double now = Ticker.global().time();
        if (now >= nextPreviewHit) {
            nextPreviewHit = now + 1.4;
            previewKind = (previewKind + 1) % 3;
            HitMarker.show(previewKind == 1, previewKind == 2);
        }
        float cx = x + w / 2f, cy = y + h / 2f;
        // the spread breathes so a dynamic crosshair shows how it opens
        float spread = 4 + (float) (Math.sin(now * 1.7) * 0.5 + 0.5) * 8;
        Crosshair.render(g, cx, cy, spread, a);
        HitMarker.render(g, cx, cy);
        String kind = tr(previewKind == 2 ? "blockland.settings.preview.kill" : previewKind == 1 ? "blockland.settings.preview.critical"
                : "blockland.settings.preview.hit");
        Draw.text(g,kind, x + (w - UiFont.width(kind)) / 2f, y + h - 16, 1f, ColorMath.fade(DIM, a), false);
    }

    // ---- input -----------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        int tx = PAD, ty = 38;
        for (Tab t : Tab.values()) {
            int w = UiFont.width(tr(t.key)) + 16;
            if (inside(mouseX, mouseY, tx, ty, w, 18)) {
                if (t != tab) {
                    tab = t;
                    scroll = 0;
                    clickSound();
                    rebuild();
                }
                return true;
            }
            tx += w + 4;
        }
        if (mouseY >= contentTop && mouseY < contentBottom) {
            for (Row row : rows) {
                if (mouseY >= row.y && mouseY < row.y + row.height() && row.click(mouseX, mouseY)) {
                    ClientSettings.save();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging != null) {
            dragging.drag(mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging != null) {
            dragging = null;
            ClientSettings.save();
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll -= (float) scrollY * 24;
        return true;
    }

    // ---- row kinds ---------------------------------------------------------------------------------------------

    private abstract class Row {
        int y;
        /** The row has no effect right now: drawn faded (still usable). */
        BooleanSupplier dimmed = () -> false;
        /** Runs after the player changed the row's value. */
        @Nullable
        Runnable onChange;

        int height() {
            return ROW_H;
        }

        abstract void render(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, float a);

        boolean click(double mouseX, double mouseY) {
            return false;
        }

        void drag(double mouseX) {
        }

        /** Where controls start: labels sit left of this. */
        int controlX(int x, int w) {
            return x + Math.min(170, w / 2);
        }

        Row dim(BooleanSupplier when) {
            dimmed = when;
            return this;
        }

        /** Adds a reaction to a change (after the ones already there). */
        Row then(Runnable reaction) {
            Runnable before = onChange;
            onChange = before == null ? reaction : () -> {
                before.run();
                reaction.run();
            };
            return this;
        }

        void changed() {
            if (onChange != null) onChange.run();
        }
    }

    private final class Header extends Row {
        private final String key;

        Header(String key) {
            this.key = key;
        }

        @Override
        int height() {
            return 26;
        }

        @Override
        void render(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, float a) {
            g.fill(x, y + 10, x + 3, y + 20, ColorMath.fade(ACCENT, a));
            Draw.text(g,tr(key), x + 8, y + 11, 1f, ColorMath.fade(TEXT, a), false);
        }
    }

    /** A dim explanation under a control; its text may change while the page is open (status, current values). */
    private final class Note extends Row {
        private final Supplier<String> text;

        Note(String key) {
            this(() -> tr(key));
        }

        Note(Supplier<String> text) {
            this.text = text;
        }

        @Override
        int height() {
            return 6 + Draw.wrap(text.get(), columnW - 8).size() * 11;
        }

        @Override
        void render(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, float a) {
            int ly = y + 2;
            for (var line : Draw.wrap(text.get(), w - 8)) {
                Draw.text(g,line, x + 8, ly, ColorMath.fade(DIM, a));
                ly += 11;
            }
        }
    }

    /** A label and a live read-only value (the pipeline's status). */
    private final class Info extends Row {
        private final String key;
        private final Supplier<String> value;
        private final IntSupplier color;

        Info(String key, Supplier<String> value, IntSupplier color) {
            this.key = key;
            this.value = value;
            this.color = color;
        }

        @Override
        void render(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, float a) {
            String label = tr(key);
            Draw.text(g,label, x + 8, y + 7, 1f, ColorMath.fade(MUTED, a), false);
            int vx = Math.max(controlX(x, w), x + 16 + UiFont.width(label));
            String text = Draw.fit(value.get(), x + w - 4 - vx);
            Draw.text(g,text, x + w - 4 - UiFont.width(text), y + 7, 1f, ColorMath.fade(color.getAsInt(), a), false);
        }
    }

    private final class Slider extends Row {
        private final String key;
        private final Object[] args;
        private final double min, max, step;
        private final DoubleSupplier get;
        private final DoubleConsumer set;
        private final java.util.function.DoubleFunction<String> format;
        private int barX, barW;

        Slider(String key, double min, double max, double step, DoubleSupplier get, DoubleConsumer set,
               java.util.function.DoubleFunction<String> format) {
            this(key, new Object[0], min, max, step, get, set, format);
        }

        /** With arguments for the label's translation (e.g. the magnification of a sensitivity class). */
        Slider(String key, Object[] args, double min, double max, double step, DoubleSupplier get, DoubleConsumer set,
               java.util.function.DoubleFunction<String> format) {
            this.key = key;
            this.args = args;
            this.min = min;
            this.max = max;
            this.step = step;
            this.get = get;
            this.set = set;
            this.format = format;
        }

        @Override
        void render(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, float a) {
            Draw.text(g,tr(key, args), x + 8, y + 7, 1f, ColorMath.fade(MUTED, a), false);
            barX = controlX(x, w);
            barW = w - (barX - x) - 46;
            double value = get.getAsDouble();
            float frac = (float) ((value - min) / (max - min));
            boolean over = inside(mouseX, mouseY, barX - 4, y, barW + 8, ROW_H) || dragging == this;
            float hv = spring("s" + key + java.util.Arrays.toString(args), over);
            g.fill(barX, y + 10, barX + barW, y + 12, ColorMath.fade(0x30FFFFFF, a));
            g.fill(barX, y + 10, barX + Math.round(barW * frac), y + 12, ColorMath.fade(ACCENT, a));
            int knob = barX + Math.round(barW * frac);
            Draw.glow(g, knob, y + 11, 5 + 3 * hv, 5 + 3 * hv, ColorMath.fade(ACCENT, a * 0.35f * hv));
            g.fill(knob - 3, y + 6, knob + 3, y + 16, ColorMath.fade(ColorMath.mix(TEXT, ACCENT, hv), a));
            String text = format.apply(value);
            Draw.text(g,text, x + w - 4 - UiFont.width(text), y + 7, 1f, ColorMath.fade(TEXT, a), false);
        }

        @Override
        boolean click(double mouseX, double mouseY) {
            if (mouseX < barX - 4 || mouseX > barX + barW + 4) return false;
            dragging = this;
            drag(mouseX);
            return true;
        }

        @Override
        void drag(double mouseX) {
            double frac = Math.max(0, Math.min(1, (mouseX - barX) / Math.max(1, barW)));
            double value = min + frac * (max - min);
            value = Math.round(value / step) * step;
            double before = get.getAsDouble();
            set.accept(Math.max(min, Math.min(max, value)));
            ClientSettings.get().crosshair.sanitize();
            ClientSettings.get().hitmarker.sanitize();
            if (get.getAsDouble() != before) changed();
        }
    }

    private final class Toggle extends Row {
        private final String key;
        private final Supplier<Boolean> get;
        private final Consumer<Boolean> set;

        Toggle(String key, Supplier<Boolean> get, Consumer<Boolean> set) {
            this.key = key;
            this.get = get;
            this.set = set;
        }

        @Override
        void render(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, float a) {
            Draw.text(g,tr(key), x + 8, y + 7, 1f, ColorMath.fade(MUTED, a), false);
            boolean on = get.get();
            float knob = spring("t" + key, on);
            int sx = x + w - 34, sy = y + 5;
            Draw.rect(g, sx, sy, 28, 12, ColorMath.fade(ColorMath.mix(0xFF2A3344, GO, knob), a));
            int kx = sx + 2 + Math.round(14 * knob);
            Draw.rect(g, kx, sy + 2, 10, 8, ColorMath.fade(TEXT, a));
            String state = tr(on ? "blockland.settings.on" : "blockland.settings.off");
            Draw.text(g,state, sx - 6 - UiFont.width(state), y + 7, 1f, ColorMath.fade(on ? OK : DIM, a), false);
        }

        @Override
        boolean click(double mouseX, double mouseY) {
            set.accept(!get.get());
            clickSound();
            changed();
            return true;
        }
    }

    /**
     * One of a few options as buttons; when they do not fit the row (long English names, narrow windows) the current
     * option shows between arrows instead and a click on either half steps through them.
     */
    private final class Choice extends Row {
        private final String key;
        private final IntSupplier get;
        private final IntConsumer set;
        private final String[] options;
        /** (x, width, option index; −1 previous, −2 next) */
        private final List<int[]> spans = new ArrayList<>();

        Choice(String key, IntSupplier get, IntConsumer set, String... options) {
            this.key = key;
            this.get = get;
            this.set = set;
            this.options = options;
        }

        @Override
        void render(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, float a) {
            Draw.text(g,tr(key), x + 8, y + 7, 1f, ColorMath.fade(MUTED, a), false);
            spans.clear();
            int cx = controlX(x, w);
            int current = get.getAsInt();
            int total = -4;
            for (String option : options) total += UiFont.width(option) + 16;
            if (cx + total > x + w) {
                renderCompact(g, mouseX, mouseY, cx, y, x + w - cx, current, a);
                return;
            }
            for (int i = 0; i < options.length; i++) {
                int bw = UiFont.width(options[i]) + 12;
                boolean chosen = i == current;
                float hv = spring("c" + key + i, inside(mouseX, mouseY, cx, y + 3, bw, 16));
                int bg = chosen ? ColorMath.withAlpha(ACCENT, 0x55) : ColorMath.mix(0xFF1A2333, 0xFF2A3650, hv);
                Draw.rect(g, cx, y + 3, bw, 16, ColorMath.fade(bg, a));
                if (chosen) Draw.outline(g, cx, y + 3, bw, 16, ColorMath.fade(ACCENT, a));
                Draw.text(g,options[i], cx + 6, y + 7, 1f, ColorMath.fade(chosen ? TEXT : MUTED, a), false);
                spans.add(new int[]{cx, bw, i});
                cx += bw + 4;
            }
        }

        private void renderCompact(GuiGraphics g, int mouseX, int mouseY, int cx, int y, int room, int current, float a) {
            String label = options[Math.max(0, Math.min(options.length - 1, current))];
            int bw = Math.min(room, UiFont.width(label) + 36);
            float hv = spring("c" + key + "compact", inside(mouseX, mouseY, cx, y + 3, bw, 16));
            Draw.rect(g, cx, y + 3, bw, 16, ColorMath.fade(ColorMath.mix(ColorMath.withAlpha(ACCENT, 0x55), 0xFF2A3650, hv * 0.4f), a));
            Draw.outline(g, cx, y + 3, bw, 16, ColorMath.fade(ACCENT, a));
            Draw.text(g,"‹", cx + 5, y + 7, 1f, ColorMath.fade(ACCENT, a), false);
            Draw.text(g,"›", cx + bw - 5 - UiFont.width("›"), y + 7, 1f, ColorMath.fade(ACCENT, a), false);
            String shown = Draw.fit(label, bw - 30);
            Draw.text(g,shown, cx + (bw - UiFont.width(shown)) / 2f, y + 7, 1f, ColorMath.fade(TEXT, a), false);
            spans.add(new int[]{cx, bw / 2, -1});
            spans.add(new int[]{cx + bw / 2, bw - bw / 2, -2});
        }

        @Override
        boolean click(double mouseX, double mouseY) {
            for (int[] span : spans) {
                if (mouseX >= span[0] && mouseX < span[0] + span[1]) {
                    int n = options.length;
                    int choice = span[2] >= 0 ? span[2] : (get.getAsInt() + (span[2] == -1 ? n - 1 : 1)) % n;
                    set.accept(choice);
                    clickSound();
                    changed();
                    return true;
                }
            }
            return false;
        }
    }

    /** Preset colours for one target; picking one also points the RGB sliders at it. */
    private final class Swatches extends Row {
        private final ColorTarget target;
        private final IntSupplier get;
        private final IntConsumer set;
        private final List<int[]> spans = new ArrayList<>();

        Swatches(ColorTarget target, IntSupplier get, IntConsumer set) {
            this.target = target;
            this.get = get;
            this.set = set;
        }

        @Override
        void render(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, float a) {
            boolean editing = colorTarget == target;
            Draw.text(g,tr(target.key), x + 8, y + 7, 1f, ColorMath.fade(editing ? TEXT : MUTED, a), false);
            if (editing) Draw.text(g,"RGB", x + 8 + UiFont.width(tr(target.key)) + 6, y + 7, 1f, ColorMath.fade(ACCENT, a), false);
            spans.clear();
            int cx = controlX(x, w);
            int current = get.getAsInt();
            for (int rgb : SWATCHES) {
                if (cx + 14 > x + w - 24) break;
                boolean chosen = rgb == current;
                Draw.rect(g, cx, y + 4, 14, 14, ColorMath.fade(0xFF000000 | rgb, a));
                if (chosen) Draw.outline(g, cx - 1, y + 3, 16, 16, ColorMath.fade(TEXT, a));
                spans.add(new int[]{cx, rgb});
                cx += 18;
            }
            // the current colour (custom ones too)
            Draw.rect(g, x + w - 18, y + 4, 14, 14, ColorMath.fade(0xFF000000 | current, a));
            Draw.outline(g, x + w - 19, y + 3, 16, 16, ColorMath.fade(editing ? ACCENT : LINE, a));
        }

        @Override
        boolean click(double mouseX, double mouseY) {
            colorTarget = target;
            for (int[] span : spans) {
                if (mouseX >= span[0] && mouseX < span[0] + 14) {
                    set.accept(span[1]);
                    break;
                }
            }
            clickSound();
            return true;
        }
    }

    private final class Action extends Row {
        private final String key;
        private final Runnable action;

        Action(String key, Runnable action) {
            this.key = key;
            this.action = action;
        }

        @Override
        int height() {
            return 30;
        }

        @Override
        void render(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w, float a) {
            String label = tr(key);
            int bw = UiFont.width(label) + 16, bx = x + 8, by = y + 8;
            float hv = spring("a" + key, inside(mouseX, mouseY, bx, by, bw, 16));
            Draw.rect(g, bx, by, bw, 16, ColorMath.fade(ColorMath.mix(0xFF2A3446, 0xFF3A4660, hv), a));
            Draw.text(g,label, bx + 8, by + 4, 1f, ColorMath.fade(TEXT, a), false);
        }

        @Override
        boolean click(double mouseX, double mouseY) {
            String label = tr(key);
            int bw = UiFont.width(label) + 16;
            if (mouseX < columnX + 8 || mouseX > columnX + 8 + bw) return false;
            action.run();
            clickSound();
            changed();
            return true;
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private float spring(String key, boolean on) {
        Spring spring = hover.computeIfAbsent(key, k -> new Spring(on ? 1 : 0).config(320, 26));
        spring.setTarget(on ? 1 : 0);
        return spring.get();
    }

    private static boolean inside(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static void clickSound() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
}
