package net.kroia.banksystem.util.async_function_forwarding;

import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

/**
 * Pair of codecs for input and response data of a forwarded async function call
 */
public  class AsyncFunctionDataCodecs {
    // If a codec is null, that means that there is either no input or output parameter for the specific function
    public final @Nullable StreamCodec<RegistryFriendlyByteBuf, ?> inputParamsCodec;
    public final @Nullable StreamCodec<RegistryFriendlyByteBuf, ?> outputParamsCodec;

    public AsyncFunctionDataCodecs(StreamCodec<RegistryFriendlyByteBuf, ?> inputParamsCodec, StreamCodec<RegistryFriendlyByteBuf, ?> outputParamsCodec)
    {
        this.inputParamsCodec = ExtraCodecUtils.nullable(inputParamsCodec);
        this.outputParamsCodec = ExtraCodecUtils.nullable(outputParamsCodec);
    }
}
