package net.kroia.banksystem.neoforge;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

@EventBusSubscriber(modid = BankSystemMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public class NeoForgeSetup {

    @SubscribeEvent
    public static void commonSetup(FMLCommonSetupEvent event) {
        BankSystemModBackend.onServerSetup();
    }
}
