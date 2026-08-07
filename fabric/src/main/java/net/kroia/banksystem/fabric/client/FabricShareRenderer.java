package net.kroia.banksystem.fabric.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.kroia.banksystem.client.render.StampedShareBadgePainter;
import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Task #53 (v2.0.8) — Fabric / Quilt-side wiring for the stamped-share monogram badge.
 *
 * <p>Registers a {@link BuiltinItemRendererRegistry.DynamicItemRenderer} that
 * delegates to the cross-loader {@link StampedShareBadgePainter}. The painter
 * only paints for GUI / FIXED / GROUND contexts; other contexts return
 * {@code false} and the vanilla tinted texture path continues to be used.
 *
 * <p>Note (v2.0.8): {@code BuiltinItemRendererRegistry} on Fabric only invokes
 * the registered renderer for items whose model JSON declares
 * {@code "parent": "builtin/entity"}. The stamped_share model is currently
 * {@code item/generated} so this hook is presently a no-op — the ARGB tint
 * handler still drives the inventory icon. Migrating the model JSON to
 * builtin/entity + providing proper item-display transforms is a follow-up
 * for v2.0.9 (see TODO in {@link StampedShareBadgePainter}). Shipping the
 * registration now so the follow-up is a single JSON edit + display-transforms
 * block, not a plumbing rewrite.
 */
public final class FabricShareRenderer implements BuiltinItemRendererRegistry.DynamicItemRenderer {

    public static final FabricShareRenderer INSTANCE = new FabricShareRenderer();

    private FabricShareRenderer() {}

    /** Client-init hook. Safe to call multiple times per JVM. */
    public static void register() {
        BuiltinItemRendererRegistry.INSTANCE.register(BankSystemItems.STAMPED_SHARE.get(), INSTANCE);
    }

    @Override
    public void render(ItemStack stack, ItemDisplayContext context, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        // Painter returns false for non-GUI/FIXED/GROUND, preset paths, and
        // cache misses — in every such case we currently fall back to leaving
        // the frame blank. TODO(v2.0.9): render the vanilla tinted quad here as
        // the fallback once the model JSON migrates to builtin/entity.
        StampedShareBadgePainter.INSTANCE.paint(stack, context, pose, buffers, packedLight, packedOverlay);
    }
}
