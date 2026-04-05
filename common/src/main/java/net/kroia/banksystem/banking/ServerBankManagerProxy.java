package net.kroia.banksystem.banking;

import com.google.gson.JsonElement;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.IBank;
import net.kroia.banksystem.api.IBankAccount;
import net.kroia.banksystem.api.IServerBankManager;
import net.kroia.banksystem.banking.clientdata.BankManagerData;
import net.kroia.banksystem.banking.clientdata.ItemInfoData;
import net.kroia.banksystem.networking.packet.server_server.PlayerJoinPacket;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ServerBankManagerProxy implements IServerBankManager {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        ServerBankManagerProxy.BACKEND_INSTANCES = backend;
        BankAccount.setBackend(backend);
    }


    @Override
    public CompletableFuture<BankManagerData> getBankManagerData() {
        return null;
    }

    @Override
    public CompletableFuture<BankManagerData.UserMapData> getBankManagerUserMapData() {
        return null;
    }

    @Override
    public CompletableFuture<BankManagerData.BankAccountsData> getBankManagerBankAccountsData() {
        return null;
    }

    @Override
    public CompletableFuture<List<ItemID>> getAllowedItems() {
        return null;
    }

    @Override
    public CompletableFuture<List<ItemID>> getBlacklistedItems() {
        return null;
    }

    @Override
    public CompletableFuture<List<ItemID>> getNotRemovableItems() {
        return null;
    }

    @Override
    public CompletableFuture<ItemInfoData> getItemInfoData(@NotNull ItemID itemID) {
        return null;
    }

    @Override
    public void addUser(@NotNull ServerPlayer player) {

    }

    @Override
    public void addUser(@NotNull UUID playerUUID, @NotNull String playerName) {

    }

    @Override
    public void addUser(@NotNull User user) {

    }

    @Override
    public CompletableFuture<Boolean> removeUser(UUID userUUID) {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> userExists(UUID userUUID) {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable User> getUserByUUID(UUID userUUID) {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable User> getUserByName(String name) {
        return null;
    }

    @Override
    public CompletableFuture<IBankAccount> createPersonalBankAccount(UUID user) {
        return null;
    }

    @Override
    public CompletableFuture<IBankAccount> createBankAccount(String accountName) {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable IBankAccount> getBankAccount(int accountNumber) {
        return null;
    }

    @Override
    public CompletableFuture<List<IBankAccount>> getBankAccounts(UUID userUUID) {
        return null;
    }

    @Override
    public CompletableFuture<List<IBankAccount>> getBankAccounts(ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable IBankAccount> getPersonalBankAccount(UUID userUUID) {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable IBankAccount> getPersonalBankAccount(String userName) {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable IBankAccount> getOrCreatePersonalBankAccount(UUID userUUID) {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable IBankAccount> getOrCreatePersonalBankAccount(@NotNull String userName) {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> userHasPersonalBankAccount(UUID userUUID) {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> deleteBankAccount(int accountNumber) {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable IBank> getPersonalBank(UUID owner, ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable IBank> getPersonalBank(String ownerName, ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable IBank> getOrCreatePersonalBank(UUID owner, ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable IBank> getOrCreatePersonalBank(String ownerName, ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> isItemIDAllowed(ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> allowItemID(ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> disallowItemID(ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> isItemIDNotRemovable(ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> isItemIDBlacklisted(ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<Double> getRealMoneyCirculation() {
        return null;
    }

    @Override
    public CompletableFuture<Double> getRealLockedMoneyCirculation() {
        return null;
    }

    @Override
    public CompletableFuture<Double> getRealItemCirculation(ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<Double> getRealLockedItemCirculation(ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<JsonElement> getCirculationDataJson() {
        return null;
    }

    @Override
    public CompletableFuture<String> getCirculationDataJsonString() {
        return null;
    }

    @Override
    public CompletableFuture<JsonElement> toJson() {
        return null;
    }

    @Override
    public CompletableFuture<String> toJsonString() {
        return null;
    }

    @Override
    public void onPlayerJoin(UUID playerUUID, String playerName)
    {
        CompletableFuture<Boolean> userExistsFuture = this.userExists(playerUUID);
        userExistsFuture.thenAccept(userExists -> {
            if(!userExists) {
                PlayerJoinPacket.sendPacketToMaster(playerUUID, playerName);
            }
        });
    }
}
