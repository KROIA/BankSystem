package net.kroia.banksystem.banking.clientdata;

import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Represents minimal bank data for a player.
 * This class is used to transfer bank data from the server to the client.
 */
public class BankData {

    public static final StreamCodec<RegistryFriendlyByteBuf, BankData> STREAM_CODEC = StreamCodec.composite(
            ItemID.STREAM_CODEC, p -> p.itemID,
            ByteBufCodecs.VAR_LONG, p -> p.balance,
            ByteBufCodecs.VAR_LONG, p -> p.lockedBalance,
            ByteBufCodecs.INT, p -> p.itemFractionScaleFactor,
            BankData::new
    );

    //public final UUID playerUUID;
    //public final String playerName;
    public final ItemID itemID;
    public final long balance;
    public final long lockedBalance;
    public final int itemFractionScaleFactor;

    public BankData(ItemID itemID, int itemFractionScaleFactor) {
        //this.playerUUID = UUID.randomUUID(); // UUID will be set later
        //this.playerName = ""; // Name will be set later
        this.itemID = itemID;
        this.balance = 0; // Default balance
        this.lockedBalance = 0; // Default locked balance
        this.itemFractionScaleFactor = itemFractionScaleFactor; // Scale factor for item fractions
    }
    /*public BankData(IBank bank) {
        this.playerUUID = bank.getPlayerUUID();
        this.playerName = bank.getPlayerName();
        this.itemID = bank.getItemID();
        this.balance = bank.getBalance();
        this.lockedBalance = bank.getLockedBalance();
        this.itemFractionScaleFactor = bank.getItemFractionScaleFactor();
    }*/
    public BankData(//UUID playerUUID,
                    //String playerName,
                    ItemID itemID,
                    long balance,
                    long lockedBalance,
                    int itemFractionScaleFactor) {
        //this.playerUUID = playerUUID;
        //this.playerName = playerName;
        this.itemID = itemID;
        this.balance = balance;
        this.lockedBalance = lockedBalance;
        this.itemFractionScaleFactor = itemFractionScaleFactor;
    }

    public float getRealBalance() {
        return balance / (float) itemFractionScaleFactor;
    }
    public float getRealLockedBalance() {
        return lockedBalance / (float) itemFractionScaleFactor;
    }
    public float getRealTotalBalance() {
        return (balance + lockedBalance) / (float) itemFractionScaleFactor;
    }

    public String getFormattedBalance(){
        return Bank.getFormattedAmount(balance, itemFractionScaleFactor);
    }
    public String getFormattedLockedBalance(){
        return Bank.getFormattedAmount(lockedBalance, itemFractionScaleFactor);
    }
    public String getFormattedTotalBalance(){
        return Bank.getFormattedAmount(balance + lockedBalance, itemFractionScaleFactor);
    }

    public String getNormalizedBalance() {
        return Bank.getNormalizedAmount(balance, itemFractionScaleFactor);
    }
    public String getNormalizedLockedBalance() {
        return Bank.getNormalizedAmount(lockedBalance, itemFractionScaleFactor);
    }
    public String getNormalizedTotalBalance() {
        return Bank.getNormalizedAmount(balance + lockedBalance, itemFractionScaleFactor);
    }

    /*@Override
    public void encode(net.minecraft.network.FriendlyByteBuf buf) {
        //buf.writeUUID(playerUUID);
        //buf.writeUtf(playerName);
        buf.writeItem(itemID.getStack());
        buf.writeLong(balance);
        buf.writeLong(lockedBalance);
        buf.writeInt(itemFractionScaleFactor);
    }
    public static BankData decode(net.minecraft.network.FriendlyByteBuf buf) {
        //UUID playerUUID = buf.readUUID();
        //String name = buf.readUtf();
        ItemID itemID = new ItemID(buf.readItem());
        long balance = buf.readLong();
        long lockedBalance = buf.readLong();
        int centScaleFactor = buf.readInt();
        return new BankData(itemID, balance, lockedBalance, centScaleFactor);
    }*/
}
