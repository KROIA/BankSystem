package net.kroia.banksystem.networking.request;

import io.netty.buffer.Unpooled;
import net.kroia.banksystem.api.ISyncServerBankManager;
import net.kroia.banksystem.banking.clientdata.BankManagerData;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class AsyncServerBankManagerForwardingRequest extends BankSystemGenericRequest<AsyncServerBankManagerForwardingRequest.InputData, AsyncServerBankManagerForwardingRequest.OutputData> {

    // Available functions to call
    public enum FunctionType
    {
        GetBankManagerDataAsync,
        GetBankManagerUserMapDataAsync,
        GetBankManagerBankAccountsDataAsync
    }
    public static class FunctionDataCodecs
    {
        // If a codec is null, that means that there is either no input or output parameter for the specific function
        public final @Nullable StreamCodec<RegistryFriendlyByteBuf, ?> inputParamsCodec;
        public final @Nullable StreamCodec<RegistryFriendlyByteBuf, ?> outputParamsCodec;

        public FunctionDataCodecs(StreamCodec<RegistryFriendlyByteBuf, ?> inputParamsCodec, StreamCodec<RegistryFriendlyByteBuf, ?> outputParamsCodec)
        {
            this.inputParamsCodec = ExtraCodecUtils.nullable(inputParamsCodec);
            this.outputParamsCodec = ExtraCodecUtils.nullable(outputParamsCodec);
        }
    }

    // Codec map for each function type
    public static final Map<FunctionType, FunctionDataCodecs> codecs = new HashMap<>(){{
        put(FunctionType.GetBankManagerDataAsync,                   new FunctionDataCodecs(null, BankManagerData.STREAM_CODEC));
        put(FunctionType.GetBankManagerUserMapDataAsync,            new FunctionDataCodecs(null, BankManagerData.UserMapData.STREAM_CODEC));
        put(FunctionType.GetBankManagerBankAccountsDataAsync,       new FunctionDataCodecs(null, BankManagerData.BankAccountsData.STREAM_CODEC));
    }};

    public static class InputData {
        public final FunctionType function;
        public final byte[] encodedParams; // pre-encoded function arguments

        public InputData(FunctionType function, byte[] encodedParams) {
            this.function = function;
            this.encodedParams = encodedParams;
        }

        // Convenience constructor for functions with no input params
        public InputData(FunctionType function) {
            this(function, new byte[0]);
        }

        // Generic factory — encodes the params using the codec from the map
        @SuppressWarnings("unchecked")
        public static <T> InputData of(FunctionType function, T params) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            StreamCodec<RegistryFriendlyByteBuf, T> codec =
                    (StreamCodec<RegistryFriendlyByteBuf, T>) codecs.get(function).inputParamsCodec;
            codec.encode((RegistryFriendlyByteBuf) buf, params);
            return new InputData(function, buf.array());
        }

        // Decode the params back into the correct type using the codec
        @SuppressWarnings("unchecked")
        public <T> T decodeParams(RegistryFriendlyByteBuf originalBuf) {
            FunctionDataCodecs functionCodecs = codecs.get(function);
            if (functionCodecs.inputParamsCodec == null) return null;
            RegistryFriendlyByteBuf paramBuf = new RegistryFriendlyByteBuf(
                    Unpooled.wrappedBuffer(encodedParams),
                    originalBuf.registryAccess()
            );
            return (T) functionCodecs.inputParamsCodec.decode(paramBuf);
        }
    }

    public static class OutputData {
        public final FunctionType function;
        public final byte[] encodedResult;

        public OutputData(FunctionType function, byte[] encodedResult) {
            this.function = function;
            this.encodedResult = encodedResult;
        }

        // Used on the master side to wrap the result before sending back
        @SuppressWarnings("unchecked")
        public static <T> OutputData of(FunctionType function, T result) {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
            StreamCodec<RegistryFriendlyByteBuf, T> codec =
                    (StreamCodec<RegistryFriendlyByteBuf, T>) codecs.get(function).outputParamsCodec;
            assert codec != null;
            codec.encode(buf, result);
            return new OutputData(function, buf.array());
        }

        // Used on the slave side to unwrap the result after receiving
        @SuppressWarnings("unchecked")
        public <T> T decodeResult() {
            FunctionDataCodecs functionCodecs = codecs.get(function);
            if (functionCodecs.outputParamsCodec == null)
                return null;
            RegistryFriendlyByteBuf resultBuf = new RegistryFriendlyByteBuf(
                    Unpooled.wrappedBuffer(encodedResult),
                    null
            );
            return (T) functionCodecs.outputParamsCodec.decode(resultBuf);
        }
    }


    @Override
    public String getRequestTypeID() {
        return AsyncServerBankManagerForwardingRequest.class.getName();
    }


    public CompletableFuture<OutputData> handleOnServer(InputData input, ServerPlayer sender) {
        return handleOnMasterServer(input, sender.getUUID());
    }
    @Override
    public CompletableFuture<OutputData> handleOnMasterServer(InputData input, UUID playerSender) {
        ISyncServerBankManager bankManager = getSyncBankManager();
        return CompletableFuture.completedFuture(switch (input.function) {
            case GetBankManagerDataAsync -> OutputData.of(input.function, bankManager.getBankManagerData());
            case GetBankManagerUserMapDataAsync -> OutputData.of(input.function, bankManager.getBankManagerUserMapData());
            case GetBankManagerBankAccountsDataAsync -> OutputData.of(input.function, bankManager.getBankManagerBankAccountsData());
        });
    }

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, InputData input) {
        buf.writeEnum(input.function);
        buf.writeByteArray(input.encodedParams);
    }

    @Override
    public InputData decodeInput(RegistryFriendlyByteBuf buf) {
        FunctionType function = buf.readEnum(FunctionType.class);
        byte[] encodedParams = buf.readByteArray();
        return new InputData(function, encodedParams);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, OutputData output) {
        buf.writeEnum(output.function);
        buf.writeByteArray(output.encodedResult);
    }

    @Override
    public OutputData decodeOutput(RegistryFriendlyByteBuf buf) {
        FunctionType function = buf.readEnum(FunctionType.class);
        byte[] encodedResult = buf.readByteArray();
        return new OutputData(function, encodedResult);
    }


}
