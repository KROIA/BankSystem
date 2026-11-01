package net.kroia.banksystem.banking.clientdata;

import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.banking.bank.ServerBank;
import net.kroia.banksystem.banking.bankmanager.BankManager;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Represents minimal bank data for a player.
 * This class is used to transfer bank data from the server to the client.
 * <p>
 * {@code rawUnitsPerItem} carries the slot's raw-units-per-physical-item ratio
 * (Task #38b, v2.0.5). Unbound slots and legacy callers get
 * {@link BankSystemModSettings#ITEM_FRACTION_SCALE_FACTOR} (100). Bound slots
 * report their provider's {@code baseUnitsPerItem} — e.g. 81 for a
 * Lightman's Currency gold slot on default config — so client-side display
 * and server-side withdrawal item-count math stay in sync with the LC ledger
 * with zero drift.
 */
public record BankData(ItemID itemID, long balance, long lockedBalance, long rawUnitsPerItem) {

    public static final StreamCodec<RegistryFriendlyByteBuf, BankData> STREAM_CODEC = StreamCodec.composite(
            ItemID.STREAM_CODEC, p -> p.itemID,
            ByteBufCodecs.VAR_LONG, p -> p.balance,
            ByteBufCodecs.VAR_LONG, p -> p.lockedBalance,
            ByteBufCodecs.VAR_LONG, p -> p.rawUnitsPerItem,
            BankData::new
    );

    public BankData(ItemID itemID, long balance, long lockedBalance) {
        this(itemID, balance, lockedBalance, BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR);
    }

    public BankData(ItemID itemID) {
        this(itemID, 0, 0);
    }


    public String getFormattedBalance() {
        return ServerBank.getFormattedAmountStatic(balance);
    }

    public String getFormattedLockedBalance() {
        return ServerBank.getFormattedAmountStatic(lockedBalance);
    }

    public String getFormattedTotalBalance() {
        return ServerBank.getFormattedAmountStatic(balance + lockedBalance);
    }

    public String getNormalizedBalance() {
        return ServerBank.getNormalizedAmountStatic(balance);
    }

    public String getNormalizedLockedBalance() {
        return ServerBank.getNormalizedAmountStatic(lockedBalance);
    }

    public String getNormalizedTotalBalance() {
        return ServerBank.getNormalizedAmountStatic(balance + lockedBalance);
    }

    /**
     * Physical-item count of the current free balance, using the slot's ratio.
     * For a bound LC gold slot (ratio=81) with balance=81 raw, returns 1.0.
     * For an unbound slot (ratio=100) with balance=100 raw, returns 1.0.
     */
    public double getRealBalance() {
        long ratio = rawUnitsPerItem > 0 ? rawUnitsPerItem : BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
        return BankManager.convertToRealAmountStatic(balance, (int) ratio);
    }

}
