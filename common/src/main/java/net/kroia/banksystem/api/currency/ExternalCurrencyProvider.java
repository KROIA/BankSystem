package net.kroia.banksystem.api.currency;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Service Provider Interface for optional integrations with third-party currency
 * mods (Numismatics, Lightman's Currency, ...).
 * <p>
 * Each supported currency mod ships an adapter that implements this interface and
 * registers itself against BankSystem via
 * {@link net.kroia.banksystem.api.BankSystemAPI#registerCurrencyProvider(ExternalCurrencyProvider)}
 * during mod init. BankSystem itself has no compile-time dependency on any
 * external mod — adapters live in their own modules and are only activated when
 * {@link #isAvailable()} returns {@code true} at runtime.
 * <p>
 * When a specific {@code IServerBank} slot is bound to an external account (see
 * the {@code BankAccountBindings} savedata added in Stage 2), balance
 * reads / deposits / withdrawals route through the corresponding
 * {@link ExternalAccount}. BankSystem retains authority over identity,
 * permissions, and the locked-balance ledger — those stay local.
 * <p>
 * All calls on this SPI happen on the <b>server thread</b>. Implementations are
 * not required to be thread-safe, and must never assume they are invoked from a
 * client or from a mod-init thread after registration completes.
 * <p>
 * See {@code .claude/Features/CurrencyModSupport.md} for the design principle
 * ("binding, not replacement"), the locked-balance protocol, currency-unit
 * conversion rules, and the shared-account permission-mapping model.
 *
 * @since 2.0.5
 * @see ExternalAccount
 * @see ExternalAccountRef
 * @see ProviderFeature
 */
public interface ExternalCurrencyProvider {

    /**
     * Stable, unique identifier for this provider. Written to savedata as part of
     * every binding row; must not change across mod versions once released.
     * Conventionally the underlying mod's id — e.g. {@code "numismatics"},
     * {@code "lightmans_currency"}.
     *
     * @return the provider id. Never {@code null} or empty.
     */
    @NotNull String providerId();

    /**
     * Whether the underlying currency mod is actually present and initialized on
     * this server. Typically implemented via {@code Platform.isModLoaded(...)} plus
     * any adapter-specific readiness checks (e.g. the mod's API bootstrapped).
     * <p>
     * Providers that return {@code false} are not offered to players in the
     * binding UI and are skipped by lookups; any pre-existing bindings pointing at
     * an unavailable provider surface as "unavailable" until the mod is reinstalled.
     *
     * @return {@code true} if the provider can serve requests.
     */
    boolean isAvailable();

    /**
     * Lists every external account the given player is allowed to bind a
     * BankSystem slot to right now. This includes both personal accounts owned by
     * the player and any shared accounts the player is a current member of.
     * <p>
     * Called on demand from the binding UI — implementations may issue live
     * queries against the underlying mod and should not aggressively cache. Order
     * is presentation-relevant; adapters typically return personal accounts first,
     * then shared accounts.
     *
     * @param player the player opening the binding picker. Never {@code null}.
     * @return the list of bindable accounts (possibly empty). Never {@code null}.
     */
    @NotNull List<ExternalAccountRef> listBindableAccounts(@NotNull UUID player);

    /**
     * Opens a live handle to the referenced external account. Returns {@code null}
     * if the reference is no longer valid — the account was deleted, the player
     * lost access, the provider became unavailable, etc.
     * <p>
     * Handles are cheap; callers may open a fresh one per read cycle and are not
     * required to close them (no {@code close()} method exists on this SPI).
     * Implementations should not hold onto returned handles beyond the caller's
     * scope.
     *
     * @param ref the reference to open. Never {@code null}. Adapters should
     *            tolerate refs whose {@code providerId} does not match their own
     *            {@link #providerId()} by returning {@code null}.
     * @return an open handle, or {@code null} if the account is not currently
     *         accessible.
     */
    @Nullable ExternalAccount open(@NotNull ExternalAccountRef ref);

    /**
     * The set of capability flags this provider advertises. Returned set is
     * immutable / snapshot-like; BankSystem inspects it before offering shared
     * bindings, before calling {@link ExternalAccount#syncMembership(Set)}
     * expecting propagation, and to decide whether
     * {@link ExternalAccount#canWithdraw(long)} is authoritative.
     *
     * @return the feature set. Never {@code null}; may be empty (but a provider
     *         with no features is not very useful).
     */
    @NotNull Set<ProviderFeature> features();
}
