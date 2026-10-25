package com.blockland.server.anticheat.check;

import com.blockland.movement.action.BodyActions;
import com.blockland.server.anticheat.rules.RuleSet;
import com.blockland.server.anticheat.track.PlayerTrack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Block placement ({@code world.place_face}, anti-cheat spec §3): the face a block is placed against must face the
 * player's eye — a ray from the eye can never strike the back of a face, so clicking one means the click did not come
 * from looking (scaffolding cheats place blocks under the feet against hidden faces).
 */
public final class PlaceCheck {
    /** How far behind a face plane the eye may be (eye height changes, a move within the tick). */
    private static final double SLACK = 0.3;

    private final Judge judge;

    public PlaceCheck(Judge judge) {
        this.judge = judge;
    }

    public void place(ServerPlayer player, PlayerTrack t, BlockHitResult hit) {
        RuleSet rules = judge.rules(player.getUUID());
        if (!rules.on(Checks.PLACE_FACE) || player.isCreative() || player.isSpectator()) return;
        Vec3 eye = BodyActions.eyePosition(player);
        Vec3 normal = hit.getDirection().getUnitVec3();
        double side = eye.subtract(hit.getLocation()).dot(normal);
        double limit = -SLACK * rules.tolerance(Checks.PLACE_FACE);
        if (side < limit) {
            judge.flag(player.getUUID(), player, t, Checks.PLACE_FACE, Math.min(5, 1 + (limit - side) * 2),
                    String.format(Locale.ROOT, "点击的 %s 面背对视线 %.2f 格", hit.getDirection().getSerializedName(), -side));
        }
    }
}
