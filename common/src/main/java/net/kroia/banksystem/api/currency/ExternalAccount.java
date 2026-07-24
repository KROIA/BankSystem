package net.kroia.banksystem.api.currency;

import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;

/**
 * A live, server-side handle to one external currency-mod account.
 * <p>
 * Handles are obtained from {@link ExternalCurrencyProvider#open(ExternalAccountRef)}.
 * Every operation on this handle proxies directly to the underlying mod — adapters
 * are thin, stateless wrappers. In particular, BankSystem does <b>not</b> cache
 * balances on this side: every {@link #getBalance()} call re-reads from the
 * external mod.
 * <p>
 * All methods on this interface are called from the <b>server thread</b> only.
 * Implementations do not need to be thread-safe.
 * <p>
 * This SPI has no locked-balance concept — the external mods it wraps do not
 * expose one. Locked-balance semantics remain BankSystem's local responsibility
 * (tracked in the {@code BankAccountBindings} savedata row, not here). Adapters
 * expose only the raw "free balance" primitives ({@code getBalance},
 * {@code deposit}, {@code withdraw}); the {@code ServerBank} refactor in Stage 2
 * layers locked semantics on top.
 *
 * @since 2.0.5
 * @see ExternalCurrencyProvider
 * @see ExternalAccountRef
 */
public interface ExternalAccount {

    /**
     * @return the reference that was used to open this handle. Never {@code null}.
     */
    @NotNull ExternalAccountRef ref();

    /**
     * Native scale factor: how many BankSystem raw units correspond to one
     * indivisible unit of the underlying external mod. Default {@code 1}
     * — the external mod natively operates at BankSystem's raw-unit granularity
     * (no fractional handling required).
     * <p>
     * When {@code > 1}, the external mod cannot represent BS-unit deltas smaller
     * than {@code nativeScale()}. Example: Numismatics uses integer spurs, so any
     * BankSystem operation smaller than 1 spur has no external representation.
     * With BankSystem's default per-item scale of 100, that's {@code nativeScale = 100}.
     * <p>
     * {@code ServerBank}'s bound-slot code path uses this value to split every
     * balance op into a whole-native-unit external delta plus a sub-unit
     * remainder that is persisted in the binding row as {@code dustBalance}.
     * That keeps money conservation exact — sub-unit fractions never silently
     * vanish, and the "phantom" that appeared in early v2.0.5 builds cannot occur.
     *
     * @return native scale factor, always {@code >= 1}.
     */
    default long nativeScale() {
        return 1L;
    }

    /**
     * Reads the current balance from the underlying external mod, expressed in
     * <b>BankSystem raw units</b> (long).
     * <p>
     * <b>Read-through, no caching.</b> Each invocation must query the underlying
     * mod. External mods have their own UIs (Numismatics Bank Terminal, LC ATM,
     * etc.) and the balance can change under our feet between two calls — the
     * drift-clamp path documented in {@code ISyncServerBank#setBalance} relies on
     * every read being live.
     * <p>
     * If the provider's native unit is smaller than a long (e.g. Numismatics'
     * {@code int} spurs), the value is widened. Providers with fractional native
     * units convert per their own scale (documented in the umbrella spec).
     *
     * @return the current balance in BankSystem raw units. Must be {@code >= 0}.
     */
    long getBalance();

    /**
     * Atomically adds {@code amount} raw units to the underlying account.
     * <p>
     * Returns {@code false} — with no state change on either side — if the
     * operation would overflow the provider's native representation (e.g. push a
     * Numismatics balance above {@link Integer#MAX_VALUE}) or is otherwise rejected
     * by the provider. Overflow is expressed by the return value: implementations
     * <b>must not</b> throw for overflow.
     *
     * @param amount amount to deposit, in BankSystem raw units. Callers pass
     *               {@code >= 0}; negative arguments produce {@code false}.
     * @return {@code true} if the balance changed, {@code false} otherwise.
     */
    boolean deposit(long amount);

    /**
     * Atomically subtracts {@code amount} raw units from the underlying account.
     * <p>
     * Returns {@code false} — with no state change on either side — if the account
     * does not have sufficient funds or the provider otherwise rejects the call.
     *
     * @param amount amount to withdraw, in BankSystem raw units. Callers pass
     *               {@code >= 0}; negative arguments produce {@code false}.
     * @return {@code true} if the balance changed, {@code false} otherwise.
     */
    boolean withdraw(long amount);

    /**
     * Checks whether a subsequent {@link #withdraw(long)} of {@code amount} would
     * succeed based purely on available funds.
     * <p>
     * If the provider advertises {@link ProviderFeature#SUFFICIENT_FUNDS_CHECK},
     * the answer is authoritative and must have no side effects (the provider
     * exposes a simulate primitive). Without that feature bit the check is
     * <b>advisory</b> — usually implemented as {@code getBalance() >= amount} — and
     * a subsequent {@code withdraw} may still fail (e.g. because the balance moved
     * between the two calls via the external mod's own UI).
     *
     * @param amount amount to test, in BankSystem raw units.
     * @return {@code true} if the withdraw is expected to succeed.
     */
    boolean canWithdraw(long amount);

    /**
     * @return {@code true} if this external account is co-owned by multiple players
     *         on the provider side (e.g. Numismatics BLAZE_BANKER). Shared BankSystem
     *         accounts may only bind to shared external accounts.
     */
    boolean isSharedAccount();

    /**
     * Snapshot of the current membership set as understood by the provider — i.e.
     * the UUIDs of players who can withdraw / spend from this external account.
     * <p>
     * Used by BankSystem for perm-mapping sanity checks (does the BankSystem
     * WITHDRAW set match the external membership set?) and for logging. For
     * personal (single-owner) accounts this returns a one-element set with the
     * owner's UUID.
     *
     * @return a snapshot set of member UUIDs. May be empty (e.g. an unclaimed
     *         shared account). Never {@code null}. Implementations should return a
     *         defensive copy — callers must not mutate provider state through it.
     */
    @NotNull Set<UUID> currentMembers();

    /**
     * Requests the provider replace its withdraw-capable membership set with the
     * given UUIDs. Called by BankSystem whenever a shared BankSystem account's user
     * list or per-user permission mask changes.
     * <p>
     * <b>Best-effort.</b> If the provider does not advertise
     * {@link ProviderFeature#MEMBERSHIP_SYNC}, this method is a no-op and BankSystem
     * treats non-propagation as expected (e.g. Numismatics PLAYER-type accounts, or
     * Lightman's personal-only bindings). When the feature bit is set, the provider
     * is expected to reflect the change on its side (Numismatics trust list, etc.)
     * before returning, but transient failures should be swallowed rather than
     * thrown — BankSystem cannot recover from an exception here.
     *
     * @param withdrawCapableUuids the target set of members that should be able to
     *                             withdraw from this external account. Never
     *                             {@code null}; may be empty.
     */
    void syncMembership(@NotNull Set<UUID> withdrawCapableUuids);
}
