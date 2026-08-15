package net.kroia.banksystem.client.cache;

import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.banking.company.AsyncCompanyManager.CompanyInfoOutput;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Task #51 fix (v2.1.0) — client-side cache of {@link CompanyInfoOutput} snapshots
 * keyed by {@code companyId}. Mirrors {@link ShareVisualCache} in shape.
 *
 * <p>Read path: {@link #get(int)} returns the cached snapshot or {@code null}.
 * On miss the caller invokes {@link #tryLookup(int)} which fires a single-flight
 * by-id ARRS query ({@link AsyncCompanyManager#getCompanyInfoByIdAsync(int)}).
 * On response the entry is populated so the next frame renders the correct name.
 *
 * <p>Used by {@link net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem}
 * to resolve the canonical {@code Company.name} for the tooltip line when
 * {@code ShareVisuals.displayName} is blank, and by
 * {@link net.kroia.banksystem.screen.custom.CompanyManagementScreen} to populate
 * Overview labels.
 */
public final class CompanyInfoCache {

    /**
     * Immutable client-side snapshot of the relevant parts of {@link CompanyInfoOutput}.
     */
    public record Snapshot(int companyId, String name, String description,
                           long maxSupply, long totalSharesIssued,
                           int bankAccountNr, List<String> founderNames,
                           int holderCount) {
        /** Task #52 (v2.1.0) — {@link CompanyInfoOutput} does not carry a holder count;
         *  it is populated separately via the login bulk-sync packet path. */
        public static Snapshot of(CompanyInfoOutput out) {
            return new Snapshot(out.companyId(), out.name() == null ? "" : out.name(),
                    out.description() == null ? "" : out.description(),
                    out.maxSupply(), out.totalSharesIssued(),
                    out.bankAccountNr(),
                    out.founderNames() == null ? List.of() : List.copyOf(out.founderNames()),
                    0);
        }
    }

    private static final Map<Integer, Snapshot> infos = new ConcurrentHashMap<>();
    private static final Set<Integer> pending = Collections.synchronizedSet(new HashSet<>());

    private CompanyInfoCache() {}

    public static boolean has(int companyId) { return infos.containsKey(companyId); }

    public static Snapshot get(int companyId) { return infos.get(companyId); }

    public static void put(Snapshot snapshot) {
        if (snapshot == null) return;
        infos.put(snapshot.companyId(), snapshot);
        pending.remove(snapshot.companyId());
    }

    public static void put(CompanyInfoOutput out) {
        if (out == null || !out.present()) return;
        Snapshot fresh = Snapshot.of(out);
        // Preserve any holderCount previously set by the login bulk-sync packet path.
        Snapshot prior = infos.get(fresh.companyId());
        if (prior != null && prior.holderCount() > 0 && fresh.holderCount() == 0) {
            fresh = new Snapshot(fresh.companyId(), fresh.name(), fresh.description(),
                    fresh.maxSupply(), fresh.totalSharesIssued(),
                    fresh.bankAccountNr(), fresh.founderNames(), prior.holderCount());
        }
        put(fresh);
    }

    /** Task #52 (v2.1.0) — merge a fresh holder count into the cached snapshot without
     *  disturbing any of the other fields. If no snapshot exists yet the update is dropped
     *  (the login bulk sync or a subsequent by-id lookup will populate it). */
    public static void updateHolderCount(int companyId, int holderCount) {
        Snapshot prior = infos.get(companyId);
        if (prior == null) return;
        if (prior.holderCount() == holderCount) return;
        infos.put(companyId, new Snapshot(prior.companyId(), prior.name(), prior.description(),
                prior.maxSupply(), prior.totalSharesIssued(),
                prior.bankAccountNr(), prior.founderNames(), holderCount));
    }

    /** Task #51 (v2.1.0, spec §1.4) — merge a fresh company description into the cached
     *  snapshot without disturbing any other fields. Mirrors {@link #updateHolderCount}.
     *  If no snapshot exists yet the update is dropped (bulk sync / by-id lookup heals). */
    public static void updateDescription(int companyId, String description) {
        Snapshot prior = infos.get(companyId);
        if (prior == null) return;
        String desc = description == null ? "" : description;
        if (prior.description().equals(desc)) return;
        infos.put(companyId, new Snapshot(prior.companyId(), prior.name(), desc,
                prior.maxSupply(), prior.totalSharesIssued(),
                prior.bankAccountNr(), prior.founderNames(), prior.holderCount()));
    }

    /**
     * Cache-miss hint: dedupes concurrent lookups via the {@code pending} set,
     * fires a by-id ARRS query, and populates the cache on response. On any
     * failure the pending marker is cleared so a later miss can retry.
     */
    public static void tryLookup(int companyId) {
        if (infos.containsKey(companyId)) return;
        if (!pending.add(companyId)) return;
        try {
            AsyncCompanyManager.getCompanyInfoByIdAsync(companyId).whenComplete((out, err) -> {
                if (err != null || out == null || !out.present()) {
                    pending.remove(companyId);
                    return;
                }
                put(out);
            });
        } catch (Throwable t) {
            pending.remove(companyId);
        }
    }

    /** Test / disconnect hook. */
    public static void clear() {
        infos.clear();
        pending.clear();
    }
}
