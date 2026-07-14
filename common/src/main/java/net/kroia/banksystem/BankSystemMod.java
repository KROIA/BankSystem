package net.kroia.banksystem;

import net.kroia.banksystem.api.BankSystemAPI;

public final class BankSystemMod {

    public static final String MOD_ID = "banksystem";
    public static final String VERSION = "2.0.4";

    // Set to false for release builds to hide dev-only commands (exportrecipes, testScreen, etc.)
    public static final boolean ENABLE_DEV_FEATURES = true;

    /**
     * Maximum squared distance (in blocks²) between a player and a block-entity position
     * before client-sent block-entity packets are rejected. 64.0 == 8 blocks, chosen to
     * cover vanilla reach plus a buffer for in-flight movement.
     */
    public static final double MAX_INTERACT_DISTANCE_SQR = 64.0;

    private static volatile BankSystemModBackend backend;

    public static synchronized void init() {
        if(backend == null)
            backend = new BankSystemModBackend();
    }


    public static BankSystemAPI getAPI() {
        if(backend == null)
            init();
        return backend;
    }
}
