package net.kroia.banksystem.networking.multi_server;

import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.IServerBank;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class DepositItemsInBankRequest extends BankSystemGenericRequest<DepositItemsInBankRequest.InputData, DepositItemsInBankRequest.OutputData> {



    public record InputData(int bankAccount, @Nullable UUID executor, Map<ItemID, Long> items)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, InputData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, p->p.bankAccount,
                ExtraCodecUtils.nullable(UUIDUtil.STREAM_CODEC), p->p.executor,
                ExtraCodecUtils.mapStreamCodec(ItemID.STREAM_CODEC, ByteBufCodecs.VAR_LONG, HashMap<ItemID, Long>::new), p -> p.items,
                InputData::new
        );
    }
    public record OutputData(Map<ItemID, Long> items)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, OutputData> STREAM_CODEC = StreamCodec.composite(
                ExtraCodecUtils.mapStreamCodec(ItemID.STREAM_CODEC, ByteBufCodecs.VAR_LONG, HashMap<ItemID, Long>::new), p -> p.items,
                OutputData::new
        );
    }

    /**
     * Deposits the requested amount of items onto the bank account
     * If the executor is set, permission checks are made to check if the player has access to the bank account
     * @param bankAccount target bank account
     * @param executor the player who has created this request
     * @param items list of items to deposit
     * @return a future containing the items that have not been deposited
     */
    public static CompletableFuture<Map<ItemID, Long>> sendToMaster(int bankAccount, @Nullable UUID executor, Map<ItemID, Long> items)
    {
        DepositItemsInBankRequest.InputData inputData = new DepositItemsInBankRequest.InputData(bankAccount, executor, items);
        CompletableFuture<Map<ItemID, Long>> future = new CompletableFuture<>();
        BankSystemNetworking.DEPOSIT_ITEMS_IN_BANK_REQUEST.sendRequestToMaster(inputData).thenAccept(response -> {
            future.complete(response.items);
        });
        return future;
    }
    /**
     * Deposits the requested amount of items onto the bank account
     * This does not check for bank account access permissions
     * @param bankAccount target bank account
     * @param items list of items to deposit
     * @return a future containing the items that have not been deposited
     */
    public static CompletableFuture<Map<ItemID, Long>> sendToMaster(int bankAccount, Map<ItemID, Long> items)
    {
        return sendToMaster(bankAccount, null, items);
    }

    @Override
    public String getRequestTypeID() {
        return DepositItemsInBankRequest.class.getName();
    }
    @Override
    public boolean needsRoutingToMaster() { return false; }

    public CompletableFuture<OutputData> handleOnMasterServer(DepositItemsInBankRequest.InputData input, String slaveID, @Nullable UUID playerSender) {
        if(playerSender != null)
        {
            warn("This request is not allowed to be sent from a client");
            return CompletableFuture.completedFuture(new OutputData(input.items));
        }

        IServerBankAccount account = getServerBankManager().getBankAccount(input.bankAccount);
        if(account == null)
        {
            error("The bank account: "+input.bankAccount+" does not exist");
            return CompletableFuture.completedFuture(new OutputData(input.items));
        }

        // Check permission
        if(input.executor != null)
        {
            if(!account.hasPermission(input.executor, BankPermission.DEPOSIT.ordinal()))
            {
                User user = getServerBankManager().getUserByUUID(input.executor);
                String playerName;
                if(user != null)
                    playerName = user.getName();
                else
                    playerName = input.executor.toString();
                warn("Player: "+playerName + " has not the right to deposit items into the bank account: "+input.bankAccount);
                return CompletableFuture.completedFuture(new OutputData(input.items));
            }
        }

        Map<ItemID, Long> notDepositedItems = new HashMap<>();
        for(Map.Entry<ItemID, Long> entry : input.items.entrySet())
        {
            long toDeposit = entry.getValue();
            IServerBank itemBank = account.getOrCreateBank(entry.getKey());
            if(itemBank != null)
            {
                if(itemBank.deposit(toDeposit) == BankStatus.SUCCESS)
                {
                    toDeposit = 0;
                }
            }
            if(toDeposit != 0)
            {
                notDepositedItems.put(entry.getKey(), toDeposit);
            }
        }
        return CompletableFuture.completedFuture(new OutputData(notDepositedItems));
    }


    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, InputData input) {
        InputData.STREAM_CODEC.encode(buf, input);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, OutputData output) {
        OutputData.STREAM_CODEC.encode(buf, output);
    }

    @Override
    public InputData decodeInput(RegistryFriendlyByteBuf buf) {
        return InputData.STREAM_CODEC.decode(buf);
    }

    @Override
    public OutputData decodeOutput(RegistryFriendlyByteBuf buf) {
        return OutputData.STREAM_CODEC.decode(buf);
    }


}
