package net.kroia.banksystem.banking;

import com.google.gson.JsonElement;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.IAsyncServerBankManager;
import net.kroia.banksystem.api.IBank;
import net.kroia.banksystem.api.IBankAccount;
import net.kroia.banksystem.banking.clientdata.BankManagerData;
import net.kroia.banksystem.banking.clientdata.ItemInfoData;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class AsyncServerBankManager implements IAsyncServerBankManager {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
        BankAccount.setBackend(backend);
    }




    @Override
    public CompletableFuture<BankManagerData> getBankManagerDataAsync() {
        return null;
    }

    @Override
    public CompletableFuture<BankManagerData.UserMapData> getBankManagerUserMapDataAsync() {
        return null;
    }

    @Override
    public CompletableFuture<BankManagerData.BankAccountsData> getBankManagerBankAccountsDataAsync() {
        return null;
    }

    @Override
    public CompletableFuture<List<ItemID>> getAllowedItemsAsync() {
        return null;
    }

    @Override
    public CompletableFuture<List<ItemID>> getBlacklistedItemsAsync() {
        return null;
    }

    @Override
    public CompletableFuture<List<ItemID>> getNotRemovableItemsAsync() {
        return null;
    }

    @Override
    public CompletableFuture<ItemInfoData> getItemInfoDataAsync(@NotNull ItemID itemID) {
        return null;
    }

    @Override
    public void addUserAsync(@NotNull ServerPlayer player) {

    }

    @Override
    public void addUserAsync(@NotNull UUID playerUUID, @NotNull String playerName) {

    }

    @Override
    public void addUserAsync(@NotNull User user) {

    }

    @Override
    public CompletableFuture<Boolean> removeUserAsync(UUID userUUID) {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> userExistsAsync(UUID userUUID) {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable User> getUserByUUIDAsync(UUID userUUID) {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable User> getUserByNameAsync(String name) {
        return null;
    }

    @Override
    public CompletableFuture<IBankAccount> createPersonalBankAccountAsync(UUID user) {
        return null;
    }

    @Override
    public CompletableFuture<IBankAccount> createBankAccountAsync(String accountName) {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable IBankAccount> getBankAccountAsync(int accountNumber) {
        return null;
    }

    @Override
    public CompletableFuture<List<IBankAccount>> getBankAccountsAsync(UUID userUUID) {
        return null;
    }

    @Override
    public CompletableFuture<List<IBankAccount>> getBankAccountsAsync(ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable IBankAccount> getPersonalBankAccountAsync(UUID userUUID) {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable IBankAccount> getPersonalBankAccountAsync(String userName) {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable IBankAccount> getOrCreatePersonalBankAccountAsync(UUID userUUID) {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable IBankAccount> getOrCreatePersonalBankAccountAsync(@NotNull String userName) {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> userHasPersonalBankAccountAsync(UUID userUUID) {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> deleteBankAccountAsync(int accountNumber) {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable IBank> getPersonalBankAsync(UUID owner, ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable IBank> getPersonalBankAsync(String ownerName, ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable IBank> getOrCreatePersonalBankAsync(UUID owner, ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable IBank> getOrCreatePersonalBankAsync(String ownerName, ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> isItemIDAllowedAsync(ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> allowItemIDAsync(ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> disallowItemIDAsync(ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> isItemIDNotRemovableAsync(ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> isItemIDBlacklistedAsync(ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<Double> getRealMoneyCirculationAsync() {
        return null;
    }

    @Override
    public CompletableFuture<Double> getRealLockedMoneyCirculationAsync() {
        return null;
    }

    @Override
    public CompletableFuture<Double> getRealItemCirculationAsync(ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<Double> getRealLockedItemCirculationAsync(ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<JsonElement> getCirculationDataJsonAsync() {
        return null;
    }

    @Override
    public CompletableFuture<String> getCirculationDataJsonStringAsync() {
        return null;
    }

    @Override
    public CompletableFuture<JsonElement> toJsonAsync() {
        return null;
    }

    @Override
    public CompletableFuture<String> toJsonStringAsync() {
        return null;
    }

    @Override
    public void onPlayerJoinAsync(UUID playerUUID, String playerName) {

    }



    private void info(String msg)
    {
        BACKEND_INSTANCES.LOGGER.info("[AsyncServerBankManager] " + msg);
    }
    private void error(String msg)
    {
        BACKEND_INSTANCES.LOGGER.error("[AsyncServerBankManager] " + msg);
    }
    private void error(String msg, Throwable e)
    {
        BACKEND_INSTANCES.LOGGER.error("[AsyncServerBankManager] " + msg, e);
    }
    private void warn(String msg)
    {
        BACKEND_INSTANCES.LOGGER.warn("[AsyncServerBankManager] " + msg);
    }
    private void debug(String msg)
    {
        BACKEND_INSTANCES.LOGGER.debug("[AsyncServerBankManager] " + msg);
    }

}
