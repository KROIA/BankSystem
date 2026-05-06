package net.kroia.banksystem.api.bankaccount;

import com.google.gson.JsonElement;
import net.kroia.banksystem.api.bank.IAsyncBank;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.banking.clientdata.BankUserData;
import net.kroia.banksystem.banking.clientdata.UserData;
import net.kroia.banksystem.util.ItemID;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface IAsyncBankAccount {


    /**
     * Gets all data stored in this bank account
     * @return the bank account data
     */
    CompletableFuture<BankAccountData> getAccountDataAsync();

    /**
     * Gets all data stored in this bank account, except for all other item banks than the one specified by the itemID
     * @param itemID the itemID of the bank to get the data for
     * @return the bank account data for the specified itemID, or null if no such bank exists
     */
    CompletableFuture<@Nullable BankAccountData> getAccountDataAsync(ItemID itemID);

    /**
     * Gets the bank data for the specified itemID
     * @param itemID the itemID of the bank to get the data for
     * @return the bank data for the specified itemID, or null if no such bank exists
     */
    CompletableFuture<@Nullable BankData> getBankDataAsync(ItemID itemID);

    /**
     * Gets all bank data stored in this bank account
     * @return a list of all bank data in this bank account
     */
    CompletableFuture<List<BankData>> getBankDataAsync();

    /**
     * Gets the user data for the specified user UUID
     * This does not include the personal bank owner data.
     * @param userUUID the UUID of the user to get the data for
     * @return the user data for the specified user UUID, or null if no such user exists in this bank account
     */
    CompletableFuture<@Nullable BankUserData> getUserDataAsync(UUID userUUID);

    /**
     * Gets all user data stored in this bank account
     * This does not include the personal bank owner data.
     * @return a list of all user data in this bank account
     */
    CompletableFuture<List<BankUserData>> getUserDataAsync();

    /**
     * Gets the personal bank owner data
     * The personal bank owner does not have any permissions set because the owner has always all permissions.
     * @return the personal bank owner data, or null if no personal bank owner is set
     */
    CompletableFuture<@Nullable UserData> getPersonalBankOwnerDataAsync();


    /**
     * Gets the account number of this bank account.
     * @return the account number
     */
    int getAccountNumberAsync();

    /**
     * Sets the name of this bank account.
     * This is only decorative and does not affect the functionality of the bank account.
     * @param accountName the new account name
     */
    void setAccountNameAsync(String accountName);

    /**
     * Gets the name of this bank account.
     * @return the account name
     */
    CompletableFuture<String> getAccountNameAsync();

    /**
     * Sets the icon of this bank account.
     * This is only decrative and does not affect the functionality of the bank account.
     * @param accountIcon the new account icon, or null to remove the icon
     */
    void setAccountIconAsync(@Nullable ItemID accountIcon);










    /**
     * Gets the icon of this bank account.
     * @return the account icon, or null if no icon is set
     */
    CompletableFuture<@Nullable ItemID> getAccountIconAsync();

    /**
     * Gets the permission level of the user with the specified UUID.
     * @see class BankPermission
     * @param userUUID the UUID of the user to get the permission level for
     * @return the permissions of the user
     */
    CompletableFuture<Integer> getPermissionAsync(UUID userUUID);

    /**
     * Checks if the user with the specified UUID has the specified permission.
     * @see class BankPermission
     * @param userUUID the UUID of the user to check
     * @param permission the permission to check for, as defined in class BankPermission
     * @return true if the user has the specified permission, false otherwise
     */
    CompletableFuture<Boolean> hasPermissionAsync(UUID userUUID, int permission);

    /**
     * Type-safe overload of {@link #hasPermissionAsync(UUID, int)} taking a {@link BankPermission} enum.
     * Prefer this overload over the int variant.
     */
    default CompletableFuture<Boolean> hasPermissionAsync(UUID userUUID, BankPermission permission) {
        return hasPermissionAsync(userUUID, permission.getValue());
    }

    /**
     * Sets the permission level of the user with the specified UUID.
     * @see class BankPermission
     * @param userUUID the UUID of the user to set the permission for
     * @param permission the permission level to set, as defined in class BankPermission
     */
    void setPermissionAsync(UUID userUUID, int permission);

    /**
     * Adds a user to this bank account with the specified permission level.
     * If the user already exists, their permission level is updated.
     * @see class BankPermission
     * @param user the user to add
     * @param permission the permission level to set for the user, as defined in class BankPermission
     */
    void addUserAsync(User user, int permission);

    /**
     * Sets the list of users for this bank account.
     * This will replace the existing user list.
     * The personal bank owner is not affected by this method.
     * @param userList a map of users and their permission levels
     */
    void setUsersAsync(Map<User, Integer> userList);

    /**
     * Removes a user from this bank account by their UUID.
     * @param userUUID the UUID of the user to remove
     */
    void removeUserAsync(UUID userUUID);

    /**
     * @return true if this bank account has any users, false otherwise
     *              This does include the personal bank owner.
     */
    CompletableFuture<Boolean> hasAnyUserAsync();

    /**
     * Checks if this bank account has a user with the specified UUID.
     * This does include the personal bank owner.
     * @param userUUID the UUID of the user to check for
     * @return true if the user exists in this bank account, false otherwise
     */
    CompletableFuture<Boolean> hasUserAsync(UUID userUUID);

    /**
     * Gets the personal bank owner of this bank account.
     * The personal bank owner is the user who created this bank account and has all permissions.
     * @return the personal bank owner, or null if no personal bank owner is set
     */
    CompletableFuture<@Nullable User> getPersonalBankOwnerAsync();


    /**
     * Creates a new bank for the specified itemID with the given starting balance.
     * If the bank already exists, it will be returned without changing the balance.
     * @param itemID the itemID of the bank to create
     * @param startBalance the starting balance of the bank, in raw amount.
     * @return the created or already existing bank, or null if the bank could not be created.
     */
    CompletableFuture<@Nullable IAsyncBank> createBankAsync(ItemID itemID, long startBalance);

    /**
     * Removes the bank for the specified itemID.
     * Items will be lost.
     * @param itemID the itemID of the bank to remove
     */
    void removeBankAsync(ItemID itemID);

    /**
     * Removes all banks that are empty, meaning they have no items stored in them.
     * @return a list of itemIDs of the removed banks
     */
    CompletableFuture<List<ItemID>> removeEmptyBanksAsync();

    /**
     * Removes all banks from this bank account.
     */
    void removeAllBanksAsync();

    /**
     * Checks if this bank account has any banks.
     * @return true if this bank account has any banks, false otherwise
     */
    CompletableFuture<Boolean> hasAnyBankAsync();

    /**
     * Checks if this bank account has a bank for the specified itemID.
     * @param itemID the itemID of the bank to check for
     * @return true if this bank account has a bank for the specified itemID, false otherwise
     */
    CompletableFuture<Boolean> hasBankAsync(ItemID itemID);

    /**
     * Gets the bank for the specified itemID.
     * @param itemID the itemID of the bank to get
     * @return the bank for the specified itemID, or null if no such bank exists
     */
    CompletableFuture<@Nullable IAsyncBank> getBankAsync(ItemID itemID);

    /**
     * Gets the bank for the specified itemID, or creates it if it does not exist.
     * @param itemID the itemID of the bank to get or create
     * @return the bank for the specified itemID, or a new bank if it did not exist before
     */
    CompletableFuture<@Nullable IAsyncBank> getOrCreateBankAsync(ItemID itemID);

    /**
     * Gets all banks in this bank account.
     * @return a map of itemIDs to their corresponding banks
     */
    CompletableFuture<Map<ItemID, IAsyncBank>> getAllBanksAsync();


    /**
     * Converts this bank account to a JSON representation.
     * This is not used for serialization, but for debugging and logging purposes.
     * @return a JsonElement representing this bank account
     */
    CompletableFuture<JsonElement> toJsonAsync();

    /**
     * Converts this bank account to a JSON string representation.
     * This is not used for serialization, but for debugging and logging purposes.
     * @return a JSON string representing this bank account
     */
    CompletableFuture<String> toJsonStringAsync();
}
