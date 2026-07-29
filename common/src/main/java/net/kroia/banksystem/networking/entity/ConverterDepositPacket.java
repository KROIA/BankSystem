package net.kroia.banksystem.networking.entity;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.converter.ConverterCacheManager;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Client-to-server packet for the ATM Money Converter tab (Task #39, v2.0.7):
 * deposits BankSystem money items from the player's inventory into the
 * per-player, per-server converter cache. Server iterates the sender's
 * inventory, removes up to {@code requested[ItemID]} of each money denomination,
 * and adds their combined worth (via {@link Math#addExact}) to the cache.
 *
 * <p><b>Trust gating.</b> Player-inventory-scoped write only (no bank surface).
 * No untrusted-slave gate required — the packet cannot affect any authoritative
 * bank state. {@code needsRoutingToMaster()} is overridden to {@code false} so
 * the packet always runs on the server the player is connected to (the cache
 * lives per-server).
 */
public class ConverterDepositPacket extends BankSystemNetworkPacket {

    public static final Type<ConverterDepositPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "converter_deposit_packet"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ConverterDepositPacket> STREAM_CODEC = StreamCodec.composite(
            ExtraCodecUtils.mapStreamCodec(ItemID.STREAM_CODEC, ByteBufCodecs.VAR_LONG, HashMap::new), p -> p.requested,
            ConverterDepositPacket::new
    );

    private final HashMap<ItemID, Long> requested;

    public ConverterDepositPacket(HashMap<ItemID, Long> requested) {
        super();
        this.requested = requested;
    }

    public static void sendPacket(HashMap<ItemID, Long> requested) {
        new ConverterDepositPacket(requested).sendToServer();
    }

    @Override
    protected boolean needsRoutingToMaster() { return false; }

    @Override
    protected void handleOnServer(ServerPlayer sender) {
        if (sender == null || requested == null || requested.isEmpty()) return;

        long depositTotal = 0L;
        Inventory inv = sender.getInventory();
        int size = inv.getContainerSize();

        for (Map.Entry<ItemID, Long> entry : requested.entrySet()) {
            ItemID itemID = entry.getKey();
            long wantCount = entry.getValue() == null ? 0L : entry.getValue();
            if (itemID == null || wantCount <= 0) continue;

            // Server-side validation: only money items are eligible.
            ItemStack template = itemID.getStack();
            if (!(template.getItem() instanceof MoneyItem moneyItem)) {
                warn("ConverterDepositPacket: refused non-money ItemID " + itemID);
                continue;
            }
            if (!MoneyItem.isMoney(template)) {
                warn("ConverterDepositPacket: refused ItemID (isMoney=false) " + itemID);
                continue;
            }
            long worth = moneyItem.worth();
            if (worth <= 0) continue;

            long removedCount = 0L;
            // Walk the inventory once per requested denomination and shrink matching stacks.
            for (int i = 0; i < size && removedCount < wantCount; i++) {
                ItemStack invStack = inv.getItem(i);
                if (invStack.isEmpty()) continue;
                if (!ItemStack.isSameItemSameComponents(template, invStack)) continue;
                int take = (int) Math.min(invStack.getCount(), wantCount - removedCount);
                if (take <= 0) continue;
                invStack.shrink(take);
                removedCount += take;
            }
            if (removedCount <= 0) continue;

            long value;
            try {
                value = Math.multiplyExact(removedCount, worth);
                depositTotal = Math.addExact(depositTotal, value);
            } catch (ArithmeticException overflow) {
                // A single denomination's value alone exceeds the running Long range.
                // Refuse this denomination line and try to unwind by putting the just-removed
                // items back into the inventory. Any that don't fit are dropped at the player.
                warn("ConverterDepositPacket: arithmetic overflow while summing "
                        + itemID + " × " + removedCount + " × " + worth + " — refunding the stack");
                ItemStack refund = template.copy();
                refund.setCount((int) Math.min(removedCount, refund.getMaxStackSize()));
                long left = removedCount - refund.getCount();
                if (!sender.getInventory().add(refund)) {
                    sender.drop(refund, false);
                }
                while (left > 0) {
                    ItemStack more = template.copy();
                    int c = (int) Math.min(left, more.getMaxStackSize());
                    more.setCount(c);
                    if (!sender.getInventory().add(more)) {
                        sender.drop(more, false);
                    }
                    left -= c;
                }
            }
        }
        sender.getInventory().setChanged();

        if (depositTotal > 0) {
            long resulting = ConverterCacheManager.get().deposit(sender.getUUID(), depositTotal);
            debug("ConverterDepositPacket: player " + sender.getUUID() + " deposited "
                    + depositTotal + " cents into converter cache; new balance = " + resulting);
        }
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
