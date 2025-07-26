package net.kroia.banksystem.networking.packet.server_sender.update;

import net.kroia.banksystem.api.IBank;
import net.kroia.banksystem.api.IBankUser;
import net.kroia.banksystem.banking.bank.MoneyBank;
import net.kroia.banksystem.networking.BankSystemNetworkPacket;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

public class SyncBankDataPacket extends BankSystemNetworkPacket {

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
        public BankData(IBank bank)
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

    public SyncBankDataPacket(IBankUser user, ArrayList<ItemID> allowedItemIDs) {
        super();
        bankData = new HashMap<>();
        HashMap<ItemID, IBank> bankMap = user.getBankMap();
        for(IBank bank : bankMap.values())
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

    public static void sendPacket(ServerPlayer player, UUID sourcePlayerUUID)
    {
        IBankUser user = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getUser(sourcePlayerUUID);
        if(user == null)
            return;
        SyncBankDataPacket packet = new SyncBankDataPacket(user, BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAllowedItemIDs());
        packet.sendToClient(player);
    }
    public static void sendPacket(ServerPlayer player)
    {
       sendPacket(player, player.getUUID());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
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
    public void decode(FriendlyByteBuf buf) {
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
