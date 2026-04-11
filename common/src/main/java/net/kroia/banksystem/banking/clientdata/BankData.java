package net.kroia.banksystem.banking.clientdata;

import net.kroia.banksystem.banking.bank.ServerBank;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Represents minimal bank data for a player.
 * This class is used to transfer bank data from the server to the client.
 */
public record BankData(ItemID itemID, long balance, long lockedBalance) {

    public static final StreamCodec<RegistryFriendlyByteBuf, BankData> STREAM_CODEC = StreamCodec.composite(
            ItemID.STREAM_CODEC, p -> p.itemID,
            ByteBufCodecs.VAR_LONG, p -> p.balance,
            ByteBufCodecs.VAR_LONG, p -> p.lockedBalance,
            BankData::new
    );

    public BankData(ItemID itemID) {
        // Default balance
        this(itemID, 0, 0); // Default locked balance
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

    public double getRealBalance() {
        return ServerBank.convertToRealAmountStatic(balance);
    }

}
