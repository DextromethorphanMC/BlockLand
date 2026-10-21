package com.blockland.server.content.model;

/**
 * One starting item of a mode's kit.
 *
 * @see com.blockland.server.kit.KitSlots for the slot syntax
 */
public final class KitItemDef {
    /** Item id with optional components in /give syntax. */
    public String item;
    public int count = 1;
    /** {@code 0}-{@code 35} (0-8 = hotbar) or {@code head}/{@code chest}/{@code legs}/{@code feet}/{@code offhand}. */
    public String slot;
}
