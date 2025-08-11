package net.kroia.banksystem.api;

import com.google.gson.JsonElement;
import net.kroia.banksystem.banking.clientdata.MinimalBankData;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Represents a bank that can hold money or items for a player.
 * Banks can be used to deposit, withdraw, and transfer funds or items.
 * Each bank is associated with a specific player and item type.
 */
public interface IBank {


    enum BankType
    {
        MONEY,
        ITEM
    }
    enum Status
    {
        SUCCESS,
        FAILED_NOT_ENOUGH_FUNDS,
        FAILED_OVERFLOW,
        FAILED_NEGATIVE_VALUE,
        FAILED_WRONG_INSTANCE_TYPE
    }

    /**
     * Returns the scale factor for this bank.
     * For item banks its always 1, for money banks its 100.
     * @return The scale factor for this bank.
     */
    int getItemFractionScaleFactor();


    /**
     * Returns minimalistic data about this bank.
     * Can be requested by the client using the ClientBankManager.
     *
     * @return Datapacket containing minimal data about this bank.
     */
    MinimalBankData getMinimalData();

    /**
     * Gets the currently free available balance of this bank.
     *
     * @return The currently free available balance of this bank.
     */
    long getBalance();

    /**
     * Gets the currently locked balance of this bank.
     * This is the amount that is currently locked and cannot be used for transactions.
     * Locked balance can exist for example when the amount is reserved for a transaction that is not yet completed
     * to prevent double spending.
     * <p>
     * For example, the StockMarketMod uses this to lock funds when a player creates a buy order which is not yet fulfilled.
     *
     * @return The currently locked balance of this bank.
     */
    long getLockedBalance();

    /**
     * Gets the total balance of this bank.
     * This is the sum of the available balance and the locked balance.
     *
     * @return The total balance of this bank.
     */
    long getTotalBalance();

    /**
     * Gets the type of this bank.
     *
     * @return The type of this bank.
     */
    ItemID getItemID();

    /**
     * Gets the name of the item associated with this bank.
     * This is used for display/logging purposes.
     *
     * @return The name of the item associated with this bank.
     */
    String getItemName();

    /**
     * Sets the balance of this bank.
     * Negative values are ignored and will not change the balance.
     * If the new target 'balance' is less then the currently locked balance,
     * then the balance of the bank account will be set to 0 and the locked amount will be set to the 'balance' parameter amount.
     * This can have an effect for other mods like the StockMarketMod, because it locked some amount which is now no more available.
     * Other mods must therefore check if the locked amount still contains as much as needed to fulfill the transaction it
     * locked the money beforehand.
     *
     * @param balance The new balance to set. 
     * @return true if the balance was set successfully,
     *         false if the balance is negative or if it ran in the case where it was to force the locked amount to be reduced.
     */
    boolean setBalance(long balance);

    /**
     * Gets the UUID of the player who owns this bank.
     *
     * @return The UUID of the player who owns this bank.
     */
    UUID getPlayerUUID();

    /**
     * Gets the ServerPlayer who owns this bank.
     *
     * @return The player who owns this bank, or null if the player is not online.
     */
    @Nullable ServerPlayer getUser();

    /**
     * Gets the name of the player who owns this bank.
     *
     * @return The name of the player who owns this bank.
     */
    String getPlayerName();

    /**
     * Deposits an amount of money or items into this bank.
     *
     * @param amount The amount to deposit. 
     * @return Status indicating the result of the deposit operation.
     *         If the status is not Status.SUCCESS, then nothing has changed in the bank.
     */
    Status deposit(long amount);


    /**
     * Checks if the bank has sufficient funds to perform a transaction.
     * It only checks for the available balance, not the locked balance.
     *
     * @param amount The amount to check. 
     * @return true if the bank has sufficient funds, false otherwise.
     */
    boolean hasSufficientFunds(long amount);

    /**
     * Withdraws an amount of money or items from this bank.
     *
     * @param amount The amount to withdraw. 
     * @return Status indicating the result of the withdrawal operation.
     *         If the status is not Status.SUCCESS, then nothing has changed in the bank.
     */
    Status withdraw(long amount);

    /**
     * Withdraws an amount of money or items from this bank, but only if the amount is locked.
     * This is useful for transactions that require the amount to be locked before the withdrawal.
     *
     * @param amount The amount to withdraw. 
     * @return Status indicating the result of the withdrawal operation.
     *         If the status is not Status.SUCCESS, then nothing has changed in the bank.
     */
    Status withdrawLocked(long amount);

    /**
     * Withdraws an amount of money or items from this bank, but first uses the locked balance and only
     * if the locked amount is not enough, it will try to withdraw the amount from the available balance.
     * This is useful for transactions that require the amount to be locked before the withdrawal.
     * Use this function prefered over the 'withdrawLocked' function, because it will not fail
     * if the amount is not locked. (can happen because of the 'setBalance' function)
     *
     * @param amount The amount to withdraw. 
     * @return Status indicating the result of the withdrawal operation.
     *         If the status is not Status.SUCCESS, then nothing has changed in the bank.
     */
    Status withdrawLockedPrefered(long amount);

    /**
     * Transfers an amount of money or items from this bank to another bank.
     *
     * @param amount The amount to transfer. 
     * @param other  The bank to transfer the amount to.
     * @return Status indicating the result of the transfer operation.
     *         If the status is not Status.SUCCESS, then nothing has changed in both banks.
     */
    Status transfer(long amount, IBank other);

    /**
     * Transfers an amount of money or items from this bank to another bank, but only if the amount is locked.
     * This is useful for transactions that require the amount to be locked before the transfer.
     *
     * @param amount The amount to transfer. 
     * @param other  The bank to transfer the amount to.
     * @return Status indicating the result of the transfer operation.
     *         If the status is not Status.SUCCESS, then nothing has changed in both banks.
     */
    Status transferFromLocked(long amount, @NotNull IBank other);

    /**
     * Transfers an amount of money or items from this bank to another bank, but first uses the locked balance and only
     * if the locked amount is not enough, it will try to transfer the amount from the available balance.
     * This is useful for transactions that require the amount to be locked before the transfer.
     * Use this function prefered over the 'transferFromLocked' function, because it will not fail
     * if the amount is not locked. (can happen because of the 'setBalance' function)
     *
     * @param amount The amount to transfer. 
     * @param other  The bank to transfer the amount to.
     * @return Status indicating the result of the transfer operation.
     *         If the status is not Status.SUCCESS, then nothing has changed in both banks.
     */
    Status transferFromLockedPrefered(long amount, @NotNull IBank other);

    /**
     * Locks an amount of money or items in this bank.
     * This is useful for transactions that require the amount to be locked before the transaction.
     *
     * @param amount The amount to lock. 
     * @return Status indicating the result of the lock operation.
     *         If the status is not Status.SUCCESS, then nothing has changed in the bank.
     */
    Status lockAmount(long amount);

    /**
     * Unlocks an amount of money or items in this bank.
     * This is useful for transactions that require the amount to be locked before the transaction and
     * then was cancelled.
     *
     * @param amount The amount to unlock. 
     * @return Status indicating the result of the unlock operation.
     *         If the status is not Status.SUCCESS, then nothing has changed in the bank.
     */
    Status unlockAmount(long amount) ;

    /**
     * Unlocks all locked amounts in this bank.
     * This is useful if all transactions are cancelled that were locking amounts in this bank.
     */
    void unlockAll();


    /**
     * Converts the given amount to the raw amount the bank uses internally as "balance".
     * Items can be scaled by a factor. The bank does not store item counts as float/double values,
     * instead a fixed point representation is used.
     * @param realAmount The real amount is that amount a user sees in the bank GUI.
     * @return The raw amount that the bank uses internally.
     */
    long convertToRawAmount(float realAmount);

    /**
     * Converts the given raw amount to the real amount the user sees in the bank GUI.
     * Items can be scaled by a factor. The bank does not store item counts as float/double values,
     * instead a fixed point representation is used.
     * @param rawAmount The raw amount is that amount the bank uses internally as "balance".
     * @return The real amount that the user sees in the bank GUI.
     */
    float convertToRealAmount(long rawAmount);


    /**
     * @return the normalized balance of this bank.
     *         This is used for display purposes
     */
    String getNormalizedBalance();

    /**
     * @return the normalized locked balance of this bank.
     *         This is used for display purposes.
     */
    String getNormalizedLockedBalance();

    /**
     * @return the normalized total balance of this bank.
     *         This is used for display purposes.
     */
    String getNormalizedTotalBalance();

    /**
     * @return the formatted balance of this bank.
     *         This is used for display purposes.
     */
    String getFormattedBalance();

    /**
     * @return the formatted locked balance of this bank.
     *         This is used for display purposes.
     */
    String getFormattedLockedBalance();

    /**
     * @return the formatted total balance of this bank.
     *         This is used for display purposes.
     */
    String getFormattedTotalBalance();

    /**
     * Normalizes the given amount to a string representation.
     * This is used for display purposes in the bank GUI.
     * The amount is normalized to a fixed point representation.
     *
     *  depending on the exponent of the amount add a "k", "M", "G", "T", "P", "E", "Z", "Y"
     *  1.0e3 = 1k
     *  1.0e6 = 1M
     *  1.0e9 = 1G
     *  1.0e12 = 1T
     *  1.0e15 = 1P
     *  1.0e18 = 1E
     *
     * @param realAmount The real amount to normalize.
     * @return A string representation of the normalized amount.
     */
    String getNormalizedAmount(float realAmount);

    /**
     * Normalizes the given raw amount to a string representation.
     * This is used for display purposes in the bank GUI.
     * The amount is normalized to a fixed point representation.
     *
     *  depending on the exponent of the amount add a "k", "M", "G", "T", "P", "E", "Z", "Y"
     *  1.0e3 = 1k
     *  1.0e6 = 1M
     *  1.0e9 = 1G
     *  1.0e12 = 1T
     *  1.0e15 = 1P
     *  1.0e18 = 1E
     *
     * @param rawAmount The raw amount to normalize.
     * @return A string representation of the normalized amount.
     */
    String getNormalizedAmount(long rawAmount);


    /**
     * Formats the given amount to a string representation.
     * This is used for display purposes in the bank GUI.
     * The amount is formatted to a fixed point representation.
     *
     * A value of 15649864 will be formatted to "15'649'864"
     *
     * @param realAmount The real amount to format.
     * @return A string representation of the formatted amount.
     */
    String getFormattedAmount(float realAmount);

    /**
     * Formats the given raw amount to a string representation.
     * This is used for display purposes in the bank GUI.
     * The amount is formatted to a fixed point representation.
     *
     * A value of 15649864 will be formatted to "15'649'864"
     *
     * @param rawAmount The raw amount to format.
     * @return A string representation of the formatted amount.
     */
    String getFormattedAmount(long rawAmount);


    /**
     * Stringifies bank data.
     * @return A string representation of the bank data, including the owner and item ID.
     */
    String toString();

    /**
     * Stringifies bank data without the owner.
     * This is used for logging and debugging purposes.
     *
     * @return A string representation of the bank data without the owner.
     */
    String toStringNoOwner();


    /**
     * Converts this bank data to a JSON representation.
     *
     * @return A JsonElement representing this bank.
     */
    JsonElement toJson();

    /**
     * Converts this bank data to a JSON string representation.
     *
     * @return A string representation of the bank data in JSON format.
     */
    String toJsonString();
}
