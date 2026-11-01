package net.kroia.quilt;

import dev.architectury.platform.Platform;
import net.fabricmc.api.EnvType;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.client.render.StampedShareRenderer;
import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.kroia.banksystem.minecraft.compat.NEZNAMY_TAB_Placeholders;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.loader.api.minecraft.MinecraftQuiltLoader;
import org.quiltmc.qsl.base.api.entrypoint.ModInitializer;
import org.quiltmc.qsl.lifecycle.api.client.event.ClientLifecycleEvents;
import org.quiltmc.qsl.lifecycle.api.event.ServerLifecycleEvents;
import org.quiltmc.qsl.networking.api.ServerPlayConnectionEvents;

public final class BankSystemQuilt implements ModInitializer {
    @Override
    public void onInitialize() {

        // Client Events
        if(MinecraftQuiltLoader.getEnvironmentType() == EnvType.CLIENT) {
            ClientLifecycleEvents.READY.register(client -> {
                BankSystemModBackend.onClientSetup();
            });
        }


        // Server Events
        ServerLifecycleEvents.STARTING.register(server-> {
            BankSystemModBackend.onServerSetup();
        });

        // Handle world load (start)
        ServerLifecycleEvents.READY.register((server)->
        {
            BankSystemModBackend.onServerStart(server);
            // Check if NEZNAMY/TAB is present and register placeholders
            if (Platform.isModLoaded("tab")) {
                NEZNAMY_TAB_Placeholders.register();
            }
        });

        // Handle world save (stop)
        ServerLifecycleEvents.STOPPING.register(BankSystemModBackend::onServerStop);


        // Player Events
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            BankSystemModBackend.onPlayerJoin(handler.getPlayer());
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            BankSystemModBackend.onPlayerLeave(handler.getPlayer());
        });

        BankSystemMod.init();

        // Custom item renderer registration requires items to exist — must be after
        // BankSystemMod.init(). Uses the Fabric rendering API (provided on Quilt by QFAPI).
        if (MinecraftQuiltLoader.getEnvironmentType() == EnvType.CLIENT) {
            BuiltinItemRendererRegistry.INSTANCE.register(BankSystemItems.STAMPED_SHARE.get(),
                    (stack, ctx, pose, buffers, light, overlay) ->
                            StampedShareRenderer.render(stack, ctx, pose, buffers, light, overlay));
        }
    }
}
