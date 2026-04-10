package net.kroia.banksystem.util.async_function_forwarding;

import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.kroia.modutilities.UtilitiesPlatform;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

public abstract class AsyncForwardingRequest<
        FuncEnumType extends Enum<FuncEnumType>,
        IN extends AsyncFunctionInputData<FuncEnumType>,
        OUT extends AsyncFunctionOutputData<FuncEnumType>> extends BankSystemGenericRequest<IN, OUT> {

    public static final boolean DEBUG_ENABLE_LOGS = false;

    private final BiFunction<FuncEnumType, byte[], IN> inputConstructor;
    private final BiFunction<FuncEnumType, byte[], OUT> outputConstructor;
    private final Class<FuncEnumType> enumClass;

    public AsyncForwardingRequest(BiFunction<FuncEnumType, byte[], IN> inputConstructor,
                                  BiFunction<FuncEnumType, byte[], OUT> outputConstructor,
                                  Class<FuncEnumType> enumClass)
    {
        this.inputConstructor = inputConstructor;
        this.outputConstructor = outputConstructor;
        this.enumClass = enumClass;
    }

    //@Override
    //public String getRequestTypeID() {
    //    return AsyncServerBankManagerForwardingRequest.class.getName();
    //}
    //@Override
    //public CompletableFuture<AsyncFunctionOutputData <FuncEnumType>> handleOnMasterServer(AsyncFunctionInputData<FuncEnumType> input, UUID playerSender) {
    //    return AsyncBankManager.handlePacketOnMaster(input, playerSender);
    //}

    protected String tryGetPlayerName(UUID player)
    {

        if(UtilitiesPlatform.getServer() != null) {
            ServerPlayer serverPlayer = ServerPlayerUtilities.getOnlinePlayer(player);
            if (serverPlayer != null) {
                return serverPlayer.getName().getString();
            }
        }
        String playerName;
        if(BACKEND_INSTANCES.SERVER_BANK_MANAGER != null) {
            IServerBankManager serverBankManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
            if (serverBankManager != null) {
                User user = serverBankManager.getUserByUUID(player);
                if (user != null) {
                    playerName = user.getName();
                } else
                    playerName = player.toString();
            } else
                playerName = player.toString();
        }
        else
        {
            playerName = player.toString();
        }
        return playerName;
    }

    /**
     * Important function:
     * Add each methode that is allowed to be called from a client directly.
     * Do not allow methods to manipulate data that could be exploited by cheating.
     * An exploit can be done by manually compiling this mod with a custom access methode,
     * that calls for example a bank deposit methode which then deposits items for free.
     * Depositing should only be possible under the control of a server instance and never by a client directly!
     * @param input used to get the function which the requestor wants to access.
     * @return true if the call is safe to use from the client side directly.
     *         false if the call could be as exploit by a client.
     */
    protected abstract boolean isAllowedToCallByClient(IN input);

    @Override
    public CompletableFuture<OUT> handleOnServer(IN input, ServerPlayer sender) {
        return handleOnMasterServer(input, "", sender.getUUID());
    }


    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, IN input) {
        buf.writeEnum(input.function);
        buf.writeByteArray(input.encodedParams);
    }

    @Override
    public IN decodeInput(RegistryFriendlyByteBuf buf) {
        FuncEnumType function = buf.readEnum(enumClass);
        byte[] encodedParams = buf.readByteArray();
        return inputConstructor.apply(function, encodedParams);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, OUT output) {
        buf.writeEnum(output.function);
        buf.writeByteArray(output.encodedResult);
    }

    @Override
    public OUT decodeOutput(RegistryFriendlyByteBuf buf) {
        FuncEnumType function = buf.readEnum(enumClass);
        byte[] encodedResult = buf.readByteArray();
        return outputConstructor.apply(function, encodedResult);
    }
}
