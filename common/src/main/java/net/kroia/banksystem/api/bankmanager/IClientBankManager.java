package net.kroia.banksystem.api.bankmanager;

import net.kroia.banksystem.data.table.record.BalanceHistoryRecord;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.minecraft.nbt.CompoundTag;
import net.kroia.banksystem.banking.clientdata.BankManagerData;
import net.kroia.banksystem.banking.clientdata.ItemInfoData;
import net.kroia.banksystem.networking.entity.BankTerminalBlockDataRequest;
import net.kroia.banksystem.networking.general.UpdateBankAccountRequest;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Client-side access to bank data of the server the player is connected to ({@code BankSystemAPI.getClientBankManager()}).
 * Every {@code *Async} / {@code request*} call sends a request to that server (which may forward it to the master).
 * The server checks access: account data goes to account members and BankSystem admins; refused reads complete
 * with {@code null} or an empty list, refused writes with {@code false}. A request without an answer fails the
 * future after the request's timeout (see {@code IAsyncBankManager}).
 */
//@Environment(EnvType.CLIENT)
public interface IClientBankManager {


    //IAsyncBankManager getAsync();

    /** Users and accounts; full data for admins, names only for other players. */
    CompletableFuture<BankManagerData> getBankManagerDataAsync();
    /** One account's data, or {@code null} if it does not exist or the player may not read it. */
    CompletableFuture<@Nullable BankAccountData> getBankAccountDataAsync(int accountNumber);
    /** The personal account of {@code userUUID}, or {@code null} (missing or not readable). */
    CompletableFuture<@Nullable BankAccountData> getPersonalBankAccountDataAsync(UUID userUUID);
    /** Bank-wide info about one item (totals, holders). */
    CompletableFuture<ItemInfoData> getItemInfoDataAsync(@NotNull ItemID itemID);
    /** Admin only: allow the item for banking. @return true if it was allowed */
    CompletableFuture<Boolean> allowItemIDAsync(ItemID itemID);
    /** Admin only: disallow the item; every holder's balance of it is cleared (no refund). @return true on success */
    CompletableFuture<Boolean> disallowItemIDAsync(ItemID itemID);
    /** Accounts {@code userUUID} is a member of, filtered to the ones the player may read. */
    CompletableFuture<List<BankAccountData>> getBankAccountsDataAsync(UUID userUUID);
    /** Deletes the account (admin or MANAGE permission). @return true if it was deleted */
    CompletableFuture<Boolean> deleteBankAccountAsync(int accountNumber);
    /** True unless the server this client plays on is a slave the master does not trust (its writes are refused). */
    CompletableFuture<Boolean> isServerTrustedAsync();
    /** Asks the server for its raw units per item ({@code ITEM_FRACTION_SCALE_FACTOR}, money: 100) and caches it. */
    CompletableFuture<Integer> getItemFractionScaleFactorAsync();
    /** The cached raw units per item (default until the server answered). */
    int getItemFractionScaleFactor();
    /** Decimal digits of the scale factor, e.g. 2 for 100. */
    int getItemFractionDigitsCount();
    /** Display amount to raw units with the cached scale factor. */
    long convertToRawAmount(double realAmount);
    /** Raw units to display amount with the cached scale factor. */
    double convertToRealAmount(long rawAmount);


    /**
     * Requests minimal bank data for a specific player and item.
     * Returns the data through a callback.
     * <p>
     * ServerBank data for another player will only be returned if the player is allowed to view it. (Admin)
     *
     * @warning
     * The resulting BankData may be null if the player is not allowed to view the bank data of the specified player.
     *
     * @param playerUUID The UUID of the player.
     * @param itemID     The ID of the item.
     * @param callback   A callback that receives the BankData.
     */
    //void requestMinimalBankData(UUID playerUUID, ItemID itemID, Consumer<@Nullable BankData> callback);

    /**
     * Requests minimal bank user data for a specific player.
     * Returns the data through a callback.
     *
     * @warning
     * The resulting MinimalBankUserData may be null if the player is not allowed to view the bank data of the specified player.
     *
     * @param accountNumber The account number of the bank account.
     */
    //CompletableFuture<@Nullable BankAccountData> requestBankAccountData(int accountNumber);

    //CompletableFuture<@Nullable BankAccountData> requestPersonalBankAccountData(UUID playerUUID);

    /**
     * Requests minimal bank manager data.
     * Returns the data through a callback.
     *
     * @warning
     * The resulting MinimalBankManagerData may be null if the player is not allowed to view the bank data of the specified player.
     *
     */
    //CompletableFuture<@Nullable BankManagerData> requestBankManagerData();

    /**
     * Requests item information data for a specific item.
     * Returns the data through a callback.
     *
     * @warning
     * The resulting ItemInfoData may be null if the player is not allowed to view the bank data of the specified player.
     *
     * @param itemID   The ID of the item.
     */
    //CompletableFuture<@Nullable ItemInfoData> requestItemInfoData(ItemID itemID);

    /**
     * Requests to the server to allow an item to be used in the bank system.
     * Returns a Boolean indicating success or failure.
     *
     * @param itemID   The ID of the item to allow.
     */
    //CompletableFuture<Boolean> requestAllowItem(ItemID itemID);

    /**
     * Requests to the server to forbid an item to be used in the bank system.
     * Returns a Boolean indicating success or failure.
     *
     * @param itemID   The ID of the item to disallow.
     */
    //CompletableFuture<Boolean> requestDisallowItem(ItemID itemID);


    /**
     * Requests to the server to remove empty banks for a specific player.
     * Returns a list of ItemIDs of the removed banks through a callback.
     * Only admins can remove banks of other players.
     *
     * @param accountNumber The account number of the bank account to remove empty banks from.
     */
    CompletableFuture<List<ItemID>> requestRemoveEmptyBanks(int accountNumber);

    /**
     * Requests to the server to remove empty banks for the current player.
     * Returns a list of ItemIDs of the removed banks through a callback.
     *
     * @param callback A callback that receives a list of ItemIDs of the removed banks.
     */
     //void requestRemoveEmptyBanks(Consumer<List<ItemID>> callback);


    /**
     * Requests the item fraction scale factor for a specific item.
     * Returns the scale factor through a callback.
     *
     * By default the scale is 1 but if a item has a scale of 10 for example, that means
     * that to store 1 item in the bank, a value of 10 is stored.
     * That means that a user can have 1/10th of the item in the bank.
     *
     * For money the scale factor is 100, meaning that 1 item in the bank
     * represents 1.00 currency unit, allowing for cents to be stored.
     *
     * @param itemID   The ID of the item.
     * @param callback A callback that receives the scale factor as an Integer.
     */
    // Removed, Use: BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR
    //void requestItemFractionScaleFactor(ItemID itemID, Consumer<Integer> callback);



    //CompletableFuture<List<Integer>> requestBankAccountNumbers(UUID playerUUID);
    //CompletableFuture<List<Integer>> requestBankAccountNumbers(List<UUID> playerUUIDs);


    /** Applies account edits (name, icon, members, permissions; MANAGE needed). @return the new account data, or {@code null} if refused */
    CompletableFuture<@Nullable BankAccountData> requestUpdateBankAccount(UpdateBankAccountRequest.InputData inputData);


    /** Data for the Bank Terminal block at {@code pos} (its selected account and banks). */
    CompletableFuture<BankTerminalBlockDataRequest.Output> requestBankTerminalData(BlockPos pos);
    //CompletableFuture<BankSelectionScreenDataRequest.Output> requestBankAccounts(UUID playerUUID);
    //CompletableFuture<Boolean> requestDeleteBankAccount(int accountNumber);
    /** Every item currently allowed for banking. */
    CompletableFuture<List<ItemID>> requestAllowdItems();

    /**
     * Requests balance-history for an account across the full time-range.
     * Delegates to the 4-arg overload with an all-history sentinel and a
     * default per-item point budget so the wire payload stays bounded on
     * long-running worlds (Task #40).
     *
     * @param accountNumber the account whose history is requested
     */
    CompletableFuture<List<BalanceHistoryRecord>> requestBalanceHistory(int accountNumber);

    /**
     * Requests bucketed balance-history for an account (Task #40). The master
     * server samples at most {@code maxPoints} rows per item series inside
     * the given {@code [fromMs, toMs]} range using bucket-last-sample
     * downsampling.
     *
     * @param accountNumber the account whose history is requested
     * @param fromMs        inclusive lower time bound in epoch millis, or
     *                      {@code BalanceHistoryRequest.Query.ALL_HISTORY_SENTINEL}
     *                      to have the server resolve the earliest row
     * @param toMs          inclusive upper time bound in epoch millis
     * @param maxPoints     per-item point budget (client convention: 500)
     */
    CompletableFuture<List<BalanceHistoryRecord>> requestBalanceHistory(
            int accountNumber, long fromMs, long toMs, int maxPoints);

    /** The player's own UI preferences stored on the server (e.g. chart series visibility). */
    CompletableFuture<CompoundTag> getUserCustomData();
    /** Replaces the player's stored UI preferences. @return true if saved */
    CompletableFuture<Boolean> updateUserCustomData(CompoundTag data);
}
