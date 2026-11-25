package net.kroia.banksystem.api.currency;

/**
 * Capability flags advertised by an {@link ExternalCurrencyProvider} through
 * {@link ExternalCurrencyProvider#features()}.
 * <p>
 * A provider returns the subset of these flags that describes what its underlying
 * currency mod can actually do. BankSystem currently only passes the set on to the
 * client with the provider list; it does not call
 * {@link ExternalAccount#syncMembership(java.util.Set)} or
 * {@link ExternalAccount#canWithdraw(long)} yet, so the flags about them describe
 * the adapter for future use.
 * <p>
 * What each flag implies for currency units and permission mapping:
 * https://kroia.github.io/BanksystemStockmarketDoku/developers/banksystem/currency-integration/#feature-flags
 *
 * @since 2.0.5
 */
public enum ProviderFeature {

    /**
     * The provider supports at least one personal (per-player) account. Almost every
     * provider will advertise this; it lets BankSystem offer personal-account bindings.
     */
    PERSONAL_ACCOUNTS,

    /**
     * The provider can enumerate more than one account for a single player (e.g. a
     * player owns multiple accounts of some kind — Numismatics' Blaze Banker model).
     * Without this flag, BankSystem assumes {@link ExternalCurrencyProvider#listBindableAccounts}
     * returns at most one entry per player.
     */
    MULTI_ACCOUNT_PER_PLAYER,

    /**
     * The provider supports accounts co-owned by multiple players. Bindings of a
     * shared BankSystem account are only offered when the provider advertises this.
     */
    SHARED_ACCOUNTS,

    /**
     * The provider will honor {@link ExternalAccount#syncMembership(java.util.Set)}
     * calls. BankSystem does not call {@code syncMembership} yet, so members of a
     * shared BankSystem account are not synced to the external mod either way.
     */
    MEMBERSHIP_SYNC,

    /**
     * {@link ExternalAccount#canWithdraw(long)} returns an authoritative
     * sufficient-funds answer without side effects (i.e. the underlying mod exposes
     * a simulate primitive). Without this flag, {@code canWithdraw} is advisory only
     * and callers must be prepared for a subsequent {@link ExternalAccount#withdraw}
     * to fail with insufficient funds anyway.
     */
    SUFFICIENT_FUNDS_CHECK
}
