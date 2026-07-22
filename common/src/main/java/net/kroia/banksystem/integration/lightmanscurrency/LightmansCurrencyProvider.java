package net.kroia.banksystem.integration.lightmanscurrency;

import dev.architectury.platform.Platform;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.currency.ExternalAccount;
import net.kroia.banksystem.api.currency.ExternalAccountRef;
import net.kroia.banksystem.api.currency.ExternalCurrencyProvider;
import net.kroia.banksystem.api.currency.ProviderFeature;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link ExternalCurrencyProvider} adapter for Lightman's Currency
 * (github.com/Lightman314/LightmansCurrency). Binds BankSystem accounts to
 * Lightman's personal <i>and</i> team bank accounts for transparent balance
 * routing.
 * <p>
 * <b>Scope.</b> Task #37 shipped personal accounts; this pass adds team (shared)
 * accounts. Advertises {@link ProviderFeature#PERSONAL_ACCOUNTS} +
 * {@link ProviderFeature#SHARED_ACCOUNTS}. LC's public {@code PlayerBankReference}
 * gives each player exactly one personal bank account, so there is no
 * MULTI_ACCOUNT_PER_PLAYER surface here. {@link ProviderFeature#MEMBERSHIP_SYNC}
 * is <b>not</b> advertised — LC's {@code ITeam} does not expose an external
 * mutator to replace its member list; changes must go through LC's own team-invite
 * flow, which is out of scope for this adapter.
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
 * <b>API mapping (verified against LC 2.3.0.5 in {@code .claude/reference/lc-api-2.3.0.5/api-dump.txt}):</b>
 * <ul>
 *   <li>{@code BankAPI.getApi()} — singleton accessor (static method, no {@code API} field exists).</li>
 *   <li>{@code PlayerBankReference.of(UUID)} — canonical factory for a player's personal reference.</li>
 *   <li>{@code TeamAPI.getApi()} — singleton accessor for the team API.</li>
 *   <li>{@code TeamAPI.GetAllTeams(boolean)} — enumerate every team (server-side: pass {@code false}).</li>
 *   <li>{@code ITeam.isOwner/isAdmin/isMember(UUID)} — direct membership predicates.</li>
 *   <li>{@code ITeam.hasBankAccount() / getBankReference()} — team bank presence + reference.</li>
 *   <li>{@code BankReference.get()} — resolves the reference to a live {@code IBankAccount} (or null).</li>
 *   <li>{@code IBankAccount.getName()} — display name as {@code MutableComponent}.</li>
 * </ul>
 * <b>{@link ExternalAccountRef#accountKey()} schema:</b>
 * <ul>
 *   <li>Personal accounts: the owning player's UUID string (e.g. {@code "ce075fe6-…"}).</li>
 *   <li>Team accounts: {@code "team:"} followed by the decimal team ID (e.g. {@code "team:42"}).</li>
 * </ul>
 * {@link #open(ExternalAccountRef)} inspects the key: {@code startsWith("team:")}
 * routes to the team path, otherwise the personal path. The prefix is the
 * disambiguator — personal keys must never start with {@code "team:"} (UUID
 * strings never do). The schema is part of the persistence-stability contract
 * on {@link ExternalAccountRef}.
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

    /** Dedup flag for open-account failure warnings. */
    private static final AtomicBoolean OPEN_ACCOUNT_WARNED = new AtomicBoolean(false);

    /** Dedup flag for team enumeration failure warnings. */
    private static final AtomicBoolean TEAM_ENUM_WARNED = new AtomicBoolean(false);

    /** Dedup flag for coin-variant reflection failure warnings. */
    private static final AtomicBoolean COIN_VALUE_WARNED = new AtomicBoolean(false);

    /** Dedup flag for the "id matched but reflection returned 0" DEBUG log. */
    private static final AtomicBoolean COIN_VALUE_ZERO_DEBUGGED = new AtomicBoolean(false);

    /**
     * Per-Item → raw-BankSystem-unit cache for LC coin-chain variants. Populated
     * lazily on first successful reflection lookup per item; LC's chain data
     * isn't hot-mutable (a config change requires a restart), so a permanent
     * cache is safe. {@link ConcurrentHashMap} because deposit routing calls
     * this on the server thread but the cache surface is defensive anyway.
     */
    private final Map<Item, Long> coinValueByItem = new ConcurrentHashMap<>();

    /** LC coin registry-key prefix — cheap early-out before invoking reflection. */
    private static final String COIN_ID_PREFIX = "lightmanscurrency:coin";

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
    public String getBaseCurrencyItemId() {
        // Task #37 (v2.0.5): declare LC's canonical gold coin as the base currency so
        // fresh accounts auto-seed a coin_gold slot alongside the money slot, and the
        // bindings UI can pre-check item/provider matches. LC's coin registry is
        // config-driven — every ship-ready LC install has coin_gold present.
        // Return null on non-NeoForge loaders / when the mod is not loaded so the
        // account-seeder skips this provider cleanly (see ServerBankManager
        // #addDefaultBankSlots — a null return short-circuits without warning).
        if (!Platform.isModLoaded("lightmanscurrency")) {
            return null;
        }
        return "lightmanscurrency:coin_gold";
    }

    @Override
    public boolean isAvailable() {
        if (!Platform.isModLoaded("lightmanscurrency")) {
            return false;
        }
        try {
            // Verify the LC API surface we depend on is present.
            // All entries are in api-dump.txt for LC 2.3.0.5. TeamAPI is required for
            // shared-account enumeration; if it's missing the provider still degrades
            // to personal-only behavior via the catch-and-log below.
            Class.forName("io.github.lightman314.lightmanscurrency.api.money.bank.BankAPI");
            Class.forName("io.github.lightman314.lightmanscurrency.api.money.bank.IBankAccount");
            Class.forName("io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue");
            Class.forName("io.github.lightman314.lightmanscurrency.api.money.bank.reference.builtin.PlayerBankReference");
            Class.forName("io.github.lightman314.lightmanscurrency.api.teams.TeamAPI");
            if (AVAILABILITY_LOGGED.compareAndSet(false, true)) {
                logInfo("Provider available and class resolution succeeded");
            }
            return true;
        } catch (ClassNotFoundException e) {
            if (CLASS_RESOLUTION_WARNED.compareAndSet(false, true)) {
                logWarn("Class resolution failed — Lightman's Currency mod may be uninstalled or API changed: "
                        + e.getMessage());
            }
            return false;
        }
    }

    @Override
    public @NotNull List<ExternalAccountRef> listBindableAccounts(@NotNull UUID player) {
        List<ExternalAccountRef> refs = new ArrayList<>();
        if (!isAvailable()) return refs;

        try {
            // Build the player's personal BankReference: PlayerBankReference.of(UUID).
            Class<?> playerRefClass = Class.forName(
                    "io.github.lightman314.lightmanscurrency.api.money.bank.reference.builtin.PlayerBankReference");
            Object playerRef = playerRefClass.getMethod("of", UUID.class).invoke(null, player);
            if (playerRef == null) return refs;

            // Resolve reference to live account: BankReference.get().
            Class<?> bankRefClass = Class.forName(
                    "io.github.lightman314.lightmanscurrency.api.money.bank.reference.BankReference");
            Object account = bankRefClass.getMethod("get").invoke(playerRef);
            if (account == null) {
                // Player has no personal account yet (LC lazily creates them on first
                // interaction). Nothing to bind to; return empty.
                return refs;
            }

            // Get the account's display name via IBankAccount.getName() -> MutableComponent.
            String label = "Personal Account";
            try {
                Class<?> accountClass = Class.forName(
                        "io.github.lightman314.lightmanscurrency.api.money.bank.IBankAccount");
                Object name = accountClass.getMethod("getName").invoke(account);
                if (name != null) {
                    // MutableComponent.getString() -> plain text label.
                    Object plain = name.getClass().getMethod("getString").invoke(name);
                    if (plain != null) label = plain.toString();
                }
            } catch (ReflectiveOperationException ignored) {
                // Keep the fallback label — non-fatal.
            }

            // LC gives each player exactly one PlayerBankReference-addressable account,
            // so we emit a single ref keyed by the player's UUID.
            refs.add(new ExternalAccountRef(
                    PROVIDER_ID,
                    player.toString(),
                    label,
                    false // personal
            ));

        } catch (ReflectiveOperationException e) {
            if (ACCOUNT_ENUM_WARNED.compareAndSet(false, true)) {
                logWarn("Failed to list bindable accounts for player " + player
                        + " — LC API may have changed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        // Team accounts: enumerate teams the player is a member/admin/owner of, keep only
        // those with a claimed bank account. Ordering: personal first (per SPI convention),
        // teams after. Isolated in its own try/catch so a team-side failure never wipes the
        // personal refs already collected.
        try {
            Class<?> teamAPIClass = Class.forName("io.github.lightman314.lightmanscurrency.api.teams.TeamAPI");
            Object teamAPI = teamAPIClass.getMethod("getApi").invoke(null);
            if (teamAPI == null) return refs;

            // Use GetAllTeams(boolean) — GetAllTeamsForPlayer(Player) requires a live Player,
            // which the SPI doesn't hand us. Server-side: pass false.
            Object allTeamsObj = teamAPIClass.getMethod("GetAllTeams", boolean.class).invoke(teamAPI, false);
            if (!(allTeamsObj instanceof List<?> allTeams)) return refs;

            Class<?> teamClass = Class.forName("io.github.lightman314.lightmanscurrency.api.teams.ITeam");

            for (Object team : allTeams) {
                if (team == null) continue;

                // Membership filter — ITeam exposes UUID-taking predicates directly.
                boolean isMember = (boolean) teamClass.getMethod("isMember", UUID.class).invoke(team, player);
                boolean isAdmin  = (boolean) teamClass.getMethod("isAdmin",  UUID.class).invoke(team, player);
                boolean isOwner  = (boolean) teamClass.getMethod("isOwner",  UUID.class).invoke(team, player);
                if (!isMember && !isAdmin && !isOwner) continue;

                // Skip teams that haven't claimed a bank account yet — nothing to route to.
                boolean hasBank = (boolean) teamClass.getMethod("hasBankAccount").invoke(team);
                if (!hasBank) continue;

                long teamID = (long) teamClass.getMethod("getID").invoke(team);
                Object nameObj = teamClass.getMethod("getName").invoke(team);
                String teamLabel = nameObj != null ? nameObj.toString() : ("Team #" + teamID);

                refs.add(new ExternalAccountRef(
                        PROVIDER_ID,
                        "team:" + teamID,
                        teamLabel,
                        true // shared
                ));
            }
        } catch (ReflectiveOperationException e) {
            if (TEAM_ENUM_WARNED.compareAndSet(false, true)) {
                logWarn("Failed to enumerate team accounts for player " + player
                        + " — LC team API may have changed: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            // Personal refs already returned; continue with what we have.
        }

        return refs;
    }

    @Override
    public @Nullable ExternalAccount open(@NotNull ExternalAccountRef ref) {
        if (!PROVIDER_ID.equals(ref.providerId())) return null;
        if (!isAvailable()) return null;

        // Team-account routing — key format "team:<decimal-id>". The prefix cannot collide
        // with a UUID string, which is always hex with dashes and starts with a hex digit.
        String key = ref.accountKey();
        if (key.startsWith("team:")) {
            return openTeam(ref, key);
        }

        UUID playerUuid;
        try {
            playerUuid = UUID.fromString(key);
        } catch (IllegalArgumentException e) {
            logWarn("Invalid account key format '" + key + "' — expected UUID string or 'team:<id>'");
            return null;
        }

        try {
            Class<?> playerRefClass = Class.forName(
                    "io.github.lightman314.lightmanscurrency.api.money.bank.reference.builtin.PlayerBankReference");
            Object playerRef = playerRefClass.getMethod("of", UUID.class).invoke(null, playerUuid);
            if (playerRef == null) return null;

            Class<?> bankRefClass = Class.forName(
                    "io.github.lightman314.lightmanscurrency.api.money.bank.reference.BankReference");
            Object account = bankRefClass.getMethod("get").invoke(playerRef);
            if (account == null) {
                logDebug("Account for player " + playerUuid + " no longer exists");
                return null;
            }

            return new LightmansCurrencyAccount(account, ref, playerUuid);
        } catch (ReflectiveOperationException e) {
            if (OPEN_ACCOUNT_WARNED.compareAndSet(false, true)) {
                logWarn("Failed to open account " + key + ": "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Team-account resolution path. Parses the {@code "team:<id>"} key, looks up the
     * team via {@code TeamAPI.GetTeam(false, id)}, and resolves its bank reference
     * to a live {@code IBankAccount}. Returns {@code null} if the team no longer
     * exists, has never claimed a bank account, or LC's team API surface is
     * unreachable.
     */
    private @Nullable ExternalAccount openTeam(@NotNull ExternalAccountRef ref, @NotNull String key) {
        long teamID;
        try {
            teamID = Long.parseLong(key.substring("team:".length()));
        } catch (NumberFormatException e) {
            logWarn("Invalid team account key format '" + key + "' — expected 'team:<decimal-id>'");
            return null;
        }

        try {
            Class<?> teamAPIClass = Class.forName("io.github.lightman314.lightmanscurrency.api.teams.TeamAPI");
            Object teamAPI = teamAPIClass.getMethod("getApi").invoke(null);
            if (teamAPI == null) return null;

            Object team = teamAPIClass.getMethod("GetTeam", boolean.class, long.class)
                    .invoke(teamAPI, false, teamID);
            if (team == null) {
                logDebug("Team " + teamID + " no longer exists");
                return null;
            }

            Class<?> teamClass = Class.forName("io.github.lightman314.lightmanscurrency.api.teams.ITeam");
            boolean hasBank = (boolean) teamClass.getMethod("hasBankAccount").invoke(team);
            if (!hasBank) {
                logDebug("Team " + teamID + " has no claimed bank account");
                return null;
            }

            Object teamRef = teamClass.getMethod("getBankReference").invoke(team);
            if (teamRef == null) return null;

            Class<?> bankRefClass = Class.forName(
                    "io.github.lightman314.lightmanscurrency.api.money.bank.reference.BankReference");
            Object account = bankRefClass.getMethod("get").invoke(teamRef);
            if (account == null) {
                logDebug("Team " + teamID + " bank reference resolved to null account");
                return null;
            }

            // Owner UUID is null for team accounts — currentMembers() derives the full
            // member list from the team object instead of a single owner.
            return new LightmansCurrencyAccount(account, ref, null, team);
        } catch (ReflectiveOperationException e) {
            if (OPEN_ACCOUNT_WARNED.compareAndSet(false, true)) {
                logWarn("Failed to open team account " + key + ": "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return null;
        }
    }

    @Override
    public @NotNull Set<ProviderFeature> features() {
        // LC's PlayerBankReference is 1:1 per player — no MULTI_ACCOUNT_PER_PLAYER.
        // Team accounts are surfaced as SHARED_ACCOUNTS via TeamBankReference.
        // MEMBERSHIP_SYNC intentionally omitted: ITeam has no external mutator for
        // its member set — replacing team membership requires LC's own invite flow.
        // Per ExternalAccount#syncMembership javadoc, BankSystem treats non-propagation
        // as expected when the feature bit is absent.
        // SUFFICIENT_FUNDS_CHECK NOT advertised — LC's BankAPI has no simulate primitive that
        // avoids the real op. canWithdraw() falls back to advisory (getBalance() >= amount),
        // and the actual withdraw call surfaces the authoritative reject if the balance moved
        // between the check and the op.
        return Collections.unmodifiableSet(EnumSet.of(
                ProviderFeature.PERSONAL_ACCOUNTS,
                ProviderFeature.SHARED_ACCOUNTS
        ));
    }

    // Honors LC's live chain data — reads getCoreValue(item) at conversion time so any
    // admin-edited ratios (copper/iron/gold/emerald/diamond/netherite) are respected.
    // Cache is safe because LC's chain data requires a restart to change.
    @Override
    public long baseUnitsPerItem(@NotNull ItemStack stack) {
        if (!isAvailable() || stack.isEmpty()) return 0L;
        if (stack.isDamaged() || stack.isEnchanted()) return 0L;

        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null || !id.toString().startsWith(COIN_ID_PREFIX)) return 0L;

        Item item = stack.getItem();
        Long cached = coinValueByItem.get(item);
        if (cached != null) return cached;

        long resolved = resolveCoreValueFromLc(item);
        if (resolved > 0L) {
            coinValueByItem.put(item, resolved);
        } else if (COIN_VALUE_ZERO_DEBUGGED.compareAndSet(false, true)) {
            logDebug("Item '" + id + "' matched the coin prefix but LC returned coreValue=0"
                    + " — chain data may not list this item; treated as non-currency");
        }
        return resolved;
    }

    private long resolveCoreValueFromLc(@NotNull Item item) {
        try {
            Class<?> coinAPIClass = Class.forName("io.github.lightman314.lightmanscurrency.api.money.coins.CoinAPI");
            Object coinAPI = coinAPIClass.getMethod("getApi").invoke(null);
            if (coinAPI == null) return 0L;

            Object chainData = coinAPIClass.getMethod("ChainDataOfCoin", Item.class).invoke(coinAPI, item);
            if (chainData == null) return 0L;

            Class<?> chainDataClass = Class.forName("io.github.lightman314.lightmanscurrency.api.money.coins.data.ChainData");
            long coreValue = (long) chainDataClass.getMethod("getCoreValue", Item.class).invoke(chainData, item);
            if (coreValue <= 0L) return 0L;

            // nativeScale() is 1L for LC today (see LightmansCurrencyAccount#nativeScale);
            // multiplication is a no-op but kept for symmetry / future-proofing.
            return coreValue * 1L;
        } catch (ReflectiveOperationException e) {
            if (COIN_VALUE_WARNED.compareAndSet(false, true)) {
                logWarn("Failed to resolve coin coreValue via CoinAPI — LC API may have changed: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return 0L;
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
