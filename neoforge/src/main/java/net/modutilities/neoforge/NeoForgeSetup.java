package net.modutilities.neoforge;

import net.kroia.banksystem.BankSystemMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod.EventBusSubscriber(modid = BankSystemMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
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
}
