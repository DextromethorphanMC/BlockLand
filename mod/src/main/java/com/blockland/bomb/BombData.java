package com.blockland.bomb;

import com.blockland.common.Handling;

/**
 * The bomb (炸药包, C4) as written in {@code data/blockland/bomb/bomb.json} (read by Gson; field names = JSON keys). How it
 * looks and feels in the hand, how hard it goes off and how it sounds while it ticks. How long planting, defusing and the
 * fuse take is the mode's business (the 枪战爆破 settings), so admins tune the match without touching the item.
 */
public final class BombData {
    public String name = "炸药包";
    public String description = "";
    /** GLB path under {@code assets/blockland/} (first person; asset spec §39). */
    public String model = "models/bomb/c4.glb";
    public float drawTime = 0.6f;
    public Anims anims = new Anims();
    public Handling handling = new Handling();
    public Blast blast = new Blast();
    public Beep beep = new Beep();
    /** Blocks from a defender's eyes to the bomb within which it can be defused. */
    public float defuseRange = 2.2f;
    /** Degrees between the view and the bomb within which a defender is looking at it. */
    public float defuseAngle = 40;
    /** Blocks the planter may drift (horizontally) from where they started before the plant is broken off. */
    public float plantDrift = 0.45f;
    /** Blocks within which an attacker walking over a dropped bomb picks it up. */
    public float pickupRange = 1.4f;

    /** Animation names in the GLB (asset spec §39). */
    public static final class Anims {
        public String idle = "idle";
        public String draw = "draw";
        public String inspect = "inspect";
        public String walk = "walk";
        public String sprint = "sprint";
        public String tacSprint = "tac_sprint";
        /** Kneel, set it down, key the code in; stretched to the mode's plant time. */
        public String plant = "plant";
    }

    /**
     * What the explosion does (server) and how big it looks (client): full {@code damage} within {@code innerRadius},
     * falling off to {@code minDamage} at {@code radius}. It never breaks blocks.
     */
    public static final class Blast {
        public float radius = 16;
        public float innerRadius = 5;
        public float damage = 200;
        public float minDamage = 6;
        /** True: walls do not shelter (a demolition charge); false: three rays decide the exposure like a grenade. */
        public boolean throughWalls = true;
        public float knockback = 1.6f;
        /** Camera shake multiplier for the players around. */
        public float shake = 3;
    }

    /** The planted bomb's beep: slow at first, faster towards the end, a solid tone in the last {@code finalSeconds}. */
    public static final class Beep {
        public float slowInterval = 1.0f;
        public float fastInterval = 0.12f;
        public float finalSeconds = 1.2f;
    }
}
