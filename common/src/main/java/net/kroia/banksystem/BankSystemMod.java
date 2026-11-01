package net.kroia.banksystem;

import net.kroia.banksystem.api.BankSystemAPI;

public final class BankSystemMod {

    public static final String MOD_ID = "banksystem";

    /** Registry id in the banksystem namespace. */
    //public static net.minecraft.resources.ResourceLocation id(String path) { // mc<=1.21.10
    public static net.minecraft.resources.Identifier id(String path) { // mc>=1.21.11
        //return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MOD_ID, path); // mc<=1.21.10
        return net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, path); // mc>=1.21.11
    }
    public static final String VERSION = "2.2.0";

    // Dev-only code (unit tests, test commands) is switched by enable_dev_features in gradle.properties:
    // Gradle compiles the testing package only when it is true, and this flag follows from its presence.
    // It also gates the exportrecipes command and async debug logging.
    public static final String DEV_BOOTSTRAP = "net.kroia.banksystem.testing.DevBootstrap";
    public static final boolean ENABLE_DEV_FEATURES = devClassesPresent();

    private static boolean devClassesPresent() {
        try {
            Class.forName(DEV_BOOTSTRAP, false, BankSystemMod.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

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
