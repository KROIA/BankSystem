package net.kroia.banksystem.neoforge;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.menu.BankSystemMenus;
import net.kroia.banksystem.screen.custom.BankTerminalScreen;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@Mod(BankSystemMod.MOD_ID)
public final class BankSystemNeoForge {
    public BankSystemNeoForge() {
        // Run our common setup.
        BankSystemMod.init();

        //RegisterMenuScreensEvent

    }



}
