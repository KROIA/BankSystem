package net.kroia.banksystem;

import net.kroia.banksystem.api.BankSystemAPI;

public final class BankSystemMod {

    public static final String MOD_ID = "banksystem";
    private static BankSystemModBackend backend;

    public static void init() {
        if(backend == null)
            backend = new BankSystemModBackend();
    }


    public static BankSystemAPI getAPI() {
        if(backend == null)
            backend = new BankSystemModBackend();
        return backend;
    }
}
