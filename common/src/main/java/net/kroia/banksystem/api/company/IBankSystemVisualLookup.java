package net.kroia.banksystem.api.company;

import net.kroia.banksystem.util.ItemID;
import org.jetbrains.annotations.Nullable;

/**
 * Task #50 (v2.1.0) — client-side lookup for per-share visual metadata + supply.
 *
 * <p>Called by external mods (e.g. StockMarket) from render paths. Implementations
 * must be cheap, cache-backed, thread-safe for the client thread, and non-blocking.
 * On cache miss the implementation self-heals (schedules an async fetch) — the
 * caller should re-query on the next frame.
 *
 * <p>On dedicated servers the cache is unpopulated, so every call returns {@code null}.
 */
public interface IBankSystemVisualLookup {

    /**
     * @param itemId an {@link ItemID} that may point at a stamped-company-share template.
     * @return visuals for the share, or {@code null} if the ItemID does not refer to a
     *         live company share OR visuals have not yet been broadcast to this client
     *         (caller should re-query next frame).
     */
    @Nullable ShareVisuals getShareVisuals(ItemID itemId);
}
