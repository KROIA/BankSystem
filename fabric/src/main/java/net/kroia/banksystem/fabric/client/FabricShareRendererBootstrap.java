package net.kroia.banksystem.fabric.client;

import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.kroia.banksystem.client.render.StampedShareRenderer;
import net.kroia.banksystem.minecraft.item.BankSystemItems;

/**
 * v2.0.9 — Fabric client hook for the stamped share's custom item renderer.
 *
 * <p>The item model is {@code builtin/entity}, so Fabric invokes the registered
 * {@code DynamicItemRenderer} for every display context; it delegates straight to
 * the common {@link StampedShareRenderer}. Must run after item registration
 * ({@code BankSystemMod.init()}), and only on the client — keep the call behind
 * the environment guard in {@code BankSystemFabric}.
 */
public final class FabricShareRendererBootstrap {

    private FabricShareRendererBootstrap() {}

    public static void register() {
        BuiltinItemRendererRegistry.INSTANCE.register(BankSystemItems.STAMPED_SHARE.get(),
                (stack, ctx, pose, buffers, light, overlay) ->
                        StampedShareRenderer.render(stack, ctx, pose, buffers, light, overlay));
    }
}
