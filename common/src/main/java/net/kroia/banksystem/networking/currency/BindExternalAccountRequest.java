package net.kroia.banksystem.networking.currency;

import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.api.currency.ExternalAccountRef;
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
 * Stage 3 (Task #33): write-side request — bind an {@code IServerBank} slot on a specific
 * BankSystem account to an external currency-mod account.
 * <p>
 * Enforces the standard write discipline:
 * <ul>
 *   <li>Untrusted-slave gate (Task #26 pattern) — writes from an unauthenticated slave return
 *       {@link BankStatus#FAILED_EXTERNAL_UNAVAILABLE} without touching state.</li>
 *   <li>Per-player MANAGE permission on the target account (checked against the
 *       authenticated sender). Same discipline as {@code UpdateBankAccountRequest}.</li>
 * </ul>
 * The actual bind logic (zero-balance precondition, provider availability, shared-state match)
 * lives in {@link ServerBankManager#bindExternalAccount(int, ItemID, ExternalAccountRef)} and
 * is not duplicated here.
 */
public class BindExternalAccountRequest extends BankSystemGenericRequest<BindExternalAccountRequest.InputData, BindExternalAccountRequest.OutputData> {

    public record InputData(int accountId, ItemID itemId, ExternalAccountRef ref) {
        public static final StreamCodec<RegistryFriendlyByteBuf, InputData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, InputData::accountId,
                ItemID.STREAM_CODEC, InputData::itemId,
                ExternalAccountRef.STREAM_CODEC, InputData::ref,
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
     * @return future completing with the {@link BankStatus} returned by the master. On transport
     *         failure the future completes with {@link BankStatus#FAILED_NO_MASTER_CONNECTION} so
     *         callers can display a distinct diagnostic.
     */
    public static CompletableFuture<BankStatus> sendRequest(int accountId, ItemID itemId, ExternalAccountRef ref) {
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        BankSystemNetworking.BIND_EXTERNAL_ACCOUNT_REQUEST
                .sendRequestToServer(new InputData(accountId, itemId, ref))
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
        return BindExternalAccountRequest.class.getSimpleName();
    }

    @Override
    public CompletableFuture<OutputData> handleOnServer(InputData input, net.minecraft.server.level.ServerPlayer sender) {
        return handleOnMasterServer(input, "", sender.getUUID());
    }

    @Override
    public CompletableFuture<OutputData> handleOnMasterServer(InputData input, String slaveID, @Nullable UUID sender) {
        // Task #26 pattern: untrusted-slave write gate. A slave could otherwise forge sender to
        // satisfy the MANAGE check below.
        if (isBlockedForUntrustedSlave(slaveID)) {
            return CompletableFuture.completedFuture(new OutputData(BankStatus.FAILED_EXTERNAL_UNAVAILABLE));
        }
        if (sender == null) {
            warn("BindExternalAccountRequest refused: no sender UUID resolvable");
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
            warn("BindExternalAccountRequest refused: player " + sender + " lacks MANAGE on account " + input.accountId);
            return CompletableFuture.completedFuture(new OutputData(BankStatus.FAILED_INVALID_ITEM_ID));
        }
        // Delegate to the manager. Its bindExternalAccount() runs the zero-balance /
        // provider-availability / shared-state-match checks and WARN-logs any refusal.
        if (!(bankManager instanceof ServerBankManager sbm)) {
            // Defensive: today's ISyncServerBankManager is always the ServerBankManager
            // implementation, but this cast keeps the compile-time coupling narrow.
            return CompletableFuture.completedFuture(new OutputData(BankStatus.FAILED_EXTERNAL_UNAVAILABLE));
        }
        BankStatus status = sbm.bindExternalAccount(input.accountId, input.itemId, input.ref);
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
