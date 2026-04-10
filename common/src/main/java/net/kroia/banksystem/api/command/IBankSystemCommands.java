package net.kroia.banksystem.api.command;

public interface IBankSystemCommands {
    boolean hasSyncAccess();
    boolean hasAsyncAccess();
    IAsyncBankSystemCommandHandler getAsync();
    IServerBankSystemCommandHandler getSync();
}
