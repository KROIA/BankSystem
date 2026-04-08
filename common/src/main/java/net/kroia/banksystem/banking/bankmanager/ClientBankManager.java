package net.kroia.banksystem.banking.bankmanager;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankmanager.IAsyncBankManager;
import net.kroia.banksystem.api.bankmanager.IClientBankManager;
import net.kroia.banksystem.banking.bankaccount.SyncServerBankAccount;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankManagerData;
import net.kroia.banksystem.banking.clientdata.ItemInfoData;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.request.*;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ClientBankManager implements IClientBankManager {

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
        SyncServerBankAccount.setBackend(backend);
    }

    private final IAsyncBankManager asyncServerBankManager;

    /*private AsyncServerBankManagerForwardingRequest forwardingRequest()
    {
        return BankSystemNetworking.ASYNC_SERVER_BANK_MANAGER_FORWARDING_REQUEST;
    }*/

    public ClientBankManager()
    {
        asyncServerBankManager = AsyncBankManager.createClientManager();
    }

    //private final int bankAccountNumber; // This is a placeholder, as the client does not have a bank account number





    /*@Override
    public void requestMinimalBankData(UUID playerUUID, ItemID itemID, Consumer<@Nullable BankData> callback)
    {
        MinimalBankDataRequest.InputType input = new MinimalBankDataRequest.InputType(playerUUID, itemID);
        BankSystemNetworking.MINIMAL_BANK_DATA_REQUEST.sendRequestToServer(input, callback);
    }*/

    @Override
    public CompletableFuture<@Nullable BankAccountData> requestBankAccountData(int accountNumber)
    {
        BankAccountDataRequest.InputData input = new BankAccountDataRequest.InputData(accountNumber);
        return BankSystemNetworking.BANK_ACCOUNT_DATA_REQUEST.sendRequestToServer(input);
    }
    @Override
    public CompletableFuture<@Nullable BankAccountData> requestPersonalBankAccountData(UUID playerUUID)
    {
        BankAccountDataRequest.InputData input = new BankAccountDataRequest.InputData(playerUUID);
        return BankSystemNetworking.BANK_ACCOUNT_DATA_REQUEST.sendRequestToServer(input);
    }

    @Override
    public CompletableFuture<@Nullable BankManagerData> requestBankManagerData()
    {
        return asyncServerBankManager.getBankManagerDataAsync();
        //CompletableFuture<BankManagerData> future = new CompletableFuture<>();
        /*AsyncBankManager.InputData inputData = new AsyncBankManager.InputData(AsyncBankManager.FunctionType.GetBankManagerDataAsync);
        CompletableFuture<AsyncBankManager.OutputData> outputDataFuture = AsyncBankManager.forwardingRequest().sendRequestToServer(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));*/
        //future.complete(null);
        //return future;
        //return BankSystemNetworking.BANK_MANAGER_DATA_REQUEST.sendRequestToServer(0);
    }

    @Override
    public CompletableFuture<@Nullable ItemInfoData> requestItemInfoData(ItemID itemID)
    {
        return BankSystemNetworking.ITEM_INFO_REQUEST.sendRequestToServer(itemID);
    }

    @Override
    public CompletableFuture<Boolean> requestAllowItem(ItemID itemID)
    {
        AllowItemRequest.Data requestData = new AllowItemRequest.Data(itemID);
        return BankSystemNetworking.ALLOW_ITEM_REQUEST.sendRequestToServer(requestData);
    }

    @Override
    public CompletableFuture<Boolean> requestDisallowItem(ItemID itemID)
    {
        return BankSystemNetworking.DISALLOW_ITEM_REQUEST.sendRequestToServer(itemID);
    }

    @Override
    public CompletableFuture<List<ItemID>> requestRemoveEmptyBanks(int accountNumber)
    {
        return BankSystemNetworking.REMOVE_EMPTY_BANKS_REQUEST.sendRequestToServer(accountNumber);
    }



    @Override
    public CompletableFuture<List<Integer>> requestBankAccountNumbers(UUID playerUUID)
    {
        return BankSystemNetworking.BANK_ACCOUNT_NUMBERS_REQUEST.sendRequestToServer(List.of(playerUUID));
    }

    @Override
    public CompletableFuture<List<Integer>> requestBankAccountNumbers(List<UUID> playerUUIDs)
    {
        return BankSystemNetworking.BANK_ACCOUNT_NUMBERS_REQUEST.sendRequestToServer(playerUUIDs);
    }

    @Override
    public CompletableFuture<BankAccountData> requestUpdateBankAccount(UpdateBankAccountRequest.InputData inputData)
    {
        return BankSystemNetworking.UPDATE_BANK_ACCOUNT_REQUEST.sendRequestToServer(inputData);
    }

    @Override
    public CompletableFuture<BankTerminalBlockDataRequest.Output> requestBankTerminalData(BlockPos pos) {
        return BankSystemNetworking.BANK_TERMINAL_BLOCK_DATA_REQUEST.sendRequestToServer(pos);
    }

    @Override
    public CompletableFuture<BankSelectionScreenDataRequest.Output> requestBankAccounts(UUID playerUUID) {
        return BankSystemNetworking.BANK_SELECTION_SCREEN_DATA_REQUEST.sendRequestToServer(playerUUID);
    }


    @Override
    public CompletableFuture<Boolean> requestDeleteBankAccount(int accountNumber) {
        return BankSystemNetworking.DELETE_BANK_ACCOUNT_REQUEST.sendRequestToServer(accountNumber);
    }

    @Override
    public CompletableFuture<List<ItemID>> requestAllowdItems() {
        return BankSystemNetworking.ALLOWED_ITEMS_REQUEST.sendRequestToServer(0);
    }
}
