package net.kroia.banksystem.integration.lightmanscurrency;

import dev.architectury.platform.Platform;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.currency.ExternalAccount;
import net.kroia.banksystem.api.currency.ExternalAccountRef;
import net.kroia.banksystem.api.currency.ExternalCurrencyProvider;
import net.kroia.banksystem.api.currency.ProviderFeature;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link ExternalCurrencyProvider} adapter for Lightman's Currency
 * (github.com/Lightman314/LightmansCurrency). Binds BankSystem accounts
 * to Lightman's personal bank accounts for transparent balance routing.
 * <p>
 * <b>Scope.</b> Task #36, v2.0.5 — second adapter following the Numismatics
 * pattern. Supports <b>personal accounts only</b> (LC team accounts are not
 * exposed via public API). Advertises PERSONAL_ACCOUNTS and MULTI_ACCOUNT_PER_PLAYER
 * features (LC allows players to have multiple personal bank accounts).
 * <p>
 * <b>Loader constraint.</b> Lightman's Currency 1.21+ is NeoForge-only (Fabric
 * port discontinued upstream). The adapter code lives in common/ and compiles
 * cross-loader, but {@link #isAvailable()} will only return {@code true} on
 * NeoForge where the mod can actually be present. No per-platform code needed.
 * <p>
 * <b>Dependency.</b> BankSystem never hard-depends on LC classes — every reference
 * goes through reflection. This provider is only registered when
 * {@code Platform.isModLoaded("lightmanscurrency")} returns {@code true} at runtime.
 * <p>
 * <b>Multi-key money model.</b> LC uses {@code MoneyValue} — a multi-chain
 * container that can hold balances in multiple currencies simultaneously. Each
 * binding picks one <b>primary chain</b> to route operations through; the adapter
 * ignores other chains the account may hold.
 *
 * @since 2.0.5
 * @see LightmansCurrencyAccount
 * @see ExternalCurrencyProvider
 */
public final class LightmansCurrencyProvider implements ExternalCurrencyProvider {

    public static final String PROVIDER_ID = "lightmanscurrency";

    /** Single JVM-wide instance; registered during mod init if LC is loaded. */
    public static final LightmansCurrencyProvider INSTANCE = new LightmansCurrencyProvider();

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    /** Dedup flag for the one-shot "provider available" INFO log. */
    private static final AtomicBoolean AVAILABILITY_LOGGED = new AtomicBoolean(false);

    /** Dedup flag for class-resolution failure warnings. */
    private static final AtomicBoolean CLASS_RESOLUTION_WARNED = new AtomicBoolean(false);

    /** Dedup flag for account enumeration failure warnings. */
    private static final AtomicBoolean ACCOUNT_ENUM_WARNED = new AtomicBoolean(false);

    private LightmansCurrencyProvider() {}

    /**
     * Wires the shared {@code Instances} container onto this class so its
     * logger can be reached. Called once during backend construction.
     */
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    @Override
    public @NotNull String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean isAvailable() {
        if (!Platform.isModLoaded("lightmanscurrency")) {
            return false;
        }
        try {
            // Verify core LC classes are present
            Class.forName("io.github.lightman314.lightmanscurrency.LCConfig");
            Class.forName("io.github.lightman314.lightmanscurrency.api.money.bank.IBankAccount");
            Class.forName("io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue");
            if (AVAILABILITY_LOGGED.compareAndSet(false, true)) {
                logInfo("Provider available and class resolution succeeded");
            }
            return true;
        } catch (ClassNotFoundException e) {
            if (CLASS_RESOLUTION_WARNED.compareAndSet(false, true)) {
                logWarn("Class resolution failed — Lightman's Currency mod may be uninstalled or API changed: " + e.getMessage());
            }
            return false;
        }
    }

    @Override
    public @NotNull List<ExternalAccountRef> listBindableAccounts(@NotNull UUID player) {
        List<ExternalAccountRef> refs = new ArrayList<>();
        if (!isAvailable()) return refs;

        try {
            // Access BankAPI via LCConfig or similar entry point
            // LC structure: BankAPI.API.getBankAccount(reference) or similar
            // For personal accounts: need to enumerate player's personal bank accounts

            // Try to access via LCConfig.SERVER.bankAccountLimit or API entry point
            Class<?> bankAPIClass = Class.forName("io.github.lightman314.lightmanscurrency.api.money.bank.BankAPI");
            Object bankAPI = bankAPIClass.getField("API").get(null);

            // Get player reference - LC uses BankReference system
            Class<?> playerRefClass = Class.forName("io.github.lightman314.lightmanscurrency.api.money.bank.reference.builtin.PlayerBankReference");
            Object playerRef = playerRefClass.getConstructor(UUID.class).newInstance(player);

            // List accounts for this player reference
            // BankAPI.GetBankAccounts(BankReference owner) returns Collection<IBankAccount>
            Object accountsCollection = bankAPIClass.getMethod("GetBankAccounts",
                    Class.forName("io.github.lightman314.lightmanscurrency.api.money.bank.reference.BankReference"))
                    .invoke(bankAPI, playerRef);

            if (accountsCollection instanceof Collection) {
                @SuppressWarnings("unchecked")
                Collection<Object> accounts = (Collection<Object>) accountsCollection;

                Class<?> accountClass = Class.forName("io.github.lightman314.lightmanscurrency.api.money.bank.IBankAccount");

                for (Object account : accounts) {
                    if (account == null) continue;

                    // Get account ID (probably a long or UUID)
                    Object accountId = accountClass.getMethod("getAccountNumber").invoke(account);
                    String accountKey = String.valueOf(accountId);

                    // Try to get account name
                    String label = "Personal Account";
                    try {
                        Object name = accountClass.getMethod("getName").invoke(account);
                        if (name != null) {
                            label = name.toString();
                        }
                    } catch (NoSuchMethodException e) {
                        // getName() doesn't exist, use fallback
                    }

                    refs.add(new ExternalAccountRef(
                            PROVIDER_ID,
                            accountKey,
                            label,
                            false  // Personal only - no shared team accounts in Task #36
                    ));
                }
            }

        } catch (Exception e) {
            if (ACCOUNT_ENUM_WARNED.compareAndSet(false, true)) {
                logWarn("Failed to list bindable accounts for player " + player + " — LC API may have changed: " + e.getMessage());
            }
        }

        return refs;
    }

    @Override
    public @Nullable ExternalAccount open(@NotNull ExternalAccountRef ref) {
        if (!PROVIDER_ID.equals(ref.providerId())) return null;
        if (!isAvailable()) return null;

        try {
            // Parse account ID from key
            long accountNumber = Long.parseLong(ref.accountKey());

            // Access BankAPI
            Class<?> bankAPIClass = Class.forName("io.github.lightman314.lightmanscurrency.api.money.bank.BankAPI");
            Object bankAPI = bankAPIClass.getField("API").get(null);

            // Get account by number: BankAPI.GetBankAccount(long accountNumber)
            Class<?> accountClass = Class.forName("io.github.lightman314.lightmanscurrency.api.money.bank.IBankAccount");
            Object account = bankAPIClass.getMethod("GetBankAccount", long.class).invoke(bankAPI, accountNumber);

            if (account == null) {
                logDebug("Account " + ref.accountKey() + " no longer exists");
                return null;
            }

            return new LightmansCurrencyAccount(account, ref);
        } catch (NumberFormatException e) {
            logWarn("Invalid account key format " + ref.accountKey() + " — expected long account number");
            return null;
        } catch (Exception e) {
            logWarn("Failed to open account " + ref.accountKey() + ": " + e.getMessage());
            return null;
        }
    }

    @Override
    public @NotNull Set<ProviderFeature> features() {
        // Personal accounts only - LC allows multiple personal accounts per player
        // No SHARED_ACCOUNTS - team accounts not exposed via public API
        // No MEMBERSHIP_SYNC - only personal accounts
        // No SUFFICIENT_FUNDS_CHECK - LC has no reliable simulate primitive
        return EnumSet.of(
                ProviderFeature.PERSONAL_ACCOUNTS,
                ProviderFeature.MULTI_ACCOUNT_PER_PLAYER
        );
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
