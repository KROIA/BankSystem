package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class RemoveEmptyBanksRequest extends BankSystemGenericRequest<Integer, List<ItemID>> {
    @Override
    public String getRequestTypeID() {
        return RemoveEmptyBanksRequest.class.getSimpleName();
    }
    @Override
    public boolean needsRoutingToMaster() { return true; }

    @Override
    public CompletableFuture<List<ItemID>> handleOnServer(Integer input, ServerPlayer sender) {
        return handleOnMasterServer(input, sender.getUUID());
    }
    @Override
    public CompletableFuture<List<ItemID>> handleOnMasterServer(Integer input, UUID sender) {
        CompletableFuture<List<ItemID>>  future = new CompletableFuture<>();
        ISyncServerBankManager bankManager = getSyncBankManager();
        ISyncServerBankAccount account = bankManager.getBankAccount(input);
        if(account == null) {
            future.complete(List.of());
            return future;
        }

        boolean canEdit = playerIsAdmin(sender) || account.hasPermission(sender, BankPermission.MANAGE.getValue());

        if(canEdit) {
            future.complete(account.removeEmptyBanks());
            return future;
        }
        future.complete(List.of());
        return future;
    }

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, Integer input) {
        ByteBufCodecs.INT.encode(buf, input);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, List<ItemID> output) {
        ExtraCodecUtils.listStreamCodec(ItemID.STREAM_CODEC).encode(buf, output);
    }

    @Override
    public Integer decodeInput(RegistryFriendlyByteBuf buf) {
        return ByteBufCodecs.INT.decode(buf);
    }

    @Override
    public List<ItemID> decodeOutput(RegistryFriendlyByteBuf buf) {
        return ExtraCodecUtils.listStreamCodec(ItemID.STREAM_CODEC).decode(buf);
    }
}
