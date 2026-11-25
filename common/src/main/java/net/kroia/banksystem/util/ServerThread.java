package net.kroia.banksystem.util;

import net.kroia.banksystem.api.ledger.LedgerActor;
import net.kroia.banksystem.banking.ledger.TransactionLedger;
import net.kroia.modutilities.UtilitiesPlatform;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** BS-303: the master's async bank API runs its work on the server thread, whatever thread calls it. */
public final class ServerThread {
    private ServerThread() {}

    /**
     * Runs {@code work} inline on the server thread (or when there is no server), otherwise hops there with
     * {@code server.submit}. Inline matters: a bare submit queues even on the server thread, so a caller that waits
     * for the result there (e.g. during a player join) would deadlock. A hop carries the caller's ledger actor and
     * scope along, so the rows it writes keep who did it and where.
     */
    public static <T> CompletableFuture<T> call(Supplier<CompletableFuture<T>> work) {
        MinecraftServer server = UtilitiesPlatform.getServer();
        if (server == null || server.isSameThread())
            return work.get();
        LedgerActor actor = TransactionLedger.currentActor();
        TransactionLedger.Scope scope = TransactionLedger.scope();
        return server.submit(() -> TransactionLedger.withActor(actor, () -> scope.run(work))).thenCompose(f -> f);
    }
}
