package net.kroia.banksystem.util;

import dev.architectury.event.events.common.LifecycleEvent;
import net.kroia.banksystem.BankSystemSettings;
import net.kroia.banksystem.banking.ServerBankManager;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;


public class PlayerEvents {


    public static void onPlayerJoin(ServerPlayer player) {
        ServerBankManager.createUser(
                player.getUUID(),
                player.getName().getString(),
                new ArrayList<>(),
                true,
                BankSystemSettings.Player.STARTING_BALANCE
        );
    }

    public static void onPlayerLeave(ServerPlayer player) {
        // Add logic for player leaving
    }
}
