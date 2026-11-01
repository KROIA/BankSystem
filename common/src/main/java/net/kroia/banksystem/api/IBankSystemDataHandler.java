package net.kroia.banksystem.api;

public interface IBankSystemDataHandler {


    boolean saveAll();
    boolean loadAll();

    boolean save_bank();
    boolean load_bank();

    boolean save_globalSettings();
    boolean load_globalSettings();
}
