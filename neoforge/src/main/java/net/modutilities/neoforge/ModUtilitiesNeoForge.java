package net.modutilities.neoforge;

import net.kroia.banksystem.BankSystemMod;
import net.neoforged.fml.common.Mod;

@Mod(BankSystemMod.MOD_ID)
public final class ModUtilitiesNeoForge {
    public ModUtilitiesNeoForge() {
        // Run our common setup.

        NeoForgeServerEvents.init();
        BankSystemMod.init();
    }
}
