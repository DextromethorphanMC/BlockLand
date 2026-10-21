package com.blockland.client;

import com.blockland.network.BLNetwork;
import com.blockland.network.packet.ShopPackets;

import java.util.List;

/** Shop catalogue received from the server (product images come from {@link ClientMedia}). Client thread only. */
public final class ClientShopState {
    private static List<ShopPackets.Product> products = List.of();
    private static String error = "";
    private static boolean loaded;
    private static String lastResult = "";
    private static boolean lastResultSuccess;

    private ClientShopState() {
    }

    public static void request() {
        BLNetwork.sendToServer(ShopPackets.C2SRequest.INSTANCE);
    }

    static void update(ShopPackets.S2CData data) {
        products = data.products();
        error = data.error();
        loaded = true;
    }

    static void purchaseResult(ShopPackets.S2CPurchaseResult result) {
        lastResult = result.message();
        lastResultSuccess = result.success();
    }

    static void clear() {
        products = List.of();
        loaded = false;
        error = "";
        lastResult = "";
    }

    public static List<ShopPackets.Product> products() {
        return products;
    }

    public static boolean loaded() {
        return loaded;
    }

    public static String error() {
        return error;
    }

    public static String lastResult() {
        return lastResult;
    }

    public static boolean lastResultSuccess() {
        return lastResultSuccess;
    }
}
