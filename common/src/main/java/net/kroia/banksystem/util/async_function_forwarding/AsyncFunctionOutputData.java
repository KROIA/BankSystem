package net.kroia.banksystem.util.async_function_forwarding;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiFunction;

/**
 * The AsyncFunctionOutputData class is used to encode/decode the response values from forwarding function calls
 * of specific classes.
 * @param <FuncEnumType> enumeration for the specialized class interface. Each enum defines one function of the interface
 */
public class AsyncFunctionOutputData <FuncEnumType extends Enum<FuncEnumType>> {
    public final FuncEnumType function;
    public final @Nullable StreamCodec<RegistryFriendlyByteBuf, ?> outputParamsCodec;
    public final byte[] encodedResult;

    public AsyncFunctionOutputData(FuncEnumType function, @Nullable StreamCodec<RegistryFriendlyByteBuf, ?> outputParamsCodec, byte[] encodedResult) {
        this.function = function;
        this.outputParamsCodec = outputParamsCodec;
        this.encodedResult = encodedResult;
    }
    // Convenience constructor for functions with no output params
    public AsyncFunctionOutputData(FuncEnumType function, @Nullable StreamCodec<RegistryFriendlyByteBuf, ?> outputParamsCodec) {
        this.function = function;
        this.outputParamsCodec = outputParamsCodec;
        this.encodedResult = new byte[0];
    }
    public AsyncFunctionOutputData(FuncEnumType function) {
        this.function = function;
        this.outputParamsCodec = null;
        this.encodedResult = new byte[0];
    }

    // Used on the master side to wrap the result before sending back
    @SuppressWarnings("unchecked")
    public static <T, FuncEnumType extends Enum<FuncEnumType>> AsyncFunctionOutputData<FuncEnumType> of(
            @Nullable StreamCodec<? extends ByteBuf, ?> outputParamsCodec,
            FuncEnumType functionType,
            T result,
            BiFunction<FuncEnumType, byte[], AsyncFunctionOutputData<FuncEnumType>> constructor) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        if(outputParamsCodec != null) {
            StreamCodec<RegistryFriendlyByteBuf, T> castedCodec = (StreamCodec<RegistryFriendlyByteBuf, T>)outputParamsCodec;
            castedCodec.encode(buf, result);
            return constructor.apply(functionType, buf.array());
        }
        else {
            return constructor.apply(functionType, new byte[0]);
        }
    }
    // Used on the master side to wrap the result before sending back
    public static <T, FuncEnumType extends Enum<FuncEnumType>> AsyncFunctionOutputData<FuncEnumType> of(
            FuncEnumType functionType,
            BiFunction<FuncEnumType, byte[], AsyncFunctionOutputData<FuncEnumType>> constructor) {
        return constructor.apply(functionType, new byte[0]);
    }

    // Used on the slave side to unwrap the result after receiving
    @SuppressWarnings("unchecked")
    public <T> T decodeResult() {
        if (outputParamsCodec == null)
            return null;
        RegistryFriendlyByteBuf resultBuf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(encodedResult), null);
        return (T) outputParamsCodec.decode(resultBuf);
    }
}
