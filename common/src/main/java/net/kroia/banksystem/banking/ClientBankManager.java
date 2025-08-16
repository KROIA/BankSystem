package net.kroia.banksystem.banking;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.IClientBankManager;
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
import java.util.function.Consumer;

public class ClientBankManager implements IClientBankManager {

    protected BankSystemModBackend.Instances BACKEND_INSTANCES;

    public ClientBankManager(BankSystemModBackend.Instances backendInstances)
    {
        this.BACKEND_INSTANCES = backendInstances;
    }

    //private final int bankAccountNumber; // This is a placeholder, as the client does not have a bank account number





    /*@Override
    public void requestMinimalBankData(UUID playerUUID, ItemID itemID, Consumer<@Nullable BankData> callback)
    {
        MinimalBankDataRequest.InputType input = new MinimalBankDataRequest.InputType(playerUUID, itemID);
        BankSystemNetworking.MINIMAL_BANK_DATA_REQUEST.sendRequestToServer(input, callback);
    }*/

    @Override
    public void requestBankAccountData(int accountNumber, Consumer<@Nullable BankAccountData> callback)
    {
        BankAccountDataRequest.InputData input = new BankAccountDataRequest.InputData(accountNumber);
        BankSystemNetworking.BANK_ACCOUNT_DATA_REQUEST.sendRequestToServer(input, callback);
    }
    @Override
    public void requestPersonalBankAccountData(UUID playerUUID, Consumer<@Nullable BankAccountData> callback)
    {
        BankAccountDataRequest.InputData input = new BankAccountDataRequest.InputData(playerUUID);
        BankSystemNetworking.BANK_ACCOUNT_DATA_REQUEST.sendRequestToServer(input, callback);
    }

    @Override
    public void requestBankManagerData(Consumer<@Nullable BankManagerData> callback)
    {
        BankSystemNetworking.BANK_MANAGER_DATA_REQUEST.sendRequestToServer(0, callback);
    }

    @Override
    public void requestItemInfoData(ItemID itemID, Consumer<@Nullable ItemInfoData> callback)
    {
        BankSystemNetworking.ITEM_INFO_REQUEST.sendRequestToServer(itemID, callback);
    }

    @Override
    public void requestAllowItem(ItemID itemID, int centScaleFactor, Consumer<Boolean> callback)
    {
        AllowItemRequest.Data requestData = new AllowItemRequest.Data(itemID, centScaleFactor);
        BankSystemNetworking.ALLOW_ITEM_REQUEST.sendRequestToServer(requestData, callback);
    }

    @Override
    public void requestDisallowItem(ItemID itemID, Consumer<Boolean> callback)
    {
        BankSystemNetworking.DISALLOW_ITEM_REQUEST.sendRequestToServer(itemID, callback);
    }

    @Override
    public void requestRemoveEmptyBanks(int accountNumber, Consumer<List<ItemID>> callback)
    {
        BankSystemNetworking.REMOVE_EMPTY_BANKS_REQUEST.sendRequestToServer(accountNumber, callback);
    }

    /*@Override
    public void requestRemoveEmptyBanks(Consumer<List<ItemID>> callback)
    {
        UUID thisPlayer = Minecraft.getInstance().player.getUUID();
        BankSystemNetworking.REMOVE_EMPTY_BANKS_REQUEST.sendRequestToServer(thisPlayer, callback);
    }*/

    @Override
    public void requestItemFractionScaleFactor(ItemID itemID, Consumer<Integer> callback)
    {
        BankSystemNetworking.ITEM_FRACTION_SCALE_FACTOR_REQUEST.sendRequestToServer(itemID, callback);
    }


    public void requestBankAccountNumbers(UUID playerUUID, Consumer<List<Integer>> callback)
    {
        BankSystemNetworking.BANK_ACCOUNT_NUMBERS_REQUEST.sendRequestToServer(List.of(playerUUID), callback);
    }
    public void requestBankAccountNumbers(List<UUID> playerUUIDs, Consumer<List<Integer>> callback)
    {
        BankSystemNetworking.BANK_ACCOUNT_NUMBERS_REQUEST.sendRequestToServer(playerUUIDs, callback);
    }

    public void requestUpdateBankAccount(UpdateBankAccountRequest.InputData inputData, Consumer<BankAccountData> callback)
    {
        BankSystemNetworking.UPDATE_BANK_ACCOUNT_REQUEST.sendRequestToServer(inputData, callback);
    }

    public void requestBankTerminalData(BlockPos pos, Consumer<BankTerminalBlockDataRequest.Output> callback) {
        BankSystemNetworking.BANK_TERMINAL_BLOCK_DATA_REQUEST.sendRequestToServer(pos, callback);
    }

    public void requestBankAccounts(UUID playerUUID, Consumer<BankSelectionScreenDataRequest.Output> callback) {
        BankSystemNetworking.BANK_SELECTION_SCREEN_DATA_REQUEST.sendRequestToServer(playerUUID, callback);
    }
}
