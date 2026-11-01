package net.kroia.banksystem.neoforge.client;

import com.mojang.blaze3d.vertex.PoseStack; // mc<=1.21.3
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.client.render.StampedShareRenderer; // mc<=1.21.3
import net.kroia.banksystem.minecraft.item.BankSystemItems; // mc<=1.21.3
import net.minecraft.client.Minecraft; // mc<=1.21.3
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer; // mc<=1.21.3
import net.minecraft.client.renderer.MultiBufferSource; // mc<=1.21.3
import net.minecraft.world.item.ItemDisplayContext; // mc<=1.21.3
import net.minecraft.world.item.ItemStack; // mc<=1.21.3
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions; // mc<=1.21.3
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent; // mc<=1.21.3
//import net.kroia.banksystem.client.render.StampedShareSpecialRenderer; // mc>=1.21.4
//import net.neoforged.neoforge.client.event.RegisterSpecialModelRendererEvent; // mc>=1.21.4

/**
 * v2.1.0 — NeoForge client hook for the stamped share's custom item renderer.
 *
 * <p>Up to 1.21.3 the item model is {@code builtin/entity}, so vanilla routes rendering through
 * {@code IClientItemExtensions#getCustomRenderer()}; the BEWLR delegates straight to the common
 * {@code StampedShareRenderer} (created lazily on first render so the Minecraft singleton is fully
 * constructed). From 1.21.4 the special item model renderer type {@code banksystem:stamped_share}
 * is registered instead.
 */
@EventBusSubscriber(modid = BankSystemMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class NeoForgeShareExtensionsBootstrap {
    private NeoForgeShareExtensionsBootstrap() {}

    @SubscribeEvent // mc<=1.21.3
    public static void onRegisterClientExtensions(RegisterClientExtensionsEvent event) { // mc<=1.21.3
        event.registerItem(new IClientItemExtensions() { // mc<=1.21.3
            private BlockEntityWithoutLevelRenderer renderer; // mc<=1.21.3
            @Override // mc<=1.21.3
            public BlockEntityWithoutLevelRenderer getCustomRenderer() { // mc<=1.21.3
                if (renderer == null) { // mc<=1.21.3
                    Minecraft mc = Minecraft.getInstance(); // mc<=1.21.3
                    renderer = new BlockEntityWithoutLevelRenderer(mc.getBlockEntityRenderDispatcher(), mc.getEntityModels()) { // mc<=1.21.3
                        @Override // mc<=1.21.3
                        public void renderByItem(ItemStack stack, ItemDisplayContext ctx, PoseStack pose, MultiBufferSource buffers, int light, int overlay) { // mc<=1.21.3
                            StampedShareRenderer.render(stack, ctx, pose, buffers, light, overlay); // mc<=1.21.3
                        } // mc<=1.21.3
                    }; // mc<=1.21.3
                } // mc<=1.21.3
                return renderer; // mc<=1.21.3
            } // mc<=1.21.3
        }, BankSystemItems.STAMPED_SHARE.get()); // mc<=1.21.3
    } // mc<=1.21.3

    //@SubscribeEvent // mc>=1.21.4
    //public static void onRegisterSpecialModelRenderers(RegisterSpecialModelRendererEvent event) { // mc>=1.21.4
        //event.register(StampedShareSpecialRenderer.ID, StampedShareSpecialRenderer.Unbaked.MAP_CODEC); // mc>=1.21.4
    //} // mc>=1.21.4
}
