package net.kroia.banksystem.banking;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.IClientBankManager;
import net.kroia.banksystem.banking.clientdata.ItemInfoData;
import net.kroia.banksystem.banking.clientdata.MinimalBankData;
import net.kroia.banksystem.banking.clientdata.MinimalBankManagerData;
import net.kroia.banksystem.banking.clientdata.MinimalBankUserData;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.request.AllowItemRequest;
import net.kroia.banksystem.networking.request.MinimalBankDataRequest;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.client.Minecraft;
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





    @Override
    public void requestMinimalBankData(UUID playerUUID, ItemID itemID, Consumer<@Nullable MinimalBankData> callback)
    {
        MinimalBankDataRequest.InputType input = new MinimalBankDataRequest.InputType(playerUUID, itemID);
        BankSystemNetworking.MINIMAL_BANK_DATA_REQUEST.sendRequestToServer(input, callback);
    }

    @Override
    public void requestMinimalBankUserData(UUID playerUUID, Consumer<@Nullable MinimalBankUserData> callback)
    {
        BankSystemNetworking.MINIMAL_BANK_USER_DATA_REQUEST.sendRequestToServer(playerUUID, callback);
    }

    @Override
    public void requestMinimalBankManagerData(Consumer<@Nullable MinimalBankManagerData> callback)
    {
        BankSystemNetworking.MINIMAL_BANK_MANAGER_DATA_REQUEST.sendRequestToServer(0, callback);
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
    public void requestRemoveEmptyBanks(UUID player, Consumer<List<ItemID>> callback)
    {
        BankSystemNetworking.REMOVE_EMPTY_BANKS_REQUEST.sendRequestToServer(player, callback);
    }

    @Override
    public void requestRemoveEmptyBanks(Consumer<List<ItemID>> callback)
    {
        UUID thisPlayer = Minecraft.getInstance().player.getUUID();
        BankSystemNetworking.REMOVE_EMPTY_BANKS_REQUEST.sendRequestToServer(thisPlayer, callback);
    }

    @Override
    public void requestItemFractionScaleFactor(ItemID itemID, Consumer<Integer> callback)
    {
        BankSystemNetworking.ITEM_FRACTION_SCALE_FACTOR_REQUEST.sendRequestToServer(itemID, callback);
    }
}
