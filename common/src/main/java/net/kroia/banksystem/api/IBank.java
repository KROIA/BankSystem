package net.kroia.banksystem.api;

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
    long getCentScaleFactor();


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
}
