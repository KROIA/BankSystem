package net.kroia.banksystem.forge;

import dev.architectury.event.events.common.LifecycleEvent;
import net.kroia.banksystem.util.BankSystemServerEvents;
import net.kroia.modutilities.ModUtilitiesMod;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber
public class ForgeServerEvents {

    public static void init()
    {
        LifecycleEvent.SERVER_STARTED.register(server -> {
            ModUtilitiesMod.LOGGER.info("[ForgeSetup] SERVER_STARTING");
            BankSystemServerEvents.onServerStart(server);
        });
        LifecycleEvent.SERVER_STARTING.register(server -> {
            ModUtilitiesMod.LOGGER.info("[ForgeSetup] SERVER_STOPPED");
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
