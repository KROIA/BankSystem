package net.kroia.banksystem.banking.bankmanager;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankmanager.IAsyncBankManager;
import net.kroia.banksystem.api.bankmanager.IClientBankManager;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankManagerData;
import net.kroia.banksystem.banking.clientdata.ItemInfoData;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.entity.BankTerminalBlockDataRequest;
import net.kroia.banksystem.networking.general.UpdateBankAccountRequest;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ClientBankManager implements IClientBankManager {

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
        ServerBankAccount.setBackend(backend);
    }

    private final IAsyncBankManager asyncServerBankManager;

    public ClientBankManager()
    {
        asyncServerBankManager = AsyncBankManager.createClientManager();
    }


    @Override
    public CompletableFuture<BankManagerData> getBankManagerDataAsync()
    {
        return asyncServerBankManager.getBankManagerDataAsync();
    }
    @Override
    public CompletableFuture<@Nullable BankAccountData> getBankAccountDataAsync(int accountNumber)
    {
        return asyncServerBankManager.getBankAccountDataAsync(accountNumber);
    }
    @Override
    public CompletableFuture<@Nullable BankAccountData> getPersonalBankAccountDataAsync(UUID userUUID)
    {
        return asyncServerBankManager.getPersonalBankAccountDataAsync(userUUID);
    }
    @Override
    public CompletableFuture<ItemInfoData> getItemInfoDataAsync(@NotNull ItemID itemID)
    {
        return asyncServerBankManager.getItemInfoDataAsync(itemID);
    }
    @Override
    public CompletableFuture<Boolean> allowItemIDAsync(ItemID itemID)
    {
        return asyncServerBankManager.allowItemIDAsync(itemID);
    }
    @Override
    public CompletableFuture<Boolean> disallowItemIDAsync(ItemID itemID)
    {
        return asyncServerBankManager.disallowItemIDAsync(itemID);
    }
    @Override
    public CompletableFuture<List<BankAccountData>> getBankAccountsDataAsync(UUID userUUID)
    {
        return asyncServerBankManager.getBankAccountsDataAsync(userUUID);
    }
    @Override
    public CompletableFuture<Boolean> deleteBankAccountAsync(int accountNumber)
    {
        return asyncServerBankManager.deleteBankAccountAsync(accountNumber);
    }















    /*@Override
    public CompletableFuture<@Nullable BankAccountData> requestBankAccountData(int accountNumber)
    {
        BankAccountDataRequest.InputData input = new BankAccountDataRequest.InputData(accountNumber);
        return BankSystemNetworking.BANK_ACCOUNT_DATA_REQUEST.sendRequestToServer(input);
    }*/
    //@Override
    //public CompletableFuture<@Nullable BankAccountData> requestPersonalBankAccountData(UUID playerUUID)
    //{
    //    BankAccountDataRequest.InputData input = new BankAccountDataRequest.InputData(playerUUID);
    //    return BankSystemNetworking.BANK_ACCOUNT_DATA_REQUEST.sendRequestToServer(input);
    //}

    //@Override
    //public CompletableFuture<@Nullable BankManagerData> requestBankManagerData()
    //{
    //    return asyncServerBankManager.getBankManagerDataAsync();
    //    //CompletableFuture<BankManagerData> future = new CompletableFuture<>();
    //    /*AsyncBankManager.InputData inputData = new AsyncBankManager.InputData(AsyncBankManager.FunctionType.GetBankManagerDataAsync);
    //    CompletableFuture<AsyncBankManager.OutputData> outputDataFuture = AsyncBankManager.forwardingRequest().sendRequestToServer(inputData);
    //    outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));*/
    //    //future.complete(null);
    //    //return future;
    //    //return BankSystemNetworking.BANK_MANAGER_DATA_REQUEST.sendRequestToServer(0);
    //}

    //@Override
    //public CompletableFuture<@Nullable ItemInfoData> requestItemInfoData(ItemID itemID)
    //{
    //    return BankSystemNetworking.ITEM_INFO_REQUEST.sendRequestToServer(itemID);
    //}

    //@Override
    //public CompletableFuture<Boolean> requestAllowItem(ItemID itemID)
    //{
    //    AllowItemRequest.Data requestData = new AllowItemRequest.Data(itemID);
    //    return BankSystemNetworking.ALLOW_ITEM_REQUEST.sendRequestToServer(requestData);
    //}

    //@Override
    //public CompletableFuture<Boolean> requestDisallowItem(ItemID itemID)
    //{
    //    return BankSystemNetworking.DISALLOW_ITEM_REQUEST.sendRequestToServer(itemID);
    //}

    @Override
    public CompletableFuture<List<ItemID>> requestRemoveEmptyBanks(int accountNumber)
    {
        return BankSystemNetworking.REMOVE_EMPTY_BANKS_REQUEST.sendRequestToServer(accountNumber);
    }



    //@Override
    //public CompletableFuture<List<Integer>> requestBankAccountNumbers(UUID playerUUID)
    //{
    //    return BankSystemNetworking.BANK_ACCOUNT_NUMBERS_REQUEST.sendRequestToServer(List.of(playerUUID));
    //}
//
    //@Override
    //public CompletableFuture<List<Integer>> requestBankAccountNumbers(List<UUID> playerUUIDs)
    //{
    //    return BankSystemNetworking.BANK_ACCOUNT_NUMBERS_REQUEST.sendRequestToServer(playerUUIDs);
    //}

    @Override
    public CompletableFuture<@Nullable BankAccountData> requestUpdateBankAccount(UpdateBankAccountRequest.InputData inputData)
    {
        return BankSystemNetworking.UPDATE_BANK_ACCOUNT_REQUEST.sendRequestToServer(inputData);
    }

    @Override
    public CompletableFuture<BankTerminalBlockDataRequest.Output> requestBankTerminalData(BlockPos pos) {
        return BankSystemNetworking.BANK_TERMINAL_BLOCK_DATA_REQUEST.sendRequestToServer(pos);
    }

   //@Override
   //public CompletableFuture<BankSelectionScreenDataRequest.Output> requestBankAccounts(UUID playerUUID) {
   //    return BankSystemNetworking.BANK_SELECTION_SCREEN_DATA_REQUEST.sendRequestToServer(playerUUID);
   //}


    //@Override
    //public CompletableFuture<Boolean> requestDeleteBankAccount(int accountNumber) {
    //    return BankSystemNetworking.DELETE_BANK_ACCOUNT_REQUEST.sendRequestToServer(accountNumber);
    //}

    @Override
    public CompletableFuture<List<ItemID>> requestAllowdItems() {
        return BankSystemNetworking.ALLOWED_ITEMS_REQUEST.sendRequestToServer(0);
    }
}
