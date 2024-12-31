package net.kroia.banksystem.fabric;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.util.BankSystemServerEvents;
import net.minecraft.server.level.ServerLevel;

public class FabricServerEvents {
    public static void register() {
        // Server start (world load)
        ServerWorldEvents.LOAD.register((server, world)-> {
            if(world.isClientSide())
                return;
            UtilitiesPlatformFabric.setServer(server);
            BankSystemMod.onServerSetup();
            //if(world.getLevel().dimension().equals(ServerLevel.OVERWORLD))
            //    BankSystemServerEvents.onServerStart(server);
        });

        // Server stop (world unload)
        ServerWorldEvents.UNLOAD.register((server, world)-> {
            if(world.isClientSide())
                return;
            if(world.getLevel().dimension().equals(ServerLevel.OVERWORLD))
                BankSystemServerEvents.onServerStop(server);
        });

        // World save
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (ServerLevel level : server.getAllLevels()) {
                BankSystemServerEvents.onWorldSave(level);
            }
        });
    }
}
