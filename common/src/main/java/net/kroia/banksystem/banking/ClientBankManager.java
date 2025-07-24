package net.kroia.banksystem.banking;

import com.mojang.datafixers.util.Pair;
import net.kroia.banksystem.networking.packet.client_sender.request.RequestAllowNewBankItemIDPacket;
import net.kroia.banksystem.networking.packet.client_sender.request.RequestBankDataPacket;
import net.kroia.banksystem.networking.packet.client_sender.request.RequestDisallowBankingItemIDPacket;
import net.kroia.banksystem.networking.packet.client_sender.request.RequestItemInfoPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDataPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncItemInfoPacket;
import net.kroia.banksystem.util.ItemID;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class ClientBankManager {
    private SyncBankDataPacket bankDataPacket;
    private SyncItemInfoPacket itemInfoPacket;
    //private SyncPotentialBankItemIDsPacket potentialBankItemIDsPacket;
    private boolean hasUpdatedBankData = false;
    private boolean hasUpdatedItemInfo = false;

    public void clear()
    {
        bankDataPacket = null;
        itemInfoPacket = null;
    }
    public void handlePacket(SyncBankDataPacket packet)
    {
        bankDataPacket = packet;
        hasUpdatedBankData = true;
    }
    /*public void handlePacket(SyncPotentialBankItemIDsPacket packet)
    {
        potentialBankItemIDsPacket = packet;
    }*/
    public void handlePacket(SyncItemInfoPacket packet)
    {
        itemInfoPacket = packet;
        hasUpdatedItemInfo = true;
    }

    public boolean hasUpdatedBankData()
    {
        boolean has = hasUpdatedBankData;
        hasUpdatedBankData = false;
        return has;
    }
    public boolean hasUpdatedItemInfo()
    {
        boolean has = hasUpdatedItemInfo;
        hasUpdatedItemInfo = false;
        return has;
    }

    public long getBalance()
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
    public ArrayList<ItemID> getAllowedItemIDs() {
        if(bankDataPacket == null)
        {
            msgBankDataNotReceived();
            return new ArrayList<>();
        }
        return bankDataPacket.getAllowedItemIDs();
    }
    public String getBankDataPlayerName()
    {
        if(bankDataPacket == null)
        {
            msgBankDataNotReceived();
            return "";
        }
        return bankDataPacket.getPlayerName();
    }
    /*public ArrayList<ItemID> getPotentialBankItemIDs() {
        if(potentialBankItemIDsPacket == null)
        {
            BankSystemMod.LOGGER.warn("Potential bank item IDs packet not received yet");
            RequestPotentialBankItemIDsPacket.sendRequest();
            return new ArrayList<>();
        }
        return potentialBankItemIDsPacket.getPotentialBankItemIDs();
    }*/
    /*public ArrayList<Pair<ItemID, SyncBankDataPacket.BankData>> getSortedItemData()
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
            if(itemID.equals(MoneyBank.ITEM_ID))
                continue; // Skip the money bank
            sortedBankAccounts.add(new Pair<>(itemID, bankAccounts.get(itemID)));
        }
        // Sort by balance
        sortedBankAccounts.sort((a, b) -> Long.compare(b.getSecond().getBalance(), a.getSecond().getBalance()));
        return sortedBankAccounts;
    }*/
    public ArrayList<Pair<ItemID, SyncBankDataPacket.BankData>> getSortedBankData()
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
    }

    public long getTotalSupply(ItemID itemID)
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
    }

    private void msgBankDataNotReceived()
    {
        RequestBankDataPacket.sendRequest();
    }
    private void msgItemInfoNotReceived(ItemID itemID)
    {
        RequestItemInfoPacket.sendRequest(itemID);
    }

    public void requestAllowNewItemID(ItemID itemID)
    {
        RequestAllowNewBankItemIDPacket.sendRequest(itemID);
    }
    public void requestRemoveItemID(ItemID itemID)
    {
        RequestDisallowBankingItemIDPacket.sendRequest(itemID);
    }
    public void requestItemInfo(ItemID itemID)
    {
        RequestItemInfoPacket.sendRequest(itemID);
    }
}
