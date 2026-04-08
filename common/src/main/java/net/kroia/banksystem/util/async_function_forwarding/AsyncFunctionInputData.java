package net.kroia.banksystem.util.async_function_forwarding;

import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiFunction;

/**
 * The AsyncFunctionInputData class is used to encode/decode the input arguments for forwarding function calls
 * of specific classes.
 * @param <FuncEnumType> enumeration for the specialized class interface. Each enum defines one function of the interface
 */
public class AsyncFunctionInputData <FuncEnumType extends Enum<FuncEnumType>>{
    public final FuncEnumType function;
    public final @Nullable StreamCodec<RegistryFriendlyByteBuf, ?> inputParamsCodec;
    public final byte[] encodedParams; // pre-encoded function arguments

    public AsyncFunctionInputData(FuncEnumType function, @Nullable StreamCodec<RegistryFriendlyByteBuf, ?> inputParamsCodec, byte[] encodedParams) {
        this.function = function;
        this.inputParamsCodec = inputParamsCodec;
        this.encodedParams = encodedParams;
    }
    // Convenience constructor for functions with no input params
    public AsyncFunctionInputData(FuncEnumType function, @Nullable StreamCodec<RegistryFriendlyByteBuf, ?> inputParamsCodec) {
        this(function, inputParamsCodec, new byte[0]);
    }
    public AsyncFunctionInputData(FuncEnumType function) {
        this(function, null, new byte[0]);
    }

    // Generic factory — encodes the params using the codec from the map
    @SuppressWarnings("unchecked")
    public static <T, FuncEnumType extends Enum<FuncEnumType>> AsyncFunctionInputData<FuncEnumType> of(
            @Nullable StreamCodec<RegistryFriendlyByteBuf, ?> inputParamsCodec,
            FuncEnumType functionType,
            T params,
            BiFunction<FuncEnumType, byte[], AsyncFunctionInputData<FuncEnumType>> constructor) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        if(inputParamsCodec != null) { //ExtraCodecUtils.nullable(
            StreamCodec<RegistryFriendlyByteBuf, T> castedCodec = (StreamCodec<RegistryFriendlyByteBuf, T>)inputParamsCodec;
            castedCodec.encode(buf, params);
            return constructor.apply(functionType, buf.array());
            //return new AsyncFunctionInputData<FuncEnumType>(functionType, codec, buf.array());
        }
        else {
            return constructor.apply(functionType, new byte[0]);
            //return new AsyncFunctionInputData<FuncEnumType>(functionType);
        }
    }
    public static <T, FuncEnumType extends Enum<FuncEnumType>> AsyncFunctionInputData<FuncEnumType> of(
            FuncEnumType functionType,
            BiFunction<FuncEnumType, byte[], AsyncFunctionInputData<FuncEnumType>> constructor) {
        return constructor.apply(functionType, new byte[0]);
    }

    // Decode the params back into the correct type using the codec
    @SuppressWarnings("unchecked")
    public <T> T decodeParams() {
        if (inputParamsCodec == null)
            return null;
        RegistryFriendlyByteBuf paramBuf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(encodedParams), null);
        return (T) inputParamsCodec.decode(paramBuf);
    }
}
