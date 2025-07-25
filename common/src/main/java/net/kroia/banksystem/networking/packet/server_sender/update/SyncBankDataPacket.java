package net.kroia.banksystem.networking.packet.server_sender.update;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.BankUserAPI;
import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.banksystem.banking.bank.MoneyBank;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.NetworkPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

public class SyncBankDataPacket extends NetworkPacket {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;


    public class BankData{
        private ItemID itemID;
        private long balance;
        private long lockedBalance;

        public BankData(FriendlyByteBuf buf)
        {
            this.itemID = new ItemID(buf.readItem());
            this.balance = buf.readLong();
            this.lockedBalance = buf.readLong();
        }
        public BankData(Bank bank)
        {
            this.itemID = bank.getItemID();
            this.balance = bank.getBalance();
            this.lockedBalance = bank.getLockedBalance();
        }
        public void toBytes(FriendlyByteBuf buf)
        {
            buf.writeItem(itemID.getStack());
            buf.writeLong(balance);
            buf.writeLong(lockedBalance);
        }
        public ItemID getItemID() {
            return itemID;
        }
        public long getBalance() {
            return balance;
        }
        public long getLockedBalance() {
            return lockedBalance;
        }
    }

    HashMap<ItemID, BankData> bankData;
    ArrayList<ItemID> allowedItemIDs;
    String playerName;

    public static void setBackend(BankSystemModBackend.Instances backend) {
        SyncBankDataPacket.BACKEND_INSTANCES = backend;
    }

    public SyncBankDataPacket(BankUserAPI user, ArrayList<ItemID> allowedItemIDs) {
        super();
        bankData = new HashMap<>();
        HashMap<ItemID, Bank> bankMap = user.getBankMap();
        for(Bank bank : bankMap.values())
        {
            BankData data = new BankData(bank);
            bankData.put(data.itemID, data);
        }
        this.allowedItemIDs = allowedItemIDs;
        playerName = user.getPlayerName();
    }
    public SyncBankDataPacket(FriendlyByteBuf buf) {
        super(buf);
    }

    public long getBalance(ItemID itemID)
    {
        BankData data = bankData.get(itemID);
        if(data == null)
            return 0;
        return data.getBalance();
    }
    public long getBalance()
    {
        return getBalance(MoneyBank.ITEM_ID);
    }
    public long getLockedBalance(ItemID itemID)
    {
        BankData data = bankData.get(itemID);
        if(data == null)
            return 0;
        return data.getLockedBalance();
    }
    public long getLockedBalance()
    {
        return getLockedBalance(MoneyBank.ITEM_ID);
    }
    public boolean hasItemBank(ItemID itemID)
    {
        return bankData.containsKey(itemID);
    }

    public HashMap<ItemID, BankData> getBankData() {
        return bankData;
    }
    public ArrayList<ItemID> getAllowedItemIDs() {
        return allowedItemIDs;
    }
    public String getPlayerName() {
        return playerName;
    }

    public static void sendPacket(ServerPlayer player, UUID courcePlayerUUID)
    {
        BankUserAPI user = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getUser(courcePlayerUUID);
        if(user == null)
            return;
        SyncBankDataPacket packet = new SyncBankDataPacket(user, BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAllowedItemIDs());
        BankSystemNetworking.sendToClient(player, packet);
    }
    public static void sendPacket(ServerPlayer player)
    {
       sendPacket(player, player.getUUID());
    }

    @Override
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeUtf(playerName);
        buf.writeInt(bankData.size());
        bankData.forEach((itemID, data) -> {
            data.toBytes(buf);
        });

        buf.writeInt(allowedItemIDs.size());
        for(ItemID itemID : allowedItemIDs)
        {
            buf.writeItem(itemID.getStack());
        }
    }

    @Override
    public void fromBytes(FriendlyByteBuf buf) {
        playerName = buf.readUtf();
        int size = buf.readInt();
        bankData = new HashMap<>();
        for(int i = 0; i < size; i++)
        {
            BankData data = new BankData(buf);
            bankData.put(data.getItemID(), data);
        }

        size = buf.readInt();
        allowedItemIDs = new ArrayList<>();
        for(int i = 0; i < size; i++)
        {
            allowedItemIDs.add(new ItemID(buf.readItem()));
        }
    }

    @Override
    protected void handleOnClient() {
        BACKEND_INSTANCES.CLIENT_BANK_MANAGER.handlePacket(this);
    }
}
