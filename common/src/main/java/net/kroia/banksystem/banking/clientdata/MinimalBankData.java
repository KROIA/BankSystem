package net.kroia.banksystem.banking.clientdata;

import net.kroia.banksystem.api.IBank;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.INetworkPayloadEncoder;

import java.util.UUID;

/**
 * Represents minimal bank data for a player.
 * This class is used to transfer bank data from the server to the client.
 */
public class MinimalBankData implements INetworkPayloadEncoder {

    public final UUID playerUUID;
    public final String playerName;
    public final ItemID itemID;
    public final long balance;
    public final long lockedBalance;
    public final int centScaleFactor;

    public MinimalBankData(IBank bank) {
        this.playerUUID = bank.getPlayerUUID();
        this.playerName = bank.getPlayerName();
        this.itemID = bank.getItemID();
        this.balance = bank.getBalance();
        this.lockedBalance = bank.getLockedBalance();
        this.centScaleFactor = bank.getItemFractionScaleFactor();
    }
    public MinimalBankData(UUID playerUUID,
                           String playerName,
                           ItemID itemID,
                           long balance,
                           long lockedBalance,
                           int centScaleFactor) {
        this.playerUUID = playerUUID;
        this.playerName = playerName;
        this.itemID = itemID;
        this.balance = balance;
        this.lockedBalance = lockedBalance;
        this.centScaleFactor = centScaleFactor;
    }

    @Override
    public void encode(net.minecraft.network.FriendlyByteBuf buf) {
        buf.writeUUID(playerUUID);
        buf.writeUtf(playerName);
        buf.writeItem(itemID.getStack());
        buf.writeLong(balance);
        buf.writeLong(lockedBalance);
        buf.writeInt(centScaleFactor);
    }
    public static MinimalBankData decode(net.minecraft.network.FriendlyByteBuf buf) {
        UUID playerUUID = buf.readUUID();
        String name = buf.readUtf();
        ItemID itemID = new ItemID(buf.readItem());
        long balance = buf.readLong();
        long lockedBalance = buf.readLong();
        int centScaleFactor = buf.readInt();
        return new MinimalBankData(playerUUID, name, itemID, balance, lockedBalance, centScaleFactor);
    }
}
