package net.kroia.banksystem.integration.numismatics;

import net.kroia.banksystem.BankSystemModBackend;
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
    public long getBalance() {
        try {
            Class<?> accountClass = numismaticsAccount.getClass();
            int spurs = (int) accountClass.getMethod("getBalance").invoke(numismaticsAccount);
            return spurs;
        } catch (Exception e) {
            if (BALANCE_OP_WARNED.compareAndSet(false, true)) {
                logWarn("Failed to read balance for account " + ref.accountKey() + ": " + e.getMessage());
            }
            return 0;
        }
    }

    @Override
    public boolean deposit(long amount) {
        if (amount < 0) return false;
        if (amount == 0) return true;

        try {
            long currentBalance = getBalance();
            if (Integer.MAX_VALUE - currentBalance < amount) {
                logDebug("Deposit refused: amount " + amount + " would overflow 32-bit cap for account " + ref.accountKey());
                return false;
            }

            int spursToDeposit = (int) amount;
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
        if (amount < 0) return false;
        if (amount == 0) return true;
        if (amount > Integer.MAX_VALUE) return false;

        long currentBalance = getBalance();
        if (currentBalance < amount) {
            return false;
        }

        try {
            int spursToDeduct = (int) amount;
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
        if (amount > Integer.MAX_VALUE) return false;
        return getBalance() >= amount;
    }

    @Override
    public boolean isSharedAccount() {
        try {
            Class<?> accountClass = numismaticsAccount.getClass();
            Object type = accountClass.getMethod("getType").invoke(numismaticsAccount);
            String typeName = type.toString();
            return "BLAZE_BANKER".equals(typeName);
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

            UUID ownerId = (UUID) accountClass.getMethod("getId").invoke(numismaticsAccount);
            members.add(ownerId);

            if (isSharedAccount()) {
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
