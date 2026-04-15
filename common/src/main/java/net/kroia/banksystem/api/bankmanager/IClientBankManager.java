package net.kroia.banksystem.api.bankmanager;

import net.kroia.banksystem.banking.clientdata.BankAccountData;
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

//@Environment(EnvType.CLIENT)
public interface IClientBankManager {


    //IAsyncBankManager getAsync();

    CompletableFuture<BankManagerData> getBankManagerDataAsync();
    CompletableFuture<@Nullable BankAccountData> getBankAccountDataAsync(int accountNumber);
    CompletableFuture<@Nullable BankAccountData> getPersonalBankAccountDataAsync(UUID userUUID);
    CompletableFuture<ItemInfoData> getItemInfoDataAsync(@NotNull ItemID itemID);
    CompletableFuture<Boolean> allowItemIDAsync(ItemID itemID);
    CompletableFuture<Boolean> disallowItemIDAsync(ItemID itemID);
    CompletableFuture<List<BankAccountData>> getBankAccountsDataAsync(UUID userUUID);
    CompletableFuture<Boolean> deleteBankAccountAsync(int accountNumber);
    CompletableFuture<Integer> getItemFractionScaleFactorAsync();
    int getItemFractionScaleFactor();
    long convertToRawAmount(double realAmount);
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


    CompletableFuture<@Nullable BankAccountData> requestUpdateBankAccount(UpdateBankAccountRequest.InputData inputData);


    CompletableFuture<BankTerminalBlockDataRequest.Output> requestBankTerminalData(BlockPos pos);
    //CompletableFuture<BankSelectionScreenDataRequest.Output> requestBankAccounts(UUID playerUUID);
    //CompletableFuture<Boolean> requestDeleteBankAccount(int accountNumber);
    CompletableFuture<List<ItemID>> requestAllowdItems();
}
