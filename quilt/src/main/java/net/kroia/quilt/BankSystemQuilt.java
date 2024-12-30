package net.kroia.quilt;

import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.kroia.modutilities.UtilitiesPlatform;
import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.qsl.base.api.entrypoint.ModInitializer;

import net.kroia.banksystem.BankSystemMod;

public final class BankSystemQuilt implements ModInitializer {
    @Override
    public void onInitialize(ModContainer mod) {
        // Run our common setup.
        UtilitiesPlatform.setPlatform(new UtilitiesPlatformQuilt());

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            BankSystemMod.LOGGER.info("[QuiltSetup] Common setup for server.");
            UtilitiesPlatformQuilt.setServer(server);
            BankSystemMod.onServerSetup();
        });

        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
                BankSystemMod.LOGGER.info("[QuiltSetup] Client setup.");
                BankSystemMod.onClientSetup();
            });
        }

        BankSystemMod.init();
        QuiltPlayerEvents.register();
        QuiltServerEvents.register();
    }
}
