package net.kroia.banksystem.api;

import net.kroia.banksystem.banking.clientdata.ItemInfoData;
import net.kroia.banksystem.banking.clientdata.MinimalBankData;
import net.kroia.banksystem.banking.clientdata.MinimalBankManagerData;
import net.kroia.banksystem.banking.clientdata.MinimalBankUserData;
import net.kroia.banksystem.util.ItemID;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

//@Environment(EnvType.CLIENT)
public interface IClientBankManager {

    /**
     * Requests minimal bank data for a specific player and item.
     * Returns the data through a callback.
     * <p>
     * Bank data for another player will only be returned if the player is allowed to view it. (Admin)
     *
     * @warning
     * The resulting MinimalBankData may be null if the player is not allowed to view the bank data of the specified player.
     *
     * @param playerUUID The UUID of the player.
     * @param itemID     The ID of the item.
     * @param callback   A callback that receives the MinimalBankData.
     */
    void requestMinimalBankData(UUID playerUUID, ItemID itemID, Consumer<@Nullable MinimalBankData> callback);

    /**
     * Requests minimal bank user data for a specific player.
     * Returns the data through a callback.
     *
     * @warning
     * The resulting MinimalBankUserData may be null if the player is not allowed to view the bank data of the specified player.
     *
     * @param playerUUID The UUID of the player.
     * @param callback   A callback that receives the MinimalBankUserData.
     */
    void requestMinimalBankUserData(UUID playerUUID, Consumer<@Nullable  MinimalBankUserData> callback);

    /**
     * Requests minimal bank manager data.
     * Returns the data through a callback.
     *
     * @warning
     * The resulting MinimalBankManagerData may be null if the player is not allowed to view the bank data of the specified player.
     *
     * @param callback A callback that receives the MinimalBankManagerData.
     */
    void requestMinimalBankManagerData(Consumer<@Nullable MinimalBankManagerData> callback);

    /**
     * Requests item information data for a specific item.
     * Returns the data through a callback.
     *
     * @warning
     * The resulting ItemInfoData may be null if the player is not allowed to view the bank data of the specified player.
     *
     * @param itemID   The ID of the item.
     * @param callback A callback that receives the ItemInfoData.
     */
    void requestItemInfoData(ItemID itemID, Consumer<@Nullable ItemInfoData> callback);

    /**
     * Requests to the server to allow an item to be used in the bank system.
     * Returns a Boolean indicating success or failure.
     *
     * @param itemID   The ID of the item to allow.
     * @param callback A callback that receives a Boolean indicating success or failure.
     */
    void requestAllowItem(ItemID itemID, Consumer<Boolean> callback);

    /**
     * Requests to the server to forbid an item to be used in the bank system.
     * Returns a Boolean indicating success or failure.
     *
     * @param itemID   The ID of the item to disallow.
     * @param callback A callback that receives a Boolean indicating success or failure.
     */
    void requestDisallowItem(ItemID itemID, Consumer<Boolean> callback);


    /**
     * Requests to the server to remove empty banks for a specific player.
     * Returns a list of ItemIDs of the removed banks through a callback.
     * Only admins can remove banks of other players.
     *
     * @param player The UUID of the player whose empty banks should be removed.
     * @param callback A callback that receives a list of ItemIDs of the removed banks.
     */
    void requestRemoveEmptyBanks(UUID player, Consumer<List<ItemID>> callback);

    /**
     * Requests to the server to remove empty banks for the current player.
     * Returns a list of ItemIDs of the removed banks through a callback.
     *
     * @param callback A callback that receives a list of ItemIDs of the removed banks.
     */
    void requestRemoveEmptyBanks(Consumer<List<ItemID>> callback);


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
    void requestItemFractionScaleFactor(ItemID itemID, Consumer<Integer> callback);
}
