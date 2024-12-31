package net.kroia.quilt;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kroia.banksystem.util.BankSystemPlayerEvents;

public class QuiltPlayerEvents {
    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            BankSystemPlayerEvents.onPlayerJoin(handler.getPlayer());
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            BankSystemPlayerEvents.onPlayerLeave(handler.getPlayer());
        });
    }
}
