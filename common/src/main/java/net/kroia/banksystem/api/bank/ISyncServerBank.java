package net.kroia.banksystem.api.bank;

import com.google.gson.JsonElement;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.util.ItemID;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a bank that can hold money or items for a player.
 * Banks can be used to deposit, withdraw, and transfer funds or items.
 * Each bank is associated with a specific player and item type.
 */
public interface ISyncServerBank {

    /**
     * @return true if the balance has changed since the last reset of the change flag
     */
    boolean hasChanges();

    void clearChangeFlag();


    /**
     * Returns minimalistic data about this bank.
     * Can be requested by the client using the ClientBankManager.
     *
     * @return Datapacket containing minimal data about this bank.
     */
    BankData getMinimalData();

    /**
     * Gets the currently free available balance of this bank.
     * The returned value is the backend value.
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
     * The returned value is the backend value.
     *
     * @return The currently locked balance of this bank.
     */
    long getLockedBalance();

    /**
     * Gets the total balance of this bank.
     * This is the sum of the available balance and the locked balance.
     * The returned value is the backend value.
     *
     * @return The total balance of this bank.
     */
    long getTotalBalance();

    /**
     * Gets the currently free available balance of this bank.
     * The returned value is the scaled backend value.
     *
     * @return The currently free available balance of this bank.
     */
    double getRealBalance();

    /**
     * Gets the total balance of this bank.
     * This is the sum of the available balance and the locked balance.
     * The returned value is the scaled backend value.
     *
     * @return The total balance of this bank.
     */
    double getRealLockedBalance();

    /**
     * Gets the total balance of this bank.
     * This is the sum of the available balance and the locked balance.
     * The returned value is the scaled backend value.
     *
     * @return The total balance of this bank.
     */
    double getRealTotalBalance();

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
     * Sets the balance using the scaled backend value
     * @param balance The new balance to set.
     * @return true if the balance was set successfully,
     *         false if the balance is negative or if it ran in the case where it was to force the locked amount to be reduced.
     */
    boolean setRealBalance(double balance);

    /**
     * Deposits an amount of money or items into this bank.
     *
     * @param amount The amount to deposit. 
     * @return BankStatus indicating the result of the deposit operation.
     *         If the BankStatus is not BankStatus.SUCCESS, then nothing has changed in the bank.
     */
    BankStatus deposit(long amount);

    /**
     * Deposits an amount of money or items into this bank.
     *
     * @param amount The amount to deposit.
     *               The given value is the real amount a user would see: 1.50
     * @return CompletableFuture<BankStatus> indicating the result of the deposit operation.
     *         If the CompletableFuture<BankStatus> is not CompletableFuture<BankStatus>.SUCCESS, then nothing has changed in the bank.
     */
    BankStatus depositReal(double amount);


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
     * @return BankStatus indicating the result of the withdrawal operation.
     *         If the BankStatus is not BankStatus.SUCCESS, then nothing has changed in the bank.
     */
    BankStatus withdraw(long amount);

    /**
     * Withdraws a scaled amount of money or items from this bank.
     *
     * @param amount The amount to withdraw.
     * @return BankStatus indicating the result of the withdrawal operation.
     *         If the BankStatus is not BankStatus.SUCCESS, then nothing has changed in the bank.
     */
    BankStatus withdrawReal(double amount);

    /**
     * Withdraws an amount of money or items from this bank, but only if the amount is locked.
     * This is useful for transactions that require the amount to be locked before the withdrawal.
     *
     * @param amount The amount to withdraw. 
     * @return BankStatus indicating the result of the withdrawal operation.
     *         If the BankStatus is not BankStatus.SUCCESS, then nothing has changed in the bank.
     */
    BankStatus withdrawLocked(long amount);

    /**
     * Withdraws a scaled amount of money or items from this bank, but only if the amount is locked.
     * This is useful for transactions that require the amount to be locked before the withdrawal.
     *
     * @param amount The amount to withdraw.
     * @return BankStatus indicating the result of the withdrawal operation.
     *         If the BankStatus is not BankStatus.SUCCESS, then nothing has changed in the bank.
     */
    BankStatus withdrawLockedReal(double amount);

    /**
     * Withdraws an amount of money or items from this bank, but first uses the locked balance and only
     * if the locked amount is not enough, it will try to withdraw the amount from the available balance.
     * This is useful for transactions that require the amount to be locked before the withdrawal.
     * Use this function prefered over the 'withdrawLocked' function, because it will not fail
     * if the amount is not locked. (can happen because of the 'setBalance' function)
     *
     * @param amount The amount to withdraw. 
     * @return BankStatus indicating the result of the withdrawal operation.
     *         If the BankStatus is not BankStatus.SUCCESS, then nothing has changed in the bank.
     */
    BankStatus withdrawLockedPrefered(long amount);
    BankStatus withdrawLockedPreferedReal(double amount);

    /**
     * Transfers an amount of money or items from this bank to another bank.
     *
     * @param amount The amount to transfer. 
     * @param other  The bank to transfer the amount to.
     * @return BankStatus indicating the result of the transfer operation.
     *         If the BankStatus is not BankStatus.SUCCESS, then nothing has changed in both banks.
     */
    BankStatus transfer(long amount, ISyncServerBank other);
    BankStatus transfer(long amount, int toAccount);
    BankStatus transferReal(double amount, @NotNull ISyncServerBank other);
    BankStatus transferReal(double amount, int toAccount);

    /**
     * Transfers an amount of money or items from this bank to another bank, but only if the amount is locked.
     * This is useful for transactions that require the amount to be locked before the transfer.
     *
     * @param amount The amount to transfer. 
     * @param other  The bank to transfer the amount to.
     * @return BankStatus indicating the result of the transfer operation.
     *         If the BankStatus is not BankStatus.SUCCESS, then nothing has changed in both banks.
     */
    BankStatus transferFromLocked(long amount, @NotNull ISyncServerBank other);
    BankStatus transferFromLocked(long amount, int toAccount);
    BankStatus transferFromLockedReal(double amount, @NotNull ISyncServerBank other);
    BankStatus transferFromLockedReal(double amount, int toAccount);

    /**
     * Transfers an amount of money or items from this bank to another bank, but first uses the locked balance and only
     * if the locked amount is not enough, it will try to transfer the amount from the available balance.
     * This is useful for transactions that require the amount to be locked before the transfer.
     * Use this function preferred over the 'transferFromLocked' function, because it will not fail
     * if the amount is not locked. (can happen because of the 'setBalance' function)
     *
     * @param amount The amount to transfer. 
     * @param other  The bank to transfer the amount to.
     * @return BankStatus indicating the result of the transfer operation.
     *         If the BankStatus is not BankStatus.SUCCESS, then nothing has changed in both banks.
     */
    BankStatus transferFromLockedPrefered(long amount, @NotNull ISyncServerBank other);
    BankStatus transferFromLockedPrefered(long amount, int toAccount);
    BankStatus transferFromLockedPreferedReal(double amount, @NotNull ISyncServerBank other);
    BankStatus transferFromLockedPreferedReal(double amount, int toAccount);

    /**
     * Locks an amount of money or items in this bank.
     * This is useful for transactions that require the amount to be locked before the transaction.
     *
     * @param amount The amount to lock. 
     * @return BankStatus indicating the result of the lock operation.
     *         If the BankStatus is not BankStatus.SUCCESS, then nothing has changed in the bank.
     */
    BankStatus lockAmount(long amount);
    BankStatus lockAmountReal(double amount);

    /**
     * Unlocks an amount of money or items in this bank.
     * This is useful for transactions that require the amount to be locked before the transaction and
     * then was cancelled.
     *
     * @param amount The amount to unlock. 
     * @return BankStatus indicating the result of the unlock operation.
     *         If the BankStatus is not BankStatus.SUCCESS, then nothing has changed in the bank.
     */
    BankStatus unlockAmount(long amount);
    BankStatus unlockAmountReal(double amount);

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
    long convertToRawAmount(double realAmount);

    /**
     * Converts the given raw amount to the real amount the user sees in the bank GUI.
     * Items can be scaled by a factor. The bank does not store item counts as float/double values,
     * instead a fixed point representation is used.
     * @param rawAmount The raw amount is that amount the bank uses internally as "balance".
     * @return The real amount that the user sees in the bank GUI.
     */
    double convertToRealAmount(long rawAmount);

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
    //String getNormalizedAmount(float realAmount);
    String getNormalizedAmount(double realAmount);

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
    String getFormattedAmount(double realAmount);

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
