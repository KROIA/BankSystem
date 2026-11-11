package net.kroia.banksystem.client.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Task #167: per-id retry delay for the client cache lookups. A failed or unanswered lookup
 * waits {@link #BASE_MS}, doubling per further failure up to {@link #MAX_MS}; a success clears it.
 * Without it a miss re-sent the request every rendered frame (master down, unknown company id).
 */
public final class LookupBackoff {
    public static final long BASE_MS = 5_000L;
    public static final long MAX_MS = 60_000L;

    private record Entry(long retryAtMs, long delayMs) {}
    private final Map<Integer, Entry> entries = new ConcurrentHashMap<>();

    public boolean isWaiting(int id, long nowMs) {
        Entry e = entries.get(id);
        return e != null && nowMs < e.retryAtMs;
    }

    public void failed(int id, long nowMs) {
        entries.compute(id, (k, e) -> {
            long delay = e == null ? BASE_MS : Math.min(e.delayMs * 2, MAX_MS);
            return new Entry(nowMs + delay, delay);
        });
    }

    public void succeeded(int id) {
        entries.remove(id);
    }

    public void clear() {
        entries.clear();
    }
}
