package net.kroia.banksystem.forge;

import net.kroia.banksystem.BankSystemMod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod.EventBusSubscriber(modid = BankSystemMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ForgeSetup {

    // Mod setup for common (server)
    @SubscribeEvent
    public static void commonSetup(FMLCommonSetupEvent event) {
        BankSystemMod.logDebug("[ForgeSetup] Common setup for server.");
        BankSystemMod.onServerSetup();
    }

    // Client setup (for client-side logic)
    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        BankSystemMod.logDebug("[ForgeSetup] Client setup.");
        BankSystemMod.onClientSetup();
    }
}
