package net.kroia.banksystem.neoforge;

import net.kroia.banksystem.BankSystemMod;
import net.neoforged.fml.common.Mod;

@Mod(BankSystemMod.MOD_ID)
public final class BankSystemNeoForge {
    public BankSystemNeoForge() {
        // Run our common setup.
        BankSystemMod.init();
    }
}
