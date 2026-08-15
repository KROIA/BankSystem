package net.kroia.banksystem.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Task #53 (v2.1.0) — cross-loader contract for the stamped-share item renderer.
 *
 * <p>Implementations plug into the per-loader render pipeline (Fabric / Quilt:
 * {@code BuiltinItemRendererRegistry}; NeoForge: {@code IClientItemExtensions} +
 * {@code BlockEntityWithoutLevelRenderer}) and delegate the actual pixel work to
 * {@link StampedShareBadgePainter}, which is shared across all loaders.
 *
 * <p>Only GUI / FIXED / GROUND contexts are painted as a monogram badge (when no
 * preset icon is configured) or as a preset-tinted texture. Held-in-hand contexts
 * fall through to the vanilla item-model path with a TODO — a proper
 * builtin/entity model + a full BEWLR body-and-hand render is deferred to v2.1.0.
 */
public interface IShareItemBadgePainter {

    /**
     * Paint (or defer to vanilla) the stamped share for the given display context.
     *
     * @param stack           the stamped share ItemStack (never null / empty)
     * @param context         Minecraft's item-display context enum
     * @param pose            pose stack scoped to the item (16x16 unit block model space)
     * @param buffers         buffer source to emit vertices into
     * @param packedLight     lightmap coords from the host renderer
     * @param packedOverlay   overlay coords from the host renderer
     * @return {@code true} if this renderer painted the item — the loader-side
     *         adapter must then suppress the vanilla model path. {@code false}
     *         means "not my problem"; the loader falls back to vanilla.
     */
    boolean paint(ItemStack stack,
                  ItemDisplayContext context,
                  PoseStack pose,
                  MultiBufferSource buffers,
                  int packedLight,
                  int packedOverlay);
}
