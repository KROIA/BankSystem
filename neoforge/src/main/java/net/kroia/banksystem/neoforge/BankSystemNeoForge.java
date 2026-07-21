package net.kroia.banksystem.neoforge;

import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.neoforged.fml.common.Mod;

@Mod(BankSystemMod.MOD_ID)
public final class BankSystemNeoForge {
    public BankSystemNeoForge() {
        // Run our common setup.

        NeoForgeServerEvents.init();
        BankSystemMod.init();

        // Let the screens reserve space for JEI's ingredient list panel
        // (mirrors the same check in BankSystemFabric).
        if (Platform.getEnvironment() == Env.CLIENT && Platform.isModLoaded("jei")) {
            BankSystemGuiScreen.setJeiModLoaded(true);
        }
    }
}
