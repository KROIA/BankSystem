package net.kroia.banksystem.networking.general;

import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.BankSystemGenericStream;
import net.kroia.modutilities.networking.client_server.streaming.GenericStream;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

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
    private final Consumer<BankAccountData> callbackRef = this::onChangesCallback;

    private void onChangesCallback(BankAccountData changeData)
    {
        pendingPackets.add(new OutputData(changeData));
    }

    @Override
    public void onStartStreamSendingOnSever() {
        InputData inputData = getContextData();
        if (!mayRead(inputData.accountNr)) {
            warn("Refused the change stream of account " + inputData.accountNr + " to player " + getRequestorPlayerUUID()
                    + (getSlaveServerID() == null || getSlaveServerID().isEmpty() ? "" : " via slave '" + getSlaveServerID() + "'"));
            stopStream();
            return;
        }
        getBankManager().subscribeBankChanges(inputData.accountNr, callbackRef);
    }

    /** Same rule as the account reads: members, and admins unless the requester came through an untrusted slave. */
    private boolean mayRead(int accountNr) {
        UUID player = getRequestorPlayerUUID();
        IServerBankManager manager = getBankManager();
        if (player == null || manager == null)
            return false;
        ISyncServerBankAccount account = manager.getBankAccount(accountNr);
        String slaveID = getSlaveServerID();
        boolean untrustedSlave = slaveID != null && !slaveID.isEmpty() && !manager.isSlaveServerTrusted(slaveID);
        return account != null && BankSystemGenericRequest.isAccessAllowed(account.hasUser(player), playerIsAdmin(player), untrustedSlave);
    }
    @Override
    public void onStopStreamSendingOnServer() {
        InputData inputData = getContextData();
        getBankManager().unsubscribeBankChanges(inputData.accountNr, callbackRef);
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
