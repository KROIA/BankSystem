package net.kroia.quilt;

import net.fabricmc.api.EnvType;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.util.BankSystemPlayerEvents;
import net.kroia.banksystem.util.BankSystemServerEvents;
import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.loader.api.minecraft.MinecraftQuiltLoader;
import org.quiltmc.qsl.base.api.entrypoint.ModInitializer;
import org.quiltmc.qsl.lifecycle.api.client.event.ClientLifecycleEvents;
import org.quiltmc.qsl.lifecycle.api.event.ServerLifecycleEvents;
import org.quiltmc.qsl.networking.api.ServerPlayConnectionEvents;

public final class BankSystemQuilt implements ModInitializer {
    @Override
    public void onInitialize(ModContainer mod) {

        // Client Events
        if(MinecraftQuiltLoader.getEnvironmentType() == EnvType.CLIENT) {
            ClientLifecycleEvents.READY.register(client -> {
                BankSystemMod.logDebug("[QuiltSetup] CLIENT READY");
                BankSystemMod.onClientSetup();
            });
        }


        // Server Events
        ServerLifecycleEvents.STARTING.register(server-> {
            BankSystemMod.logDebug("[QuiltSetup] SERVER STARTING");
            BankSystemMod.onServerSetup();
        });

        ServerLifecycleEvents.READY.register(server-> {
            BankSystemMod.logDebug("[QuiltSetup] SERVER READY");
            BankSystemServerEvents.onServerStart(server); // Handle world load (start)
        });

        ServerLifecycleEvents.STOPPING.register(server -> {
            BankSystemMod.logDebug("[QuiltSetup] SERVER STOPPING");
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
