package net.kroia.banksystem.networking.general;

import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.util.BankSystemGenericStream;
import net.kroia.modutilities.networking.client_server.streaming.GenericStream;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public class BankAccountChangeStream extends BankSystemGenericStream<BankAccountChangeStream.InputData, BankAccountChangeStream.OutputData> {
    public record InputData(int accountNr)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, InputData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, p -> p.accountNr,
                InputData::new
        );
    }

    public record OutputData(BankAccountData changedData)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, OutputData> STREAM_CODEC = StreamCodec.composite(
                BankAccountData.STREAM_CODEC, p -> p.changedData,
                OutputData::new
        );
    }

    OutputData nextPacket = null;

    private void onChangesCallback(BankAccountData changeData)
    {
        nextPacket = new OutputData(changeData);
    }

    @Override
    public void onStartStreamSendingOnSever() {
        InputData inputData = getContextData();
        getBankManager().subscribeBankChanges(inputData.accountNr, this::onChangesCallback);
    }
    @Override
    public void onStopStreamSendingOnServer() {
        InputData inputData = getContextData();
        getBankManager().unsubscribeBankChanges(inputData.accountNr, this::onChangesCallback);
    }

    @Override
    protected void updateOnServer(){
        if(nextPacket != null)
        {
            sendPacket();
        }
    }

    @Override
    public OutputData provideStreamPacketOnServer()  {
        OutputData outputData = nextPacket;
        nextPacket = null;
        return outputData;
    }





    @Override
    public GenericStream<InputData, OutputData> copy() {
        return new BankAccountChangeStream();
    }

    @Override
    public String getStreamTypeID() {
        return BankAccountChangeStream.class.getName();
    }

    @Override
    public void encodeContextData(RegistryFriendlyByteBuf buffer, InputData context) {
        InputData.STREAM_CODEC.encode(buffer, context);
    }

    @Override
    public InputData decodeContextData(RegistryFriendlyByteBuf buffer) {
        return InputData.STREAM_CODEC.decode(buffer);
    }

    @Override
    public void encodeData(RegistryFriendlyByteBuf buffer, OutputData outputData) {
        OutputData.STREAM_CODEC.encode(buffer, outputData);
    }

    @Override
    public OutputData decodeData(RegistryFriendlyByteBuf buffer) {
        return OutputData.STREAM_CODEC.decode(buffer);
    }

}
