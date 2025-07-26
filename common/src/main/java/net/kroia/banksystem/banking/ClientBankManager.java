package net.kroia.banksystem.banking;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.banking.clientdata.ItemInfoData;
import net.kroia.banksystem.banking.clientdata.MinimalBankData;
import net.kroia.banksystem.banking.clientdata.MinimalBankManagerData;
import net.kroia.banksystem.banking.clientdata.MinimalBankUserData;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.request.MinimalBankDataRequest;
import net.kroia.banksystem.util.ItemID;

import java.util.UUID;
import java.util.function.Consumer;

public class ClientBankManager {

    protected BankSystemModBackend.Instances BACKEND_INSTANCES;
    //private SyncBankDataPacket bankDataPacket;
    //private SyncItemInfoPacket itemInfoPacket;
    //private boolean hasUpdatedBankData = false;
    //private boolean hasUpdatedItemInfo = false;

    public ClientBankManager(BankSystemModBackend.Instances backendInstances)
    {
        this.BACKEND_INSTANCES = backendInstances;
    }

    /*public void clear()
    {
        bankDataPacket = null;
        //itemInfoPacket = null;
    }
    public void handlePacket(SyncBankDataPacket packet)
    {
        bankDataPacket = packet;
        hasUpdatedBankData = true;
    }*/

    //public void handlePacket(SyncItemInfoPacket packet)
    //{
        //itemInfoPacket = packet;
        //hasUpdatedItemInfo = true;
    //}

    /*public boolean hasUpdatedBankData()
    {
        boolean has = hasUpdatedBankData;
        hasUpdatedBankData = false;
        return has;
    }*/
    /*public boolean hasUpdatedItemInfo()
    {
        boolean has = hasUpdatedItemInfo;
        hasUpdatedItemInfo = false;
        return has;
    }*/

    /*public long getBalance()
    {
        if(bankDataPacket == null)
        {
            msgBankDataNotReceived();
            return 0;
        }
        return bankDataPacket.getBalance();
    }
    public long getBalance(ItemID itemID)
    {
        if(bankDataPacket == null)
        {
            msgBankDataNotReceived();
            return 0;
        }
        return bankDataPacket.getBalance(itemID);
    }

    public long getLockedBalance()
    {
        if(bankDataPacket == null)
        {
            msgBankDataNotReceived();
            return 0;
        }
        return bankDataPacket.getLockedBalance();
    }
    public long getLockedBalance(ItemID itemID)
    {
        if(bankDataPacket == null)
        {
            msgBankDataNotReceived();
            return 0;
        }
        return bankDataPacket.getLockedBalance(itemID);
    }
    public boolean hasItemBank(ItemID itemID)
    {
        if(bankDataPacket == null)
        {
            msgBankDataNotReceived();
            return false;
        }
        return bankDataPacket.hasItemBank(itemID);
    }

    public Map<ItemID, SyncBankDataPacket.BankData> getBankData() {
        if(bankDataPacket == null)
        {
            msgBankDataNotReceived();
            return new HashMap<>();
        }
        return bankDataPacket.getBankData();
    }
    public String getBankDataPlayerName()
    {
        if(bankDataPacket == null)
        {
            msgBankDataNotReceived();
            return "";
        }
        return bankDataPacket.getPlayerName();
    }*/

    /*public ArrayList<Pair<ItemID, SyncBankDataPacket.BankData>> getSortedBankData()
    {
        if(bankDataPacket == null)
        {
            msgBankDataNotReceived();
            return new ArrayList<>();
        }
        HashMap<ItemID, SyncBankDataPacket.BankData> bankAccounts = bankDataPacket.getBankData();
        // Sort the bank accounts by itemID
        ArrayList<Pair<ItemID,SyncBankDataPacket.BankData>> sortedBankAccounts = new ArrayList<>();
        for (ItemID itemID : bankAccounts.keySet()) {
            sortedBankAccounts.add(new Pair<>(itemID, bankAccounts.get(itemID)));
        }
        // Sort by balance
        sortedBankAccounts.sort((a, b) -> Long.compare(b.getSecond().getBalance(), a.getSecond().getBalance()));
        return sortedBankAccounts;
    }*/

    /*public long getTotalSupply(ItemID itemID)
    {
        if(itemInfoPacket == null || !itemInfoPacket.getItemID().equals(itemID))
        {
            msgItemInfoNotReceived(itemID);
            return 0;
        }
        return itemInfoPacket.getTotalSupply();
    }
    public long getTotalLocked(ItemID itemID)
    {
        if(itemInfoPacket == null || !itemInfoPacket.getItemID().equals(itemID))
        {
            msgItemInfoNotReceived(itemID);
            return 0;
        }
        return itemInfoPacket.getTotalLocked();
    }
    public Map<String, SyncItemInfoPacket.BankData> getItemInfoBankData(ItemID itemID)
    {
        if(itemInfoPacket == null || !itemInfoPacket.getItemID().equals(itemID))
        {
            msgItemInfoNotReceived(itemID);
            return null;
        }
        return itemInfoPacket.getBankData();
    }*/

    /*private void msgBankDataNotReceived()
    {
        RequestBankDataPacket.sendRequest();
    }*/
    /*private void msgItemInfoNotReceived(ItemID itemID)
    {
        RequestItemInfoPacket.sendRequest(itemID);
    }*/

    /*public void requestAllowNewItemID(ItemID itemID)
    {
        RequestAllowNewBankItemIDPacket.sendRequest(itemID);
    }*/
    public void requestDisallowItem(ItemID itemID, Consumer<Boolean> consumer)
    {
        BankSystemNetworking.DISALLOW_ITEM_REQUEST.sendRequestToServer(itemID, consumer);
    }



    public void requestMinimalBankData(UUID playerUUID, ItemID itemID, Consumer<MinimalBankData> consumer)
    {
        MinimalBankDataRequest.InputType input = new MinimalBankDataRequest.InputType(playerUUID, itemID);
        BankSystemNetworking.MINIMAL_BANK_DATA_REQUEST.sendRequestToServer(input, consumer);
    }
    public void requestMinimalBankUserData(UUID playerUUID, Consumer<MinimalBankUserData> consumer)
    {
        BankSystemNetworking.MINIMAL_BANK_USER_DATA_REQUEST.sendRequestToServer(playerUUID, consumer);
    }
    public void requestAllowItem(ItemID itemID, Consumer<Boolean> consumer)
    {
        BankSystemNetworking.ALLOW_ITEM_REQUEST.sendRequestToServer(itemID, consumer);
    }
    public void requestMinimalBankManagerData(Consumer<MinimalBankManagerData> consumer)
    {
        BankSystemNetworking.MINIMAL_BANK_MANAGER_DATA_REQUEST.sendRequestToServer(0, consumer);
    }

    public void requestItemInfoData(ItemID itemID, Consumer<ItemInfoData> consumer)
    {
        BankSystemNetworking.ITEM_INFO_REQUEST.sendRequestToServer(itemID, consumer);
    }
}
