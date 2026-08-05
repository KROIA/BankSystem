package net.kroia.banksystem.api;

import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.api.bankmanager.IClientBankManager;
import net.kroia.banksystem.api.currency.ExternalCurrencyProvider;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

public interface BankSystemAPI {

    /**
     * Returns the mod ID of the ServerBank System mod.
     *
     * @return The mod ID as a String.
     */
    String getModID();

    /**
     * Returns the version of the ServerBank System mod.
     *
     * @return The mod version as a String.
     */
    String getModVersion();

    /**
     * @return An instance of IBankSystemEvents that provides access to various events and signals related to the bank system.
     */
    IBankSystemEvents getEvents();

    /**
     * @return An instance of IBankUserManager that provides access to bank user management functionalities.
     */
    IBankManager getServerBankManager();

    /**
     * @return An instance of IClientBankManager that provides access to client-side bank management functionalities.
     */
    IClientBankManager getClientBankManager();

    /**
     * @return An instance of IBankSystemDataHandler that provides access to data handling functionalities for the bank system.
     */
    IBankSystemDataHandler getDataHandler();


    boolean isSlave();

    /**
     * Registers an item price provider for wealth calculation.
     * The provider supplies current market prices for items, enabling
     * the balance history to track total portfolio wealth.
     *
     * @param provider the price provider, or null to unregister
     */
    void setItemPriceProvider(@Nullable ItemPriceProvider provider);

    /**
     * @return the currently registered item price provider, or null if none
     */
    @Nullable ItemPriceProvider getItemPriceProvider();

    /**
     * Sets the item ID that represents the currency used for wealth calculation.
     * Wealth is expressed in units of this currency item.
     *
     * @param currencyItemId the short ID of the currency item
     */
    void setPriceCurrencyItem(short currencyItemId);

    /**
     * @return the short ID of the currency item used for wealth calculation, or 0 if not set
     */
    short getPriceCurrencyItem();

    /**
     * Registers an {@link ExternalCurrencyProvider} adapter with BankSystem.
     * <p>
     * Adapters call this during their mod-init phase to make themselves discoverable
     * for the binding UI and for {@code ServerBank} balance delegation (see
     * {@code .claude/Features/CurrencyModSupport.md}).
     * <p>
     * If a provider with the same {@link ExternalCurrencyProvider#providerId()} is
     * already registered, the previous registration is replaced — this lets
     * adapters be hot-swapped in dev without a restart, and mirrors a service-loader-
     * style "last-wins" convention. Passing {@code null} is a no-op.
     *
     * @param provider the adapter to register.
     * @since 2.0.5
     */
    void registerCurrencyProvider(@Nullable ExternalCurrencyProvider provider);

    /**
     * Removes the currently-registered provider for {@code providerId}.
     * <p>
     * Intended for teardown paths that need to guarantee a provider no longer appears
     * in the binding UI or in {@link #getCurrencyProviders()} — most importantly the
     * in-game test suite, whose {@code StubCurrencyProvider} must not leak into
     * production once the tests finish. Real adapters normally never need this: they
     * live for the JVM lifetime.
     * <p>
     * A {@code null} or unknown {@code providerId} is a no-op and returns {@code false}.
     *
     * @param providerId the {@link ExternalCurrencyProvider#providerId()} to drop.
     * @return {@code true} if a provider was removed; {@code false} otherwise.
     * @since 2.0.5
     */
    boolean unregisterCurrencyProvider(@Nullable String providerId);

    /**
     * @return an immutable snapshot of every currently-registered
     *         {@link ExternalCurrencyProvider}. Iteration order is unspecified.
     *         Never {@code null}; may be empty when no adapter has registered.
     * @since 2.0.5
     */
    Collection<ExternalCurrencyProvider> getCurrencyProviders();

    /**
     * Looks up a registered provider by its stable id.
     *
     * @param providerId the {@link ExternalCurrencyProvider#providerId()} to find.
     *                   May be {@code null}, in which case {@code null} is returned.
     * @return the matching provider, or {@code null} if none is registered under
     *         that id.
     * @since 2.0.5
     */
    @Nullable ExternalCurrencyProvider getCurrencyProvider(@Nullable String providerId);

    /**
     * Task #45 (v2.0.8) — recurring payout API. Never {@code null}; on slaves the returned
     * instance is a fail-closed shim that returns {@code NOT_MASTER} for every mutation and
     * an empty list/future for every read.
     *
     * @return the payout manager. Downstream mods and UI callers use this interface only.
     */
    net.kroia.banksystem.api.payout.IPayoutManager getPayoutManager();
}
