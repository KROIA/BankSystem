package net.kroia.quilt;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kroia.banksystem.util.PlayerEvents;
import net.minecraft.server.level.ServerPlayer;

public class QuiltPlayerEvents {
    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            PlayerEvents.onPlayerJoin(handler.getPlayer());
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            PlayerEvents.onPlayerLeave(handler.getPlayer());
        });
    }
}
