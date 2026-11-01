package net.kroia.banksystem.fabric.client;

import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry; // mc<=1.21.3
import net.kroia.banksystem.client.render.StampedShareRenderer; // mc<=1.21.3
import net.kroia.banksystem.minecraft.item.BankSystemItems; // mc<=1.21.3

/**
 * v2.1.0 — Fabric client hook for the stamped share's custom item renderer.
 *
 * <p>Up to 1.21.3 the item model is {@code builtin/entity} and a {@code DynamicItemRenderer}
 * delegates to the common {@code StampedShareRenderer}; from 1.21.4 the special item model
 * renderer type {@code banksystem:stamped_share} is registered instead. Must run after item registration
 * ({@code BankSystemMod.init()}), and only on the client — keep the call behind
 * the environment guard in {@code BankSystemFabric}.
 */
public final class FabricShareRendererBootstrap {

    private FabricShareRendererBootstrap() {}

    public static void register() {
        BuiltinItemRendererRegistry.INSTANCE.register(BankSystemItems.STAMPED_SHARE.get(), (stack, ctx, pose, buffers, light, overlay) -> StampedShareRenderer.render(stack, ctx, pose, buffers, light, overlay)); // mc<=1.21.3
        //net.kroia.banksystem.client.render.StampedShareSpecialRenderer.registerViaVanillaMapper(); // mc>=1.21.4 (item model special renderer type)
    }
}
