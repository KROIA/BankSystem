package net.kroia.banksystem.api;

import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.api.bankmanager.IClientBankManager;
import org.jetbrains.annotations.Nullable;

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
}
