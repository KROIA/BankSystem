package net.kroia.banksystem.forge;

import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.fml.common.Mod;

import net.kroia.banksystem.BankSystemMod;

@Mod(BankSystemMod.MOD_ID)
public final class BankSystemForge {
    public BankSystemForge() {
        // Submit our event bus to let Architectury API register our content on the right time.
        EventBuses.registerModEventBus(BankSystemMod.MOD_ID, Mod.EventBusSubscriber.Bus.MOD.bus().get());
        ForgeServerEvents.init();
        // Run our common setup.
        BankSystemMod.init();
    }

}
