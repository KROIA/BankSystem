package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemMod;
import net.minecraft.server.level.ServerPlayer;


public class BankSystemPlayerEvents {


    public static void onPlayerJoin(ServerPlayer player) {

        BankSystemMod.onPlayerJoin(player);
    }

    public static void onPlayerLeave(ServerPlayer player) {
        // Add logic for player leaving
        BankSystemMod.onPlayerLeave(player);
    }
}
