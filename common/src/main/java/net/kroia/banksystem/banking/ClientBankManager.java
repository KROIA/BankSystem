package net.kroia.banksystem.banking;

import com.mojang.datafixers.util.Pair;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.bank.MoneyBank;
import net.kroia.banksystem.networking.packet.client_sender.request.*;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDataPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncItemInfoPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncPotentialBankItemIDsPacket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class ClientBankManager {
    private static SyncBankDataPacket bankDataPacket;
    private static SyncItemInfoPacket itemInfoPacket;
    private static SyncPotentialBankItemIDsPacket potentialBankItemIDsPacket;
    private static boolean hasUpdatedBankData = false;
    private static boolean hasUpdatedItemInfo = false;

    public static void clear()
    {
        bankDataPacket = null;
        itemInfoPacket = null;
    }
    public static void handlePacket(SyncBankDataPacket packet)
    {
        bankDataPacket = packet;
        hasUpdatedBankData = true;
    }
    public static void handlePacket(SyncPotentialBankItemIDsPacket packet)
    {
        potentialBankItemIDsPacket = packet;
    }
    public static void handlePacket(SyncItemInfoPacket packet)
    {
        itemInfoPacket = packet;
        hasUpdatedItemInfo = true;
    }

    public static boolean hasUpdatedBankData()
    {
        boolean has = hasUpdatedBankData;
        hasUpdatedBankData = false;
        return has;
    }
    public static boolean hasUpdatedItemInfo()
    {
        boolean has = hasUpdatedItemInfo;
        hasUpdatedItemInfo = false;
        return has;
    }

    public static long getBalance()
    {
        if(bankDataPacket == null)
        {
            msgBankDataNotReceived();
            return 0;
        }
        return bankDataPacket.getBalance();
    }
    public static long getBalance(String itemID)
    {
        if(bankDataPacket == null)
        {
            msgBankDataNotReceived();
            return 0;
        }
        return bankDataPacket.getBalance(itemID);
    }

    public static long getLockedBalance()
    {
        if(bankDataPacket == null)
        {
            msgBankDataNotReceived();
            return 0;
        }
        return bankDataPacket.getLockedBalance();
    }
    public static long getLockedBalance(String itemID)
    {
        if(bankDataPacket == null)
        {
            msgBankDataNotReceived();
            return 0;
        }
        return bankDataPacket.getLockedBalance(itemID);
    }
    public static boolean hasItemBank(String itemID)
    {
        if(bankDataPacket == null)
        {
            msgBankDataNotReceived();
            return false;
        }
        return bankDataPacket.hasItemBank(itemID);
    }

    public static Map<String, SyncBankDataPacket.BankData> getBankData() {
        if(bankDataPacket == null)
        {
            msgBankDataNotReceived();
            return new HashMap<>();
        }
        return bankDataPacket.getBankData();
    }
    public static ArrayList<String> getAllowedItemIDs() {
        if(bankDataPacket == null)
        {
            msgBankDataNotReceived();
            return new ArrayList<>();
        }
        return bankDataPacket.getAllowedItemIDs();
    }
    public static String getBankDataPlayerName()
    {
        if(bankDataPacket == null)
        {
            msgBankDataNotReceived();
            return "";
        }
        return bankDataPacket.getPlayerName();
    }
    public static ArrayList<String> getPotentialBankItemIDs() {
        if(potentialBankItemIDsPacket == null || potentialBankItemIDsPacket.getPotentialBankItemIDs() == null)
        {
            BankSystemMod.LOGGER.warn("Potential bank item IDs packet not received yet");
            RequestPotentialBankItemIDsPacket.sendRequest();
            return new ArrayList<>();
        }
        return potentialBankItemIDsPacket.getPotentialBankItemIDs();
    }
    public static ArrayList<Pair<String, SyncBankDataPacket.BankData>> getSortedItemData()
    {
        HashMap<String, SyncBankDataPacket.BankData> bankAccounts = bankDataPacket.getBankData();
        // Sort the bank accounts by itemID
        ArrayList<Pair<String,SyncBankDataPacket.BankData>> sortedBankAccounts = new ArrayList<>();
        for (String itemID : bankAccounts.keySet()) {
            if(itemID.equals(MoneyBank.ITEM_ID))
                continue; // Skip the money bank
            sortedBankAccounts.add(new Pair<>(itemID, bankAccounts.get(itemID)));
        }
        //sortedBankAccounts.sort((a, b) -> a.getFirst().compareTo(b.getFirst()));
        // Sort by balance
        sortedBankAccounts.sort((a, b) -> Long.compare(b.getSecond().getBalance(), a.getSecond().getBalance()));
        return sortedBankAccounts;
    }
    public static ArrayList<Pair<String, SyncBankDataPacket.BankData>> getSortedBankData()
    {
        HashMap<String, SyncBankDataPacket.BankData> bankAccounts = bankDataPacket.getBankData();
        // Sort the bank accounts by itemID
        ArrayList<Pair<String,SyncBankDataPacket.BankData>> sortedBankAccounts = new ArrayList<>();
        for (String itemID : bankAccounts.keySet()) {
            sortedBankAccounts.add(new Pair<>(itemID, bankAccounts.get(itemID)));
        }
        //sortedBankAccounts.sort((a, b) -> a.getFirst().compareTo(b.getFirst()));
        // Sort by balance
        sortedBankAccounts.sort((a, b) -> Long.compare(b.getSecond().getBalance(), a.getSecond().getBalance()));
        return sortedBankAccounts;
    }

    public static long getTotalSupply(String itemID)
    {
        if(itemInfoPacket == null || !itemInfoPacket.getItemID().equals(itemID))
        {
            msgItemInfoNotReceived(itemID);
            return 0;
        }
        return itemInfoPacket.getTotalSupply();
    }
    public static long getTotalLocked(String itemID)
    {
        if(itemInfoPacket == null || !itemInfoPacket.getItemID().equals(itemID))
        {
            msgItemInfoNotReceived(itemID);
            return 0;
        }
        return itemInfoPacket.getTotalLocked();
    }
    public static Map<String, SyncItemInfoPacket.BankData> getItemInfoBankData(String itemID)
    {
        if(itemInfoPacket == null || !itemInfoPacket.getItemID().equals(itemID))
        {
            msgItemInfoNotReceived(itemID);
            return null;
        }
        return itemInfoPacket.getBankData();
    }

    private static void msgBankDataNotReceived()
    {
        RequestBankDataPacket.sendRequest();
        //BankSystemMod.LOGGER.warn("Bank data packet not received yet");
    }
    private static void msgItemInfoNotReceived(String itemID)
    {
        RequestItemInfoPacket.sendRequest(itemID);
        //BankSystemMod.LOGGER.warn("Item info packet not received yet");
    }

    public static void requestAllowNewItemID(String itemID)
    {
        RequestAllowNewBankItemIDPacket.sendRequest(itemID);
    }
    public static void requestRemoveItemID(String itemID)
    {
        RequestDisallowBankingItemIDPacket.sendRequest(itemID);
    }
    public static void requestItemInfo(String itemID)
    {
        RequestItemInfoPacket.sendRequest(itemID);
    }
}
