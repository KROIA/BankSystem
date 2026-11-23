package net.kroia.banksystem.neoforge;

import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLConstructModEvent;

@Mod(BankSystemMod.MOD_ID)
public final class BankSystemNeoForge {
    public BankSystemNeoForge(IEventBus modBus) {
        // NeoForge constructs mods in parallel and Architectury's event lists are not thread-safe, so the setup runs
        // in the construct deferred queue: main thread, one task at a time, after every constructor, before RegisterEvent.
        modBus.addListener((FMLConstructModEvent e) -> e.enqueueWork(() -> {
            NeoForgeServerEvents.init();
            BankSystemMod.init();

            // Let the screens reserve space for JEI's ingredient list panel
            // (mirrors the same check in BankSystemFabric).
            if (Platform.getEnvironment() == Env.CLIENT && Platform.isModLoaded("jei")) {
                BankSystemGuiScreen.setJeiModLoaded(true);
            }
        }));
    }
}
