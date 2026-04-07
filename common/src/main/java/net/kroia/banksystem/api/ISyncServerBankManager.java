package net.kroia.banksystem.api;

import com.google.gson.JsonElement;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.clientdata.BankManagerData;
import net.kroia.banksystem.banking.clientdata.ItemInfoData;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

public interface ISyncServerBankManager {
    /**
     * Contains all data about the bank manager.
     * @return Data packet containing all data about the bank manager.
     */
    BankManagerData getBankManagerData();

    /**
     * Contains all user information about the bank manager.
     * A user consists of their UUID, name, and other individual settings.
     * @return Data packet containing all user information about the bank manager.
     */
    BankManagerData.UserMapData getBankManagerUserMapData();

    /**
     * Contains a list of all bank accounts and their banks.
     * @return Data packet containing all bank accounts and their banks.
     */
    BankManagerData.BankAccountsData getBankManagerBankAccountsData();


    /**
     * Returns a list of all allowed items that can be stored in the bank.
     * @return A list of allowed items that can be stored in the bank.
     */
    List<ItemID> getAllowedItems();

    /**
     * Returns a list of all blacklisted items that cannot be stored in the bank.
     * @return A list of blacklisted items that cannot be stored in the bank.
     */
    List<ItemID> getBlacklistedItems();

    /**
     * Returns a list of all items that cannot be removed from the bank.
     * These items are not allowed to be removed from the bank account.
     * @return A list of items that cannot be removed from the bank.
     */
    List<ItemID> getNotRemovableItems();

    /**
     * Returns minimalistic data about the bank manager.
     * This data can be requested by the client using the ClientBankManager.
     *
     * @return Data packet containing minimal data about the bank manager.
     */
    ItemInfoData getItemInfoData(@NotNull ItemID itemID);


    /**
     * Creates a new User for the manager which is used for assigning to a BankAccount.
     * This must be called once a player joins the server for the first time.
     * @param player The player to create a user for.
     */
    void addUser(@NotNull ServerPlayer player);
    void addUser(@NotNull UUID playerUUID, @NotNull String playerName);

    /**
     * Adds a new User to the bank manager.
     * If the player to add is not online but all relevant data is available,
     * this function can be called to add the user instead of the addUser(ServerPlayer player) method.
     * @param user contains all relevant data about the user to add.
     */
    void addUser(@NotNull User user);

    /**
     * Removes the user with the given UUID.
     *
     * //@note
     * //This method will emit the CLOSE_ITEM_BANK_EVENT for all banks of the user
     *
     * @param userUUID UUID of the user to remove.
     * @return True if the user was successfully removed, false otherwise.
     */
    boolean removeUser(UUID userUUID);

    /**
     * Checks if a user with the given UUID exists in the bank manager.
     * @param userUUID UUID of the user to check.
     * @return True if the user exists, false otherwise.
     */
    boolean userExists(UUID userUUID);

    /**
     * Gets the User object for the given UUID.
     * @param userUUID UUID of the user to get.
     * @return The User object if found, null otherwise.
     */
    @Nullable User getUserByUUID(UUID userUUID);

    /**
     * Gets the User object for the given name.
     * @param name Name of the user to get.
     * @return The User object if found, null otherwise.
     */
    @Nullable User getUserByName(String name);








    /**
     * Creates a new personal bank account for the given user UUID.
     * The personal bank account is the default bank account for a user.
     * @param user The UUID of the user to create the personal bank account for.
     * @return The created or already existing BankAccount object.
     */
    IBankAccount createPersonalBankAccount(UUID user);

    /**
     * Creates a new bank account with a given account name.
     * The account is empty and no user is assigned to it.
     * @param accountName The name of the bank account to create.
     *                    The name must not be unique.
     * @return The created BankAccount object.
     */
    IBankAccount createBankAccount(String accountName);

    /**
     * Gets a bank account with the given account number.
     * @param accountNumber The account number of the bank account to get.
     * @return The BankAccount object if found, null otherwise.
     */
    @Nullable IBankAccount getBankAccount(int accountNumber);


    /**
     * Gets all bank accounts that have the given user UUID as a bank user.
     * @param userUUID The UUID of the user to get bank accounts for.
     * @return A list of BankAccount objects associated with the user UUID.
     */
    List<IBankAccount> getBankAccounts(UUID userUUID);


    /**
     * Gets all bank accounts that have the given item ID as a bank item.
     * @param itemID The item ID to get bank accounts for.
     * @return A list of BankAccount objects associated with the item ID.
     */
    List<IBankAccount> getBankAccounts(ItemID itemID);

    /**
     * Gets the personal bank account for a given user UUID.
     * The personal bank account is the default bank account for a user.
     * @param userUUID The UUID of the user to get the personal bank account for.
     * @return The BankAccount object if found, null otherwise.
     */
    @Nullable IBankAccount getPersonalBankAccount(UUID userUUID);

    /**
     * Gets the personal bank account for a given user name.
     * The personal bank account is the default bank account for a user.
     * @param userName The name of the user to get the personal bank account for.
     * @return The BankAccount object if found, null otherwise.
     */
    @Nullable IBankAccount getPersonalBankAccount(String userName);

    /**
     * Trys to get the personal bank account for a given user UUID.
     * If the personal bank account does not exist, it will try to create a new one.
     * @param userUUID The UUID of the user to get or create the personal bank account for.
     * @return The BankAccount object if found or created, null if an error occurs.
     */
    @Nullable IBankAccount getOrCreatePersonalBankAccount(UUID userUUID);

    /**
     * Trys to get the personal bank account for a given user name.
     * If the personal bank account does not exist, it will try to create a new one.
     * @param userName The name of the user to get or create the personal bank account for.
     * @return The BankAccount object if found or created, null if an error occurs.
     */
    @Nullable IBankAccount getOrCreatePersonalBankAccount(@NotNull String userName);

    /**
     * Checks if a user has a personal bank account.
     * @param userUUID The UUID of the user to check.
     * @return True if the user has a personal bank account, false otherwise.
     */
    boolean userHasPersonalBankAccount(UUID userUUID);

    /**
     * Deletes a bank account with the given account number.
     * @param accountNumber The account number of the bank account to delete.
     * @return True if the bank account was successfully deleted, false otherwise.
     */
    boolean deleteBankAccount(int accountNumber);












    /**
     * Gets the personal bank for a given owner UUID and item ID.
     * The personal bank is the default bank for a user.
     * @param owner The UUID of the owner to get the personal bank for.
     * @param itemID The item ID of the personal bank to get.
     * @return The IBank object if found, null otherwise.
     */
    @Nullable IBank getPersonalBank(UUID owner, ItemID itemID);

    /**
     * Gets the personal bank for a given owner name and item ID.
     * The personal bank is the default bank for a user.
     * @param ownerName The name of the owner to get the personal bank for.
     * @param itemID The item ID of the personal bank to get.
     * @return The IBank object if found, null otherwise.
     */
    @Nullable IBank getPersonalBank(String ownerName, ItemID itemID);

    /**
     * Gets or creates a personal bank for a given owner UUID and item ID.
     * If the personal bank does not exist, it will try to create a new one.
     * @param owner The UUID of the owner to get or create the personal bank for.
     * @param itemID The item ID of the personal bank to get or create.
     * @return The IBank object if found or created, null if an error occurs.
     */
    @Nullable IBank getOrCreatePersonalBank(UUID owner, ItemID itemID);

    /**
     * Gets or creates a personal bank for a given owner name and item ID.
     * If the personal bank does not exist, it will try to create a new one.
     * @param ownerName The name of the owner to get or create the personal bank for.
     * @param itemID The item ID of the personal bank to get or create.
     * @return The IBank object if found or created, null if an error occurs.
     */
    @Nullable IBank getOrCreatePersonalBank(String ownerName, ItemID itemID);












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




    /**
     * Converts the bank manager data to a JSON representation.
     * This is not used for persisting or transferring data over the network.
     * It's used for debugging and logging purposes.
     *
     * @return A JsonElement containing the bank manager data.
     */
    JsonElement toJson();

    /**
     * Converts the bank manager data to a JSON string representation.
     * This is not used for persisting or transferring data over the network.
     * It's used for debugging and logging purposes.
     *
     * @return A String containing the bank manager data in JSON format.
     */
    String toJsonString();


    /**
     * Call this function when a player joins the server to setup its bank account
     * @param playerUUID
     * @param playerName
     */
    void onPlayerJoin(UUID playerUUID, String playerName);

}
