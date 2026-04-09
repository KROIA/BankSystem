package net.kroia.banksystem.util.async_function_forwarding;

import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

public abstract class AsyncForwardingRequest<
        FuncEnumType extends Enum<FuncEnumType>,
        IN extends AsyncFunctionInputData<FuncEnumType>,
        OUT extends AsyncFunctionOutputData<FuncEnumType>> extends BankSystemGenericRequest<IN, OUT> {

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
