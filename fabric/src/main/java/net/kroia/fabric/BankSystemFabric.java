package net.kroia.fabric;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.fabric.client.UtilitiesPlatformFabric;
import net.kroia.modutilities.UtilitiesPlatform;

public final class BankSystemFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // Proceed with mild caution.
        UtilitiesPlatform.setPlatform(new UtilitiesPlatformFabric());
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            System.out.println("[FabricSetup] Common setup for server.");
            UtilitiesPlatformFabric.setServer(server);
            BankSystemMod.onServerSetup();
        });

        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
                System.out.println("[FabricSetup] Client setup.");
                BankSystemMod.onClientSetup();
            });
        }


        // Run our common setup.
        BankSystemMod.init();
        FabricPlayerEvents.register();
        FabricServerEvents.register();
    }
}
