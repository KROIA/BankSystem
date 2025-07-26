package net.kroia.banksystem.api;

import net.kroia.banksystem.util.ItemID;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public interface IServerBankManager {

    IBankUser createUser(UUID userUUID, String userName, ArrayList<ItemID> itemIDs, boolean createMoneyBank, long startMoney);
    IBankUser createUser(ServerPlayer player, ArrayList<ItemID> itemIDs, boolean createMoneyBank, long startMoney);


    IBankUser getUser(UUID userUUID);
    IBankUser getUser(String userName);
    Map<UUID, IBankUser> getUser();
    void clear();
    boolean closeBankAccount(UUID playerUUID, ItemID itemID);
    void closeBankAccount(ItemID itemID);

    void closeBankAccount(String itemIDStr);
    boolean removeUser(UUID userUUID);

    HashMap<UUID, String> getPlayerNameMap();

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
