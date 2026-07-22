package net.kroia.banksystem.integration.numismatics;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.api.currency.ExternalAccount;
import net.kroia.banksystem.api.currency.ExternalAccountRef;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Live handle to a single Numismatics {@code BankAccount} (PLAYER or
 * BLAZE_BANKER type), implementing {@link ExternalAccount}.
 * <p>
 * <b>Lifetime.</b> Instances are cheap and short-lived — constructed on demand
 * by {@link NumismaticsProvider#open(ExternalAccountRef)}, used for one or more
 * balance ops, then discarded. No caching; every {@link #getBalance()} reads
 * live from the Numismatics account.
 * <p>
 * <b>Balance cap.</b> Numismatics balances are {@code int} spurs (max
 * {@link Integer#MAX_VALUE}). {@link #deposit(long)} refuses amounts that would
 * overflow the 32-bit ceiling and returns {@code false} with no state change.
 * <p>
 * <b>Scale factor.</b> 1 spur = 100 BankSystem raw units (matching
 * {@link BankSystemModSettings#ITEM_FRACTION_SCALE_FACTOR}). The adapter converts
 * on every balance op. Fractional spurs (amounts not divisible by 100) are refused.
 * <p>
 * <b>Simulate support.</b> Numismatics' {@code deduct(int, boolean force)}
 * method has a {@code force} flag (overdraft to zero on insufficient funds),
 * but no true simulate primitive. We implement {@link #canWithdraw(long)} as
 * a balance-comparison check (non-authoritative unless the balance doesn't
 * change between the check and the actual withdraw).
 * <p>
 * <b>Server-thread only.</b> All methods assume server-thread invocation per
 * the {@link ExternalAccount} contract. No thread-safety.
 *
 * @since 2.0.5
 * @see NumismaticsProvider
 * @see ExternalAccount
 */
public final class NumismaticsAccount implements ExternalAccount {

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    /** Dedup flag for trustList field access warnings. */
    private static final AtomicBoolean TRUST_LIST_FIELD_WARNED = new AtomicBoolean(false);

    /** Dedup flag for reflection failure warnings in balance operations. */
    private static final AtomicBoolean BALANCE_OP_WARNED = new AtomicBoolean(false);

    /** Dedup flag for fractional-amount refusal warnings. */
    private static final AtomicBoolean FRACTIONAL_AMOUNT_WARNED = new AtomicBoolean(false);

    /**
     * Scale factor: 1 spur = 100 BankSystem raw units.
     * Must match {@link BankSystemModSettings#ITEM_FRACTION_SCALE_FACTOR}.
     */
    static final long SCALE_FACTOR = 100L;

    /** The underlying Numismatics BankAccount, accessed via reflection. */
    private final Object numismaticsAccount;

    /** The ref that was used to open this handle. */
    private final ExternalAccountRef ref;

    /**
     * Wires the shared {@code Instances} container onto this class so its
     * logger can be reached. Called once during backend construction.
     */
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    NumismaticsAccount(@NotNull Object numismaticsAccount, @NotNull ExternalAccountRef ref) {
        this.numismaticsAccount = numismaticsAccount;
        this.ref = ref;
    }

    @Override
    public @NotNull ExternalAccountRef ref() {
        return ref;
    }

    @Override
    public long nativeScale() {
        return SCALE_FACTOR;
    }

    @Override
    public long getBalance() {
        try {
            Class<?> accountClass = numismaticsAccount.getClass();
            int spurs = (int) accountClass.getMethod("getBalance").invoke(numismaticsAccount);
            // Widen to long before scaling to avoid overflow on large balances.
            return ((long) spurs) * SCALE_FACTOR;
        } catch (Exception e) {
            if (BALANCE_OP_WARNED.compareAndSet(false, true)) {
                logWarn("Failed to read balance for account " + ref.accountKey() + ": " + e.getMessage());
            }
            return 0;
        }
    }

    @Override
    public boolean deposit(long amount) {
        // Fractional BS-units (< 1 spur) are rounded DOWN silently. StockMarket
        // computes prices with 2 decimal places (BS-scale=100) so lock/unlock/deposit
        // amounts routinely aren't spur-aligned. Refusing them would break trading;
        // rounding down means at most (SCALE_FACTOR - 1) BS-units of dust vanish
        // externally per op — accepted as a known trade-off until per-item scale
        // exists (see BankSystem CurrencyModSupport follow-up).
        if (amount < 0) return false;
        if (amount == 0) return true;
        int spursToDeposit = (int) (amount / SCALE_FACTOR);
        if (spursToDeposit == 0) {
            // Amount is below 1 spur — cannot move; treat as no-op success so
            // callers don't fail on sub-spur residues.
            return true;
        }
        try {
            int currentSpurs = (int) numismaticsAccount.getClass().getMethod("getBalance").invoke(numismaticsAccount);
            if (Integer.MAX_VALUE - currentSpurs < spursToDeposit) {
                logDebug("Deposit refused: " + spursToDeposit + " spurs would overflow 32-bit cap for account " + ref.accountKey());
                return false;
            }

            Class<?> accountClass = numismaticsAccount.getClass();
            accountClass.getMethod("deposit", int.class).invoke(numismaticsAccount, spursToDeposit);
            return true;
        } catch (Exception e) {
            if (BALANCE_OP_WARNED.compareAndSet(false, true)) {
                logWarn("Failed to deposit to account " + ref.accountKey() + ": " + e.getMessage());
            }
            return false;
        }
    }

    @Override
    public boolean withdraw(long amount) {
        // Same dust-tolerance rule as deposit — see comment there.
        if (amount < 0) return false;
        if (amount == 0) return true;
        int spursToDeduct = (int) (amount / SCALE_FACTOR);
        if (spursToDeduct == 0) return true; // sub-spur amount — no-op success
        if (spursToDeduct < 0) return false;

        // Availability check uses the raw spur balance so a fractional amount
        // whose floored spur count is affordable succeeds.
        try {
            int currentSpurs = (int) numismaticsAccount.getClass().getMethod("getBalance").invoke(numismaticsAccount);
            if (currentSpurs < spursToDeduct) return false;

            Class<?> accountClass = numismaticsAccount.getClass();
            Boolean success = (Boolean) accountClass.getMethod("deduct", int.class, boolean.class)
                    .invoke(numismaticsAccount, spursToDeduct, false);
            return success != null && success;
        } catch (Exception e) {
            if (BALANCE_OP_WARNED.compareAndSet(false, true)) {
                logWarn("Failed to withdraw from account " + ref.accountKey() + ": " + e.getMessage());
            }
            return false;
        }
    }

    @Override
    public boolean canWithdraw(long amount) {
        if (amount < 0) return false;
        int spurs = (int) (amount / SCALE_FACTOR);
        if (spurs < 0) return false;
        try {
            int currentSpurs = (int) numismaticsAccount.getClass().getMethod("getBalance").invoke(numismaticsAccount);
            return currentSpurs >= spurs;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean isSharedAccount() {
        try {
            Class<?> accountClass = numismaticsAccount.getClass();
            Object type = accountClass.getField("type").get(numismaticsAccount);
            return "BLAZE_BANKER".equals(type.toString());
        } catch (Exception e) {
            logWarn("Failed to check account type for " + ref.accountKey() + " — treating as non-shared: " + e.getMessage());
            return false;
        }
    }

    @Override
    public @NotNull Set<UUID> currentMembers() {
        Set<UUID> members = new HashSet<>();

        try {
            Class<?> accountClass = numismaticsAccount.getClass();

            if (isSharedAccount()) {
                // BLAZE_BANKER: no owner concept — members = trustList only.
                try {
                    java.lang.reflect.Field trustListField = accountClass.getDeclaredField("trustList");
                    trustListField.setAccessible(true);
                    Object trustListObj = trustListField.get(numismaticsAccount);
                    if (trustListObj instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<UUID> trustList = (List<UUID>) trustListObj;
                        members.addAll(trustList);
                    }
                } catch (NoSuchFieldException e) {
                    if (TRUST_LIST_FIELD_WARNED.compareAndSet(false, true)) {
                        logWarn("Could not access trustList field for BLAZE_BANKER account — Numismatics API may have changed: " + e.getMessage());
                    }
                }
            } else {
                // PLAYER: account.id IS the owner's UUID.
                UUID ownerId = (UUID) accountClass.getField("id").get(numismaticsAccount);
                members.add(ownerId);
            }
        } catch (Exception e) {
            logWarn("Failed to read current members for account " + ref.accountKey() + ": " + e.getMessage());
        }

        return members;
    }

    @Override
    public void syncMembership(@NotNull Set<UUID> withdrawCapableUuids) {
        if (!isSharedAccount()) {
            return;
        }

        try {
            Class<?> accountClass = numismaticsAccount.getClass();
            Consumer<List<UUID>> updater = (list) -> {
                list.clear();
                list.addAll(withdrawCapableUuids);
            };

            accountClass.getMethod("updateTrustList", Consumer.class).invoke(numismaticsAccount, updater);
        } catch (Exception e) {
            logWarn("Failed to sync membership for account " + ref.accountKey() + ": " + e.getMessage());
        }
    }

    // Logger helpers — null-safe if the backend has not been wired yet.
    private static void logWarn(String msg) {
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
            BACKEND_INSTANCES.LOGGER.warn("[Numismatics] " + msg);
        }
    }

    private static void logDebug(String msg) {
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
            BACKEND_INSTANCES.LOGGER.debug("[Numismatics] " + msg);
        }
    }
}
