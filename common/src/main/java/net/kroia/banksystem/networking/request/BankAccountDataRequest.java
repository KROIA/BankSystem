package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.api.IBankAccount;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class BankAccountDataRequest extends BankSystemGenericRequest<BankAccountDataRequest.InputData, BankAccountData> {


    public record InputData(int accountNumber, UUID personalUserUUID)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, InputData> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.INT, InputData::accountNumber,
                        ExtraCodecUtils.nullable(UUIDUtil.STREAM_CODEC), InputData::personalUserUUID,
                        InputData::new
                );

        public InputData(int accountNumber) {
            this(accountNumber, null);
        }

        public InputData(UUID personalUserUUID) {
            this(-1, personalUserUUID);
        }
        /*public final int accountNumber;
        public final UUID personalUserUUID;

        public InputData(int accountNumber) {
            this.accountNumber = accountNumber;
            this.personalUserUUID = null;
        }
        public InputData(UUID personalUserUUID) {
            this.accountNumber = -1;
            this.personalUserUUID = personalUserUUID;
        }*/
    }

    @Override
    public String getRequestTypeID() {
        return BankAccountDataRequest.class.getName();
    }

    @Override
    public BankAccountData handleOnClient(InputData input) {
        return null;
    }

    @Override
    public BankAccountData handleOnServer(InputData inputData, ServerPlayer sender) {

        boolean isAdmin = playerIsAdmin(sender);
        if(inputData.personalUserUUID == null) {
            IBankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getBankAccount(inputData.accountNumber);
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
            IBankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getPersonalBankAccount(inputData.personalUserUUID);
            if(account == null) {
                return null; // If the personal bank account does not exist, return null
            }
            User owner = account.getPersonalBankOwner();
            if(owner != null)
            {
                if(!owner.getUUID().equals(inputData.personalUserUUID) && !isAdmin) {
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
    public void encodeInput(RegistryFriendlyByteBuf buf, InputData input) {
        InputData.STREAM_CODEC.encode(buf, input);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, BankAccountData output) {
        ExtraCodecUtils.nullable(BankAccountData.STREAM_CODEC).encode(buf, output);
    }

    @Override
    public InputData decodeInput(RegistryFriendlyByteBuf buf) {
        return InputData.STREAM_CODEC.decode(buf);
    }

    @Override
    public BankAccountData decodeOutput(RegistryFriendlyByteBuf buf) {
        return ExtraCodecUtils.nullable(BankAccountData.STREAM_CODEC).decode(buf);
    }
}
