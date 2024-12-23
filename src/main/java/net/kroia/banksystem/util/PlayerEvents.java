package net.kroia.banksystem.util;

import net.kroia.banksystem.ModSettings;
import net.kroia.banksystem.banking.ServerBankManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;

@Mod.EventBusSubscriber
public class PlayerEvents {

    // Called when a player joins the server_sender
    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {

            ServerBankManager.createUser(player.getUUID(),player.getName().getString(), new ArrayList<>(),true, ModSettings.Player.STARTING_BALANCE);

        }
    }

    // Called when a player leaves the server_sender
    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {

        }
    }
}
