package net.kroia.quilt;

import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.qsl.base.api.entrypoint.ModInitializer;

import net.kroia.banksystem.BankSystemMod;

public final class BankSystemQuilt implements ModInitializer {
    @Override
    public void onInitialize(ModContainer mod) {
        // Run our common setup.
        BankSystemMod.init();
        QuiltPlayerEvents.register();
        QuiltServerEvents.register();
    }
}
