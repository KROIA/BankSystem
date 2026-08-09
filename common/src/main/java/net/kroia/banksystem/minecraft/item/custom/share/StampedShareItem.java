package net.kroia.banksystem.minecraft.item.custom.share;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.ShareVisuals;
import net.kroia.banksystem.client.cache.CompanyInfoCache;
import net.kroia.banksystem.client.cache.ShareVisualCache;
import net.kroia.banksystem.minecraft.component.BankSystemDataComponents;
import net.minecraft.ChatFormatting;
import net.kroia.banksystem.util.BankSystemClientHooks;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
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
        super(new Properties());
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

    /**
     * Task #51 UX fix — dynamic item name so the top tooltip line reads
     * "&lt;CompanyName&gt; Share" when the client visuals cache has the display name
     * for this stack's companyId. Falls back to the default translated name
     * ("Company Share") on cache miss / unstamped stacks.
     */
    @Override
    public @NotNull Component getName(@NotNull ItemStack stack) {
        Integer companyId = getCompanyId(stack);
        if (companyId == null) return super.getName(stack);
        if (!ShareVisualCache.has(companyId)) {
            ShareVisualCache.tryLookup(companyId);
            return super.getName(stack);
        }
        ShareVisuals visuals = ShareVisualCache.getVisualsOrPlaceholder(companyId);
        String display = visuals.getDisplayName();
        int tint = visuals.getTint();
        if (display == null || display.isBlank()) {
            // Task #51 fix — fall back to the canonical Company.name when the owner
            // hasn't set a display name yet. Consult CompanyInfoCache; on miss trigger
            // a by-id lookup and self-heal next frame (same pattern as ShareVisualCache).
            CompanyInfoCache.Snapshot snap = CompanyInfoCache.get(companyId);
            if (snap == null) {
                CompanyInfoCache.tryLookup(companyId);
                return super.getName(stack);
            }
            String cname = snap.name();
            if (cname == null || cname.isBlank()) return super.getName(stack);
            return Component.translatable("item." + BankSystemMod.MOD_ID + ".stamped_share.named", cname)
                    .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(tint & 0xFFFFFF)));
        }
        return Component.translatable("item." + BankSystemMod.MOD_ID + ".stamped_share.named", display)
                .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(tint & 0xFFFFFF)));
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

        // Trigger a cache lookup on miss; tooltip re-runs each frame and self-heals.
        boolean missing = !ShareVisualCache.has(companyId);
        if (missing) {
            tooltip.add(Component.literal("company#" + companyId).withStyle(ChatFormatting.DARK_GRAY));
            ShareVisualCache.tryLookup(companyId);
            super.appendHoverText(stack, context, tooltip, flag);
            return;
        }

        ShareVisuals visuals = ShareVisualCache.getVisualsOrPlaceholder(companyId);
        long issued = ShareVisualCache.getIssued(companyId);
        long max = ShareVisualCache.getMax(companyId);
        tooltip.add(Component.translatable(SUPPLY_KEY, formatLong(issued), formatLong(max))
                .withStyle(ChatFormatting.DARK_GRAY));

        // Shift-hold → expanded info (description + preset id, other-mod convention).
        if (BankSystemClientHooks.isShiftDown()) {
            String desc = visuals.getDescription();
            if (desc != null && !desc.isBlank()) {
                for (String line : desc.split("\\r?\\n")) {
                    tooltip.add(Component.literal(line).withStyle(ChatFormatting.GRAY));
                }
            }
            if (visuals.getIconPresetId() != null && !visuals.getIconPresetId().isBlank()) {
                tooltip.add(Component.literal("[" + visuals.getIconPresetId() + "]")
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        } else {
            String desc = visuals.getDescription();
            if (desc != null && !desc.isBlank()) {
                tooltip.add(Component.translatable(
                                "tooltip." + BankSystemMod.MOD_ID + ".stamped_share.hold_shift")
                        .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
            }
        }

        super.appendHoverText(stack, context, tooltip, flag);
    }

    /**
     * Task #51 (v2.0.8) — right-click a stamped share to open the Company Management
     * screen for that stack's company. Client-side only; server-side is a no-op pass
     * so no bogus interaction packet reaches the block/entity layer.
     */
    @Override
    public @NotNull InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        Integer companyId = getCompanyId(stack);
        if (companyId == null) return InteractionResultHolder.pass(stack);

        if (level.isClientSide) {
            // Send a C2S request so the server can resolve rights and push them back via
            // S2COpenCompanyManagementPacket. This avoids ARRS calls from the client that
            // may fail before the channel is fully established on dedicated servers.
            net.kroia.banksystem.networking.general.C2SRequestCompanyManagementScreen.send(companyId);
            return InteractionResultHolder.success(stack);
        }
        return InteractionResultHolder.pass(stack);
    }

    private static String formatLong(long value) {
        return String.format("%,d", value);
    }
}
