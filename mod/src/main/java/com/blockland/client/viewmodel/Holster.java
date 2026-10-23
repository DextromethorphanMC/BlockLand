package com.blockland.client.viewmodel;

import com.blockland.anim.Ease;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.eventbus.api.listener.Priority;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

/**
 * Weapon switch (asset spec §31.4): the first-person weapon being switched away from does not vanish — it drops out of
 * view for {@link #TIME} seconds, still moving (its clip and its procedural motion run on), while the next weapon's
 * draw clip rises from below. Weapon modules hand over what they were showing as an {@link Outgoing}; this class only
 * times the drop and draws it before the hand pass, whatever is held now (another weapon or a vanilla item).
 */
public final class Holster {
    /** Seconds the outgoing weapon takes to leave the screen. */
    public static final float TIME = 0.2f;
    /** Roughly the shooting hand: the drop rotates the weapon about it. */
    private static final float PX = 0.12f, PY = -0.12f, PZ = -0.30f;
    /** The drop accelerates away (the hand lets go, gravity takes it); the lift as it is released rises and fades. */
    private static final Ease DROP = Ease.QUAD_IN, LIFT = Ease.SINE_OUT;

    /** A weapon on its way out. */
    public interface Outgoing {
        /** Advances its animation. */
        void update(float dt);

        /** Poses, queues and submits it with {@code base} mapping viewmodel space to view space. */
        void draw(Matrix4f base, int blockLight, int skyLight);
    }

    @Nullable
    private static Object owner;
    @Nullable
    private static Outgoing outgoing;
    private static long started, last;
    private static final Matrix4f base = new Matrix4f();

    private Holster() {
    }

    public static void init() {
        // before the weapon modules take the hand pass over (they cancel it); this listener never cancels
        RenderHandEvent.BUS.addListener(Priority.HIGH, event -> {
            if (event.getHand() == InteractionHand.MAIN_HAND) render(event.getPackedLight());
        });
    }

    /**
     * Starts dropping a weapon (replacing one still dropping).
     *
     * @param who the module's viewmodel instance, so the module can take it back ({@link #release})
     */
    public static void start(Object who, Outgoing weapon) {
        owner = who;
        outgoing = weapon;
        started = last = System.nanoTime();
    }

    /** A module reuses this viewmodel instance: it stops dropping. */
    public static void release(Object who) {
        if (owner == who) {
            owner = null;
            outgoing = null;
        }
    }

    private static void render(int light) {
        Outgoing weapon = outgoing;
        if (weapon == null) return;
        long now = System.nanoTime();
        float u = (now - started) / 1e9f / TIME;
        float dt = Math.min(0.1f, (now - last) / 1e9f);
        last = now;
        if (u >= 1) {
            outgoing = null;
            owner = null;
            return;
        }
        weapon.update(dt);
        // the hand lets go with a small lift, then the weapon falls away down-right, accelerating, muzzle dipping
        float e = (float) DROP.apply(u);
        float lift = u < 0.3f ? (float) (LIFT.apply(u / 0.15f > 1 ? 2 - u / 0.15f : u / 0.15f)) * 0.005f : 0;
        base.identity()
                .translate(0.05f * e, -0.34f * e + lift, 0.03f * e)
                .translate(PX, PY, PZ)
                .rotateX((float) Math.toRadians(-38 * e))
                .rotateZ((float) Math.toRadians(-24 * e))
                .rotateY((float) Math.toRadians(-8 * e))
                .translate(-PX, -PY, -PZ);
        weapon.draw(base, LightTexture.block(light), LightTexture.sky(light));
    }
}
