package net.kroia.banksystem.banking;

import com.mojang.datafixers.util.Pair;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.bank.MoneyBank;
import net.kroia.banksystem.networking.packet.client_sender.request.RequestAllowNewBankItemIDPacket;
import net.kroia.banksystem.networking.packet.client_sender.request.RequestBankDataPacket;
import net.kroia.banksystem.networking.packet.client_sender.request.RequestDisallowBankingItemIDPacket;
import net.kroia.banksystem.networking.packet.client_sender.request.RequestPotentialBankItemIDsPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDataPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncPotentialBankItemIDsPacket;

import java.util.ArrayList;
import java.util.HashMap;

public class ClientBankManager {
    private static SyncBankDataPacket bankDataPacket;
    private static SyncPotentialBankItemIDsPacket potentialBankItemIDsPacket;
    private static boolean hasUpdatedBankData = false;

    public static void clear()
    {
        bankDataPacket = null;
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

    public static boolean hasUpdatedBankData()
    {
        boolean has = hasUpdatedBankData;
        hasUpdatedBankData = false;
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

    public static HashMap<String, SyncBankDataPacket.BankData> getBankData() {
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
    public static ArrayList<String> getPotentialBankItemIDs() {
        if(potentialBankItemIDsPacket == null)
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

    private static void msgBankDataNotReceived()
    {
        RequestBankDataPacket.sendRequest();
        BankSystemMod.LOGGER.warn("Bank data packet not received yet");
    }

    public static void requestAllowNewItemID(String itemID)
    {
        RequestAllowNewBankItemIDPacket.sendRequest(itemID);
    }
    public static void requestRemoveItemID(String itemID)
    {
        RequestDisallowBankingItemIDPacket.sendRequest(itemID);
    }
}
