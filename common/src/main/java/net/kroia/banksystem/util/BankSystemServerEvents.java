package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.ServerBankManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public class BankSystemServerEvents {

    public static void onServerStart(MinecraftServer server) {
        ServerBankManager.removeAllEventListeners();
        ServerBankManager.clear();
        BankSystemMod.loadDataFromFiles(server);
    }

    public static void onServerStop(MinecraftServer server) {
        BankSystemMod.saveDataToFiles(server);

    }

    public static void onWorldSave(ServerLevel level) {
        if(level.dimension().equals(ServerLevel.OVERWORLD))
            BankSystemMod.saveDataToFiles(level.getServer());
    }
    public static void onWorldSave(MinecraftServer server) {
            BankSystemMod.saveDataToFiles(server);
    }
}
