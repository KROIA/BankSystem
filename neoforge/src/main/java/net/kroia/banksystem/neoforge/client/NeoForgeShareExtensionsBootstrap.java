package net.kroia.banksystem.neoforge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.client.render.StampedShareRenderer;
import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/**
 * v2.0.9 — NeoForge client hook for the stamped share's custom item renderer.
 *
 * <p>The item model is {@code builtin/entity}, so vanilla routes rendering through
 * {@link IClientItemExtensions#getCustomRenderer()}; the BEWLR delegates straight to
 * the common {@link StampedShareRenderer}. Created lazily on first render so the
 * {@link Minecraft} singleton is guaranteed to be fully constructed.
 */
@EventBusSubscriber(modid = BankSystemMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class NeoForgeShareExtensionsBootstrap {
    private NeoForgeShareExtensionsBootstrap() {}

    @SubscribeEvent
    public static void onRegisterClientExtensions(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            private BlockEntityWithoutLevelRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) {
                    Minecraft mc = Minecraft.getInstance();
                    renderer = new BlockEntityWithoutLevelRenderer(
                            mc.getBlockEntityRenderDispatcher(), mc.getEntityModels()) {
                        @Override
                        public void renderByItem(ItemStack stack, ItemDisplayContext ctx,
                                                 PoseStack pose, MultiBufferSource buffers,
                                                 int light, int overlay) {
                            StampedShareRenderer.render(stack, ctx, pose, buffers, light, overlay);
                        }
                    };
                }
                return renderer;
            }
        }, BankSystemItems.STAMPED_SHARE.get());
    }
}
