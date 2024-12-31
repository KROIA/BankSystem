package net.kroia.banksystem.forge;

import net.kroia.banksystem.util.ServerEvents;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber
public class ForgeServerEvents {
    @SubscribeEvent
    public static void onServerStart(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            if (serverLevel.dimension().equals(ServerLevel.OVERWORLD))
                ServerEvents.onServerStart(serverLevel.getServer());
        }
    }

    @SubscribeEvent
    public static void onServerStop(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            if (serverLevel.dimension().equals(ServerLevel.OVERWORLD))
                ServerEvents.onServerStop(serverLevel.getServer());
        }
    }

    @SubscribeEvent
    public static void onWorldSave(LevelEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            if (serverLevel.dimension().equals(ServerLevel.OVERWORLD))
                ServerEvents.onWorldSave(serverLevel);
        }
    }
}
