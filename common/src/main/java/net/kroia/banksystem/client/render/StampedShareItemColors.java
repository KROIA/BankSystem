package net.kroia.banksystem.client.render;

import net.kroia.banksystem.banking.company.ShareVisuals;
import net.kroia.banksystem.client.cache.ShareVisualCache;
import net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem;
import net.minecraft.client.color.item.ItemColor;
import net.minecraft.world.item.ItemStack;

/**
 * Task #50 (v2.0.8) — Tint-only {@link ItemColor} for the stamped share item.
 *
 * <p>Reads the company's ARGB tint from {@link ShareVisualCache} and returns it for
 * {@code tintIndex == 0} (layer0 of the vanilla {@code item/generated} model). Unstamped
 * stacks and cache misses render at full white ({@code 0xFFFFFFFF}). Cache miss also
 * schedules a self-heal via {@link ShareVisualCache#tryLookup(int)} so the icon repaints
 * on the next frame once the by-id ARRS response lands.
 *
 * <p>TODO(v2.0.9): full preset-swap renderer. This tints the base
 * {@code banksystem:item/stamped_share} texture only; it does NOT swap the layer0
 * texture to the company's chosen preset icon. Two deferred paths exist:
 * <ul>
 *   <li>Data-only: 30 model JSONs + {@code overrides} keyed on an
 *       {@code ItemPropertyFunction} that surfaces the preset index. Cross-loader.</li>
 *   <li>Full BEWLR: per-loader {@code BlockEntityWithoutLevelRenderer}
 *       (Fabric: {@code BuiltinItemRendererRegistry}; NeoForge: {@code IClientItemExtensions}
 *       + {@code "parent": "builtin/entity"} in the model JSON). More flexible, higher cost.</li>
 * </ul>
 */
public final class StampedShareItemColors implements ItemColor {

    @Override
    public int getColor(ItemStack stack, int tintIndex) {
        if (tintIndex != 0) return 0xFFFFFFFF;
        Integer companyId = StampedShareItem.getCompanyId(stack);
        if (companyId == null) return 0xFFFFFFFF;
        if (!ShareVisualCache.has(companyId)) {
            ShareVisualCache.tryLookup(companyId);
            return 0xFFFFFFFF;
        }
        ShareVisuals v = ShareVisualCache.getVisualsOrPlaceholder(companyId);
        int tint = v.getBgLayer().tint();
        // Ensure the alpha channel is fully opaque — a stored tint of 0 (fully transparent)
        // would make the item invisible; treat "no tint chosen" as white.
        if ((tint & 0xFF000000) == 0) tint |= 0xFF000000;
        return tint;
    }
}
