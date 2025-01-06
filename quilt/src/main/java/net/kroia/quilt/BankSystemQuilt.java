package net.kroia.quilt;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.kroia.banksystem.util.BankSystemPlayerEvents;
import net.kroia.banksystem.util.BankSystemServerEvents;
import org.quiltmc.loader.api.minecraft.MinecraftQuiltLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.kroia.banksystem.BankSystemMod;

public final class BankSystemQuilt implements ModInitializer {
    @Override
    public void onInitialize() {

        // Client Events
        if(MinecraftQuiltLoader.getEnvironmentType() == EnvType.CLIENT) {
            ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
                BankSystemMod.LOGGER.info("[QuiltSetup] CLIENT READY");
                BankSystemMod.onClientSetup();
            });
        }


        // Server Events
        ServerLifecycleEvents.SERVER_STARTING.register(server-> {
            BankSystemMod.LOGGER.info("[QuiltSetup] SERVER STARTING");
            BankSystemMod.onServerSetup();
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server-> {
            BankSystemMod.LOGGER.info("[QuiltSetup] SERVER READY");
            BankSystemServerEvents.onServerStart(server); // Handle world load (start)
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            BankSystemMod.LOGGER.info("[QuiltSetup] SERVER STOPPED");
            BankSystemServerEvents.onServerStop(server); // Handle world save (stop)
        });


        // Player Events
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            BankSystemPlayerEvents.onPlayerJoin(handler.getPlayer());
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            BankSystemPlayerEvents.onPlayerLeave(handler.getPlayer());
        });

        BankSystemMod.init();
    }
}
