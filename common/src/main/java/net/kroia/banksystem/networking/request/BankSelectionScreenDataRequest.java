package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.modutilities.networking.INetworkPayloadEncoder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BankSelectionScreenDataRequest extends BankSystemGenericRequest<UUID, BankSelectionScreenDataRequest.Output> {
    public static class Output implements INetworkPayloadEncoder
    {
        public final List<BankAccountData> bankAccounts = new ArrayList<>();


        @Override
        public void encode(FriendlyByteBuf buf) {
            buf.writeInt(bankAccounts.size());
            for (BankAccountData accountData : bankAccounts) {
                accountData.encode(buf);
            }
        }

        public static Output decode(FriendlyByteBuf buf) {
            Output output = new Output();
            int size = buf.readInt();
            for (int i = 0; i < size; i++) {
                BankAccountData accountData = BankAccountData.decode(buf);
                output.bankAccounts.add(accountData);
            }
            return output;
        }
    }


    @Override
    public String getRequestTypeID() {
        return BankSelectionScreenDataRequest.class.getSimpleName();
    }

    @Override
    public Output handleOnClient(UUID input) {
        return null;
    }

    @Override
    public Output handleOnServer(UUID input, ServerPlayer sender) {
        var accounts = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getBankAccounts(input);
        Output output = new Output();
        for (var account : accounts) {
            output.bankAccounts.add(account.getAccountData());
        }
        return output;
    }

    @Override
    public void encodeInput(FriendlyByteBuf buf, UUID input) {
        buf.writeUUID(input);
    }

    @Override
    public void encodeOutput(FriendlyByteBuf buf, Output output) {
        output.encode(buf);
    }

    @Override
    public UUID decodeInput(FriendlyByteBuf buf) {
        return buf.readUUID();
    }

    @Override
    public Output decodeOutput(FriendlyByteBuf buf) {
        return Output.decode(buf);
    }


}
