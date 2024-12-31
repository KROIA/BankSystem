package net.kroia.quilt;

import net.kroia.banksystem.util.BankSystemServerEvents;
import org.quiltmc.qsl.lifecycle.api.event.ServerLifecycleEvents;

public class QuiltServerEvents {
    public static void register() {
        // World load event
        ServerLifecycleEvents.STARTING.register(server-> {
            BankSystemServerEvents.onServerStart(server); // Handle world load (start)
        });

        // World save event
        ServerLifecycleEvents.STOPPING.register(server -> {
            BankSystemServerEvents.onServerStop(server); // Handle world save (stop)
        });
    }
}
