package net.kroia.banksystem.api.bankmanager;

import com.google.gson.JsonElement;
import net.kroia.banksystem.api.bank.IServerBank;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankManagerData;
import net.kroia.banksystem.banking.clientdata.ItemInfoData;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public interface ISyncServerBankManager {

    /**
     * Task #55: mark the bank-data save unit dirty. Master-side persistence hint — call after
     * mutating any persisted manager state that is NOT caught by the 1&nbsp;Hz account-change
     * aggregation (e.g. a {@link net.kroia.banksystem.banking.User} field setter, whose owning
     * {@code User} is serialized inside the manager's save). A missed mark risks silent data
     * loss on a hard kill because the timer-gated save skips clean units.
     */
    void markPersistDirty();


    void subscribeBankChanges(int accountNr, Consumer<BankAccountData> callback);
    void unsubscribeBankChanges(int accountNr, Consumer<BankAccountData> callback);

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
     * Contains a list of all bankaccounts and their banks.
     * @return Data packet containing all bankaccounts and their banks.
     */
    BankManagerData.BankAccountsData getBankManagerBankAccountsData();

    /**
     * Changes the admin mode for the banksystem mod for the given player.
     * @param playerUUID for which the admin mode is set.
     * @param isAdmin true: the player will be an admin.
     *                false: the player will no longer be an admin.
     * @return true: if the transaction was successfully.
     *         false: if the transaction was not successfully.
     *                Possible reasons:
     *                  - The user is not known by the banksystem. Register the user using addUser() first
     */
    boolean setBanksystemAdminMode(UUID playerUUID, boolean isAdmin);

    /**
     * Checks if the given player is a banksystem admin
     * @param playerUUID player to check
     * @return  true: if the given player is an admin.
     *          false: if the given player is not an admin or the player is not known by the banksystem.
     */
    boolean isBanksystemAdmin(UUID playerUUID);


    /**
     * Checks if the given slave server ID is in the trusted list.
     * A trusted server has the permission to deposit and withdraw items on banks
     * @param slaveID to check for
     * @return true: if the given ID is trusted, otherwise false
     */
    boolean isSlaveServerTrusted(String slaveID);

    /**
     * @return The Set of trusted slave ID's
     */
    Set<String> getTrustedSlaveServers();

    /**
     * Adds the given slaveID to the trusted list
     * @param slaveID to add
     */
    void trustSlaveServer(String slaveID);

    /**
     * Removes the given slaveID from the trusted list
     * @param slaveID to remove
     */
    void untrustSlaveServer(String slaveID);


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
     * Returns minimalistic data about the bank manager.
     * This data can be requested by the client using the ClientBankManager.
     *
     * @return Data packet containing minimal data about the bank manager.
     */
    ItemInfoData getItemInfoData(@NotNull ItemID itemID);


    /**
     * Creates a new User for the manager which is used for assigning to a IServerBankAccount.
     * This must be called once a player joins the server for the first time.
     * @param player The player to create a user for.
     */
    void addUser(@NotNull ServerPlayer player);

    /**
     * Creates a new User for the manager which is used for assigning to a ServerBankAccount.
     * This must be called once a player joins the server for the first time.
     * This is an alternative for addUser(@NotNull ServerPlayer player);
     * @param playerUUID Players UUID
     * @param playerName Players name
     */
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
     * Since the name of a player is not constant, this function will update
     * the stored name for a given playerUUID to keep consistent.
     * @param playerUUID players UUID for which the name needs to be updated
     * @param playerName new name
     * @return true:  if a player exists and the name has changed
     *         false: otherwise
     */
    boolean updateUserName(UUID playerUUID, String playerName);

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
     * Checks if a bank account exist for the given accountNumber
     * @param accountNumber to check
     * @return true:  if an account exists
     *         false: if no account exists
     */
    boolean bankAccountExists(int accountNumber);

    /**
     * Checks if a specific item bank exist in a bank account with the specific accountNumber
     * @param accountNumber in which it checks for the item bank
     * @param itemID to check for an item bank
     * @return true:  if an account exists with the given item bank in it
     *         false: if no account exists or no item bank exists in that bank account
     */
    boolean bankAccountHasBank(int accountNumber, ItemID itemID);


    /**
     * Gets a bank account with the given account number.
     * @param accountNumber The account number of the bank account to get.
     * @return The IServerBankAccount object if found, null otherwise.
     */
    @Nullable IServerBankAccount getBankAccount(int accountNumber);

    /**
     * Gets the bank account data for the given account number.
     * @param accountNumber The account number for which the data is requested.
     * @return The BankAccountData object if found, null otherwise.
     */
    @Nullable BankAccountData getBankAccountData(int accountNumber);


    /**
     * Creates a new personal bank account for the given user UUID.
     * The personal bank account is the default bank account for a user.
     * @param user The UUID of the user to create the personal bank account for.
     * @return The created or already existing IServerBankAccount object.
     */
    @Nullable IServerBankAccount createPersonalBankAccount(UUID user);

    /**
     * Creates a personal bank account for the given user and returns the
     * bank account number to that bank account.
     * If there is already a personal bank account for that user, it will not create a new one
     * but return the account number to the already existing account.
     * @param user for which the account gets created
     * @return the account number to the personal bank account or
     *         ServerBankAccount.INVALID_ACCOUNT_NUMBER if the creation of a personal bank account has failed.
     *         Possible reasons for a fail:
     *          - User is not registered to the banksystem.
     */
    int createPersonalBankAccountGetAccountNr(UUID user);

    /**
     * Creates a personal bank account for the given user and returns the
     * bank account number to that bank account.
     * If there is already a personal bank account for that user, it will not create a new one
     * but return the account number to the already existing account.
     * @param userName for which the account gets created
     * @return the account number to the personal bank account or
     *         ServerBankAccount.INVALID_ACCOUNT_NUMBER if the creation of a personal bank account has failed.
     *         Possible reasons for a fail:
     *          - User is not registered to the banksystem.
     */
    int createPersonalBankAccountGetAccountNr(String userName);

    /**
     * Gets the bank account number to the personal bank account for the given player
     * @param user 
     * @return the account number or ServerBankAccount.INVALID_ACCOUNT_NUMBER if it does not exist
     */
    int getPersonalBankAccountNr(UUID user);

    /**
     * Gets the bank account number to the personal bank account for the given player
     * @param userName
     * @return the account number or ServerBankAccount.INVALID_ACCOUNT_NUMBER if it does not exist
     */
    int getPersonalBankAccountNr(String userName);

    /**
     * Creates a new bank account with a given account name.
     * The account is empty and no user is assigned to it.
     * @param accountName The name of the bank account to create.
     *                    The name must not be unique.
     * @return The created IServerBankAccount object.
     */
    @Nullable IServerBankAccount createBankAccount(String accountName);

    /**
     * Creates a bank account with a specified account name
     * @param accountName used to create the account
     * @return The account number of the new created bank account or
     *         ServerBankAccount.INVALID_ACCOUNT_NUMBER if the bank account can't be created
     */
    int createBankAccountGetAccountNr(String accountName);





    /**
     * Gets all bankaccounts that have the given user UUID as a bank user.
     * @param userUUID The UUID of the user to get bankaccounts for.
     * @return A list of IServerBankAccount objects associated with the user UUID.
     */
    List<IServerBankAccount> getBankAccounts(UUID userUUID);

    /**
     * Gets the first bank account interface that uses a specific name
     * @param accountName for searching
     * @return the bank account interface to the first match
     */
    @Nullable IServerBankAccount getBankAccountByName(String accountName);


    /**
     * Gets a list of bank account numbers that have the user in it
     * @param userUUID The UUID of the user to get bankaccounts for.
     * @return A list of bank account numbers associated with the user
     */
    List<Integer> getBankAccountNumbers(UUID userUUID);

    /**
     * Task #58 (v2.1.1) — counts ONLY the bank accounts that the given player CREATED
     * (via {@code /bank create} or {@code /company create}), i.e. accounts whose
     * {@code creatorUUID} equals {@code userUUID}. The auto-created personal account,
     * shared-account memberships and company-employee status are all excluded — unlike
     * {@link #getBankAccountNumbers(UUID)}, which counts membership. Backs the per-player
     * account cap.
     * @param userUUID the creating player's UUID
     * @return the number of accounts created by this player
     */
    int countAccountsCreatedBy(UUID userUUID);

    /**
     * Gets a list of bank account numbers that contain an item bank for the specified item
     * @param itemID to search for
     * @return list of bank account numbers
     */
    List<Integer> getBankAccountNumbers(ItemID itemID);

    /**
     * Task #48 (v2.1.0) — holder index for the dividend payer + share-holder queries.
     * <p>
     * Enumerates account numbers whose item bank for {@code itemID} currently holds a
     * <b>strictly positive total balance</b> (free + locked). Unlike
     * {@link #getBankAccountNumbers(ItemID)}, this filters out accounts that opened a
     * slot but drained it to zero — the dividend distributor doesn't want zero-balance
     * rows and neither does the future "Companies" holder-count column.
     * <p>
     * Implemented as a linear scan on master; if the holder set churns hot enough to
     * matter, the plan spec (§Open Items) contemplates a maintained secondary index.
     *
     * @param itemID the share (or any) ItemID to enumerate holders for
     * @return the set of account numbers with a positive balance; empty if none
     */
    java.util.Set<Integer> listAccountsHolding(ItemID itemID);

    /**
     * Gets a list of bank account data objects that have the user in it
     * @param userUUID The UUID of the user to get bankaccounts for.
     * @return A list of bank account data objects associated with the user
     */
    List<BankAccountData> getBankAccountsData(UUID userUUID);



    /**
     * Gets all bankaccounts that have the given item ID as a bank item.
     * @param itemID The item ID to get bankaccounts for.
     * @return A list of IServerBankAccount objects associated with the item ID.
     */
    List<IServerBankAccount> getBankAccounts(ItemID itemID);

    /**
     * Gets a list of bank account data objects that contain an item bank for the specified item
     * @param itemID to search for
     * @return list of bank account data objects
     */
    List<BankAccountData> getBankAccountsData(ItemID itemID);

    /**
     * Gets the personal bank account for a given user UUID.
     * The personal bank account is the default bank account for a user.
     * @param userUUID The UUID of the user to get the personal bank account for.
     * @return The IServerBankAccount object if found, null otherwise.
     */
    @Nullable IServerBankAccount getPersonalBankAccount(UUID userUUID);

    /**
     * Gets the personal bank account data for a given user UUID.
     * The personal bank account is the default bank account for a user.
     * @param userUUID The UUID of the user to get the personal bank account for.
     * @return The data object if found, null otherwise.
     */
    @Nullable BankAccountData getPersonalBankAccountData(UUID userUUID);

    /**
     * Gets the personal bank account for a given username.
     * The personal bank account is the default bank account for a user.
     * @param userName The name of the user to get the personal bank account for.
     * @return The IServerBankAccount object if found, null otherwise.
     */
    @Nullable IServerBankAccount getPersonalBankAccount(String userName);

    /**
     * Gets the personal bank account data for a given user UUID.
     * The personal bank account is the default bank account for a user.
     * @param userName The username to get the personal bank account for.
     * @return The data object if found, null otherwise.
     */
    @Nullable BankAccountData getPersonalBankAccountData(String userName);

    /**
     * Trys to get the personal bank account for a given user UUID.
     * If the personal bank account does not exist, it will try to create a new one.
     * @param userUUID The UUID of the user to get or create the personal bank account for.
     * @return The IServerBankAccount object if found or created, null if an error occurs.
     */
    @Nullable IServerBankAccount getOrCreatePersonalBankAccount(UUID userUUID);

    /**
     * Trys to get the personal bank account for a given username.
     * If the personal bank account does not exist, it will try to create a new one.
     * @param userName The name of the user to get or create the personal bank account for.
     * @return The IServerBankAccount object if found or created, null if an error occurs.
     */
    @Nullable IServerBankAccount getOrCreatePersonalBankAccount(@NotNull String userName);

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
     * Checks if the personal bank account, for the specified user, contains an itembank for the given item
     * @param owner to seach for the personal bank account
     * @param itemID to check for a itembank
     * @return true:  if such an itembank exists.
     *         false: if no bank account or no itembank exists.
     */
    boolean personalBankExists(UUID owner, ItemID itemID);

    /**
     * Checks if the personal bank account, for the specified user, contains an itembank for the given item
     * @param ownerName to seach for the personal bank account
     * @param itemID to check for a itembank
     * @return true:  if such an itembank exists.
     *         false: if no bank account or no itembank exists.
     */
    boolean personalBankExists(String ownerName, ItemID itemID);

    /**
     * Gets the personal bank for a given owner UUID and item ID.
     * The personal bank is the default bank for a user.
     * @param owner The UUID of the owner to get the personal bank for.
     * @param itemID The item ID of the personal bank to get.
     * @return The IServerBank object if found, null otherwise.
     */
    @Nullable IServerBank getPersonalBank(UUID owner, ItemID itemID);
    
    /**
     * Gets the personal bank for a given owner name and item ID.
     * The personal bank is the default bank for a user.
     * @param ownerName The name of the owner to get the personal bank for.
     * @param itemID The item ID of the personal bank to get.
     * @return The IServerBank object if found, null otherwise.
     */
    @Nullable IServerBank getPersonalBank(String ownerName, ItemID itemID);
    
    /**
     * Gets or creates a personal bank for a given owner UUID and item ID.
     * If the personal bank does not exist, it will try to create a new one.
     * @param owner The UUID of the owner to get or create the personal bank for.
     * @param itemID The item ID of the personal bank to get or create.
     * @return The IServerBank object if found or created, null if an error occurs.
     */
    @Nullable IServerBank getOrCreatePersonalBank(UUID owner, ItemID itemID);
    
    /**
     * Gets or creates a personal bank for a given owner name and item ID.
     * If the personal bank does not exist, it will try to create a new one.
     * @param ownerName The name of the owner to get or create the personal bank for.
     * @param itemID The item ID of the personal bank to get or create.
     * @return The IServerBank object if found or created, null if an error occurs.
     */
    @Nullable IServerBank getOrCreatePersonalBank(String ownerName, ItemID itemID);










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
     * Task #57: disallows the given item ID (incl. {@code banksystem:money}) and returns the
     * list of holder accounts that were cleared, each with its captured free + locked balance
     * at clear time. The full holder dump is also written to the server console. NO REFUND is
     * issued. Backs the (capped) chat summary the command handler shows the executor.
     *
     * @param itemID The item ID to disallow.
     * @return The cleared holders (possibly empty), or {@code null} if the input was invalid.
     */
    @Nullable
    List<DisallowedHolder> disallowItemIDAndReport(ItemID itemID);

    /**
     * Audit record of one account that held a just-disallowed item (Task #57).
     *
     * @param accountNr the holder's bank account number
     * @param ownerName the account's personal-bank owner name, or the account name for a
     *                  shared account
     * @param free      the free (unlocked) balance cleared, in raw units
     * @param locked    the locked balance cleared, in raw units
     */
    record DisallowedHolder(int accountNr, String ownerName, long free, long locked) {
        public long total() { return free + locked; }
    }

    /**
     * Checks if the given item ID is blacklisted and cannot be stored in a bank account.
     *
     * @param itemID The item ID to check.
     * @return True if the item ID is blacklisted, false otherwise.
     */
    boolean isItemIDBlacklisted(ItemID itemID);


    /**
     * Gets the scaling factor used for backend item amounts.
     * A scaling factor of 100 means that a raw bank value of 1 represents 0.01 Items
     * @return global scaling factor, used for all items
     */
    int getItemFractionScaleFactor();




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
     *         (Including the locked balance)
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


    long convertToRawAmount(double realAmount);
    double convertToRealAmount(long rawAmount);



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
