package net.kroia.banksystem.forge;

import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.platform.Platform;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.compat.NEZNAMY_TAB_Placeholders;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber
public class ForgeServerEvents {

    public static void init()
    {
        LifecycleEvent.SERVER_STARTED.register((server)->{
            // Check if NEZNAMY/TAB is present and register placeholders
            BankSystemModBackend.onServerStart(server);
            DistExecutor.unsafeRunWhenOn(Dist.DEDICATED_SERVER, () -> {
                if (Platform.isModLoaded("tab"))
                {
                    NEZNAMY_TAB_Placeholders.register();
                }
                return () -> {};
            });

        });
        LifecycleEvent.SERVER_STOPPING.register(BankSystemModBackend::onServerStop);
    }
}
