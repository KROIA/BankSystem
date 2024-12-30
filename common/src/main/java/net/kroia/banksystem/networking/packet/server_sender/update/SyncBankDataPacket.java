package net.kroia.banksystem.networking.packet.server_sender.update;

import net.kroia.banksystem.banking.BankUser;
import net.kroia.banksystem.banking.ClientBankManager;
import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.banksystem.banking.bank.MoneyBank;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.modutilities.networking.NetworkPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;

public class SyncBankDataPacket extends NetworkPacket {

    public class BankData{
        private String itemID;
        private long balance;
        private long lockedBalance;

        public BankData(FriendlyByteBuf buf)
        {
            this.itemID = buf.readUtf();
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
            buf.writeUtf(itemID);
            buf.writeLong(balance);
            buf.writeLong(lockedBalance);
        }
        public String getItemID() {
            return itemID;
        }
        public long getBalance() {
            return balance;
        }
        public long getLockedBalance() {
            return lockedBalance;
        }
    }

    HashMap<String, BankData> bankData;
    ArrayList<String> allowedItemIDs;

    public SyncBankDataPacket(BankUser user, ArrayList<String> allowedItemIDs) {
        super();
        bankData = new HashMap<>();
        HashMap<String, Bank> bankMap = user.getBankMap();
        for(Bank bank : bankMap.values())
        {
            BankData data = new BankData(bank);
            bankData.put(data.itemID, data);
        }
        this.allowedItemIDs = allowedItemIDs;
    }
    public SyncBankDataPacket(FriendlyByteBuf buf) {
        super(buf);
    }

    public long getBalance(String itemID)
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
    public long getLockedBalance(String itemID)
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
    public boolean hasItemBank(String itemID)
    {
        return bankData.containsKey(itemID);
    }

    public HashMap<String, BankData> getBankData() {
        return bankData;
    }
    public ArrayList<String> getAllowedItemIDs() {
        return allowedItemIDs;
    }

    public static void sendPacket(ServerPlayer player)
    {
        BankUser user = ServerBankManager.getUser(player.getUUID());
        if(user == null)
            return;
        SyncBankDataPacket packet = new SyncBankDataPacket(user, ServerBankManager.getAllowedItemIDs());
        BankSystemNetworking.sendToClient(player, packet);
    }

    @Override
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeInt(bankData.size());
        bankData.forEach((itemID, data) -> {
            data.toBytes(buf);
        });

        buf.writeInt(allowedItemIDs.size());
        for(String itemID : allowedItemIDs)
        {
            buf.writeUtf(itemID);
        }
    }

    @Override
    public void fromBytes(FriendlyByteBuf buf) {
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
            allowedItemIDs.add(buf.readUtf());
        }
    }

    @Override
    protected void handleOnClient() {
        ClientBankManager.handlePacket(this);
    }
}
