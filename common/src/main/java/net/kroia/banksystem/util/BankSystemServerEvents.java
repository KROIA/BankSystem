package net.kroia.banksystem.util;

import dev.architectury.event.events.common.TickEvent;
import net.kroia.banksystem.BankSystemMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public class BankSystemServerEvents {

    public static void onServerStart(MinecraftServer server) {


        BankSystemMod.onServerStart(server);

        TickEvent.SERVER_POST.register(BankSystemServerEvents::onServerTick);
    }

    public static void onServerStop(MinecraftServer server) {
        TickEvent.SERVER_POST.unregister(BankSystemServerEvents::onServerTick);
        BankSystemMod.onServerStop(server);
    }

    public static void onWorldSave(ServerLevel level) {

    }

    private static void onServerTick(MinecraftServer server)
    {
        BankSystemMod.SERVER_DATA_HANDLER.tickUpdate();
    }
}
