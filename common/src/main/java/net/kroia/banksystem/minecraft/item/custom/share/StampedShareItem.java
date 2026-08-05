package net.kroia.banksystem.minecraft.item.custom.share;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.ShareVisuals;
import net.kroia.banksystem.client.cache.ShareVisualCache;
import net.kroia.banksystem.minecraft.component.BankSystemDataComponents;
import net.kroia.banksystem.minecraft.item.BankSystemCreativeModeTab;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Task #46 (v2.0.8) — physical company-stamped share. Carries the identity-relevant
 * {@code banksystem:company_id} int data component (see {@link BankSystemDataComponents}).
 * Two stamped stacks that differ only in {@code company_id} produce distinct
 * {@link net.kroia.banksystem.util.ItemID ItemID}s because that component is NOT
 * marked volatile.
 *
 * <p>Right-click is inert; the item exists purely as a fungible tradable stack.
 * Tooltip resolves company visuals via {@link ShareVisualCache}; a cache miss shows
 * a grey placeholder and self-heals on the next frame when the server broadcast arrives
 * (same pattern as {@link net.kroia.banksystem.util.ItemID#getName()}).
 */
public class StampedShareItem extends Item {

    public static final String NAME = "stamped_share";

    private static final Component UNKNOWN_NAME =
            Component.translatable("item." + BankSystemMod.MOD_ID + ".stamped_share.unknown");
    private static final String SUPPLY_KEY =
            "tooltip." + BankSystemMod.MOD_ID + ".stamped_share.supply";

    public StampedShareItem() {
        super(new Properties().arch$tab(BankSystemCreativeModeTab.BANK_SYSTEM_TAB));
    }

    /** Convenience accessor: reads the {@code company_id} component, or {@code null} if unstamped. */
    @Nullable
    public static Integer getCompanyId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return stack.get(BankSystemDataComponents.COMPANY_ID.get());
    }

    /**
     * Task #48 (v2.0.8) — resolve the company id from an {@link net.kroia.banksystem.util.ItemID}
     * if (and only if) it points at a stamped-share template. Returns {@code null} for any
     * other item, an unstamped share, or an unresolvable ItemID. Used by the ledger write
     * hooks in {@code DepositItemsInBankRequest} / {@code WithdrawItemsFromBankRequest} to
     * decide whether a movement should be logged as a {@code SHARE_TRADE} instead of a
     * plain deposit/withdraw.
     */
    @Nullable
    public static Integer getCompanyIdForItemID(net.kroia.banksystem.util.ItemID itemID) {
        if (itemID == null || !itemID.isValid()) return null;
        ItemStack template = net.kroia.banksystem.util.ItemIDManager.getItemStack(itemID);
        if (template.isEmpty()) return null;
        if (template.getItem() != net.kroia.banksystem.minecraft.item.BankSystemItems.STAMPED_SHARE.get()) return null;
        return getCompanyId(template);
    }

    /** Stamps a fresh single-item stack with the given {@code companyId}. */
    public static ItemStack ofCompany(Item stampedShareItem, int companyId) {
        ItemStack stack = new ItemStack(stampedShareItem);
        stack.set(BankSystemDataComponents.COMPANY_ID.get(), companyId);
        return stack;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        Integer companyId = getCompanyId(stack);
        if (companyId == null) {
            tooltip.add(Component.translatable("item." + BankSystemMod.MOD_ID + ".stamped_share.no_company")
                    .withStyle(ChatFormatting.DARK_GRAY));
            super.appendHoverText(stack, context, tooltip, flag);
            return;
        }

        // Trigger a cache lookup on miss (fire-and-forget; the S2C visual/supply packets
        // update the cache once the server responds; the tooltip re-runs every frame and
        // self-heals — mirroring ItemID.getName()'s placeholder-vs-cache logic).
        boolean missing = !ShareVisualCache.has(companyId);
        ShareVisuals visuals = ShareVisualCache.getVisualsOrPlaceholder(companyId);

        if (missing) {
            tooltip.add(UNKNOWN_NAME.copy().withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.literal("company#" + companyId).withStyle(ChatFormatting.DARK_GRAY));
            ShareVisualCache.tryLookup(companyId);
        } else {
            String displayName = visuals.getDisplayName();
            if (displayName == null || displayName.isBlank()) displayName = "company#" + companyId;
            int tint = visuals.getTint();
            MutableComponent name = Component.literal(displayName);
            // The tint is stored as 0xAARRGGBB (see ShareVisuals.EMPTY = 0xFFFFFFFF).
            // Chat colors ignore alpha; take the low 24 bits.
            name = name.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(tint & 0xFFFFFF)));
            tooltip.add(name);

            String desc = visuals.getDescription();
            if (desc != null && !desc.isBlank()) {
                for (String line : desc.split("\\r?\\n")) {
                    tooltip.add(Component.literal(line).withStyle(ChatFormatting.GRAY));
                }
            }

            long issued = ShareVisualCache.getIssued(companyId);
            long max = ShareVisualCache.getMax(companyId);
            tooltip.add(Component.translatable(SUPPLY_KEY, formatLong(issued), formatLong(max))
                    .withStyle(ChatFormatting.DARK_GRAY));

            if (visuals.getIconPresetId() != null && !visuals.getIconPresetId().isBlank()) {
                tooltip.add(Component.literal("[" + visuals.getIconPresetId() + "]")
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        }

        super.appendHoverText(stack, context, tooltip, flag);
    }

    private static String formatLong(long value) {
        return String.format("%,d", value);
    }
}
