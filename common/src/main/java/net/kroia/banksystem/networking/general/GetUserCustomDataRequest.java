package net.kroia.banksystem.networking.general;

import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class GetUserCustomDataRequest extends BankSystemGenericRequest<Boolean, CompoundTag> {

    @Override
    public String getRequestTypeID() {
        return GetUserCustomDataRequest.class.getSimpleName();
    }

    @Override
    public CompletableFuture<CompoundTag> handleOnServer(Boolean input, ServerPlayer sender) {
        return handleOnMasterServer(input, "", sender.getUUID());
    }

    @Override
    public CompletableFuture<CompoundTag> handleOnMasterServer(Boolean input, String slaveID, UUID sender) {
        ISyncServerBankManager manager = getServerBankManager();
        if (manager == null) return CompletableFuture.completedFuture(new CompoundTag());
        User user = manager.getUserByUUID(sender);
        if (user == null) return CompletableFuture.completedFuture(new CompoundTag());
        return CompletableFuture.completedFuture(user.getCustomData().copy());
    }

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, Boolean input) {
        ByteBufCodecs.BOOL.encode(buf, input);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, CompoundTag output) {
        ByteBufCodecs.TRUSTED_COMPOUND_TAG.encode(buf, output);
    }

    @Override
    public Boolean decodeInput(RegistryFriendlyByteBuf buf) {
        return ByteBufCodecs.BOOL.decode(buf);
    }

    @Override
    public CompoundTag decodeOutput(RegistryFriendlyByteBuf buf) {
        return ByteBufCodecs.TRUSTED_COMPOUND_TAG.decode(buf);
    }
}
