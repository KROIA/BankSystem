package net.kroia.banksystem.networking.currency;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.api.currency.ExternalCurrencyProvider;
import net.kroia.banksystem.api.currency.ProviderFeature;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Stage 3 (Task #33): the binding UI asks the master for the set of currency providers currently
 * registered and available on this server. The response carries only providers whose
 * {@link ExternalCurrencyProvider#isAvailable()} returns {@code true} — the UI is not shown
 * unavailable providers as picker options.
 * <p>
 * Read-only, no permission gate: the answer reveals only which currency mods are installed on
 * the master, information every player already sees through mod-loader UI.
 */
public class ListCurrencyProvidersRequest extends BankSystemGenericRequest<ListCurrencyProvidersRequest.InputData, ListCurrencyProvidersRequest.OutputData> {

    /**
     * Empty payload — a dummy boolean satisfies the composite-codec requirement. The request has
     * no meaningful input; the master's own {@link BankSystemMod#getAPI()} provides the answer.
     */
    public record InputData(boolean dummy) {
        public static final StreamCodec<RegistryFriendlyByteBuf, InputData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, InputData::dummy,
                InputData::new
        );
    }

    /**
     * One entry per available currency provider on the master.
     *
     * @param providerId          Stable id (matches {@link ExternalCurrencyProvider#providerId()})
     * @param displayName         Human-readable name for the picker. Defaults to {@code providerId} —
     *                            provider adapters may later expose a nicer name via a lang key.
     * @param features            Feature flags advertised by the provider. Used by the UI to decide
     *                            whether to enable the shared-account picker path, etc.
     * @param baseCurrencyItemId  Resource-location string of the item this provider is authoritative
     *                            for (e.g. {@code "numismatics:spur"}), or empty string when the
     *                            provider is not tied to a single item. Empty is used in place of
     *                            {@code null} to keep the wire codec simple.
     */
    public record ProviderInfo(String providerId, String displayName, Set<ProviderFeature> features, String baseCurrencyItemId) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ProviderFeature> FEATURE_CODEC =
                ExtraCodecUtils.enumStreamCodec(ProviderFeature.class);

        public static final StreamCodec<RegistryFriendlyByteBuf, ProviderInfo> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, ProviderInfo::providerId,
                ByteBufCodecs.STRING_UTF8, ProviderInfo::displayName,
                ExtraCodecUtils.setStreamCodec(FEATURE_CODEC, HashSet::new), ProviderInfo::features,
                ByteBufCodecs.STRING_UTF8, ProviderInfo::baseCurrencyItemId,
                ProviderInfo::new
        );

        /** {@code null}-tolerant alias for {@link #baseCurrencyItemId()} — empty string maps back to {@code null}. */
        public @org.jetbrains.annotations.Nullable String baseCurrencyItemIdOrNull() {
            return baseCurrencyItemId == null || baseCurrencyItemId.isEmpty() ? null : baseCurrencyItemId;
        }
    }

    public record OutputData(List<ProviderInfo> providers) {
        public static final StreamCodec<RegistryFriendlyByteBuf, OutputData> STREAM_CODEC = StreamCodec.composite(
                ExtraCodecUtils.listStreamCodec(ProviderInfo.STREAM_CODEC), OutputData::providers,
                OutputData::new
        );
    }

    /**
     * Client-side entry point. Forwarded to the master via the ARRS routing machinery
     * ({@link #needsRoutingToMaster()} inherited from {@link BankSystemGenericRequest} returns
     * {@code true} on slaves).
     *
     * @return future of the available-provider list. On transport failure the future completes
     *         with an empty list rather than propagating the exception, matching the request
     *         semantics ("no providers offered" is the natural degradation).
     */
    public static CompletableFuture<List<ProviderInfo>> sendRequest() {
        CompletableFuture<List<ProviderInfo>> future = new CompletableFuture<>();
        BankSystemNetworking.LIST_CURRENCY_PROVIDERS_REQUEST
                .sendRequestToServer(new InputData(false))
                .whenComplete((response, ex) -> {
                    if (ex != null || response == null) {
                        future.complete(new ArrayList<>());
                    } else {
                        future.complete(response.providers != null ? response.providers : new ArrayList<>());
                    }
                });
        return future;
    }

    @Override
    public String getRequestTypeID() {
        return ListCurrencyProvidersRequest.class.getSimpleName();
    }

    @Override
    public CompletableFuture<OutputData> handleOnServer(InputData input, net.minecraft.server.level.ServerPlayer sender) {
        return handleOnMasterServer(input, "", sender.getUUID());
    }

    @Override
    public CompletableFuture<OutputData> handleOnMasterServer(InputData input, String slaveID, @Nullable UUID playerSender) {
        // Read-only enumeration — no untrusted-slave gate needed (nothing to write, no permission
        // to leak). All players see this info at mod-loader / server info level anyway.
        List<ProviderInfo> providers = new ArrayList<>();
        Collection<ExternalCurrencyProvider> registered = BankSystemMod.getAPI().getCurrencyProviders();
        if (registered != null) {
            for (ExternalCurrencyProvider provider : registered) {
                if (provider == null || !provider.isAvailable()) continue;
                String id = provider.providerId();
                Set<ProviderFeature> features = provider.features();
                if (features == null) features = EnumSet.noneOf(ProviderFeature.class);
                String baseCurrencyItemId = provider.getBaseCurrencyItemId();
                providers.add(new ProviderInfo(id, id, new HashSet<>(features),
                        baseCurrencyItemId == null ? "" : baseCurrencyItemId));
            }
        }
        return CompletableFuture.completedFuture(new OutputData(providers));
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
