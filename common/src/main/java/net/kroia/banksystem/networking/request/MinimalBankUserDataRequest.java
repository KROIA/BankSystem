package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.banking.BankAccount;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class MinimalBankUserDataRequest extends BankSystemGenericRequest<MinimalBankUserDataRequest.InputData, BankAccountData> {


    public static class InputData
    {
        public final int accountNumber;
        public final UUID userUUID;

        public InputData(int accountNumber) {
            this.accountNumber = accountNumber;
            this.userUUID = null;
        }
        public InputData(UUID userUUID) {
            this.accountNumber = -1;
            this.userUUID = userUUID;
        }
    }

    @Override
    public String getRequestTypeID() {
        return MinimalBankUserDataRequest.class.getName();
    }

    @Override
    public BankAccountData handleOnClient(InputData input) {
        return null;
    }

    @Override
    public BankAccountData handleOnServer(InputData inputData, ServerPlayer sender) {

        boolean isAdmin = playerIsAdmin(sender);
        if(inputData.userUUID == null) {
            BankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getBankAccount(inputData.accountNumber);
            if (account == null) {
                return null; // If the account does not exist, return null
            }
            UUID senderUUID = sender.getUUID();
            if (account.hasUser(senderUUID) || playerIsAdmin(sender)) {
                return account.getAccountData(); // If the sender is a user of the account, return the account data
            }
        }
        else
        {
            BankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getPersonalBankAccount(inputData.userUUID);
            if(account == null) {
                return null; // If the personal bank account does not exist, return null
            }
            User owner = account.getPersonalBankOwner();
            if(owner != null)
            {
                if(!owner.getUUID().equals(inputData.userUUID) && !isAdmin) {
                    return null; // If the user UUID does not match the account owner, return null
                }
                return account.getAccountData();
            }
            else if(isAdmin)
            {
                return account.getAccountData();
            }
        }
        return null; // If the sender is not a user of the account, return null
    }

    @Override
    public void encodeInput(FriendlyByteBuf buf, InputData input) {
        if(input.userUUID != null) {
            buf.writeInt(-1); // Use -1 to indicate that the input is a user UUID
            buf.writeUUID(input.userUUID);
        } else {
            buf.writeInt(input.accountNumber); // Encode the account number
        }
    }

    @Override
    public void encodeOutput(FriendlyByteBuf buf, BankAccountData output) {
        buf.writeBoolean(output != null);
        if(output != null) {
            output.encode(buf); // Encode the MinimalBankUserData
        }
    }

    @Override
    public InputData decodeInput(FriendlyByteBuf buf) {
        int accountNumber = buf.readInt();
        if(accountNumber == -1) {
            UUID userUUID = buf.readUUID(); // If the input is a user UUID, read it
            return new InputData(userUUID);
        }
        return new InputData(accountNumber); // Otherwise, return the account number
    }

    @Override
    public BankAccountData decodeOutput(FriendlyByteBuf buf) {
        if(buf.readBoolean()) {
            return BankAccountData.decode(buf); // Decode the MinimalBankUserData
        }
        return null; // If no data was encoded, return null
    }
}
