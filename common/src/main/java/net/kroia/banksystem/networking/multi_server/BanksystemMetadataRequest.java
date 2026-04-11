package net.kroia.banksystem.networking.multi_server;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.api.BankSystemAPI;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;


public class BanksystemMetadataRequest extends BankSystemGenericRequest<Boolean, BanksystemMetadataRequest.OutputData> {

    public record OutputData(String modversion)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, OutputData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, p->p.modversion,
                OutputData::new
        );
    }

    @Override
    public String getRequestTypeID() {
        return BanksystemMetadataRequest.class.getName();
    }


    public static CompletableFuture<OutputData> sendRequestToSlave(String slaveID)
    {
        return BankSystemNetworking.BANKSYSTEM_METADATA_REQUEST.sendRequestToSlave(slaveID, false);
    }


    public CompletableFuture<OutputData> handleOnSlaveServer(Boolean input, @Nullable UUID playerSender) {
        BankSystemAPI api = BankSystemMod.getAPI();
        OutputData data = new OutputData(api.getModVersion());

        return CompletableFuture.completedFuture(data);
    }

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, Boolean input) {
        ByteBufCodecs.BOOL.encode(buf, input);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, OutputData output) {
        OutputData.STREAM_CODEC.encode(buf, output);
    }

    @Override
    public Boolean decodeInput(RegistryFriendlyByteBuf buf) {
        return ByteBufCodecs.BOOL.decode(buf);
    }

    @Override
    public OutputData decodeOutput(RegistryFriendlyByteBuf buf) {
        return OutputData.STREAM_CODEC.decode(buf);
    }


}
