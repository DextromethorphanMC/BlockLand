package com.blockland.client.backpack;

/** An inventory screen that shows the backpack slot and, when open, its storage (UI spec §1). */
interface BackpackScreen {
    boolean backpackSlotShown();

    boolean storageShown();
}
