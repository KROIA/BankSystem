package net.kroia.banksystem.networking.entity;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.converter.ConverterCacheManager;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.multi_server.DropItemsInPlayerInventoryRequest;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.UtilitiesPlatform;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client-to-server packet for the ATM Money Converter tab (Task #39, v2.0.7):
 * withdraws a requested banknote combination from the per-player converter
 * cache. Server validates that the sum of {@code requested[ItemID] × worth} is
 * &le; the cache balance, decrements the cache atomically, then dispenses the
 * items to the player's inventory (with drop-at-feet fallback for overflow).
 *
 * <p><b>No bank transaction.</b> This is the crucial "conversion is transaction-
 * free" branch — the packet must NOT touch any {@code ISyncServerBank} state.
 * The bank system is only involved in {@code ConverterCommitToBankPacket}.
 *
 * <p><b>Trust gating.</b> Player-inventory-scoped write only. No untrusted-slave
 * gate required. Runs locally on whichever server the player is connected to
 * (cache is per-server).
 */
public class ConverterWithdrawPacket extends BankSystemNetworkPacket {

    public static final Type<ConverterWithdrawPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "converter_withdraw_packet"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ConverterWithdrawPacket> STREAM_CODEC = StreamCodec.composite(
            ExtraCodecUtils.mapStreamCodec(ItemID.STREAM_CODEC, ByteBufCodecs.VAR_LONG, HashMap::new), p -> p.requested,
            ConverterWithdrawPacket::new
    );

    private final HashMap<ItemID, Long> requested;

    public ConverterWithdrawPacket(HashMap<ItemID, Long> requested) {
        super();
        this.requested = requested;
    }

    public static void sendPacket(HashMap<ItemID, Long> requested) {
        new ConverterWithdrawPacket(requested).sendToServer();
    }

    @Override
    protected boolean needsRoutingToMaster() { return false; }

    @Override
    protected void handleOnServer(ServerPlayer sender) {
        if (sender == null || requested == null || requested.isEmpty()) return;

        // Compute total value to withdraw with overflow-safe arithmetic; validate
        // every entry is a money item along the way.
        long sum = 0L;
        Map<ItemID, Long> validated = new HashMap<>();
        for (Map.Entry<ItemID, Long> entry : requested.entrySet()) {
            ItemID itemID = entry.getKey();
            long count = entry.getValue() == null ? 0L : entry.getValue();
            if (itemID == null || count <= 0) continue;

            ItemStack template = itemID.getStack();
            if (!(template.getItem() instanceof MoneyItem moneyItem)) {
                warn("ConverterWithdrawPacket: refused non-money ItemID " + itemID);
                continue;
            }
            long worth = moneyItem.worth();
            if (worth <= 0) continue;

            long value;
            try {
                value = Math.multiplyExact(count, worth);
                sum = Math.addExact(sum, value);
            } catch (ArithmeticException overflow) {
                warn("ConverterWithdrawPacket: arithmetic overflow computing "
                        + itemID + " × " + count + " × " + worth + " — refusing withdraw");
                return;
            }
            validated.put(itemID, count);
        }
        if (sum <= 0 || validated.isEmpty()) return;

        UUID playerUUID = sender.getUUID();
        // Atomic reserve: withdraw-or-nothing against the cache.
        if (!ConverterCacheManager.get().withdraw(playerUUID, sum)) {
            debug("ConverterWithdrawPacket: refused for player " + playerUUID
                    + " — requested " + sum + " > cache");
            return;
        }

        // Dispense items. Any items that don't fit are re-credited to the cache so
        // the player never loses value. dropItems returns the map of NOT-dispensed
        // amounts.
        MinecraftServer server = UtilitiesPlatform.getServer();
        Map<ItemID, Long> notDispensed = DropItemsInPlayerInventoryRequest.dropItems(server, playerUUID, validated, true);
        if (!notDispensed.isEmpty()) {
            long refund = 0L;
            for (Map.Entry<ItemID, Long> e : notDispensed.entrySet()) {
                ItemStack s = e.getKey().getStack();
                if (s.getItem() instanceof MoneyItem money) {
                    long worth = money.worth();
                    long cnt = e.getValue() == null ? 0L : e.getValue();
                    if (worth <= 0 || cnt <= 0) continue;
                    try {
                        refund = Math.addExact(refund, Math.multiplyExact(cnt, worth));
                    } catch (ArithmeticException overflow) {
                        error("ConverterWithdrawPacket: overflow computing refund for " + e.getKey());
                    }
                }
            }
            if (refund > 0) {
                ConverterCacheManager.get().deposit(playerUUID, refund);
                warn("ConverterWithdrawPacket: " + refund + " cents could not be dispensed to "
                        + playerUUID + " and were returned to the cache");
            }
        }
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
