package net.kroia.banksystem.api;

import net.kroia.banksystem.banking.clientdata.ItemInfoData;
import net.kroia.banksystem.banking.clientdata.MinimalBankData;
import net.kroia.banksystem.banking.clientdata.MinimalBankManagerData;
import net.kroia.banksystem.banking.clientdata.MinimalBankUserData;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public interface IServerBankManager {

    /**
     * Returns minimalistic data about the bank user with the given UUID.
     * This data can be requested by the client using the ClientBankManager.
     *
     * @param userUUID The UUID of the bank user to get minimal data for.
     * @return Data packet containing minimal data about the bank user, or null if no such user exists.
     */
    MinimalBankUserData getMinimalBankUserData(UUID userUUID);

    /**
     * Returns minimalistic data about the bank with the given item ID for the user with the given UUID.
     * This data can be requested by the client using the ClientBankManager.
     *
     * @param userUUID The UUID of the bank user to get minimal data for.
     * @param itemID   The item ID of the bank to get minimal data for.
     * @return Data packet containing minimal data about the bank, or null if no such bank exists.
     */
    MinimalBankData getMinimalBankData(UUID userUUID, ItemID itemID);

    /**
     * Returns minimalistic data about the bank manager.
     * This data can be requested by the client using the ClientBankManager.
     *
     * @return Data packet containing minimal data about the bank manager.
     */
    ItemInfoData getItemInfoData(ItemID itemID);

    /**
     * Returns minimalistic data about the bank manager.
     * This data can be requested by the client using the ClientBankManager.
     *
     * @return Data packet containing minimal data about the bank manager.
     */
    MinimalBankManagerData getMinimalData();


    /**
     * Creates a new bank user with the given UUID and name.
     *
     * @param userUUID UUID of the user to create.
     * @param userName Name of the user to create.
     * @param itemIDs List of bank items to create for the user.
     * @param createMoneyBank If true, a money bank will be created for the user.
     * @param startMoney Initial amount of money to start with in the money bank.
     * @return The created bank user.
     */
    IBankUser createUser(UUID userUUID, String userName, ArrayList<ItemID> itemIDs, boolean createMoneyBank, long startMoney);

    /**
     * Creates a new bank user for the given player.
     *
     * @param player The player to create the bank user for.
     * @param itemIDs List of bank items to create for the user.
     * @param createMoneyBank If true, a money bank will be created for the user.
     * @param startMoney Initial amount of money to start with in the money bank.
     * @return The created bank user.
     */
    IBankUser createUser(ServerPlayer player, ArrayList<ItemID> itemIDs, boolean createMoneyBank, long startMoney);


    /**
     * Gets the bank user with the given UUID.
     *
     * @param userUUID UUID of the user to get.
     * @return The bank user with the given UUID, or null if no such user exists.
     */
    @Nullable IBankUser getUser(UUID userUUID);

    /**
     * Gets the bank user with the given name.
     *
     * @param userName Name of the user to get.
     * @return The bank user with the given name, or null if no such user exists.
     */
    @Nullable IBankUser getUser(String userName);

    /**
     * Gets all bank users.
     *
     * @return A map of UUIDs to bank users.
     */
    Map<UUID, IBankUser> getUser();

    /**
     * Clears all bank users and their data.
     *
     * @note
     * This methode will emit the CLOSE_ITEM_BANK_EVENT
     */
    void clear();

    /**
     * Closes the bank account for the given user and item ID.
     *
     * @note
     * This method will emit the CLOSE_ITEM_BANK_EVENT if succeeded
     *
     * @param userUUID UUID of the user whose bank account to close.
     * @param itemID   The item ID of the bank account to close.
     * @return True if the bank account was successfully closed, false otherwise.
     */
    boolean closeBankAccount(UUID userUUID, ItemID itemID);

    /**
     * Closes the bank account for the given item for all users.
     *
     * @note
     * This method will emit the CLOSE_ITEM_BANK_EVENT if succeeded
     *
     * @param itemID   The item ID of the bank account to close.
     */
    void closeBankAccount(ItemID itemID);

    /**
     * Closes the bank account for the given item ID as a string.
     *
     * @note
     * This method will emit the CLOSE_ITEM_BANK_EVENT if succeeded
     *
     * @param itemIDStr The item ID of the bank account to close as a string.
     */
    void closeBankAccount(String itemIDStr);

    /**
     * Removes the user with the given UUID.
     *
     * @note
     * This method will emit the CLOSE_ITEM_BANK_EVENT for all banks of the user
     *
     * @param userUUID UUID of the user to remove.
     * @return True if the user was successfully removed, false otherwise.
     */
    boolean removeUser(UUID userUUID);

    /**
     * Gets a map of user UUIDs to their names.
     * @return A map of user UUIDs to their names.
     */
    Map<UUID, String> getUserNameMap();

    /**
     * Gets a list of all user UUIDs.
     * @return A list of all user UUIDs.
     */
    List<UUID> getUserUUIDList();

    /**
     * Gets the money bank for the given user UUID.
     *
     * @param userUUID UUID of the user to get the money bank for.
     * @return The money bank for the user, or null if no such bank exists.
     */
    @Nullable IBank getMoneyBank(UUID userUUID);

    /**
     * Gets the money bank for the given user name.
     *
     * @param userName Name of the user to get the money bank for.
     * @return The money bank for the user, or null if no such bank exists.
     */
    @Nullable IBank getMoneyBank(String userName);

    /**
     * Gets the bank for the given user UUID and item ID.
     *
     * @param userUUID UUID of the user to get the bank for.
     * @param itemID   The item ID of the bank to get.
     * @return The bank for the user and item ID, or null if no such bank exists.
     */
    @Nullable IBank getBank(UUID userUUID, ItemID itemID);

    /**
     * Gets the bank for the given user name and item ID.
     *
     * @param userName Name of the user to get the bank for.
     * @param itemID   The item ID of the bank to get.
     * @return The bank for the user and item ID, or null if no such bank exists.
     */
    @Nullable IBank getBank(String userName, ItemID itemID);

    /**
     * @return The total amount of money in circulation across all banks.
     */
    long getMoneyCirculation();

    /**
     * @return The total amount of locked money in circulation across all banks.
     */
    long getLockedMoneyCirculation();

    /**
     * @param itemID
     * @return The total amount of the specified item in circulation across all banks.
     */
    long getItemCirculation(ItemID itemID);

    /**
     * @param itemID
     * @return The total amount of the specified item that is locked in circulation across all banks.
     */
    long getLockedItemCirculation(ItemID itemID);

    /**
     * Gets the scale factor for item fractions for the given item ID.
     * This is used to determine how to scale the real bank balance amount to its displayed value.
     *
     * @param itemID The item ID to get the scale factor for.
     * @return The scale factor for item fractions, or 1 if no specific scale factor is defined.
     */
    int getItemFractionScaleFactor(ItemID itemID);

    /**
     * @return A list of all item IDs that are allowed to be stored in a bank account.
     */
    List<ItemID>  getAllowedItemIDs();

    /**
     * @return A list of all item IDs that are blacklisted and cannot be stored in a bank account.
     */
    List<ItemID>  getBlacklistedItemIDs();

    /**
     * @return A list of all item IDs that cannot be removed to be stored in a bank account.
     */
    List<ItemID>  getNotRemovableItemIDs();

    /**
     * Checks if the given item ID is allowed to be stored in a bank account.
     *
     * @param itemID The item ID to check.
     * @return True if the item ID is allowed, false otherwise.
     */
    boolean isItemIDAllowed(ItemID itemID);

    /**
     * Checks if the given item ID is blacklisted and cannot be stored in a bank account.
     *
     * @param itemID The item ID to check.
     * @return True if the item ID is blacklisted, false otherwise.
     */
    boolean isItemIDBlacklisted(ItemID itemID);

    /**
     * Checks if the given item ID is not removable and cannot be removed from a bank account.
     *
     * @param itemID The item ID to check.
     * @return True if the item ID is not removable, false otherwise.
     */
    boolean isItemIDNotRemovable(ItemID itemID);

    /**
     * Allows the given item ID to be stored in a bank account.
     *
     * @param itemID The item ID to allow.
     * @return True if the item ID was successfully allowed, false otherwise.
     */
    boolean allowItemID(ItemID itemID);

    /**
     * Disallows the given item ID from being stored in a bank account.
     *
     * @param itemID The item ID to disallow.
     * @return True if the item ID was successfully disallowed, false otherwise.
     */
    boolean disallowItemID(ItemID itemID);
}
