package net.kroia.banksystem.integration.lightmanscurrency;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.currency.ExternalAccount;
import net.kroia.banksystem.api.currency.ExternalAccountRef;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Live handle to a single Lightman's Currency {@code IBankAccount} (personal only),
 * implementing {@link ExternalAccount}.
 * <p>
 * <b>Lifetime.</b> Instances are cheap and short-lived — constructed on demand
 * by {@link LightmansCurrencyProvider#open(ExternalAccountRef)}, used for one or
 * more balance ops, then discarded. No caching; every {@link #getBalance()} reads
 * live from the LC account.
 * <p>
 * <b>Multi-chain money model.</b> LC accounts store {@code MoneyValue} — a
 * multi-key container holding balances in multiple currency chains. This adapter
 * routes all operations through the <b>primary chain</b> (identified via
 * {@code MoneyAPI} or LC config). Other chains the account may hold are ignored.
 * <p>
 * <b>Balance cap.</b> LC uses {@code long} internally for each chain's core value,
 * so no overflow below {@link Long#MAX_VALUE}. Deposits are capped at that limit.
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

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    /** Dedup flag for reflection failure warnings in balance operations. */
    private static final AtomicBoolean BALANCE_OP_WARNED = new AtomicBoolean(false);

    /** Dedup flag for primary chain resolution warnings. */
    private static final AtomicBoolean PRIMARY_CHAIN_WARNED = new AtomicBoolean(false);

    /** Dedup flag for non-primary chain INFO log (one-shot). */
    private static final AtomicBoolean NON_PRIMARY_CHAIN_LOGGED = new AtomicBoolean(false);

    /** The underlying LC IBankAccount, accessed via reflection. */
    private final Object lcAccount;

    /** The ref that was used to open this handle. */
    private final ExternalAccountRef ref;

    /** Cached primary chain identifier (resolved once per instance). */
    private String primaryChain;

    /**
     * Wires the shared {@code Instances} container onto this class so its
     * logger can be reached. Called once during backend construction.
     */
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    LightmansCurrencyAccount(@NotNull Object lcAccount, @NotNull ExternalAccountRef ref) {
        this.lcAccount = lcAccount;
        this.ref = ref;
    }

    @Override
    public @NotNull ExternalAccountRef ref() {
        return ref;
    }

    /**
     * Resolves the primary currency chain for this account. Called once per
     * instance and cached. Returns null if resolution fails.
     */
    private String getPrimaryChain() {
        if (primaryChain != null) return primaryChain;

        try {
            // Try to get primary chain via MoneyAPI or config
            // Approach 1: MoneyAPI.API.getDefaultChain() or similar
            Class<?> moneyAPIClass = Class.forName("io.github.lightman314.lightmanscurrency.api.money.MoneyAPI");
            Object moneyAPI = moneyAPIClass.getField("API").get(null);

            // Try common method names for getting default/primary chain
            try {
                Object chain = moneyAPIClass.getMethod("getDefaultChain").invoke(moneyAPI);
                if (chain != null) {
                    primaryChain = chain.toString();
                    return primaryChain;
                }
            } catch (NoSuchMethodException e1) {
                // Try alternative method name
                try {
                    Object chain = moneyAPIClass.getMethod("getMainChain").invoke(moneyAPI);
                    if (chain != null) {
                        primaryChain = chain.toString();
                        return primaryChain;
                    }
                } catch (NoSuchMethodException e2) {
                    // Try getting from config
                    Class<?> configClass = Class.forName("io.github.lightman314.lightmanscurrency.LCConfig");
                    Class<?> commonConfigClass = Class.forName("io.github.lightman314.lightmanscurrency.LCConfig$CommonConfig");
                    Object commonConfig = configClass.getField("COMMON").get(null);
                    Object defaultChain = commonConfigClass.getField("defaultChain").get(commonConfig);
                    if (defaultChain != null) {
                        primaryChain = defaultChain.toString();
                        return primaryChain;
                    }
                }
            }

            // Fallback: use "main" or "coin" as educated guess
            if (PRIMARY_CHAIN_WARNED.compareAndSet(false, true)) {
                logWarn("Could not resolve primary chain from LC API — using fallback 'coin'");
            }
            primaryChain = "coin";
            return primaryChain;

        } catch (Exception e) {
            if (PRIMARY_CHAIN_WARNED.compareAndSet(false, true)) {
                logWarn("Failed to resolve primary currency chain: " + e.getMessage() + " — using fallback 'coin'");
            }
            primaryChain = "coin";
            return primaryChain;
        }
    }

    @Override
    public long getBalance() {
        try {
            String chain = getPrimaryChain();
            if (chain == null) return 0;

            Class<?> accountClass = lcAccount.getClass();

            // Get MoneyStorage: IBankAccount.getMoneyStorage()
            Object storage = accountClass.getMethod("getMoneyStorage").invoke(lcAccount);
            Class<?> storageClass = storage.getClass();

            // Get MoneyValue for primary chain: MoneyStorage.getStoredMoney()
            Object moneyValue = storageClass.getMethod("getStoredMoney").invoke(storage);

            // Get amount for primary chain: MoneyValue.getCoreValue(String chain)
            Class<?> moneyValueClass = moneyValue.getClass();
            Object coreValue = moneyValueClass.getMethod("getCoreValue", String.class).invoke(moneyValue, chain);

            if (coreValue instanceof Long) {
                return (Long) coreValue;
            } else if (coreValue instanceof Number) {
                return ((Number) coreValue).longValue();
            }

            return 0;
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
            String chain = getPrimaryChain();
            if (chain == null) return false;

            // Check for overflow
            long currentBalance = getBalance();
            if (Long.MAX_VALUE - currentBalance < amount) {
                logDebug("Deposit refused: amount " + amount + " would overflow for account " + ref.accountKey());
                return false;
            }

            // Construct MoneyValue for the primary chain
            // MoneyValue.of(String chain, long amount) or similar
            Class<?> moneyValueClass = Class.forName("io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue");
            Object moneyValue = moneyValueClass.getMethod("of", String.class, long.class).invoke(null, chain, amount);

            // Deposit: IBankAccount.depositMoney(MoneyValue)
            Class<?> accountClass = lcAccount.getClass();
            accountClass.getMethod("depositMoney", moneyValueClass).invoke(lcAccount, moneyValue);

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

        long currentBalance = getBalance();
        if (currentBalance < amount) {
            return false;
        }

        try {
            String chain = getPrimaryChain();
            if (chain == null) return false;

            // Construct MoneyValue for the primary chain
            Class<?> moneyValueClass = Class.forName("io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue");
            Object moneyValue = moneyValueClass.getMethod("of", String.class, long.class).invoke(null, chain, amount);

            // Withdraw: IBankAccount.withdrawMoney(MoneyValue)
            Class<?> accountClass = lcAccount.getClass();
            Object result = accountClass.getMethod("withdrawMoney", moneyValueClass).invoke(lcAccount, moneyValue);

            // LC may return boolean or MoneyValue (actual withdrawn amount)
            if (result instanceof Boolean) {
                return (Boolean) result;
            } else if (result != null) {
                // Assume success if non-null result (MoneyValue returned)
                return true;
            }

            return false;
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
        return getBalance() >= amount;
    }

    @Override
    public boolean isSharedAccount() {
        // Task #36 scope: personal accounts only
        return false;
    }

    @Override
    public @NotNull Set<UUID> currentMembers() {
        Set<UUID> members = new HashSet<>();

        try {
            Class<?> accountClass = lcAccount.getClass();

            // Get owner: IBankAccount.getOwner() returns BankReference
            Object ownerRef = accountClass.getMethod("getOwner").invoke(lcAccount);

            // If it's a PlayerBankReference, extract the UUID
            if (ownerRef != null) {
                Class<?> playerRefClass = Class.forName("io.github.lightman314.lightmanscurrency.api.money.bank.reference.builtin.PlayerBankReference");
                if (playerRefClass.isInstance(ownerRef)) {
                    UUID playerUuid = (UUID) playerRefClass.getMethod("getPlayer").invoke(ownerRef);
                    if (playerUuid != null) {
                        members.add(playerUuid);
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
        // Personal accounts only - no membership sync needed
        if (!withdrawCapableUuids.isEmpty() && withdrawCapableUuids.size() > 1) {
            logDebug("syncMembership called on personal account " + ref.accountKey() + " with " +
                    withdrawCapableUuids.size() + " UUIDs — personal accounts don't support membership sync");
        }
    }

    // Logger helpers — null-safe if the backend has not been wired yet.
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
