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
public class BankData {

    public static final StreamCodec<RegistryFriendlyByteBuf, BankData> STREAM_CODEC = StreamCodec.composite(
            ItemID.STREAM_CODEC, p -> p.itemID,
            ByteBufCodecs.VAR_LONG, p -> p.balance,
            ByteBufCodecs.VAR_LONG, p -> p.lockedBalance,
            BankData::new
    );

    //public final UUID playerUUID;
    //public final String playerName;
    public final ItemID itemID;
    public final long balance;
    public final long lockedBalance;

    public BankData(ItemID itemID) {
        //this.playerUUID = UUID.randomUUID(); // UUID will be set later
        //this.playerName = ""; // Name will be set later
        this.itemID = itemID;
        this.balance = 0; // Default balance
        this.lockedBalance = 0; // Default locked balance
    }
    /*public BankData(ISyncServerBank bank) {
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
                    long lockedBalance) {
        //this.playerUUID = playerUUID;
        //this.playerName = playerName;
        this.itemID = itemID;
        this.balance = balance;
        this.lockedBalance = lockedBalance;
    }

    //public float getRealBalance() {
    //    return ServerBank.convertToRealAmountStatic(balance);
    //}
    //public float getRealLockedBalance() {
    //    return ServerBank.convertToRealAmountStatic(lockedBalance);
    //}
    //public float getRealTotalBalance() {
    //    return ServerBank.convertToRealAmountStatic(balance + lockedBalance);
    //}

    public String getFormattedBalance(){
        return ServerBank.getFormattedAmountStatic(balance);
    }
    public String getFormattedLockedBalance(){
        return ServerBank.getFormattedAmountStatic(lockedBalance);
    }
    public String getFormattedTotalBalance(){
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

    public long getBalance() {
        return balance;
    }
    public double getRealBalance()
    {
        return ServerBank.convertToRealAmountStatic(balance);
    }
    public long getLockedBalance() {
        return lockedBalance;
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
