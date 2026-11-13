package net.kroia.banksystem.networking.multi_server;

import net.kroia.banksystem.banking.ledger.AdminAudit;
import net.kroia.banksystem.data.TransferJournal;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * BS-267 — a slave tells the master that one of its transfers was parked (outcome unknown, treated as applied), so the
 * master raises the TRANSFER_PARKED flag ({@link AdminAudit#raiseParked}). Accepted only from a trusted slave without a
 * player sender; anything else answers false. Sent by {@link AdminAudit#parkTransfer} on a slave and retried on the
 * {@link TransferStatusRequest} retry tick while the link is down. A repeat with the same transfer id raises no second flag.
 */
public class TransferParkedRequest extends BankSystemGenericRequest<TransferParkedRequest.Input, Boolean> {

    /** @param raw raw amount of {@code item}; {@code details} = the slave's readable reason */
    public record Input(int account, ItemID item, long raw, @Nullable String transferId, String details) {
        static final StreamCodec<RegistryFriendlyByteBuf, Input> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Input::account,
                ItemID.STREAM_CODEC, Input::item,
                ByteBufCodecs.VAR_LONG, Input::raw,
                TransferJournal.ID_CODEC, Input::transferId,
                ByteBufCodecs.STRING_UTF8, Input::details,
                Input::new);
    }

    /** Slave side: sends the report, retried while the master gives no answer. Never throws. */
    public static void report(int account, ItemID item, long raw, @Nullable String transferId, String details) {
        Input in = new Input(account, item, raw, transferId, details);
        TransferStatusRequest.retryWhileNoAnswer(() -> {
            try {
                return BankSystemNetworking.TRANSFER_PARKED_REQUEST.sendRequestToMaster(in).handle((ok, ex) -> {
                    if (ex != null) return TransferStatusRequest.OutputData.NO_ANSWER;
                    if (!Boolean.TRUE.equals(ok)) log(false, "master refused the parked-transfer flag for transfer " + transferId + " (slave not trusted?)");
                    return TransferStatusRequest.OutputData.UNKNOWN_RESULT;
                });
            } catch (RuntimeException e) {
                return CompletableFuture.completedFuture(TransferStatusRequest.OutputData.NO_ANSWER);
            }
        }, st -> {
            if (st == null || st == TransferStatusRequest.OutputData.NO_ANSWER)
                log(true, "parked-transfer flag for transfer " + transferId + " not delivered to the master: " + details);
        });
    }

    private static void log(boolean error, String msg) {
        if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.LOGGER == null) return;
        if (error) BACKEND_INSTANCES.LOGGER.error("[TransferParkedRequest] " + msg);
        else BACKEND_INSTANCES.LOGGER.warn("[TransferParkedRequest] " + msg);
    }

    @Override
    public String getRequestTypeID() {
        return TransferParkedRequest.class.getName();
    }

    @Override
    public CompletableFuture<Boolean> handleOnMasterServer(Input in, String slaveID, @Nullable UUID playerSender) {
        if (playerSender != null || slaveID == null || slaveID.isEmpty() || isBlockedForUntrustedSlave(slaveID))
            return CompletableFuture.completedFuture(false);
        // a retry after a lost answer: the flag is already raised (same store and TTL as the transfers themselves)
        String key = in.transferId() == null ? null : "parked|" + in.transferId();
        CompletableFuture<Boolean> again = TransferStatusRequest.replay(slaveID, key);
        if (again != null) return again;
        AdminAudit.raiseParked(in.account(), in.item(), in.raw(), in.transferId(), in.details() + " (slave " + slaveID + ")");
        TransferStatusRequest.remember(slaveID, key, true, Map.of(), Boolean.TRUE);
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, Input input) { Input.CODEC.encode(buf, input); }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, Boolean output) { ByteBufCodecs.BOOL.encode(buf, output); }

    @Override
    public Input decodeInput(RegistryFriendlyByteBuf buf) { return Input.CODEC.decode(buf); }

    @Override
    public Boolean decodeOutput(RegistryFriendlyByteBuf buf) { return ByteBufCodecs.BOOL.decode(buf); }
}
