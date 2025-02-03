package net.kroia.banksystem.neoforge;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.menu.BankSystemMenus;
import net.kroia.banksystem.screen.custom.BankTerminalScreen;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = BankSystemMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public class NeoForgeSetup {

    // Mod setup for common (server)
    @SubscribeEvent
    public static void commonSetup(FMLCommonSetupEvent event) {
        BankSystemMod.LOGGER.info("[NeoForgeSetup] Common setup for server.");
        BankSystemMod.onServerSetup();
    }

    // Client setup (for client-side logic)
    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        BankSystemMod.LOGGER.info("[NeoForgeSetup] Client setup.");
        BankSystemMod.onClientSetup();
    }

    /*
        This is a workaround since the Architectury screen registration does not work with NeoForge.
     */
    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(BankSystemMenus.BANK_TERMINAL_CONTAINER_MENU.get(), BankTerminalScreen::new);
    }
}
