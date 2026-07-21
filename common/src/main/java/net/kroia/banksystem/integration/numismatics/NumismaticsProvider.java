package net.kroia.banksystem.integration.numismatics;

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
 * {@link ExternalCurrencyProvider} adapter for Create: Numismatics
 * (github.com/Layers-of-Railways/CreateNumismatics). Binds BankSystem accounts
 * to Numismatics PLAYER and BLAZE_BANKER accounts for transparent balance
 * routing.
 * <p>
 * <b>Scope.</b> Task #34, v2.0.5 — first real adapter following the SPI
 * established in Task #33. Supports both personal (PLAYER-type) and shared
 * (BLAZE_BANKER-type) Numismatics accounts; advertises all four capability
 * flags (PERSONAL_ACCOUNTS, SHARED_ACCOUNTS, MEMBERSHIP_SYNC,
 * SUFFICIENT_FUNDS_CHECK).
 * <p>
 * <b>Dependency.</b> BankSystem never hard-depends on Numismatics classes —
 * every reference goes through reflection. This provider is only registered
 * when {@code Platform.isModLoaded("numismatics")} returns {@code true} at
 * runtime, and {@link #isAvailable()} adds a defensive class-resolution check
 * to guard against the mod being removed after registration.
 *
 * @since 2.0.5
 * @see NumismaticsAccount
 * @see ExternalCurrencyProvider
 */
public final class NumismaticsProvider implements ExternalCurrencyProvider {

    public static final String PROVIDER_ID = "numismatics";

    /** Single JVM-wide instance; registered during mod init if Numismatics is loaded. */
    public static final NumismaticsProvider INSTANCE = new NumismaticsProvider();

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    /** Dedup flag for the one-shot "provider available" INFO log. */
    private static final AtomicBoolean AVAILABILITY_LOGGED = new AtomicBoolean(false);

    /** Dedup flag for class-resolution failure warnings. */
    private static final AtomicBoolean CLASS_RESOLUTION_WARNED = new AtomicBoolean(false);

    /** Dedup flag for BLAZE_BANKER enumeration failure warnings. */
    private static final AtomicBoolean BLAZE_BANKER_ENUM_WARNED = new AtomicBoolean(false);

    private NumismaticsProvider() {}

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
    public String getBaseCurrencyItemId() {
        return "numismatics:spur";
    }

    @Override
    public boolean isAvailable() {
        if (!Platform.isModLoaded("numismatics")) {
            return false;
        }
        try {
            Class.forName("dev.ithundxr.createnumismatics.Numismatics");
            Class.forName("dev.ithundxr.createnumismatics.content.backend.GlobalBankManager");
            Class.forName("dev.ithundxr.createnumismatics.content.backend.BankAccount");
            if (AVAILABILITY_LOGGED.compareAndSet(false, true)) {
                logInfo("Provider available and class resolution succeeded");
            }
            return true;
        } catch (ClassNotFoundException e) {
            if (CLASS_RESOLUTION_WARNED.compareAndSet(false, true)) {
                logWarn("Class resolution failed — Numismatics mod may be uninstalled or API changed: " + e.getMessage());
            }
            return false;
        }
    }

    @Override
    public @NotNull List<ExternalAccountRef> listBindableAccounts(@NotNull UUID player) {
        List<ExternalAccountRef> refs = new ArrayList<>();
        if (!isAvailable()) return refs;

        try {
            Class<?> numismaticsClass = Class.forName("dev.ithundxr.createnumismatics.Numismatics");
            Object bankManager = numismaticsClass.getField("BANK").get(null);

            Class<?> typeClass = Class.forName("dev.ithundxr.createnumismatics.content.backend.BankAccount$Type");
            Object playerType = Enum.valueOf((Class<Enum>) typeClass, "PLAYER");
            Object blazeBankerType = Enum.valueOf((Class<Enum>) typeClass, "BLAZE_BANKER");

            Class<?> managerClass = Class.forName("dev.ithundxr.createnumismatics.content.backend.GlobalBankManager");
            Class<?> accountClass = Class.forName("dev.ithundxr.createnumismatics.content.backend.BankAccount");

            Object playerAccount = managerClass.getMethod("getOrCreateAccount", UUID.class, typeClass)
                    .invoke(bankManager, player, playerType);

            if (playerAccount != null) {
                UUID accountId = (UUID) accountClass.getField("id").get(playerAccount);
                refs.add(new ExternalAccountRef(
                        PROVIDER_ID,
                        accountId.toString(),
                        "Personal Account",
                        false
                ));
            }

            try {
                // GlobalBankManager exposes accounts as a public Map<UUID, BankAccount> field.
                Object accountsField = managerClass.getField("accounts").get(bankManager);
                if (accountsField instanceof java.util.Map) {
                    @SuppressWarnings("unchecked")
                    Collection<Object> accounts = ((java.util.Map<Object, Object>) accountsField).values();

                    for (Object account : accounts) {
                        if (account == null) continue;

                        Object type = accountClass.getField("type").get(account);
                        if (!blazeBankerType.equals(type)) continue;

                        // isAuthorized(UUID) covers both direct owner and trust-list membership.
                        boolean authorized = (boolean) accountClass.getMethod("isAuthorized", UUID.class)
                                .invoke(account, player);
                        if (!authorized) continue;

                        UUID accountId = (UUID) accountClass.getField("id").get(account);
                        String label = "Blaze Banker " + accountId.toString().substring(0, 8);
                        try {
                            Object rawLabel = accountClass.getMethod("getLabel").invoke(account);
                            if (rawLabel instanceof String s && !s.isEmpty()) {
                                label = s;
                            }
                        } catch (NoSuchMethodException e) {
                            // getLabel() missing — fall back
                        }

                        refs.add(new ExternalAccountRef(
                                PROVIDER_ID,
                                accountId.toString(),
                                label,
                                true
                        ));
                    }
                }
            } catch (NoSuchFieldException e) {
                if (BLAZE_BANKER_ENUM_WARNED.compareAndSet(false, true)) {
                    logWarn("Failed to enumerate BLAZE_BANKER accounts — GlobalBankManager.accounts field not found. Numismatics API may have changed: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            logWarn("Failed to list bindable accounts for player " + player + ": " + e.getMessage());
        }

        return refs;
    }

    @Override
    public @Nullable ExternalAccount open(@NotNull ExternalAccountRef ref) {
        if (!PROVIDER_ID.equals(ref.providerId())) return null;
        if (!isAvailable()) return null;

        try {
            UUID accountId = UUID.fromString(ref.accountKey());

            Class<?> numismaticsClass = Class.forName("dev.ithundxr.createnumismatics.Numismatics");
            Object bankManager = numismaticsClass.getField("BANK").get(null);

            Class<?> managerClass = Class.forName("dev.ithundxr.createnumismatics.content.backend.GlobalBankManager");
            Object account = managerClass.getMethod("getAccount", UUID.class).invoke(bankManager, accountId);

            if (account == null) {
                logDebug("Account " + ref.accountKey() + " no longer exists");
                return null;
            }

            return new NumismaticsAccount(account, ref);
        } catch (Exception e) {
            logWarn("Failed to open account " + ref.accountKey() + ": " + e.getMessage());
            return null;
        }
    }

    @Override
    public @NotNull Set<ProviderFeature> features() {
        return EnumSet.of(
                ProviderFeature.PERSONAL_ACCOUNTS,
                ProviderFeature.SHARED_ACCOUNTS,
                ProviderFeature.MEMBERSHIP_SYNC,
                ProviderFeature.SUFFICIENT_FUNDS_CHECK
        );
    }

    // Logger helpers — null-safe if the backend has not been wired yet.
    private static void logInfo(String msg) {
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
            BACKEND_INSTANCES.LOGGER.info("[Numismatics] " + msg);
        }
    }

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
