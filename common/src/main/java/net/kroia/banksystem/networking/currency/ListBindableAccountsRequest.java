package net.kroia.banksystem.networking.currency;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.api.currency.ExternalAccountRef;
import net.kroia.banksystem.api.currency.ExternalCurrencyProvider;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Stage 3 (Task #33): the binding UI's "pick an external account" step queries the master for
 * every external account the given player can currently bind to a given provider.
 * <p>
 * Master-side, the request calls {@link ExternalCurrencyProvider#listBindableAccounts(UUID)}
 * and returns whatever refs the provider yields. Unavailable / unknown providers yield an
 * empty list — the UI degrades to "no accounts available" without a hard error.
 * <p>
 * The {@code player} field in the input is redundant on a same-loader master-only setup (the
 * master would derive it from {@code playerSender}), but must be carried explicitly to survive
 * the slave→master forwarding path where the master receives {@code playerSender=null} and only
 * the slave-authenticated {@code slaveID}. The master prefers {@code playerSender} when non-null
 * and falls back to the input {@code player} otherwise; forged input UUIDs are moot because this
 * is a read-only enumeration that never touches persistent state.
 */
public class ListBindableAccountsRequest extends BankSystemGenericRequest<ListBindableAccountsRequest.InputData, ListBindableAccountsRequest.OutputData> {

    public record InputData(String providerId, UUID player) {
        public static final StreamCodec<RegistryFriendlyByteBuf, InputData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, InputData::providerId,
                UUIDUtil.STREAM_CODEC, InputData::player,
                InputData::new
        );
    }

    public record OutputData(List<ExternalAccountRef> accounts) {
        public static final StreamCodec<RegistryFriendlyByteBuf, OutputData> STREAM_CODEC = StreamCodec.composite(
                ExtraCodecUtils.listStreamCodec(ExternalAccountRef.STREAM_CODEC), OutputData::accounts,
                OutputData::new
        );
    }

    /**
     * Client-side entry point.
     *
     * @param providerId the provider whose accounts to enumerate
     * @param player     the player whose bindable accounts to list — usually the local client
     * @return future completing with a possibly-empty list; never {@code null}
     */
    public static CompletableFuture<List<ExternalAccountRef>> sendRequest(String providerId, UUID player) {
        CompletableFuture<List<ExternalAccountRef>> future = new CompletableFuture<>();
        BankSystemNetworking.LIST_BINDABLE_ACCOUNTS_REQUEST
                .sendRequestToServer(new InputData(providerId, player))
                .whenComplete((response, ex) -> {
                    if (ex != null || response == null) {
                        future.complete(new ArrayList<>());
                    } else {
                        future.complete(response.accounts != null ? response.accounts : new ArrayList<>());
                    }
                });
        return future;
    }

    @Override
    public String getRequestTypeID() {
        return ListBindableAccountsRequest.class.getSimpleName();
    }

    @Override
    public CompletableFuture<OutputData> handleOnServer(InputData input, net.minecraft.server.level.ServerPlayer sender) {
        return handleOnMasterServer(input, "", sender.getUUID());
    }

    @Override
    public CompletableFuture<OutputData> handleOnMasterServer(InputData input, String slaveID, @Nullable UUID playerSender) {
        // Prefer the authenticated sender when present; otherwise use the input UUID (slave→master
        // forwarding path where playerSender is null and the slave carries the initiating UUID).
        UUID effectivePlayer = playerSender != null ? playerSender : input.player;
        if (effectivePlayer == null) {
            return CompletableFuture.completedFuture(new OutputData(new ArrayList<>()));
        }
        ExternalCurrencyProvider provider = BankSystemMod.getAPI().getCurrencyProvider(input.providerId);
        if (provider == null || !provider.isAvailable()) {
            return CompletableFuture.completedFuture(new OutputData(new ArrayList<>()));
        }
        List<ExternalAccountRef> refs;
        try {
            refs = provider.listBindableAccounts(effectivePlayer);
        } catch (Throwable t) {
            // Provider adapters are third-party code — never let an adapter bug take down the UI.
            warn("Provider '" + input.providerId + "' threw while listing bindable accounts: " + t.getMessage());
            refs = null;
        }
        return CompletableFuture.completedFuture(new OutputData(refs != null ? refs : new ArrayList<>()));
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
