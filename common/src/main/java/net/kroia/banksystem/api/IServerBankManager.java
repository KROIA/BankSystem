package net.kroia.banksystem.api;

import com.google.gson.JsonElement;
import net.kroia.banksystem.banking.BankAccount;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.clientdata.BankManagerData;
import net.kroia.banksystem.banking.clientdata.ItemInfoData;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

public interface IServerBankManager {



    BankManagerData getBankManagerData();
    BankManagerData.UserMapData getBankManagerUserMapData();
    BankManagerData.ItemFractionScaleFactorData getBankManagerItemFractionScaleFactorData();
    BankManagerData.BankAccountsData getBankManagerBankAccountsData();

    List<ItemID> getAllowedItems();
    List<ItemID> getBlacklistedItems();
    List<ItemID> getNotRemovableItems();

    /**
     * Returns minimalistic data about the bank manager.
     * This data can be requested by the client using the ClientBankManager.
     *
     * @return Data packet containing minimal data about the bank manager.
     */
    ItemInfoData getItemInfoData(ItemID itemID);




    void addUser(@NotNull ServerPlayer player);

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
    boolean userExists(UUID userUUID);
    User getUserByUUID(UUID userUUID);
    User getUserByName(String name);


    BankAccount getPersonalBankAccount(UUID userUUID);
    BankAccount getPersonalBankAccount(User user);


    boolean userHasPersonalBank(UUID userUUID);
    void createPersonalBank(UUID user);


    /**
     * Gets the scale factor for item fractions for the given item ID.
     * This is used to determine how to scale the real bank balance amount to its displayed value.
     *
     * @param itemID The item ID to get the scale factor for.
     * @return The scale factor for item fractions, or 1 if no specific scale factor is defined.
     */
    int getItemFractionScaleFactor(ItemID itemID);


    /**
     * Checks if the given item ID is allowed to be stored in a bank account.
     *
     * @param itemID The item ID to check.
     * @return True if the item ID is allowed, false otherwise.
     */
    boolean isItemIDAllowed(ItemID itemID);


    /**
     * Allows the given item ID to be stored in a bank account.
     *
     * @param itemID The item ID to allow.
     * @param itemFractionScaleFactor The scale factor for the item, used to convert the item amount to bank system money amount.
     *                                Allowed values are 1, 10, 100
     * @return True if the item ID was successfully allowed, false otherwise.
     */
    boolean allowItemID(ItemID itemID, int itemFractionScaleFactor);

    /**
     * Disallows the given item ID from being stored in a bank account.
     *
     * @param itemID The item ID to disallow.
     * @return True if the item ID was successfully disallowed, false otherwise.
     */
    boolean disallowItemID(ItemID itemID);

    /**
     * Checks if the given item ID is not removable and cannot be removed from a bank account.
     *
     * @param itemID The item ID to check.
     * @return True if the item ID is not removable, false otherwise.
     */
    boolean isItemIDNotRemovable(ItemID itemID);

    /**
     * Checks if the given item ID is blacklisted and cannot be stored in a bank account.
     *
     * @param itemID The item ID to check.
     * @return True if the item ID is blacklisted, false otherwise.
     */
    boolean isItemIDBlacklisted(ItemID itemID);

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
     * @return The total amount of money in circulation across all banks.
     */
    double getRealMoneyCirculation();

    /**
     * @return The total amount of locked money in circulation across all banks.
     */
    double getRealLockedMoneyCirculation();

    /**
     * @param itemID
     * @return The total amount of the specified item in circulation across all banks.
     */
    double getRealItemCirculation(ItemID itemID);

    /**
     * @param itemID
     * @return The total amount of the specified item that is locked in circulation across all banks.
     */
    double getRealLockedItemCirculation(ItemID itemID);

    /**
     * Gets the JSON representation of the circulation data.
     *
     * @return A JsonElement containing the circulation data.
     */
    JsonElement getCirculationDataJson();


    /**
     * Gets the JSON string representation of the circulation data.
     *
     * @return A String containing the circulation data in JSON format.
     */
    String getCirculationDataJsonString();




    JsonElement toJson();
    String toJsonString();









}
