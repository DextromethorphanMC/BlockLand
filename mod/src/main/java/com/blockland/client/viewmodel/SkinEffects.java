package com.blockland.client.viewmodel;

import com.blockland.BlockLand;
import com.blockland.client.render.DrawList;
import com.blockland.client.render.GpuModel;
import com.blockland.client.render.GpuParticles;
import com.blockland.client.render.LightSources;
import com.blockland.client.render.MaterialLayer;
import com.blockland.client.render.TextureRef;
import com.blockland.common.Sounds;
import com.blockland.craft.CraftData;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

/**
 * What a special skin adds to a first-person weapon beyond its model and its shots (asset spec §38): its own inspect —
 * the cues its clip events set off (particle bursts, flashes of light, a sound, a surge of the skin's shader effects, a
 * field-of-view punch), particle streams over parts of the clip, a light that glows with the surge — and the cue of a
 * confirmed kill. The surge (0..1, the dynamic material) is the inspect's curve and any pulses, read by the weapon's
 * style ({@link #style}); interrupted, it cools down instead of snapping back. Shared by every weapon viewmodel: it only
 * knows the skin data, the rig and node matrices the weapon hands it.
 */
public final class SkinEffects {
    /** Seconds the inspect's streams and surge take to die away when the inspect is cut off. */
    private static final float END_FADE = 0.35f;

    /**
     * Where the effects find the drawn model's nodes this frame, in the weapon's root space.
     */
    public interface Nodes {
        /**
         * @param gun 0 = the right (or only) weapon, 1 = a dual weapon's left one (the node's twin)
         * @return the node's matrix, null when the model has no such node (or it is hidden)
         */
        @Nullable
        Matrix4f node(String name, int gun);

        /** Whether the weapon is a dual pair (cues and streams marked {@code twin} also play at the left one). */
        boolean dual();
    }

    private record Burst(GpuParticles.Emitter emitter, String node, int gun, float[] age) {
    }

    private record Flash(CraftData.SkinLight light, String node, int gun) {
    }

    private static final class Pulse {
        final float amount, fade;
        float age;

        Pulse(float amount, float fade) {
            this.amount = amount;
            this.fade = Math.max(0.05f, fade);
        }

        float value() {
            float u = Math.min(1, age / fade);
            return amount * (1 - u) * (1 - u);
        }
    }

    @Nullable
    private CraftData.SkinInspect inspect;
    /** Seconds into the inspect's clip; the inspect is cut off (fading for {@code endAge} seconds) when ending. */
    private float clipTime;
    private boolean ending;
    private float endAge, endSurge;
    private final List<Burst> bursts = new ArrayList<>();
    private final List<Pulse> pulses = new ArrayList<>();
    private final List<Flash> flashes = new ArrayList<>();
    private float surge;
    /** The glow light: where it is (world) and how bright; attached while it shines (null detaches it). */
    @Nullable
    private LightSources.Light glowLight;
    private final Supplier<LightSources.Light> glowSource = () -> glowLight;

    // scratch
    private final Matrix4f modelView = new Matrix4f();
    private final Vector3f viewDown = new Vector3f();
    private final Quaternionf q = new Quaternionf();

    // ---- what happens -----------------------------------------------------------------------------------

    /** The skin's inspect starts (its clip has just begun playing). */
    public void startInspect(CraftData.SkinInspect inspect) {
        this.inspect = inspect;
        clipTime = 0;
        ending = false;
        endAge = 0;
    }

    /** The inspect ended or was cut off (a shot, aiming): its streams fade and its surge cools down from where it is. */
    public void stopInspect() {
        if (inspect == null || ending) return;
        ending = true;
        endAge = 0;
        endSurge = inspect.surgeAt(clipTime);
    }

    public boolean inspecting() {
        return inspect != null && !ending;
    }

    /** A clip event of the playing inspect: its cue, when the skin gives the event one. */
    public void event(String name, ViewmodelRig<?> rig) {
        if (inspect == null || ending || inspect.cues == null) return;
        CraftData.SkinCue cue = inspect.cues.get(name);
        if (cue != null) cue(cue, rig);
    }

    /** Sets off a cue: bursts and a flash at its node, its sound, a surge pulse, a shove of the rig, a FOV punch. */
    public void cue(CraftData.SkinCue cue, ViewmodelRig<?> rig) {
        String node = cue.node == null || cue.node.isBlank() ? "gun" : cue.node.trim();
        int guns = cue.twin ? 2 : 1;
        if (cue.particles != null) {
            for (String id : cue.particles) {
                GpuParticles.Emitter emitter = id == null ? null : ParticlePresets.get(id);
                if (emitter == null) continue;
                for (int g = 0; g < guns; g++) bursts.add(new Burst(emitter, node, g, new float[1]));
            }
        }
        if (cue.light != null) for (int g = 0; g < guns; g++) flashes.add(new Flash(cue.light, node, g));
        if (cue.sound != null && !cue.sound.isBlank()) {
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(
                    Sounds.byId(BlockLand.id(cue.sound.trim())), 1.0f, 0.8f));
        }
        if (cue.surge > 0) pulses.add(new Pulse(Math.min(1, cue.surge), cue.surgeFade));
        if (cue.impulse != null && !cue.impulse.isBlank()) {
            ViewmodelImpulses.Impulse impulse = ViewmodelImpulses.get(cue.impulse.trim());
            if (impulse != null) rig.motion().react(impulse);
        }
        if (cue.fov != 0) rig.camera().punchFov(cue.fov);
    }

    // ---- per frame --------------------------------------------------------------------------------------

    /**
     * Advances the effects.
     *
     * @param clipTime seconds into the inspect's clip while it plays (the rig's elapsed time), ignored when ending
     */
    public void update(float dt, float clipTime) {
        if (inspect != null) {
            if (ending) {
                this.clipTime += dt;
                endAge += dt;
                if (endAge >= END_FADE) inspect = null;
            } else {
                this.clipTime = clipTime;
            }
        }
        for (Iterator<Burst> it = bursts.iterator(); it.hasNext(); ) {
            Burst b = it.next();
            b.age[0] += dt;
            if (b.age[0] > b.emitter.life() + b.emitter.spawn() + 0.05f) it.remove();
        }
        for (Iterator<Pulse> it = pulses.iterator(); it.hasNext(); ) {
            Pulse p = it.next();
            p.age += dt;
            if (p.age >= p.fade) it.remove();
        }
        float s = 0;
        if (inspect != null) {
            if (ending) {
                float u = Math.min(1, endAge / END_FADE);
                s = endSurge * (1 - u * u * (3 - 2 * u));
            } else {
                s = inspect.surgeAt(this.clipTime);
            }
        }
        for (Pulse p : pulses) s += p.value();
        surge = Math.min(1, s);
        // the glow goes out with the inspect even when no frame is drawn any more (draw() places it while it shines)
        if (inspect == null || surge < 0.01f) glowLight = null;
    }

    /** 0..1 how far the skin's effects surge this frame. */
    public float surge() {
        return surge;
    }

    /** Anything left to draw or light (bursts, streams, the glow, a pending flash). */
    public boolean active() {
        return inspect != null || !bursts.isEmpty() || !flashes.isEmpty() || surge > 0.002f;
    }

    /**
     * Queues this frame's particles and places the lights.
     *
     * @param base the weapon's root space → view space (as for its meshes)
     */
    public void draw(DrawList list, Matrix4f base, Nodes nodes) {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) return;
        camera.rotation().conjugate(q).transform(viewDown.set(0, -1, 0));
        int guns = nodes.dual() ? 2 : 1;
        for (Burst b : bursts) {
            if (b.gun >= guns) continue;
            Matrix4f at = nodes.node(b.node, b.gun);
            if (at != null) GpuParticles.draw(list, b.emitter, modelView.set(base).mul(at), b.age[0], 1, viewDown);
        }
        if (inspect != null && inspect.emitters != null) {
            float end = ending ? 1 - Math.min(1, endAge / END_FADE) : 1;
            for (CraftData.SkinEmitter stream : inspect.emitters) {
                if (stream == null || stream.particles == null) continue;
                float fade = Math.max(0.01f, stream.fade);
                float alpha = Math.min(smooth((clipTime - stream.from) / fade), smooth((stream.to - clipTime) / fade)) * end;
                if (alpha <= 0.002f) continue;
                GpuParticles.Emitter emitter = ParticlePresets.get(stream.particles);
                if (emitter == null) continue;
                String node = stream.node == null || stream.node.isBlank() ? "gun" : stream.node.trim();
                for (int g = 0; g < (stream.twin ? guns : 1); g++) {
                    Matrix4f at = nodes.node(node, g);
                    if (at != null) GpuParticles.draw(list, emitter, modelView.set(base).mul(at), clipTime - stream.from, alpha, viewDown);
                }
            }
        }
        for (Flash f : flashes) {
            if (f.gun >= guns) continue;
            Matrix4f at = nodes.node(f.node, f.gun);
            if (at == null) continue;
            Vec3 world = ViewmodelRenderer.viewToWorld(camera, modelView.set(base).mul(at), camera.getPosition());
            CraftData.SkinLight l = f.light;
            LightSources.flash(world.x, world.y, world.z, l.rgb(), l.intensity, l.radius, l.seconds);
        }
        flashes.clear();
        glow(camera, base, nodes);
    }

    /** The inspect's glow: a light at its node, as bright as the surge, attached while it shines. */
    private void glow(Camera camera, Matrix4f base, Nodes nodes) {
        CraftData.SkinLight glow = inspect == null ? null : inspect.glow;
        Matrix4f at = glow == null || surge < 0.01f ? null : nodes.node(glow.node == null ? "gun" : glow.node.trim(), 0);
        if (at == null) {
            glowLight = null;
            return;
        }
        Vec3 world = ViewmodelRenderer.viewToWorld(camera, modelView.set(base).mul(at), camera.getPosition());
        int c = glow.rgb();
        glowLight = new LightSources.Light(world.x, world.y, world.z, (c >> 16 & 0xFF) / 255f, (c >> 8 & 0xFF) / 255f,
                (c & 0xFF) / 255f, glow.intensity * surge, glow.radius);
        if (!LightSources.isAttached(glowSource)) LightSources.attach(glowSource);
    }

    /** Drops everything (the weapon was put away or changed). */
    public void clear() {
        inspect = null;
        ending = false;
        bursts.clear();
        pulses.clear();
        flashes.clear();
        surge = 0;
        glowLight = null;
    }

    private static float smooth(float x) {
        float u = Math.max(0, Math.min(1, x));
        return u * u * (3 - 2 * u);
    }

    // ---- the skin's look --------------------------------------------------------------------------------

    private final SurgedStyle surged = new SurgedStyle();

    /** The weapon's skin style with this frame's surge (the same object every frame; only valid until the next call). */
    public GpuModel.Style style(GpuModel.Style skin) {
        surged.base = skin;
        surged.surge = surge;
        return surged;
    }

    /** A skin style whose shader effects surge. */
    private static final class SurgedStyle implements GpuModel.Style {
        GpuModel.Style base = GpuModel.Style.NONE;
        float surge;

        @Override
        public float[] tint(String material) {
            return base.tint(material);
        }

        @Override
        public TextureRef textureRef(String material) {
            return base.textureRef(material);
        }

        @Override
        public GpuTextureView texture(String material) {
            return base.texture(material);
        }

        @Override
        public MaterialLayer layer(String material, String region) {
            return base.layer(material, region);
        }

        @Override
        public boolean hidden(String material) {
            return base.hidden(material);
        }

        @Override
        public float surge() {
            return surge;
        }
    }
}
