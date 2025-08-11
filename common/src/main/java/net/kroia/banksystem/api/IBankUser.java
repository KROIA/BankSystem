package net.kroia.banksystem.api;

import com.google.gson.JsonElement;
import net.kroia.banksystem.banking.clientdata.MinimalBankData;
import net.kroia.banksystem.banking.clientdata.MinimalBankUserData;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public interface IBankUser {

    /**
     * Returns minimalistic data about this bank user.
     * It contains all banks that this user has, including money and item banks.
     * Can be requested by the client using the ClientBankManager.
     *
     * @return Datapacket containing minimal data about this bank user.
     */
    MinimalBankUserData getMinimalData();

    /**
     * Returns minimalistic data about the bank with the given item ID.
     * Can be requested by the client using the ClientBankManager.
     *
     * @param itemID The item ID of the bank to get minimal data for.
     * @return Datapacket containing minimal data about the bank, or empty packet if the bank does not exist.
     */
    MinimalBankData getMinimalBankData(ItemID itemID);

    /**
     * Gets the UUID of the player who owns this bank user.
     *
     * @return The UUID of the player who owns this bank user.
     */
    UUID getPlayerUUID();

    /**
     * Gets the ServerPlayer who owns this bank user.
     *
     * @return The player who owns this bank user, or null if the player is not online.
     */
    @Nullable ServerPlayer getPlayer();

    /**
     * Gets the name of the player who owns this bank user.
     *
     * @return The name of the player who owns this bank user.
     */
    String getPlayerName();

    /**
     * Creates a new money bank for this user with the given starting balance. (If it not already exists)
     * If the money bank already exists, it will return the existing bank. (Not changing the balance)
     *
     * @param startBalance The starting balance for the new money bank.
     * @return The created money bank.
     */
    IBank createMoneyBank(float startBalance);

    /**
     * Creates a new item bank for this user with the given item ID and starting balance.
     * If the bank already exists, it will return the existing bank. (Not changing the balance)
     *
     * @param itemID The item ID for the new item bank.
     * @param startBalance The starting balance for the new item bank.
     * @param notifyPlayerOnFail Whether to notify the player who owns the bank account if the creation fails.
     * @return The created or existing item bank, or null if creation failed.
     */
    @Nullable IBank createItemBank(ItemID itemID, float startBalance, boolean notifyPlayerOnFail);

    /**
     * Returns the bank associated with the given item ID.
     * @param itemID
     * @return The bank associated with the item ID, or null if no such bank exists.
     */
    @Nullable IBank getBank(ItemID itemID);

    /**
     * Returns the money bank associated with this user.
     * @return The money bank, or null if no money bank exists for this user.
     */
    @Nullable IBank getMoneyBank();

    /**
     * Returns all banks that this user has, including money and item banks.
     * @return A map of item IDs to banks.
     */
    HashMap<ItemID, IBank> getAllBanks();

    /**
     * Removes the bank associated with the given item ID.
     * @param itemID The item ID of the bank to remove.
     * @return True if the bank was successfully removed, false otherwise.
     */
    boolean removeBank(ItemID itemID);

    /**
     * Removes all empty banks from this user's bank list.
     * This will remove all banks that have a balance of 0.
     * It will also remove the money bank if it has a balance of 0.
     *
     * @return A list of item IDs of the removed banks.
     */
    List<ItemID> removeEmptyBanks();

    /**
     * Gets the currently free available money balance of this user.
     *
     * @return The currently free available balance of this user.
     */
    long getMoneyBalance();

    /**
     * Gets the currently locked money balance of this user.
     * This is the balance that is currently locked and cannot be used for transactions.
     *
     * @return The currently locked balance of this user.
     */
    long getLockedMoneyBalance();

    /**
     * Gets the total money balance of this user. (free + locked balance)
     *
     * @return The currently free available balance of this user.
     */
    long getTotalMoneyBalance();

    /**
     * @return true if notifications to the bank account owner are enabled, false otherwise.
     */
    boolean isBankNotificationEnabled();

    /**
     * Sets whether notifications to the bank account owner are enabled or not.
     * @param enabled true to enable notifications, false to disable them.
     */
    void setBankNotificationEnabled(boolean enabled);

    /**
     * Converts this bank user data to a JSON representation.
     *
     * @return A JsonElement representing this bank user.
     */
    JsonElement toJson();

    /**
     * Converts this bank user data to a JSON string representation.
     *
     * @return A JSON string representing this bank user.
     */
    String toJsonString();

    /**
     * Returns a string representation of this bank user.
     * This is used for logging and debugging purposes.
     *
     * @return A string representation of this bank user.
     */
    String toString();


}
