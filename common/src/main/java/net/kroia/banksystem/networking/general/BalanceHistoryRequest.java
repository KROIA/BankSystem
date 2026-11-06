package net.kroia.banksystem.networking.general;

import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.data.table.record.BalanceHistoryRecord;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Balance-history read request (Task #40).
 * <p>
 * Carries a {@link Query} describing (account, time-range, point-budget). The master
 * server-side handler dispatches to
 * {@link net.kroia.banksystem.data.table.BalanceHistoryManager#getHistoryBucketed}
 * so payload stays bounded (bucket-last-sample downsampling) even on long-running
 * worlds. Clients that want "all history" pass {@link Query#ALL_HISTORY_SENTINEL} as
 * {@code fromMs} — the server then resolves the effective range from the underlying
 * table via {@code MIN(time)} / {@code MAX(time)}.
 */
public class BalanceHistoryRequest extends BankSystemGenericRequest<BalanceHistoryRequest.Query, List<BalanceHistoryRecord>> {

    /**
     * Parameters of a balance-history query.
     *
     * @param accountNumber the account whose history is requested
     * @param fromMs        lower time bound (inclusive) in epoch millis; use
     *                      {@link #ALL_HISTORY_SENTINEL} to mean "from the earliest row"
     * @param toMs          upper time bound (inclusive) in epoch millis
     * @param maxPoints     per-item point budget for downsampling (0 or negative =
     *                      unlimited — the client always sends a positive value)
     */
    public record Query(int accountNumber, long fromMs, long toMs, int maxPoints) {
        /** Sentinel {@code fromMs} value meaning "resolve from MIN(time) on the server". */
        public static final long ALL_HISTORY_SENTINEL = Long.MIN_VALUE;

        public boolean isAllHistory() { return fromMs == ALL_HISTORY_SENTINEL; }
    }

    @Override
    public String getRequestTypeID() {
        return BalanceHistoryRequest.class.getSimpleName();
    }

    @Override
    public CompletableFuture<List<BalanceHistoryRecord>> handleOnServer(Query input, ServerPlayer sender) {
        return handleOnMasterServer(input, "", sender.getUUID());
    }

    @Override
    public CompletableFuture<List<BalanceHistoryRecord>> handleOnMasterServer(Query input, String slaveID, UUID sender) {
        if (BACKEND_INSTANCES.BALANCE_HISTORY_MANAGER == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        // Task #100: only account members and BankSystem admins may read an account's history.
        // On a slave, needsRoutingToMaster() forwards the request, so this master-side gate covers
        // both direct and slave-forwarded requests.
        // An untrusted slave can forge the sender UUID (see isBlockedForUntrustedSlave), so its
        // requests get no admin shortcut; only membership counts. Same rule as the account-data
        // reads in AsyncBankManager (Task #103).
        ISyncServerBankManager manager = getServerBankManager();
        boolean untrustedSlave = isFromUntrustedSlave(slaveID);
        IServerBankAccount account = manager == null ? null : manager.getBankAccount(input.accountNumber());
        boolean isMember = account != null && account.hasUser(sender);
        if (!isAccessAllowed(isMember, playerIsAdmin(sender), untrustedSlave)) {
            debug("Denied balance history of account " + input.accountNumber() + " to " + sender
                    + (untrustedSlave ? " (untrusted slave " + slaveID + ")" : ""));
            return CompletableFuture.completedFuture(List.of());
        }
        int maxPoints = input.maxPoints() <= 0 ? 0 : input.maxPoints();
        return BACKEND_INSTANCES.BALANCE_HISTORY_MANAGER.getHistoryBucketed(
                input.accountNumber(),
                input.fromMs(),
                input.toMs(),
                maxPoints
        );
    }

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, Query input) {
        ByteBufCodecs.INT.encode(buf, input.accountNumber());
        ByteBufCodecs.VAR_LONG.encode(buf, input.fromMs());
        ByteBufCodecs.VAR_LONG.encode(buf, input.toMs());
        ByteBufCodecs.INT.encode(buf, input.maxPoints());
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, List<BalanceHistoryRecord> output) {
        ExtraCodecUtils.listStreamCodec(BalanceHistoryRecord.STREAM_CODEC).encode(buf, output);
    }

    @Override
    public Query decodeInput(RegistryFriendlyByteBuf buf) {
        int accountNumber = ByteBufCodecs.INT.decode(buf);
        long fromMs = ByteBufCodecs.VAR_LONG.decode(buf);
        long toMs = ByteBufCodecs.VAR_LONG.decode(buf);
        int maxPoints = ByteBufCodecs.INT.decode(buf);
        return new Query(accountNumber, fromMs, toMs, maxPoints);
    }

    @Override
    public List<BalanceHistoryRecord> decodeOutput(RegistryFriendlyByteBuf buf) {
        return ExtraCodecUtils.listStreamCodec(BalanceHistoryRecord.STREAM_CODEC).decode(buf);
    }
}
