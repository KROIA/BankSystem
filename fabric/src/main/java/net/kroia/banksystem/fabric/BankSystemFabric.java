package net.kroia.banksystem.fabric;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.util.BankSystemPlayerEvents;
import net.kroia.banksystem.util.BankSystemServerEvents;

public final class BankSystemFabric implements ModInitializer {
    @Override
    public void onInitialize() {

        // Client Events
        if(FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
                BankSystemMod.LOGGER.info("[FabricSetup] CLIENT_STARTED");
                BankSystemMod.onClientSetup();
            });
        }


        // Server Events
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            BankSystemMod.LOGGER.info("[FabricSetup] SERVER_STARTING");
            BankSystemMod.onServerSetup();
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            BankSystemMod.LOGGER.info("[FabricSetup] SERVER_STARTED");
            BankSystemServerEvents.onServerStart(server); // Handle world load (start)
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            BankSystemMod.LOGGER.info("[FabricSetup] SERVER_STOPPING");
            BankSystemServerEvents.onServerStop(server);
        });


        // Player Events
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            BankSystemPlayerEvents.onPlayerJoin(handler.getPlayer());
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            BankSystemPlayerEvents.onPlayerLeave(handler.getPlayer());
        });


        // Run our common setup.
        BankSystemMod.init();
    }
}
