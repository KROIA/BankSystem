package net.kroia.banksystem.networking.packet.server_sender.update;

import net.kroia.banksystem.banking.BankUser;
import net.kroia.banksystem.banking.ClientBankManager;
import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.NetworkPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SyncItemInfoPacket extends NetworkPacket {

    ItemID itemID;
    long totalSupply;
    long totalLocked;

    public static class BankData
    {
        public UUID player;
        public long balance;
        public long lockedBalance;
        public BankData(FriendlyByteBuf buf)
        {
            this.fromBytes(buf);
        }
        public BankData(UUID player, long balance, long lockedBalance)
        {
            this.player = player;
            this.balance = balance;
            this.lockedBalance = lockedBalance;
        }
        public void toBytes(FriendlyByteBuf buf) {
            buf.writeUUID(player);
            buf.writeLong(balance);
            buf.writeLong(lockedBalance);
        }
        public void fromBytes(FriendlyByteBuf buf) {
            player = buf.readUUID();
            balance = buf.readLong();
            lockedBalance = buf.readLong();
        }
    }
    private Map<String, BankData> playerData;

    public SyncItemInfoPacket() {
        super();
        playerData = new HashMap<>();
    }

    public SyncItemInfoPacket(FriendlyByteBuf buf) {
        super(buf);
    }
    public ItemID getItemID()
    {
        return itemID;
    }
    public long getTotalSupply()
    {
        return totalSupply;
    }
    public long getTotalLocked()
    {
        return totalLocked;
    }
    public BankData getBankData(UUID player)
    {
        return playerData.get(player);
    }
    public Map<String, BankData> getBankData()
    {
        return playerData;
    }

    public static void sendResponse(ServerPlayer receiver, ItemID itemID)
    {
        SyncItemInfoPacket packet = new SyncItemInfoPacket();
        packet.itemID = itemID;
        Map<UUID, BankUser> users = ServerBankManager.getUser();
        for(UUID player : users.keySet())
        {
            BankUser user = users.get(player);
            if(user == null)
                continue;
            Bank bankAccount = user.getBank(itemID);
            if(bankAccount == null)
                continue;
            long balance = bankAccount.getBalance();
            long lockedBalance = bankAccount.getLockedBalance();
            packet.totalLocked += lockedBalance;
            packet.totalSupply += balance + lockedBalance;
            String playerName = user.getPlayerName();
            packet.playerData.put(playerName, new BankData(player, balance, lockedBalance));
        }
        BankSystemNetworking.sendToClient(receiver, packet);
    }

    @Override
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeItem(itemID.getStack());
        buf.writeLong(totalSupply);
        buf.writeLong(totalLocked);
        buf.writeInt(playerData.size());
        for(String player : playerData.keySet())
        {
            buf.writeUtf(player);
            playerData.get(player).toBytes(buf);
        }
    }

    @Override
    public void fromBytes(FriendlyByteBuf buf) {
        itemID = new ItemID(buf.readItem());
        totalSupply = buf.readLong();
        totalLocked = buf.readLong();
        int size = buf.readInt();
        if(playerData == null)
            playerData = new HashMap<>();
        for(int i = 0; i < size; i++)
        {
            String player = buf.readUtf();
            playerData.put(player, new BankData(buf));
        }
    }

    @Override
    protected void handleOnClient() {
        ClientBankManager.handlePacket(this);
    }
}
