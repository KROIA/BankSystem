package net.kroia.banksystem.api;

import net.kroia.banksystem.banking.clientdata.ItemInfoData;
import net.kroia.banksystem.banking.clientdata.MinimalBankData;
import net.kroia.banksystem.banking.clientdata.MinimalBankUserData;
import net.kroia.banksystem.banking.clientdata.MinimalBankManagerData;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

public interface IServerBankManager {

    MinimalBankUserData getMinimalBankUserData(UUID userUUID);
    MinimalBankData getMinimalBankData(UUID userUUID, ItemID itemID);
    ItemInfoData getItemInfoData(ItemID itemID);
    MinimalBankManagerData getMinimalData();


    IBankUser createUser(UUID userUUID, String userName, ArrayList<ItemID> itemIDs, boolean createMoneyBank, long startMoney);
    IBankUser createUser(ServerPlayer player, ArrayList<ItemID> itemIDs, boolean createMoneyBank, long startMoney);


    IBankUser getUser(UUID userUUID);
    IBankUser getUser(String userName);
    Map<UUID, IBankUser> getUser();
    void clear();
    boolean closeBankAccount(UUID userUUID, ItemID itemID);
    void closeBankAccount(ItemID itemID);

    void closeBankAccount(String itemIDStr);
    boolean removeUser(UUID userUUID);

    HashMap<UUID, String> getUserNameMap();
    List<UUID> getUserUUIDList();

    IBank getMoneyBank(UUID userUUID);
    IBank getMoneyBank(String userName);
    IBank getBank(UUID userUUID, ItemID itemID);
    IBank getBank(String userName, ItemID itemID);


    long getMoneyCirculation();

    long getItemCirculation(ItemID itemID);
    long getLockedItemCirculation(ItemID itemID);

    ArrayList<ItemID> getAllowedItemIDs();
    ArrayList<ItemID> getBlacklistedItemIDs();
    ArrayList<ItemID> getNotRemovableItemIDs();

    boolean isItemIDAllowed(ItemID itemID);
    boolean isItemIDBlacklisted(ItemID itemID);
    boolean isItemIDNotRemovable(ItemID itemID);
    boolean allowItemID(ItemID itemID);
    boolean disallowItemID(ItemID itemID);
}
