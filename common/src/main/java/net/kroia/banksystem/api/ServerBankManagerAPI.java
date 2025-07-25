package net.kroia.banksystem.api;

import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public interface ServerBankManagerAPI {

    BankUserAPI createUser(UUID userUUID, String userName, ArrayList<ItemID> itemIDs, boolean createMoneyBank, long startMoney);
    BankUserAPI createUser(ServerPlayer player, ArrayList<ItemID> itemIDs, boolean createMoneyBank, long startMoney);


    BankUserAPI getUser(UUID userUUID);
    BankUserAPI getUser(String userName);
    Map<UUID, BankUserAPI> getUser();
    void clear();
    boolean closeBankAccount(UUID playerUUID, ItemID itemID);
    void closeBankAccount(ItemID itemID);

    void closeBankAccount(String itemIDStr);
    boolean removeUser(UUID userUUID);

    HashMap<UUID, String> getPlayerNameMap();

    Bank getMoneyBank(UUID userUUID);
    Bank getMoneyBank(String userName);
    Bank getBank(UUID userUUID, ItemID itemID);
    Bank getBank(String userName, ItemID itemID);


    long getMoneyCirculation();

    ArrayList<ItemID> getAllowedItemIDs();
    ArrayList<ItemID> getBlacklistedItemIDs();
    ArrayList<ItemID> getNotRemovableItemIDs();

    boolean isItemIDAllowed(ItemID itemID);
    boolean isItemIDBlacklisted(ItemID itemID);
    boolean isItemIDNotRemovable(ItemID itemID);
    boolean allowItemID(ItemID itemID);
    boolean disallowItemID(ItemID itemID);
}
