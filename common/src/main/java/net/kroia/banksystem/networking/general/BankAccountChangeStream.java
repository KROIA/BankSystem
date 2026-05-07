package net.kroia.banksystem.networking.general;

import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.util.BankSystemGenericStream;
import net.kroia.modutilities.networking.client_server.streaming.GenericStream;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.concurrent.ConcurrentLinkedQueue;

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

    private final ConcurrentLinkedQueue<OutputData> pendingPackets = new ConcurrentLinkedQueue<>();

    private void onChangesCallback(BankAccountData changeData)
    {
        pendingPackets.add(new OutputData(changeData));
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
        pendingPackets.clear();
    }

    @Override
    protected void updateOnServer(){
        // Drain all queued events so rapid changes are not delayed across ticks
        while(!pendingPackets.isEmpty())
        {
            sendPacket();
        }
    }

    @Override
    public OutputData provideStreamPacketOnServer()  {
        return pendingPackets.poll();
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
