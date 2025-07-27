package net.kroia.banksystem.banking;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.IClientBankManager;
import net.kroia.banksystem.banking.clientdata.ItemInfoData;
import net.kroia.banksystem.banking.clientdata.MinimalBankData;
import net.kroia.banksystem.banking.clientdata.MinimalBankManagerData;
import net.kroia.banksystem.banking.clientdata.MinimalBankUserData;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.request.MinimalBankDataRequest;
import net.kroia.banksystem.util.ItemID;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Consumer;

public class ClientBankManager implements IClientBankManager {

    protected BankSystemModBackend.Instances BACKEND_INSTANCES;

    public ClientBankManager(BankSystemModBackend.Instances backendInstances)
    {
        this.BACKEND_INSTANCES = backendInstances;
    }





    @Override
    public void requestMinimalBankData(UUID playerUUID, ItemID itemID, Consumer<@Nullable MinimalBankData> consumer)
    {
        MinimalBankDataRequest.InputType input = new MinimalBankDataRequest.InputType(playerUUID, itemID);
        BankSystemNetworking.MINIMAL_BANK_DATA_REQUEST.sendRequestToServer(input, consumer);
    }

    @Override
    public void requestMinimalBankUserData(UUID playerUUID, Consumer<@Nullable MinimalBankUserData> consumer)
    {
        BankSystemNetworking.MINIMAL_BANK_USER_DATA_REQUEST.sendRequestToServer(playerUUID, consumer);
    }

    @Override
    public void requestMinimalBankManagerData(Consumer<@Nullable MinimalBankManagerData> consumer)
    {
        BankSystemNetworking.MINIMAL_BANK_MANAGER_DATA_REQUEST.sendRequestToServer(0, consumer);
    }

    @Override
    public void requestItemInfoData(ItemID itemID, Consumer<@Nullable ItemInfoData> consumer)
    {
        BankSystemNetworking.ITEM_INFO_REQUEST.sendRequestToServer(itemID, consumer);
    }

    @Override
    public void requestAllowItem(ItemID itemID, Consumer<Boolean> consumer)
    {
        BankSystemNetworking.ALLOW_ITEM_REQUEST.sendRequestToServer(itemID, consumer);
    }

    @Override
    public void requestDisallowItem(ItemID itemID, Consumer<Boolean> consumer)
    {
        BankSystemNetworking.DISALLOW_ITEM_REQUEST.sendRequestToServer(itemID, consumer);
    }
}
