package net.kroia.banksystem.forge;

import dev.architectury.event.events.common.LifecycleEvent;
import net.kroia.banksystem.BankSystemModBackend;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber
public class ForgeServerEvents {

    public static void init()
    {
        LifecycleEvent.SERVER_STARTED.register(server -> {
            //BankSystemMod.logDebug("[ForgeSetup] SERVER_STARTING");
            BankSystemModBackend.onServerStart(server);
        });
        LifecycleEvent.SERVER_STOPPING.register(server -> {
            //BankSystemMod.logDebug("[ForgeSetup] SERVER_STOPPED");
            BankSystemModBackend.onServerStop(server);
        });
    }
}
