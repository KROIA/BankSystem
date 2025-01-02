package net.kroia.banksystem.fabric;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.util.BankSystemServerEvents;

public final class BankSystemFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // Proceed with mild caution.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            BankSystemMod.LOGGER.info("[FabricSetup] Common setup for server.");
            BankSystemServerEvents.onServerStart(server);
        });

        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
                BankSystemMod.LOGGER.info("[FabricSetup] Client setup.");
                BankSystemMod.onClientSetup();
            });
        }


        // Run our common setup.
        BankSystemMod.init();
        FabricPlayerEvents.register();
        FabricServerEvents.register();
    }
}
