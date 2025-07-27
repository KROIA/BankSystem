package net.kroia.banksystem.forge;

import dev.architectury.event.events.common.LifecycleEvent;
import net.kroia.banksystem.BankSystemModBackend;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber
public class ForgeServerEvents {

    public static void init()
    {
        LifecycleEvent.SERVER_STARTED.register(BankSystemModBackend::onServerStart);
        LifecycleEvent.SERVER_STOPPING.register(BankSystemModBackend::onServerStop);
    }
}
