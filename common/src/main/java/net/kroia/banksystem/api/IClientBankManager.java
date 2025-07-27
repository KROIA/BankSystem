package net.kroia.banksystem.api;

import net.kroia.banksystem.banking.clientdata.ItemInfoData;
import net.kroia.banksystem.banking.clientdata.MinimalBankData;
import net.kroia.banksystem.banking.clientdata.MinimalBankManagerData;
import net.kroia.banksystem.banking.clientdata.MinimalBankUserData;
import net.kroia.banksystem.util.ItemID;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Consumer;

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
     * @param consumer   A callback that receives the MinimalBankData.
     */
    void requestMinimalBankData(UUID playerUUID, ItemID itemID, Consumer<@Nullable MinimalBankData> consumer);

    /**
     * Requests minimal bank user data for a specific player.
     * Returns the data through a callback.
     *
     * @warning
     * The resulting MinimalBankUserData may be null if the player is not allowed to view the bank data of the specified player.
     *
     * @param playerUUID The UUID of the player.
     * @param consumer   A callback that receives the MinimalBankUserData.
     */
    void requestMinimalBankUserData(UUID playerUUID, Consumer<@Nullable  MinimalBankUserData> consumer);

    /**
     * Requests minimal bank manager data.
     * Returns the data through a callback.
     *
     * @warning
     * The resulting MinimalBankManagerData may be null if the player is not allowed to view the bank data of the specified player.
     *
     * @param consumer A callback that receives the MinimalBankManagerData.
     */
    void requestMinimalBankManagerData(Consumer<@Nullable MinimalBankManagerData> consumer);

    /**
     * Requests item information data for a specific item.
     * Returns the data through a callback.
     *
     * @warning
     * The resulting ItemInfoData may be null if the player is not allowed to view the bank data of the specified player.
     *
     * @param itemID   The ID of the item.
     * @param consumer A callback that receives the ItemInfoData.
     */
    void requestItemInfoData(ItemID itemID, Consumer<@Nullable ItemInfoData> consumer);

    /**
     * Requests to the server to allow an item to be used in the bank system.
     * Returns a Boolean indicating success or failure.
     *
     * @param itemID   The ID of the item to allow.
     * @param consumer A callback that receives a Boolean indicating success or failure.
     */
    void requestAllowItem(ItemID itemID, Consumer<Boolean> consumer);

    /**
     * Requests to the server to forbid an item to be used in the bank system.
     * Returns a Boolean indicating success or failure.
     *
     * @param itemID   The ID of the item to disallow.
     * @param consumer A callback that receives a Boolean indicating success or failure.
     */
    void requestDisallowItem(ItemID itemID, Consumer<Boolean> consumer);
}
