package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.banking.ServerBankManager;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;


public class PlayerEvents {


    public static void onPlayerJoin(ServerPlayer player) {
        ServerBankManager.createUser(
                player,
                new ArrayList<>(),
                true,
                BankSystemModSettings.Player.STARTING_BALANCE
        );
    }

    public static void onPlayerLeave(ServerPlayer player) {
        // Add logic for player leaving
    }
}
