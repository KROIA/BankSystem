package net.kroia.banksystem.command;

import dev.architectury.event.events.common.CommandRegistrationEvent;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.command.IAsyncBankSystemCommandHandler;
import net.kroia.banksystem.api.command.IServerBankSystemCommandHandler;
import net.kroia.banksystem.api.command.IBankSystemCommands;
import org.jetbrains.annotations.Nullable;

public class BankSystemCommands implements IBankSystemCommands {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
        AsyncBankSystemCommandHandler.setBackend(backend);
        ServerBankSystemCommandHandler.setBackend(backend);
        BankSystemCommandsRegistration.setBackend(backend);
    }

    private final IAsyncBankSystemCommandHandler asyncHandler;
    private final @Nullable IServerBankSystemCommandHandler serverHandler;


    private BankSystemCommands(IAsyncBankSystemCommandHandler asyncHandler, @Nullable IServerBankSystemCommandHandler serverHandler)
    {
        this.asyncHandler = asyncHandler;
        this.serverHandler = serverHandler;
    }

    public static void registerCommands()
    {
        CommandRegistrationEvent.EVENT.register((dispatcher, registryAccess, environment) -> {
            BankSystemCommandsRegistration.register(dispatcher);
        });
    }
    public static BankSystemCommands createSlave()
    {
        return new BankSystemCommands(new AsyncBankSystemCommandHandler(), null);
    }
    public static BankSystemCommands createMaster()
    {
        ServerBankSystemCommandHandler handler = new ServerBankSystemCommandHandler();
        return new BankSystemCommands(handler, handler);
    }



    @Override
    public boolean hasSyncAccess()
    {
        return serverHandler != null;
    }
    @Override
    public boolean hasAsyncAccess()
    {
        return true;
    }
    @Override
    public IAsyncBankSystemCommandHandler getAsync()
    {
        return asyncHandler;
    }
    @Override
    public @Nullable IServerBankSystemCommandHandler getSync()
    {
        return serverHandler;
    }

}
