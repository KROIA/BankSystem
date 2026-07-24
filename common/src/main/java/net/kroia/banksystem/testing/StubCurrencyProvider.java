package net.kroia.banksystem.testing;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.api.currency.ExternalAccount;
import net.kroia.banksystem.api.currency.ExternalAccountRef;
import net.kroia.banksystem.api.currency.ExternalCurrencyProvider;
import net.kroia.banksystem.api.currency.ProviderFeature;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link ExternalCurrencyProvider} used exclusively by the in-game
 * test suite (Task #33, v2.0.5). The provider owns a set of controllable fake
 * external accounts — each holds a balance, an overflow ceiling, and a
 * membership set — and lets tests drive every branch of the
 * {@link net.kroia.banksystem.banking.bank.ServerBank} bound-slot code path
 * without depending on a real third-party mod (Numismatics, Lightman's, ...).
 * <p>
 * <b>Location.</b> This class lives under {@code testing/} because it is
 * test infrastructure and NOT part of the public API surface. Task #34's
 * Numismatics adapter (and future adapters) may reuse it for pre-integration
 * smoke tests where wiring a real currency mod is impractical.
 * <p>
 * <b>Lifetime.</b> A single {@link #INSTANCE} is kept per JVM so
 * {@link net.kroia.banksystem.api.BankSystemAPI#registerCurrencyProvider(ExternalCurrencyProvider)}
 * needs to be called at most once (registration overrides an existing entry
 * for the same {@link #PROVIDER_ID}, so re-registration is safe and cheap).
 * Tests call {@link #reset()} at the start of every case; it wipes all
 * accounts, restores {@code available=true}, and re-registers the singleton
 * so the previous case's state cannot leak.
 * <p>
 * <b>Server thread only.</b> Mirrors the {@code ExternalCurrencyProvider}
 * contract — every method is expected to run on the server thread. The
 * account map uses a {@link ConcurrentHashMap} solely as a defense against
 * off-thread reset-vs-open races the test framework does not currently
 * exercise; do not treat this as a general-purpose thread-safe fake.
 *
 * @since 2.0.5
 * @see ExternalCurrencyProvider
 * @see net.kroia.banksystem.banking.bank.ServerBank
 */
public final class StubCurrencyProvider implements ExternalCurrencyProvider {

    /** Stable id used by every {@link ExternalAccountRef} the stub hands out. */
    public static final String PROVIDER_ID = "banksystem_test_stub";

    /** Single JVM-wide instance; re-registered against the API by {@link #reset()}. */
    private static final StubCurrencyProvider INSTANCE = new StubCurrencyProvider();

    /** All accounts currently owned by the stub, keyed by their {@code accountKey}. */
    private final Map<String, StubAccount> accounts = new ConcurrentHashMap<>();

    /**
     * Whether the stub is "available". Flipping this to {@code false} simulates
     * the underlying mod being removed / uninstalled mid-session — tests use it
     * to exercise the degraded-state branch in
     * {@link net.kroia.banksystem.banking.bank.ServerBank}.
     */
    private volatile boolean available = true;

    /**
     * Optional resource-location string returned from {@link #getBaseCurrencyItemId()}.
     * Defaults to {@code null} (item validation opt-out). Tests exercising the
     * auto-seed / item-match code paths set this to a real vanilla item id.
     */
    private volatile @Nullable String baseCurrencyItemId = null;

    private StubCurrencyProvider() {}

    // -----------------------------------------------------------------------
    // Test-only static helpers
    // -----------------------------------------------------------------------

    /** @return the singleton provider instance. */
    public static @NotNull StubCurrencyProvider getInstance() {
        return INSTANCE;
    }

    /**
     * Creates or replaces a stub account under {@code accountKey} with the given
     * starting state. Overwrites any pre-existing account with the same key.
     *
     * @param accountKey     opaque provider key (also passed through to
     *                       {@link ExternalAccountRef#accountKey()}).
     * @param shared         {@code true} to advertise the account as shared /
     *                       multi-user (mapped to {@link ExternalAccountRef#shared()}).
     * @param initialBalance starting balance; negative values are clamped to zero.
     * @return the freshly-created account handle so callers can chain setup
     *         (e.g. {@code create(...).setOverflowCeiling(1000)}).
     */
    public static @NotNull StubAccount create(@NotNull String accountKey, boolean shared, long initialBalance) {
        StubAccount account = new StubAccount(accountKey, shared, initialBalance);
        INSTANCE.accounts.put(accountKey, account);
        return account;
    }

    /**
     * Looks up a stub account by key.
     *
     * @param accountKey the key passed to {@link #create(String, boolean, long)}.
     * @return the account, or {@code null} if no such account was created.
     */
    public static @Nullable StubAccount getAccount(@NotNull String accountKey) {
        return INSTANCE.accounts.get(accountKey);
    }

    /**
     * Wipes the stub back to a fresh state:
     * <ul>
     *   <li>all accounts are dropped,</li>
     *   <li>{@link #isAvailable()} is set back to {@code true},</li>
     *   <li>the singleton is (re-)registered against
     *       {@code BankSystemMod.getAPI()} — replacing any prior registration
     *       for {@link #PROVIDER_ID} (there is no unregister primitive on the
     *       API, so re-registration is the closest reachable "drop and restore"
     *       semantics).</li>
     * </ul>
     * Call at the start of every test case to guarantee run-order independence.
     */
    public static void reset() {
        INSTANCE.accounts.clear();
        INSTANCE.available = true;
        INSTANCE.baseCurrencyItemId = null;
        // Re-register self so BankSystemMod.getAPI() knows about the fresh
        // instance state. registerCurrencyProvider is idempotent by providerId.
        BankSystemMod.getAPI().registerCurrencyProvider(INSTANCE);
    }

    /**
     * Suite-level teardown: drops the stub from the live provider registry and
     * clears its state. Must be called from {@code teardown()} of every test
     * suite that used {@link #reset()} — otherwise the stub (and any accounts
     * the last test left behind) leaks into the production binding UI for the
     * rest of the JVM session.
     */
    public static void teardown() {
        INSTANCE.accounts.clear();
        INSTANCE.baseCurrencyItemId = null;
        INSTANCE.available = false;
        BankSystemMod.getAPI().unregisterCurrencyProvider(PROVIDER_ID);
    }

    /**
     * Test-only toggle for the {@link #isAvailable()} return value.
     *
     * @param available {@code true} to advertise the provider as loaded /
     *                  reachable; {@code false} to simulate the mod being
     *                  uninstalled.
     */
    public void setAvailable(boolean available) {
        this.available = available;
    }

    /**
     * Test-only setter for {@link #getBaseCurrencyItemId()}. Pass a vanilla
     * item id like {@code "minecraft:emerald"} to make the stub advertise a
     * base currency for the auto-seed / item-match tests, or {@code null} to
     * revert to the "no base currency declared" default.
     *
     * @param itemId resource-location string or {@code null}.
     */
    public void setBaseCurrencyItemId(@Nullable String itemId) {
        this.baseCurrencyItemId = itemId;
    }

    @Override
    public @Nullable String getBaseCurrencyItemId() {
        return baseCurrencyItemId;
    }

    // -----------------------------------------------------------------------
    // ExternalCurrencyProvider contract
    // -----------------------------------------------------------------------

    @Override
    public @NotNull String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public @NotNull List<ExternalAccountRef> listBindableAccounts(@NotNull UUID player) {
        List<ExternalAccountRef> refs = new ArrayList<>();
        for (StubAccount account : accounts.values()) {
            refs.add(account.ref());
        }
        return refs;
    }

    @Override
    public @Nullable ExternalAccount open(@NotNull ExternalAccountRef ref) {
        if (!PROVIDER_ID.equals(ref.providerId())) return null;
        return accounts.get(ref.accountKey());
    }

    /**
     * Advertises the full set of {@link ProviderFeature} flags so tests can
     * exercise every branch that inspects them (e.g. the
     * {@link ProviderFeature#SUFFICIENT_FUNDS_CHECK}-authoritative path in
     * {@link net.kroia.banksystem.banking.bank.ServerBank#withdraw(long)}).
     */
    @Override
    public @NotNull Set<ProviderFeature> features() {
        return EnumSet.allOf(ProviderFeature.class);
    }

    // -----------------------------------------------------------------------
    // Nested StubAccount type
    // -----------------------------------------------------------------------

    /**
     * One controllable fake account. Every operation is confined to in-memory
     * state on this instance; no side effects reach the real world.
     * <p>
     * Beyond the {@link ExternalAccount} contract, {@code StubAccount} exposes
     * two test-only back-doors:
     * <ul>
     *   <li>{@link #setBalance(long)} — direct balance rewrite, used by the
     *       drift-clamp test to simulate a player using the underlying mod's
     *       own UI between two BankSystem calls.</li>
     *   <li>{@link #setOverflowCeiling(long)} — cap on the maximum representable
     *       balance, used by the overflow-guard test to simulate Numismatics'
     *       {@link Integer#MAX_VALUE} spur cap without needing a real 32-bit
     *       overflow. A deposit that would push the balance above the ceiling
     *       returns {@code false}, matching the "no state change on overflow"
     *       contract of the SPI.</li>
     * </ul>
     */
    public static final class StubAccount implements ExternalAccount {

        private final String accountKey;
        private final boolean shared;
        private long balance;
        private long overflowCeiling = Long.MAX_VALUE;
        private final Set<UUID> members = new HashSet<>();

        private StubAccount(@NotNull String accountKey, boolean shared, long initialBalance) {
            this.accountKey = accountKey;
            this.shared = shared;
            this.balance = Math.max(0L, initialBalance);
        }

        @Override
        public @NotNull ExternalAccountRef ref() {
            return new ExternalAccountRef(PROVIDER_ID, accountKey, "Stub:" + accountKey, shared);
        }

        @Override
        public long getBalance() {
            return balance;
        }

        @Override
        public boolean deposit(long amount) {
            if (amount < 0) return false;
            if (amount == 0) return true;
            // Overflow-safe ceiling check — refuse the deposit if adding
            // amount would land the balance above the overflow ceiling OR
            // above Long.MAX_VALUE.
            if (Long.MAX_VALUE - balance < amount) return false;
            long next = balance + amount;
            if (next > overflowCeiling) return false;
            balance = next;
            return true;
        }

        @Override
        public boolean withdraw(long amount) {
            if (amount < 0) return false;
            if (amount == 0) return true;
            if (balance < amount) return false;
            balance -= amount;
            return true;
        }

        @Override
        public boolean canWithdraw(long amount) {
            return amount >= 0 && balance >= amount;
        }

        @Override
        public boolean isSharedAccount() {
            return shared;
        }

        @Override
        public @NotNull Set<UUID> currentMembers() {
            return new HashSet<>(members);
        }

        @Override
        public void syncMembership(@NotNull Set<UUID> withdrawCapableUuids) {
            members.clear();
            members.addAll(withdrawCapableUuids);
        }

        // ----- Test-only back-doors ------------------------------------------

        /**
         * Test-only: overwrite the balance directly, bypassing
         * {@link #deposit(long)} / {@link #withdraw(long)}. Used to simulate a
         * player using the underlying mod's own UI (Numismatics Bank Terminal,
         * LC ATM, ...) between two BankSystem calls — the drift-clamp branch
         * in {@link net.kroia.banksystem.banking.bank.ServerBank} depends on
         * being able to reproduce that scenario deterministically.
         *
         * @param newBalance new balance; negative values are clamped to zero.
         */
        public void setBalance(long newBalance) {
            this.balance = Math.max(0L, newBalance);
        }

        /**
         * Test-only: cap on the account's maximum representable balance. A
         * deposit that would push {@link #getBalance()} above this ceiling
         * fails with {@code false} and no state change. Default is
         * {@link Long#MAX_VALUE} (effectively unlimited).
         *
         * @param ceiling upper bound on the balance; negative values are
         *                clamped to zero.
         */
        public void setOverflowCeiling(long ceiling) {
            this.overflowCeiling = Math.max(0L, ceiling);
        }

        /** @return the current overflow ceiling (default {@link Long#MAX_VALUE}). */
        public long getOverflowCeiling() {
            return overflowCeiling;
        }

        /** @return the account key this stub was created with. */
        public @NotNull String getAccountKey() {
            return accountKey;
        }
    }
}
