package net.modutilities.neoforge;

import dev.architectury.event.events.common.LifecycleEvent;
import net.kroia.banksystem.util.BankSystemServerEvents;
import net.kroia.modutilities.ModUtilitiesMod;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.level.LevelEvent;

@EventBusSubscriber
public class NeoForgeServerEvents {

    public static void init()
    {
        LifecycleEvent.SERVER_STARTED.register(server -> {
            ModUtilitiesMod.LOGGER.info("[NeoForgeSetup] SERVER_STARTING");
            BankSystemServerEvents.onServerStart(server);
        });
        LifecycleEvent.SERVER_STOPPING.register(server -> {
            ModUtilitiesMod.LOGGER.info("[NeoForgeSetup] SERVER_STOPPED");
            BankSystemServerEvents.onServerStop(server);
        });
    }

    @SubscribeEvent
    public static void onWorldSave(LevelEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            if (serverLevel.dimension().equals(ServerLevel.OVERWORLD))
                BankSystemServerEvents.onWorldSave(serverLevel);
        }
    }
}
