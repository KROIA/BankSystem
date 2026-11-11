package net.kroia.banksystem.api;

/**
 * Save and load BankSystem's data on this server ({@code BankSystemAPI.getDataHandler()}). BankSystem saves on its
 * own timer and on shutdown; add-ons rarely need these. Call on the server thread. Every method returns
 * {@code true} on success.
 */
public interface IBankSystemDataHandler {


    /** Saves bank data and settings. */
    boolean saveAll();
    /** Loads bank data and settings from disk, replacing what is in memory. */
    boolean loadAll();

    /** Saves the bank data (users, accounts, balances). */
    boolean save_bank();
    /** Loads the bank data from disk. */
    boolean load_bank();

    /** Saves the server settings file. */
    boolean save_globalSettings();
    /** Loads the server settings file. */
    boolean load_globalSettings();
}
