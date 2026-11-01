package net.kroia.banksystem.networking.currency;

import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.api.currency.ExternalAccountRef;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.binding.BankAccountBindings;
import net.kroia.banksystem.banking.binding.BindingRow;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Stage 3 (Task #33): the binding UI's per-account row list needs to know, for each slot on the
 * account, whether it is currently bound to an external account and — if so — to which one.
 * <p>
 * Extending {@code BankAccountData}'s wire schema would ripple through
 * {@code BankAccountChangeStream}, existing serialization tests, and every downstream consumer,
 * so this Stage-3 work uses a dedicated request instead. The screen fetches the binding list on
 * open, after every bind/unbind action, and whenever the user presses the manual Refresh button
 * — a push-based invalidation path is a Task #34/#35 polish item (see {@code
 * CurrencyModSupport.md}, Part D of the Stage-3 spec).
 * <p>
 * Read-only, MANAGE-gated: the same permission that lets the user see the management screen
 * also lets them see its binding state. Admins bypass the check.
 */
public class ListBindingsForAccountRequest extends BankSystemGenericRequest<ListBindingsForAccountRequest.InputData, ListBindingsForAccountRequest.OutputData> {

    public record InputData(int accountId) {
        public static final StreamCodec<RegistryFriendlyByteBuf, InputData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, InputData::accountId,
                InputData::new
        );
    }

    /**
     * One entry per bound slot on the queried account.
     *
     * @param itemId the slot
     * @param ref    the external account reference bound to that slot; never {@code null}
     */
    public record BindingEntry(ItemID itemId, ExternalAccountRef ref) {
        public static final StreamCodec<RegistryFriendlyByteBuf, BindingEntry> STREAM_CODEC = StreamCodec.composite(
                ItemID.STREAM_CODEC, BindingEntry::itemId,
                ExternalAccountRef.STREAM_CODEC, BindingEntry::ref,
                BindingEntry::new
        );
    }

    public record OutputData(List<BindingEntry> bindings) {
        public static final StreamCodec<RegistryFriendlyByteBuf, OutputData> STREAM_CODEC = StreamCodec.composite(
                ExtraCodecUtils.listStreamCodec(BindingEntry.STREAM_CODEC), OutputData::bindings,
                OutputData::new
        );
    }

    /**
     * Client-side entry point. Callers should treat an empty list as either "no bindings on this
     * account" or "you don't have MANAGE on this account" — the two are indistinguishable at the
     * response layer; the screen only shows the list when the user is already known to have
     * MANAGE, so an empty list on that path always means "no bindings".
     */
    public static CompletableFuture<List<BindingEntry>> sendRequest(int accountId) {
        CompletableFuture<List<BindingEntry>> future = new CompletableFuture<>();
        BankSystemNetworking.LIST_BINDINGS_FOR_ACCOUNT_REQUEST
                .sendRequestToServer(new InputData(accountId))
                .whenComplete((response, ex) -> {
                    if (ex != null || response == null) {
                        future.complete(new ArrayList<>());
                    } else {
                        future.complete(response.bindings != null ? response.bindings : new ArrayList<>());
                    }
                });
        return future;
    }

    @Override
    public String getRequestTypeID() {
        return ListBindingsForAccountRequest.class.getSimpleName();
    }

    @Override
    public CompletableFuture<OutputData> handleOnServer(InputData input, net.minecraft.server.level.ServerPlayer sender) {
        return handleOnMasterServer(input, "", sender.getUUID());
    }

    @Override
    public CompletableFuture<OutputData> handleOnMasterServer(InputData input, String slaveID, @Nullable UUID sender) {
        // Read-only — no untrusted-slave gate. Permission check trusts the forwarded sender UUID;
        // an untrusted slave forging that UUID could at worst peek at which providers a
        // BankSystem account is bound to, which is not a security-sensitive fact (the bind action
        // itself is gated on the writes-side request).
        if (sender == null) {
            return CompletableFuture.completedFuture(new OutputData(new ArrayList<>()));
        }
        ISyncServerBankManager bankManager = getServerBankManager();
        if (bankManager == null) {
            return CompletableFuture.completedFuture(new OutputData(new ArrayList<>()));
        }
        ISyncServerBankAccount account = bankManager.getBankAccount(input.accountId);
        if (account == null) {
            return CompletableFuture.completedFuture(new OutputData(new ArrayList<>()));
        }
        boolean isAdmin = playerIsAdmin(sender);
        if (!isAdmin && !account.hasPermission(sender, BankPermission.MANAGE)) {
            return CompletableFuture.completedFuture(new OutputData(new ArrayList<>()));
        }
        BankAccountBindings bindings = BankAccountBindings.get();
        if (bindings == null) {
            return CompletableFuture.completedFuture(new OutputData(new ArrayList<>()));
        }
        List<BindingRow> rows = bindings.listBindingsFor(input.accountId);
        List<BindingEntry> result = new ArrayList<>(rows.size());
        for (BindingRow row : rows) {
            // Reconstruct the ItemID from the persisted short. Row stores the short only —
            // the client resolves the name/stack from its local ItemIDManager on receipt.
            ItemID itemId = new ItemID(row.itemIdShort());
            result.add(new BindingEntry(itemId, row.ref()));
        }
        return CompletableFuture.completedFuture(new OutputData(result));
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
