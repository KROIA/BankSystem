package net.kroia.banksystem.fabric;

import dev.architectury.platform.Platform;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.minecraft.compat.NEZNAMY_TAB_Placeholders;
import net.kroia.banksystem.util.BankSystemGuiScreen;

public final class BankSystemFabric implements ModInitializer {
    @Override
    public void onInitialize() {

        // Client Events
        if(FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
                BankSystemModBackend.onClientSetup();
            });
            // Task #53 (v2.0.8) — stamped-share item icon: tint-only via ItemColor handler.
            // Full monogram-on-item render deferred (BuiltinItemRenderer + builtin/entity
            // needs per-context vanilla-parity quad rendering; scaffold at
            // net.kroia.banksystem.fabric.client.FabricShareRenderer is intentionally
            // NOT registered until that work lands. IShareIconRenderer covers mod GUIs.
        }


        // Server Events
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            BankSystemModBackend.onServerSetup();
        });

        // Handle world load (start)
        ServerLifecycleEvents.SERVER_STARTED.register((server)->
        {
            BankSystemModBackend.onServerStart(server);
            // Check if NEZNAMY/TAB is present and register placeholders
            if (Platform.isModLoaded("tab")) {
                NEZNAMY_TAB_Placeholders.register();
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(BankSystemModBackend::onServerStop);


        // Player Events
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            BankSystemModBackend.onPlayerJoin(handler.getPlayer());
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            BankSystemModBackend.onPlayerLeave(handler.getPlayer());
        });



        /*ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            // code to run when leaving a server
            BankSystemModBackend.onPlayerLeaveClientSide();
        });*/


        // Run our common setup.
        BankSystemMod.init();

        // Register Fabric Transfer API item storage providers (must run after BE types exist).
        FabricItemStorageProviders.register();

        if (isJeiLoaded() && Platform.getEnv() == EnvType.CLIENT) {
            BankSystemGuiScreen.setJeiModLoaded(true);
        }
    }


    public static boolean isJeiLoaded() {
        return FabricLoader.getInstance().isModLoaded("jei");
    }
}
