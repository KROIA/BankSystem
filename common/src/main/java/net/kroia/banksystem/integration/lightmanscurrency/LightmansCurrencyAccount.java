package net.kroia.banksystem.integration.lightmanscurrency;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.currency.ExternalAccount;
import net.kroia.banksystem.api.currency.ExternalAccountRef;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Live handle to a single Lightman's Currency {@code IBankAccount} — personal
 * <i>or</i> team — implementing {@link ExternalAccount}.
 * <p>
 * <b>Lifetime.</b> Instances are cheap and short-lived — constructed on demand
 * by {@link LightmansCurrencyProvider#open(ExternalAccountRef)}, used for one or
 * more balance ops, then discarded. No caching; every {@link #getBalance()} reads
 * live from the LC account.
 * <p>
 * <b>Personal vs. team.</b> Both variants use the same underlying
 * {@code IBankAccount} API for balance ops ({@code getMoneyStorage},
 * {@code depositMoney}, {@code withdrawMoney}), so the balance code path is
 * identical. The only per-variant divergence is {@link #currentMembers()} —
 * personal returns the single owner UUID, team returns the full
 * {@code ITeam.getAllMembers()} set. {@link #isSharedAccount()} discriminates.
 * <p>
 * <b>Coin chain routing.</b> LC accounts hold a {@code MoneyStorage} that lists
 * every {@code MoneyValue} the account currently owns (one per chain). This adapter
 * only accounts for values whose {@code getUniqueName()} starts with
 * {@code "lightmanscurrency:coin"} — i.e. the ship-default coin chain
 * (copper/iron/gold/emerald/diamond/netherite denominations). Community-modded
 * currency chains are intentionally ignored.
 * <p>
 * <b>MoneyValue construction.</b> LC exposes {@code CoinValue.fromNumber(String chain, long)}
 * — a static factory that builds a coin-chain MoneyValue at the requested core amount
 * without needing a seed. This works on empty accounts and on accounts holding any mix
 * of denominations. LC's internal denomination allocation handles the split.
 * <p>
 * <b>Balance cap.</b> LC uses {@code long} internally per chain's core value, so no
 * overflow below {@link Long#MAX_VALUE}. Deposits are capped at that limit.
 * <p>
 * <b>Simulate support.</b> LC has no authoritative simulate primitive. We implement
 * {@link #canWithdraw(long)} as a balance-comparison check (non-authoritative).
 * <p>
 * <b>Server-thread only.</b> All methods assume server-thread invocation per
 * the {@link ExternalAccount} contract. No thread-safety.
 *
 * @since 2.0.5
 * @see LightmansCurrencyProvider
 * @see ExternalAccount
 */
public final class LightmansCurrencyAccount implements ExternalAccount {

    /** LC's coin chain unique-name prefix. Every ship-default coin denomination
     *  ({@code coin_copper}, {@code coin_gold}, ...) starts with this. */
    private static final String COIN_CHAIN_PREFIX = "lightmanscurrency:coin";

    /**
     * LC's default coin chain identifier passed to {@code CoinValue.fromNumber(String, long)}.
     * Matches {@code CoinAPI.MAIN_CHAIN} — the vanilla default chain. Resolved at first use via
     * reflection with a hardcoded fallback to remain robust to LC constant renames.
     */
    private static volatile String COIN_CHAIN_NAME = null;
    private static final String COIN_CHAIN_NAME_FALLBACK = "main";

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    /** Dedup flag for reflection failure warnings in balance operations. */
    private static final AtomicBoolean BALANCE_OP_WARNED = new AtomicBoolean(false);

    /** Dedup flag for CoinValue.fromNumber construction failures. */
    private static final AtomicBoolean COIN_BUILD_WARNED = new AtomicBoolean(false);

    /** Dedup flag for team-membership resolution failures. */
    private static final AtomicBoolean TEAM_MEMBERS_WARNED = new AtomicBoolean(false);

    /** The underlying LC IBankAccount, accessed via reflection. */
    private final Object lcAccount;

    /** The ref that was used to open this handle. */
    private final ExternalAccountRef ref;

    /**
     * UUID of the player who owns this personal LC account. {@code null} for team
     * accounts — the membership set comes from {@link #lcTeam} in that case.
     */
    private final @Nullable UUID ownerUuid;

    /**
     * The underlying LC {@code ITeam} instance for team-account handles. {@code null}
     * for personal accounts. Passed in at open() time so {@link #currentMembers()}
     * can call {@code getAllMembers()} without re-resolving the team from the
     * account key on every read.
     */
    private final @Nullable Object lcTeam;

    /**
     * Wires the shared {@code Instances} container onto this class so its
     * logger can be reached. Called once during backend construction.
     */
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    /**
     * Personal-account constructor. Owner UUID must be non-null; delegates to the
     * team-aware constructor with {@code lcTeam = null}.
     */
    LightmansCurrencyAccount(@NotNull Object lcAccount, @NotNull ExternalAccountRef ref, @NotNull UUID ownerUuid) {
        this(lcAccount, ref, ownerUuid, null);
    }

    /**
     * Team-aware constructor.
     * <p>
     * For personal accounts: {@code ownerUuid} is the owning player and
     * {@code lcTeam} is {@code null}.
     * For team accounts: {@code ownerUuid} is {@code null} (the membership set is
     * derived from {@code lcTeam.getAllMembers()}) and {@code lcTeam} is the
     * underlying LC {@code ITeam} instance.
     */
    LightmansCurrencyAccount(@NotNull Object lcAccount,
                             @NotNull ExternalAccountRef ref,
                             @Nullable UUID ownerUuid,
                             @Nullable Object lcTeam) {
        this.lcAccount = lcAccount;
        this.ref = ref;
        this.ownerUuid = ownerUuid;
        this.lcTeam = lcTeam;
    }

    @Override
    public @NotNull ExternalAccountRef ref() {
        return ref;
    }

    @Override
    public long nativeScale() {
        // LC's coin chain stores each value's smallest unit 1:1 with BankSystem raw units
        // under the default LC config. Servers that retune LC's ChainData may see rounding
        // toward the smaller unit for sub-unit deltas — same accepted approximation as the
        // Numismatics adapter, but tighter (LC's finest denom is finer than a spur).
        return 1L;
    }

    /**
     * Resolves LC's canonical main-chain identifier via {@code CoinAPI.MAIN_CHAIN}, caching
     * the result. Falls back to {@link #COIN_CHAIN_NAME_FALLBACK} on reflection failure.
     */
    private static String resolveCoinChainName() {
        String cached = COIN_CHAIN_NAME;
        if (cached != null) return cached;
        try {
            Class<?> coinAPIClass = Class.forName("io.github.lightman314.lightmanscurrency.api.money.coins.CoinAPI");
            Object value = coinAPIClass.getField("MAIN_CHAIN").get(null);
            if (value instanceof String s && !s.isEmpty()) {
                COIN_CHAIN_NAME = s;
                return s;
            }
        } catch (ReflectiveOperationException ignored) {
            // Fall through to fallback.
        }
        COIN_CHAIN_NAME = COIN_CHAIN_NAME_FALLBACK;
        return COIN_CHAIN_NAME_FALLBACK;
    }

    /**
     * Constructs a coin-chain {@code MoneyValue} at the given core amount via LC's
     * {@code CoinValue.fromNumber(String chain, long value)} static factory. Works on
     * empty accounts (no seed required) and on accounts holding any mix of
     * denominations — LC internally spreads the amount across denominations on deposit
     * and pulls from the smallest denomination first on withdraw.
     *
     * @return the constructed MoneyValue, or {@code null} on reflection failure.
     */
    private static Object buildCoinMoneyValue(long amount) {
        String chainName = resolveCoinChainName();
        try {
            Class<?> coinValueClass = Class.forName(
                    "io.github.lightman314.lightmanscurrency.api.money.value.builtin.CoinValue");
            return coinValueClass.getMethod("fromNumber", String.class, long.class)
                    .invoke(null, chainName, amount);
        } catch (ReflectiveOperationException e) {
            if (COIN_BUILD_WARNED.compareAndSet(false, true)) {
                logWarn("Failed to build CoinValue for amount " + amount + " via CoinValue.fromNumber('"
                        + chainName + "', long): " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return null;
        }
    }

    /** Reflective read of {@code MoneyValue.getUniqueName()}. Returns {@code null} on failure. */
    private static String readUniqueName(Object moneyValue) {
        try {
            Object name = moneyValue.getClass().getMethod("getUniqueName").invoke(moneyValue);
            return name != null ? name.toString() : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** Reflective read of {@code MoneyValue.getCoreValue()}. Returns 0L on failure. */
    private static long readCoreValue(Object moneyValue) {
        try {
            Object core = moneyValue.getClass().getMethod("getCoreValue").invoke(moneyValue);
            if (core instanceof Number n) return n.longValue();
            return 0L;
        } catch (ReflectiveOperationException e) {
            return 0L;
        }
    }

    @Override
    public long getBalance() {
        try {
            Object storage = lcAccount.getClass().getMethod("getMoneyStorage").invoke(lcAccount);
            if (storage == null) return 0L;

            Object allValuesObj = storage.getClass().getMethod("allValues").invoke(storage);
            if (!(allValuesObj instanceof List<?> allValues)) return 0L;

            long sum = 0L;
            for (Object mv : allValues) {
                if (mv == null) continue;
                String uniqueName = readUniqueName(mv);
                if (uniqueName == null || !uniqueName.startsWith(COIN_CHAIN_PREFIX)) continue;
                long core = readCoreValue(mv);
                // Saturate on overflow rather than wrapping — treat as "at cap".
                if (core > 0 && sum > Long.MAX_VALUE - core) {
                    return Long.MAX_VALUE;
                }
                sum += core;
            }
            return sum < 0 ? 0L : sum;
        } catch (ReflectiveOperationException e) {
            if (BALANCE_OP_WARNED.compareAndSet(false, true)) {
                logWarn("Failed to read balance for account " + ref.accountKey() + ": "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return 0L;
        }
    }

    @Override
    public boolean deposit(long amount) {
        if (amount < 0) return false;
        if (amount == 0) return true;

        // Overflow check against Long.MAX_VALUE.
        long currentBalance = getBalance();
        if (Long.MAX_VALUE - currentBalance < amount) {
            logDebug("Deposit refused: amount " + amount + " would overflow for account " + ref.accountKey());
            return false;
        }

        Object target = buildCoinMoneyValue(amount);
        if (target == null) return false;

        // Diagnostic trace at INFO — surfaces what MoneyValue was actually built so we can
        // triage silent refusals. Cheap: fires per deposit attempt, bounded by upstream call rate.
        logInfo("Deposit attempt on account " + ref.accountKey() + " (shared=" + isSharedAccount()
                + "): amount=" + amount + ", MoneyValue=" + describeMoneyValue(target));

        // Route through BankAPI.BankDepositFromServer(account, MoneyValue, notifyPlayers=false)
        // — returns boolean so we know whether LC actually accepted the funds. The 3-arg
        // form's third boolean is the "notifyPlayers" flag (the 2-arg wrapper defaults it to
        // true, which fires LC's "An admin deposited X coins" chat message on every op — too
        // noisy for the transparent-routing scenario where BankSystem is programmatically
        // driving the deposit rather than an admin manually acting). Passing false silences
        // the notification without changing the accept/reject semantics.
        try {
            Class<?> bankAPIClass = Class.forName(
                    "io.github.lightman314.lightmanscurrency.api.money.bank.BankAPI");
            Class<?> ibankAccountClass = Class.forName(
                    "io.github.lightman314.lightmanscurrency.api.money.bank.IBankAccount");
            Class<?> moneyValueClass = Class.forName(
                    "io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue");

            Object bankAPI = bankAPIClass.getMethod("getApi").invoke(null);
            Object result = bankAPIClass.getMethod(
                    "BankDepositFromServer", ibankAccountClass, moneyValueClass, boolean.class)
                    .invoke(bankAPI, lcAccount, target, /*notifyPlayers=*/false);
            boolean accepted = (result instanceof Boolean b) && b;
            if (!accepted) {
                logWarn("Deposit refused by LC: account " + ref.accountKey() + " (shared="
                        + isSharedAccount() + "), amount " + amount
                        + " — LC returned false from BankDepositFromServer. Likely causes: team "
                        + "bank limit exceeded, account not writable in current LC state, or MoneyValue "
                        + "constructed with an unregistered chain. Balance BEFORE attempt: " + currentBalance);
            } else {
                logInfo("Deposit accepted by LC: account " + ref.accountKey() + ", amount " + amount);
            }
            return accepted;
        } catch (ReflectiveOperationException e) {
            if (BALANCE_OP_WARNED.compareAndSet(false, true)) {
                logWarn("Failed to deposit to account " + ref.accountKey() + ": "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return false;
        }
    }

    /**
     * Best-effort diagnostic string for a MoneyValue instance. Never throws.
     * Format: {@code CoinValue[uniqueName=..., core=..., empty=..., invalid=...]}.
     */
    private static String describeMoneyValue(Object mv) {
        if (mv == null) return "null";
        String cls = mv.getClass().getSimpleName();
        String uniqueName = readUniqueName(mv);
        long core = readCoreValue(mv);
        String empty = "?";
        String invalid = "?";
        try {
            Object e = mv.getClass().getMethod("isEmpty").invoke(mv);
            if (e instanceof Boolean b) empty = String.valueOf(b);
        } catch (ReflectiveOperationException ignored) {}
        try {
            Object i = mv.getClass().getMethod("isInvalid").invoke(mv);
            if (i instanceof Boolean b) invalid = String.valueOf(b);
        } catch (ReflectiveOperationException ignored) {}
        return cls + "[uniqueName=" + uniqueName + ", core=" + core + ", empty=" + empty + ", invalid=" + invalid + "]";
    }

    @Override
    public boolean withdraw(long amount) {
        if (amount < 0) return false;
        if (amount == 0) return true;

        // Fast reject: don't call the API if we already know the balance is short.
        long currentBalance = getBalance();
        if (currentBalance < amount) return false;

        Object target = buildCoinMoneyValue(amount);
        if (target == null) return false;

        // BankAPI.BankWithdrawFromServer(account, MoneyValue, notifyPlayers=false) — returns
        // Pair<Boolean, MoneyValue>. Same third-arg semantics as the deposit path: silence
        // the "An admin withdrew X" notification without changing the accept/reject behaviour.
        try {
            Class<?> bankAPIClass = Class.forName(
                    "io.github.lightman314.lightmanscurrency.api.money.bank.BankAPI");
            Class<?> ibankAccountClass = Class.forName(
                    "io.github.lightman314.lightmanscurrency.api.money.bank.IBankAccount");
            Class<?> moneyValueClass = Class.forName(
                    "io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue");

            Object bankAPI = bankAPIClass.getMethod("getApi").invoke(null);
            Object pair = bankAPIClass.getMethod(
                    "BankWithdrawFromServer", ibankAccountClass, moneyValueClass, boolean.class)
                    .invoke(bankAPI, lcAccount, target, /*notifyPlayers=*/false);
            if (pair == null) return false;

            Object first = pair.getClass().getMethod("getFirst").invoke(pair);
            if (!(first instanceof Boolean success) || !success) {
                logDebug("Withdraw refused by LC: account " + ref.accountKey() + " (shared="
                        + isSharedAccount() + "), amount " + amount);
                return false;
            }
            Object withdrawn = pair.getClass().getMethod("getSecond").invoke(pair);
            if (withdrawn == null) return false;
            long withdrawnCore = readCoreValue(withdrawn);
            return withdrawnCore >= amount;
        } catch (ReflectiveOperationException e) {
            if (BALANCE_OP_WARNED.compareAndSet(false, true)) {
                logWarn("Failed to withdraw from account " + ref.accountKey() + ": "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return false;
        }
    }

    @Override
    public boolean canWithdraw(long amount) {
        if (amount < 0) return false;
        // Advisory only — LC's BankAPI has no simulate primitive that doesn't perform the
        // real operation. MoneyStorage's insertMoney/extractMoney do carry a boolean that
        // MAY be simulate, but the LC API doesn't document it and interpreting it wrong here
        // would cause silent real-money movement on every balance pre-check. Provider does
        // NOT advertise SUFFICIENT_FUNDS_CHECK — BankSystem treats the result as advisory
        // and the actual withdraw call will surface the authoritative reject if the balance
        // moved between the check and the op.
        return getBalance() >= amount;
    }

    @Override
    public boolean isSharedAccount() {
        // Team accounts carry a non-null ITeam; personal accounts carry null.
        return lcTeam != null;
    }

    @Override
    public @NotNull Set<UUID> currentMembers() {
        if (lcTeam == null) {
            // Personal — exactly one member: the owner. Passed in at construction so no
            // reflection needed. ownerUuid is @NotNull on this path (constructor contract).
            return Set.of(ownerUuid);
        }
        // Team — walk ITeam.getAllMembers() (default method: owner + admins + members).
        // Each entry is a PlayerReference; UUID comes from its `public final UUID id` field.
        try {
            Object membersObj = lcTeam.getClass().getMethod("getAllMembers").invoke(lcTeam);
            if (!(membersObj instanceof List<?> members)) return Set.of();

            Set<UUID> result = new HashSet<>(members.size());
            for (Object pr : members) {
                if (pr == null) continue;
                // PlayerReference.id is a public final field per api-dump.
                Object idValue = pr.getClass().getField("id").get(pr);
                if (idValue instanceof UUID uuid) result.add(uuid);
            }
            return result;
        } catch (ReflectiveOperationException e) {
            if (TEAM_MEMBERS_WARNED.compareAndSet(false, true)) {
                logWarn("Failed to resolve team membership for account " + ref.accountKey() + ": "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return Set.of();
        }
    }

    @Override
    public void syncMembership(@NotNull Set<UUID> withdrawCapableUuids) {
        // No-op — provider does not advertise MEMBERSHIP_SYNC.
        // Personal accounts have no membership set to mutate. Team accounts DO have one,
        // but LC's ITeam exposes no external mutator — team member changes must go
        // through LC's own invite/promote/demote flow. Per ExternalAccount#syncMembership
        // javadoc this is best-effort; the caller expects a no-op when the feature bit
        // isn't advertised.
        if (withdrawCapableUuids.size() > 1) {
            logDebug("syncMembership called on account " + ref.accountKey() + " (shared="
                    + isSharedAccount() + ") with " + withdrawCapableUuids.size()
                    + " UUIDs — LC adapter does not propagate membership changes");
        }
    }

    // Logger helpers — null-safe if the backend has not been wired yet.
    private static void logInfo(String msg) {
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
            BACKEND_INSTANCES.LOGGER.info("[Lightman's] " + msg);
        }
    }

    private static void logWarn(String msg) {
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
            BACKEND_INSTANCES.LOGGER.warn("[Lightman's] " + msg);
        }
    }

    private static void logDebug(String msg) {
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
            BACKEND_INSTANCES.LOGGER.debug("[Lightman's] " + msg);
        }
    }
}
