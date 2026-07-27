package net.kroia.banksystem.banking.converter;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-side per-player money cache for the ATM Money Converter tab (Task #39,
 * v2.0.7). Not persisted — a server crash discards all caches; a clean player
 * disconnect drains the cache back to the player's feet via the auto-drop
 * hook in {@code BankSystemModBackend.onPlayerLeave}.
 *
 * <p>Backing store: {@code ConcurrentHashMap<UUID, Long>}. All amounts are in
 * cents (fixed-point). Deposits are overflow-safe via {@link Math#addExact};
 * on overflow the deposit is refused (returns the previous total) rather than
 * saturating — the caller is responsible for surfacing that condition.
 *
 * <p>Cross-server behavior: master-side and slave-side each maintain their own
 * independent cache map, keyed by the local {@link UUID} space. The "deposit
 * remainder to bank" branch is the only cross-server bank write and is routed
 * through {@code DepositItemsInBankRequest} which carries the Task #26
 * untrusted-slave gate.
 */
public final class ConverterCacheManager {

    private static final ConverterCacheManager INSTANCE = new ConverterCacheManager();

    public static ConverterCacheManager get() {
        return INSTANCE;
    }

    private final ConcurrentHashMap<UUID, Long> cache = new ConcurrentHashMap<>();

    private ConverterCacheManager() {}

    /**
     * @return the player's cache balance in cents, or {@code 0} when no entry exists.
     */
    public long getCache(UUID player) {
        if (player == null) return 0L;
        Long v = cache.get(player);
        return v == null ? 0L : v;
    }

    /**
     * Adds {@code amount} cents to the player's cache. Non-positive amounts are
     * ignored. On {@link Math#addExact} overflow the cache is left untouched and
     * the previous value is returned — the caller can compare the return value
     * against the amount they attempted to deposit to detect refusal.
     *
     * @return the resulting cache balance
     */
    public long deposit(UUID player, long amount) {
        if (player == null || amount <= 0) return getCache(player);
        AtomicLong result = new AtomicLong();
        cache.compute(player, (k, v) -> {
            long cur = v == null ? 0L : v;
            long next;
            try {
                next = Math.addExact(cur, amount);
            } catch (ArithmeticException overflow) {
                // Refuse the deposit; keep the previous balance.
                result.set(cur);
                return cur;
            }
            result.set(next);
            return next;
        });
        return result.get();
    }

    /**
     * Attempts to subtract {@code amount} cents from the player's cache.
     * Refuses (returns {@code false}) if the current balance is insufficient
     * or the amount is non-positive.
     *
     * @return {@code true} on success, {@code false} on refusal
     */
    public boolean withdraw(UUID player, long amount) {
        if (player == null || amount <= 0) return false;
        boolean[] ok = {false};
        cache.compute(player, (k, v) -> {
            long cur = v == null ? 0L : v;
            if (cur < amount) {
                ok[0] = false;
                return cur;
            }
            ok[0] = true;
            long next = cur - amount;
            return next == 0 ? null : next;
        });
        return ok[0];
    }

    /**
     * Removes and returns the player's cache balance. Used by the "drop remainder"
     * and "auto-drop on disconnect" flows.
     *
     * @return the removed amount (0 when no entry exists)
     */
    public long clear(UUID player) {
        if (player == null) return 0L;
        Long removed = cache.remove(player);
        return removed == null ? 0L : removed;
    }

    /**
     * Test-only. Clears the entire cache map so a subsequent test starts from
     * a clean state.
     */
    public void clearAll_forTesting() {
        cache.clear();
    }
}
