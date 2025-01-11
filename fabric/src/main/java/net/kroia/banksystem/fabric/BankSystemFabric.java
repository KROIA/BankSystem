package net.kroia.banksystem.fabric;

import mezz.jei.api.IModPlugin;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.compat.BankSystemJeiPlugin;
import net.kroia.banksystem.screen.custom.BankTerminalScreen;
import net.kroia.banksystem.util.BankSystemPlayerEvents;
import net.kroia.banksystem.util.BankSystemServerEvents;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

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


        if (isJeiLoaded()) {
            BankTerminalScreen.widthPercentage = 70;
            // Dynamically register JEI plugin
            /*try {
                Class<?> pluginClass = Class.forName("net.kroia.banksystem.compat.BankSystemJeiPlugin");
                Object pluginInstance = pluginClass.getDeclaredConstructor().newInstance();


                // Optionally, invoke methods to ensure plugin registration
                System.out.println("JEI integration loaded: " + pluginClass.getName());
            } catch (Exception e) {
                e.printStackTrace();
                System.err.println("Failed to load JEI integration.");
            }*/

            /*try {
                Class<?> jeiLoaderClass = Class.forName("mezz.jei.startup.JeiStarter");
                Field pluginManagerField = jeiLoaderClass.getDeclaredField("pluginManager");
                pluginManagerField.setAccessible(true);
                Object pluginManager = pluginManagerField.get(null);

                // Assuming there's an addPlugin method in pluginManager
                Method addPluginMethod = pluginManager.getClass().getDeclaredMethod("addPlugin", IModPlugin.class);
                addPluginMethod.invoke(pluginManager, new BankSystemJeiPlugin());
                System.out.println("JEI plugin registered dynamically.");
            } catch (Exception e) {
                e.printStackTrace();
                System.err.println("Failed to register JEI plugin dynamically.");
            }*/
        }
    }


    public static boolean isJeiLoaded() {
        return FabricLoader.getInstance().isModLoaded("jei");
    }
}
