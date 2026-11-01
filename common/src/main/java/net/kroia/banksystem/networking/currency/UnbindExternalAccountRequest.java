package net.kroia.banksystem.networking.currency;

import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.bankmanager.ServerBankManager;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Stage 3 (Task #33): write-side request — unbind an {@code IServerBank} slot from its
 * external-currency binding. The current external balance is materialized as the BankSystem-
 * local balance (see {@link ServerBankManager#unbindExternalAccount(int, ItemID)}); any locked
 * amount remains in the local ledger.
 * <p>
 * Same MANAGE-permission + untrusted-slave gate as {@link BindExternalAccountRequest}.
 */
public class UnbindExternalAccountRequest extends BankSystemGenericRequest<UnbindExternalAccountRequest.InputData, UnbindExternalAccountRequest.OutputData> {

    public record InputData(int accountId, ItemID itemId, boolean keepOnBankSystem) {
        public static final StreamCodec<RegistryFriendlyByteBuf, InputData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, InputData::accountId,
                ItemID.STREAM_CODEC, InputData::itemId,
                ByteBufCodecs.BOOL, InputData::keepOnBankSystem,
                InputData::new
        );
    }

    public record OutputData(BankStatus status) {
        public static final StreamCodec<RegistryFriendlyByteBuf, OutputData> STREAM_CODEC = StreamCodec.composite(
                BankStatus.STREAM_CODEC, OutputData::status,
                OutputData::new
        );
    }

    /**
     * Client-side entry point.
     *
     * @param accountId         BankSystem account number
     * @param itemId            item slot on the account
     * @param keepOnBankSystem  {@code true} → recover all funds locally;
     *                          {@code false} → return funds to external with
     *                          fractional-dust loss accepted
     * @return future completing with the {@link BankStatus} returned by the master. On transport
     *         failure the future completes with {@link BankStatus#FAILED_NO_MASTER_CONNECTION}.
     */
    public static CompletableFuture<BankStatus> sendRequest(int accountId, ItemID itemId, boolean keepOnBankSystem) {
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        BankSystemNetworking.UNBIND_EXTERNAL_ACCOUNT_REQUEST
                .sendRequestToServer(new InputData(accountId, itemId, keepOnBankSystem))
                .whenComplete((response, ex) -> {
                    if (ex != null || response == null || response.status == null) {
                        future.complete(BankStatus.FAILED_NO_MASTER_CONNECTION);
                    } else {
                        future.complete(response.status);
                    }
                });
        return future;
    }

    @Override
    public String getRequestTypeID() {
        return UnbindExternalAccountRequest.class.getSimpleName();
    }

    @Override
    public CompletableFuture<OutputData> handleOnServer(InputData input, net.minecraft.server.level.ServerPlayer sender) {
        return handleOnMasterServer(input, "", sender.getUUID());
    }

    @Override
    public CompletableFuture<OutputData> handleOnMasterServer(InputData input, String slaveID, @Nullable UUID sender) {
        if (isBlockedForUntrustedSlave(slaveID)) {
            return CompletableFuture.completedFuture(new OutputData(BankStatus.FAILED_EXTERNAL_UNAVAILABLE));
        }
        if (sender == null) {
            warn("UnbindExternalAccountRequest refused: no sender UUID resolvable");
            return CompletableFuture.completedFuture(new OutputData(BankStatus.FAILED_INVALID_ITEM_ID));
        }
        ISyncServerBankManager bankManager = getServerBankManager();
        if (bankManager == null) {
            return CompletableFuture.completedFuture(new OutputData(BankStatus.FAILED_EXTERNAL_UNAVAILABLE));
        }
        ISyncServerBankAccount account = bankManager.getBankAccount(input.accountId);
        if (account == null) {
            return CompletableFuture.completedFuture(new OutputData(BankStatus.FAILED_NO_BANK));
        }
        boolean isAdmin = playerIsAdmin(sender);
        if (!isAdmin && !account.hasPermission(sender, BankPermission.MANAGE)) {
            warn("UnbindExternalAccountRequest refused: player " + sender + " lacks MANAGE on account " + input.accountId);
            return CompletableFuture.completedFuture(new OutputData(BankStatus.FAILED_INVALID_ITEM_ID));
        }
        if (!(bankManager instanceof ServerBankManager sbm)) {
            return CompletableFuture.completedFuture(new OutputData(BankStatus.FAILED_EXTERNAL_UNAVAILABLE));
        }
        BankStatus status = sbm.unbindExternalAccount(input.accountId, input.itemId, input.keepOnBankSystem);
        return CompletableFuture.completedFuture(new OutputData(status != null ? status : BankStatus.FAILED_EXTERNAL_UNAVAILABLE));
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
