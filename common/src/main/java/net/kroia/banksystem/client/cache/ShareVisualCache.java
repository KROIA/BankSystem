package net.kroia.banksystem.client.cache;

import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.banking.company.ShareVisuals;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Task #46 (v2.0.8) — client-side cache of company share visuals, share supply, and
 * max supply, keyed by {@code companyId}. Populated by the S2C packets
 * {@link net.kroia.banksystem.networking.general.S2CCompanyVisualUpdatePacket},
 * {@link net.kroia.banksystem.networking.general.S2CCompanyVisualBulkPacket},
 * {@link net.kroia.banksystem.networking.general.S2CCompanyVisualSupplyUpdatePacket}.
 *
 * <p>Reads are lock-free (ConcurrentHashMap). On cache miss the tooltip renderer calls
 * {@link #tryLookup(int)} which schedules an async request via
 * {@link net.kroia.banksystem.banking.company.AsyncCompanyManager#getCompanyInfoByAccountAsync(int)}
 * — actually a placeholder here since we don't have a by-id RPC yet; the pending-set
 * dedupe prevents request storms. When the master broadcasts a visual update the
 * cache self-heals on the next frame (see {@code StampedShareItem.appendHoverText}).
 */
public final class ShareVisualCache {

    private static final Map<Integer, ShareVisuals> visuals = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> totalIssued = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> maxSupply = new ConcurrentHashMap<>();

    /** Dedupe set for outstanding lookups — mirrors {@code ItemID.tryUpdateNameCache}. */
    private static final Set<Integer> pending = Collections.synchronizedSet(new HashSet<>());

    private ShareVisualCache() {}

    public static boolean has(int companyId) {
        return visuals.containsKey(companyId);
    }

    public static ShareVisuals getVisualsOrPlaceholder(int companyId) {
        ShareVisuals v = visuals.get(companyId);
        return v != null ? v : ShareVisuals.EMPTY;
    }

    public static long getIssued(int companyId) {
        Long v = totalIssued.get(companyId);
        return v != null ? v : 0L;
    }

    public static long getMax(int companyId) {
        Long v = maxSupply.get(companyId);
        return v != null ? v : 0L;
    }

    /** Applied by the S2C update / bulk handlers on the render thread. */
    public static void put(int companyId, ShareVisuals v, long issued, long max) {
        if (v == null) v = ShareVisuals.EMPTY;
        visuals.put(companyId, v);
        totalIssued.put(companyId, issued);
        maxSupply.put(companyId, max);
        pending.remove(companyId);
    }

    /** Supply-only update; leaves visuals untouched. */
    public static void updateSupply(int companyId, long issued) {
        totalIssued.put(companyId, issued);
    }

    /**
     * Cache-miss hint: mark the id as pending so we don't spam the network with duplicate
     * lookups. The actual request path is owned by the future by-id ARRS query
     * (deferred with the editor screen — see task deferrals). For now, the cache
     * simply relies on server-initiated bulk sync at login + broadcast on edit.
     */
    public static void tryLookup(int companyId) {
        if (visuals.containsKey(companyId)) return;
        if (!pending.add(companyId)) return;
        // Task #46 (v2.0.8) — by-id ARRS lookup. Dedupe via `pending` set mirrors
        // ItemID.tryUpdateNameCache. On response: on present, populate cache (self-heal
        // on next frame); on missing/error, clear pending so a later miss can retry.
        try {
            AsyncCompanyManager.getShareVisualsAsync(companyId).whenComplete((out, err) -> {
                if (err != null || out == null || !out.present()) {
                    pending.remove(companyId);
                    return;
                }
                ShareVisuals v = new ShareVisuals(
                        new ShareVisuals.ShareLayer(out.bgSymbolId(), out.bgTint()),
                        new ShareVisuals.ShareLayer(out.fgSymbolId(), out.fgTint()),
                        out.baseTint(), out.displayName(), out.description());
                put(companyId, v, out.totalIssued(), out.maxSupply());
            });
        } catch (Throwable t) {
            pending.remove(companyId);
        }
    }

    /** Test / disconnect hook. */
    public static void clear() {
        visuals.clear();
        totalIssued.clear();
        maxSupply.clear();
        pending.clear();
    }
}
