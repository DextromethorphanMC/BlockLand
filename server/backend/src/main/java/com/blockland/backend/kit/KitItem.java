package com.blockland.backend.kit;

/**
 * One item a player starts a match with ("开局预设物品"). Any game type can opt in to kits.
 *
 * @param item  item id, optionally with components in /give syntax
 * @param slot  {@code 0}-{@code 35} (0-8 = hotbar), or {@code head}/{@code chest}/{@code legs}/{@code feet}/{@code offhand}.
 *              Players may rearrange non-armour items in the client ("装备布局").
 */
public record KitItem(String item, int count, String slot) {
}
