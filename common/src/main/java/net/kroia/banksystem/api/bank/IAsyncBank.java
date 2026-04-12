package net.kroia.banksystem.api.bank;

import com.google.gson.JsonElement;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.util.ItemID;

import java.util.concurrent.CompletableFuture;

/**
 * Represents a bank that can hold money or items for a player.
 * Banks can be used to deposit, withdraw, and transfer funds or items.
 * Each bank is associated with a specific player and item type.
 */
public interface IAsyncBank {

    /**
     * Returns minimalistic data about this bank.
     * Can be requested by the client using the ClientBankManager.
     *
     * @return Datapacket containing minimal data about this bank.
     */
    CompletableFuture<BankData> getMinimalDataAsync();

    /**
     * Gets the currently free available balance of this bank.
     * The returned value is the backend value.
     *
     * @return The currently free available balance of this bank.
     */
    CompletableFuture<Long> getBalanceAsync();

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
    CompletableFuture<Long> getLockedBalanceAsync();

    /**
     * Gets the total balance of this bank.
     * This is the sum of the available balance and the locked balance.
     * The returned value is the backend value.
     *
     * @return The total balance of this bank.
     */
    CompletableFuture<Long> getTotalBalanceAsync();

    /**
     * Gets the currently free available balance of this bank.
     * The returned value is the scaled backend value.
     *
     * @return The currently free available balance of this bank.
     */
    CompletableFuture<Double> getRealBalanceAsync();

    /**
     * Gets the total balance of this bank.
     * This is the sum of the available balance and the locked balance.
     * The returned value is the scaled backend value.
     *
     * @return The total balance of this bank.
     */
    CompletableFuture<Double> getRealLockedBalanceAsync();

    /**
     * Gets the total balance of this bank.
     * This is the sum of the available balance and the locked balance.
     * The returned value is the scaled backend value.
     *
     * @return The total balance of this bank.
     */
    CompletableFuture<Double> getRealTotalBalanceAsync();

    /**
     * Gets the type of this bank.
     *
     * @return The type of this bank.
     */
    ItemID getItemIDAsync();

    /**
     * Gets the name of the item associated with this bank.
     * This is used for display/logging purposes.
     *
     * @return The name of the item associated with this bank.
     */
    CompletableFuture<String> getItemNameAsync();

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
    CompletableFuture<Boolean> setBalanceAsync(long balance);

    /**
     * Sets the balance using the scaled backend value
     * @param balance The new balance to set.
     * @return true if the balance was set successfully,
     *         false if the balance is negative or if it ran in the case where it was to force the locked amount to be reduced.
     */
    CompletableFuture<Boolean> setRealBalanceAsync(double balance);

    /**
     * Deposits an amount of money or items into this bank.
     *
     * @param amount The amount to deposit.
     *               The given value is the backend value. Example: 150 for a real amount of 1.50
     * @return CompletableFuture<BankStatus> indicating the result of the deposit operation.
     *         If the CompletableFuture<BankStatus> is not CompletableFuture<BankStatus>.SUCCESS, then nothing has changed in the bank.
     */
    CompletableFuture<BankStatus> depositAsync(long amount);

    /**
     * Deposits an amount of money or items into this bank.
     *
     * @param amount The amount to deposit.
     *               The given value is the real amount a user would see: 1.50
     * @return CompletableFuture<BankStatus> indicating the result of the deposit operation.
     *         If the CompletableFuture<BankStatus> is not CompletableFuture<BankStatus>.SUCCESS, then nothing has changed in the bank.
     */
    CompletableFuture<BankStatus> depositRealAsync(double amount);


    /**
     * Checks if the bank has sufficient funds to perform a transaction.
     * It only checks for the available balance, not the locked balance.
     *
     * @param amount The amount to check. 
     * @return true if the bank has sufficient funds, false otherwise.
     */
    CompletableFuture<Boolean> hasSufficientFundsAsync(long amount);

    /**
     * Withdraws an amount of money or items from this bank.
     *
     * @param amount The amount to withdraw. 
     * @return CompletableFuture<BankStatus> indicating the result of the withdrawal operation.
     *         If the CompletableFuture<BankStatus> is not CompletableFuture<BankStatus>.SUCCESS, then nothing has changed in the bank.
     */
    CompletableFuture<BankStatus> withdrawAsync(long amount);

    /**
     * Withdraws a scaled amount of money or items from this bank.
     *
     * @param amount The amount to withdraw.
     * @return BankStatus indicating the result of the withdrawal operation.
     *         If the BankStatus is not BankStatus.SUCCESS, then nothing has changed in the bank.
     */
    CompletableFuture<BankStatus> withdrawRealAsync(double amount);

    /**
     * Withdraws an amount of money or items from this bank, but only if the amount is locked.
     * This is useful for transactions that require the amount to be locked before the withdrawal.
     *
     * @param amount The amount to withdraw. 
     * @return CompletableFuture<BankStatus> indicating the result of the withdrawal operation.
     *         If the CompletableFuture<BankStatus> is not CompletableFuture<BankStatus>.SUCCESS, then nothing has changed in the bank.
     */
    CompletableFuture<BankStatus> withdrawLockedAsync(long amount);

    /**
     * Withdraws a scaled amount of money or items from this bank, but only if the amount is locked.
     * This is useful for transactions that require the amount to be locked before the withdrawal.
     *
     * @param amount The amount to withdraw.
     * @return BankStatus indicating the result of the withdrawal operation.
     *         If the BankStatus is not BankStatus.SUCCESS, then nothing has changed in the bank.
     */
    CompletableFuture<BankStatus> withdrawLockedRealAsync(double amount);

    /**
     * Withdraws an amount of money or items from this bank, but first uses the locked balance and only
     * if the locked amount is not enough, it will try to withdraw the amount from the available balance.
     * This is useful for transactions that require the amount to be locked before the withdrawal.
     * Use this function prefered over the 'withdrawLocked' function, because it will not fail
     * if the amount is not locked. (can happen because of the 'setBalance' function)
     *
     * @param amount The amount to withdraw. 
     * @return CompletableFuture<BankStatus> indicating the result of the withdrawal operation.
     *         If the CompletableFuture<BankStatus> is not CompletableFuture<BankStatus>.SUCCESS, then nothing has changed in the bank.
     */
    CompletableFuture<BankStatus> withdrawLockedPreferedAsync(long amount);
    CompletableFuture<BankStatus> withdrawLockedPreferedRealAsync(double amount);

    /**
     * Transfers an amount of money or items from this bank to another bank.
     *
     * @param amount The amount to transfer. 
     * @param toAccount  The bank account to transfer the amount to.
     * @return CompletableFuture<BankStatus> indicating the result of the transfer operation.
     *         If the CompletableFuture<BankStatus> is not CompletableFuture<BankStatus>.SUCCESS, then nothing has changed in both banks.
     */
    CompletableFuture<BankStatus> transferAsync(long amount, int toAccount);
    CompletableFuture<BankStatus> transferRealAsync(double amount, int toAccount);

    /**
     * Transfers an amount of money or items from this bank to another bank, but only if the amount is locked.
     * This is useful for transactions that require the amount to be locked before the transfer.
     *
     * @param amount The amount to transfer. 
     * @param toAccount  The bank account to transfer the amount to.
     * @return CompletableFuture<BankStatus> indicating the result of the transfer operation.
     *         If the CompletableFuture<BankStatus> is not CompletableFuture<BankStatus>.SUCCESS, then nothing has changed in both banks.
     */
    CompletableFuture<BankStatus> transferFromLockedAsync(long amount, int toAccount);
    CompletableFuture<BankStatus> transferFromLockedRealAsync(double amount, int toAccount);



    /**
     * Transfers an amount of money or items from this bank to another bank, but first uses the locked balance and only
     * if the locked amount is not enough, it will try to transfer the amount from the available balance.
     * This is useful for transactions that require the amount to be locked before the transfer.
     * Use this function prefered over the 'transferFromLocked' function, because it will not fail
     * if the amount is not locked. (can happen because of the 'setBalance' function)
     *
     * @param amount The amount to transfer. 
     * @param toAccount  The bank to transfer the amount to.
     * @return CompletableFuture<BankStatus> indicating the result of the transfer operation.
     *         If the CompletableFuture<BankStatus> is not CompletableFuture<BankStatus>.SUCCESS, then nothing has changed in both banks.
     */
    CompletableFuture<BankStatus> transferFromLockedPreferedAsync(long amount, int toAccount);
    CompletableFuture<BankStatus> transferFromLockedPreferedRealAsync(double amount, int toAccount);



    /**
     * Locks an amount of money or items in this bank.
     * This is useful for transactions that require the amount to be locked before the transaction.
     *
     * @param amount The amount to lock. 
     * @return CompletableFuture<BankStatus> indicating the result of the lock operation.
     *         If the CompletableFuture<BankStatus> is not CompletableFuture<BankStatus>.SUCCESS, then nothing has changed in the bank.
     */
    CompletableFuture<BankStatus> lockAmountAsync(long amount);
    CompletableFuture<BankStatus> lockAmountRealAsync(double amount);

    /**
     * Unlocks an amount of money or items in this bank.
     * This is useful for transactions that require the amount to be locked before the transaction and
     * then was cancelled.
     *
     * @param amount The amount to unlock. 
     * @return CompletableFuture<BankStatus> indicating the result of the unlock operation.
     *         If the CompletableFuture<BankStatus> is not CompletableFuture<BankStatus>.SUCCESS, then nothing has changed in the bank.
     */
    CompletableFuture<BankStatus> unlockAmountAsync(long amount);
    CompletableFuture<BankStatus> unlockAmountRealAsync(double amount);

    /**
     * Unlocks all locked amounts in this bank.
     * This is useful if all transactions are cancelled that were locking amounts in this bank.
     */
    void unlockAllAsync();


    /**
     * Converts the given amount to the raw amount the bank uses internally as "balance".
     * Items can be scaled by a factor. The bank does not store item counts as float/double values,
     * instead a fixed point representation is used.
     * @param realAmount The real amount is that amount a user sees in the bank GUI.
     * @return The raw amount that the bank uses internally.
     */
    CompletableFuture<Long> convertToRawAmountAsync(double realAmount);

    /**
     * Converts the given raw amount to the real amount the user sees in the bank GUI.
     * Items can be scaled by a factor. The bank does not store item counts as float/double values,
     * instead a fixed point representation is used.
     * @param rawAmount The raw amount is that amount the bank uses internally as "balance".
     * @return The real amount that the user sees in the bank GUI.
     */
    CompletableFuture<Double> convertToRealAmountAsync(long rawAmount);


    /**
     * @return the normalized balance of this bank.
     *         This is used for display purposes
     */
    CompletableFuture<String> getNormalizedBalanceAsync();

    /**
     * @return the normalized locked balance of this bank.
     *         This is used for display purposes.
     */
    CompletableFuture<String> getNormalizedLockedBalanceAsync();

    /**
     * @return the normalized total balance of this bank.
     *         This is used for display purposes.
     */
    CompletableFuture<String> getNormalizedTotalBalanceAsync();

    /**
     * @return the formatted balance of this bank.
     *         This is used for display purposes.
     */
    CompletableFuture<String> getFormattedBalanceAsync();

    /**
     * @return the formatted locked balance of this bank.
     *         This is used for display purposes.
     */
    CompletableFuture<String> getFormattedLockedBalanceAsync();

    /**
     * @return the formatted total balance of this bank.
     *         This is used for display purposes.
     */
    CompletableFuture<String> getFormattedTotalBalanceAsync();

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
    CompletableFuture<String> getNormalizedAmountAsync(double realAmount);

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
    CompletableFuture<String> getNormalizedAmountAsync(long rawAmount);


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
    CompletableFuture<String> getFormattedAmountAsync(double realAmount);

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
    CompletableFuture<String> getFormattedAmountAsync(long rawAmount);


    /**
     * Stringifies bank data.
     * @return A string representation of the bank data, including the owner and item ID.
     */
    CompletableFuture<String> toStringAsync();

    /**
     * Stringifies bank data without the owner.
     * This is used for logging and debugging purposes.
     *
     * @return A string representation of the bank data without the owner.
     */
    CompletableFuture<String> toStringNoOwnerAsync();


    /**
     * Converts this bank data to a JSON representation.
     *
     * @return A JsonElement representing this bank.
     */
    CompletableFuture<JsonElement> toJsonAsync();

    /**
     * Converts this bank data to a JSON string representation.
     *
     * @return A string representation of the bank data in JSON format.
     */
    CompletableFuture<String> toJsonStringAsync();
}
