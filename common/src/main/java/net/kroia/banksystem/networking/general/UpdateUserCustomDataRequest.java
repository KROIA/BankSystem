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

public class UpdateUserCustomDataRequest extends BankSystemGenericRequest<CompoundTag, Boolean> {

    @Override
    public String getRequestTypeID() {
        return UpdateUserCustomDataRequest.class.getSimpleName();
    }

    @Override
    public CompletableFuture<Boolean> handleOnServer(CompoundTag input, ServerPlayer sender) {
        return handleOnMasterServer(input, "", sender.getUUID());
    }

    @Override
    public CompletableFuture<Boolean> handleOnMasterServer(CompoundTag input, String slaveID, UUID sender) {
        // Task #26: an untrusted slave could forge the sender UUID to write another user's
        // custom data — block writes from untrusted slaves outright.
        if (isBlockedForUntrustedSlave(slaveID)) {
            return CompletableFuture.completedFuture(false);
        }
        ISyncServerBankManager manager = getServerBankManager();
        if (manager == null) return CompletableFuture.completedFuture(false);
        User user = manager.getUserByUUID(sender);
        if (user == null) return CompletableFuture.completedFuture(false);
        user.setCustomData(input);
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, CompoundTag input) {
        ByteBufCodecs.TRUSTED_COMPOUND_TAG.encode(buf, input);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, Boolean output) {
        ByteBufCodecs.BOOL.encode(buf, output);
    }

    @Override
    public CompoundTag decodeInput(RegistryFriendlyByteBuf buf) {
        return ByteBufCodecs.TRUSTED_COMPOUND_TAG.decode(buf);
    }

    @Override
    public Boolean decodeOutput(RegistryFriendlyByteBuf buf) {
        return ByteBufCodecs.BOOL.decode(buf);
    }
}
