package net.kroia.banksystem.api;

public interface IBankManager {

    boolean hasSyncAccess();
    boolean hasAsyncAccess();
    IAsyncServerBankManager getAsync();
    ISyncServerBankManager getSync();
}
