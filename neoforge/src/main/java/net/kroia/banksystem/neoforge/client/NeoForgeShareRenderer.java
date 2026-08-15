package net.kroia.banksystem.neoforge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.kroia.banksystem.client.render.StampedShareBadgePainter;
import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/**
 * Task #53 (v2.1.0) — NeoForge-side wiring for the stamped-share monogram badge.
 * Registers an {@link IClientItemExtensions#getCustomRenderer()} hand-off to a
 * BEWLR that delegates to the cross-loader {@link StampedShareBadgePainter}.
 * Requires the stamped_share model JSON parent to be {@code builtin/entity}
 * for NeoForge to invoke the custom renderer.
 */
public final class NeoForgeShareRenderer extends BlockEntityWithoutLevelRenderer {

    private NeoForgeShareRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),
              Minecraft.getInstance().getEntityModels());
    }

    public static void register(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            private NeoForgeShareRenderer cached;
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (cached == null) cached = new NeoForgeShareRenderer();
                return cached;
            }
        }, BankSystemItems.STAMPED_SHARE.get());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose,
                             MultiBufferSource buffers, int packedLight, int packedOverlay) {
        StampedShareBadgePainter.INSTANCE.paint(stack, context, pose, buffers, packedLight, packedOverlay);
    }
}
