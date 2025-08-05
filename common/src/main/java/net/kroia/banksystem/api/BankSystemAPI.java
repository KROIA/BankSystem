package net.kroia.banksystem.api;

public interface BankSystemAPI {

    /**
     * Returns the mod ID of the Bank System mod.
     *
     * @return The mod ID as a String.
     */
    String getModID();

    /**
     * Returns the version of the Bank System mod.
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
    IServerBankManager getServerBankManager();

    /**
     * @return An instance of IClientBankManager that provides access to client-side bank management functionalities.
     */
    IClientBankManager getClientBankManager();

    /**
     * @return An instance of IBankSystemDataHandler that provides access to data handling functionalities for the bank system.
     */
    IBankSystemDataHandler getDataHandler();
}
