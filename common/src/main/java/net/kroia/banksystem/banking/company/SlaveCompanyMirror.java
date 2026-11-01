package net.kroia.banksystem.banking.company;

import net.kroia.banksystem.networking.general.S2CCompanyVisualBulkPacket;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Task #54 (v2.1.0) — slave-side mirror of the master's per-company visual + info
 * payload.
 *
 * <p>{@link CompanyManager#get()} returns {@code null} on slave servers (all
 * Company state lives on the master). That means the master-only bulk-sync
 * branch inside {@code PlayerJoinSyncPacket.send} was previously skipped on
 * slaves — a player joining a slave saw blank stamped-share tooltips until the
 * per-id ARRS self-heal fired on hover.
 *
 * <p>This mirror stores whatever entries the master has pushed to the slave
 * (via a future S2S bulk push and per-mutation broadcasts — see TODOs below).
 * On player join to a slave, {@code PlayerJoinSyncPacket.send} now reads from
 * this mirror instead of {@link CompanyManager}.
 *
 * <p><b>Population (deferred to a follow-up):</b>
 * <ul>
 *   <li>On slave-master handshake: request bulk via a new ARRS function
 *       {@code LIST_ALL_COMPANY_VISUALS} — TODO(v2.1.0).</li>
 *   <li>Live master→slave mutation broadcasts (UPDATE_SHARE_VISUALS, stamp / redeem,
 *       description, create / dissolve / transfer): route master's existing S2C
 *       packets through a slave-side listener that updates this mirror AND
 *       forwards to locally-connected players — TODO(v2.1.0).</li>
 * </ul>
 *
 * <p>Cleared on slave→master disconnect via {@link #clear()} so a stale mirror
 * cannot leak across reconnects when the master might have mutated companies.
 *
 * <p>Thread-safety: {@link ConcurrentHashMap}, all methods safe from any thread.
 */
public final class SlaveCompanyMirror {

    private static final Map<Integer, S2CCompanyVisualBulkPacket.Entry> ENTRIES = new ConcurrentHashMap<>();

    private SlaveCompanyMirror() {}

    /** Insert / replace one company's entry. */
    public static void put(S2CCompanyVisualBulkPacket.Entry entry) {
        if (entry == null) return;
        ENTRIES.put(entry.companyId(), entry);
    }

    /** Bulk replace (idempotent for a fresh handshake). */
    public static void putAll(Collection<S2CCompanyVisualBulkPacket.Entry> entries) {
        if (entries == null) return;
        for (S2CCompanyVisualBulkPacket.Entry e : entries) {
            if (e != null) ENTRIES.put(e.companyId(), e);
        }
    }

    /** Remove a company that no longer exists on the master (e.g. dissolved). */
    public static void remove(int companyId) {
        ENTRIES.remove(companyId);
    }

    /**
     * Patch the supply field of an existing entry without touching visuals or metadata.
     * No-op if the company is not in the mirror (the caller's subsequent per-id
     * ARRS lookup will populate it fresh).
     */
    public static void updateSupply(int companyId, long totalSharesIssued) {
        ENTRIES.computeIfPresent(companyId, (id, prev) -> new S2CCompanyVisualBulkPacket.Entry(
                prev.companyId(), prev.bgSymbolId(), prev.bgTint(), prev.fgSymbolId(), prev.fgTint(),
                prev.baseTint(),
                prev.displayName(), prev.description(), totalSharesIssued, prev.maxSupply(),
                prev.internalName(), prev.companyDescription(),
                prev.bankAccountNr(), prev.founderNames(), prev.holderCount()));
    }

    /** Snapshot for the join-time bulk send. */
    public static List<S2CCompanyVisualBulkPacket.Entry> snapshot() {
        return new ArrayList<>(ENTRIES.values());
    }

    public static boolean isEmpty() { return ENTRIES.isEmpty(); }

    public static int size() { return ENTRIES.size(); }

    /** Called from the slave-server disconnect hook so we never serve stale data. */
    public static void clear() {
        ENTRIES.clear();
    }
}
