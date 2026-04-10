package net.kroia.banksystem.api.bankmanager;

public interface IBankManager {
    boolean hasSyncAccess();
    boolean hasAsyncAccess();
    IAsyncBankManager getAsync();
    IServerBankManager getSync();
}
