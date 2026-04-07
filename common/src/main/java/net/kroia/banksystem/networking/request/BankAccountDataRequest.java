package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.api.IBankAccount;
import net.kroia.banksystem.api.ISyncServerBankManager;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class BankAccountDataRequest extends BankSystemGenericRequest<BankAccountDataRequest.InputData, @Nullable BankAccountData> {


    public record InputData(int accountNumber, @Nullable UUID personalUserUUID)
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
    public boolean needsRoutingToMaster() { return true; }


    @Override
    public CompletableFuture<@Nullable BankAccountData> handleOnServer(InputData inputData, ServerPlayer sender) {
        return handleOnMasterServer(inputData, sender.getUUID());
    }
    @Override
    public CompletableFuture<@Nullable BankAccountData> handleOnMasterServer(InputData inputData, UUID sender) {
        ISyncServerBankManager bankManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        CompletableFuture<BankAccountData>  future = new CompletableFuture<>();
        if(bankManager == null)
        {
            future.complete(null);
            return future;
        }

        boolean isAdmin = playerIsAdmin(sender);
        if(inputData.personalUserUUID == null) {
            IBankAccount account = bankManager.getBankAccount(inputData.accountNumber);
            if (account == null) {
                future.complete(null);
                return future; // If the account does not exist, return null
            }
            if (account.hasUser(sender) || isAdmin) {
                future.complete(account.getAccountData());
                return future; // If the sender is a user of the account, return the account data
            }
        }
        else
        {
            IBankAccount account = bankManager.getPersonalBankAccount(inputData.personalUserUUID);
            if(account == null) {
                future.complete(null);
                return future; // If the personal bank account does not exist, return null
            }
            User owner = account.getPersonalBankOwner();
            if(owner != null)
            {
                if(!owner.getUUID().equals(inputData.personalUserUUID) && !isAdmin) {
                    future.complete(null);
                    return future; // If the user UUID does not match the account owner, return null
                }
                future.complete(account.getAccountData());
                return future;
            }
            else if(isAdmin)
            {
                future.complete(account.getAccountData());
                return future;
            }
        }
        future.complete(null);
        return future; // If the sender is not a user of the account, return null
    }

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, InputData input) {
        InputData.STREAM_CODEC.encode(buf, input);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, @Nullable BankAccountData output) {
        ExtraCodecUtils.nullable(BankAccountData.STREAM_CODEC).encode(buf, output);
    }

    @Override
    public InputData decodeInput(RegistryFriendlyByteBuf buf) {
        return InputData.STREAM_CODEC.decode(buf);
    }

    @Override
    public @Nullable BankAccountData decodeOutput(RegistryFriendlyByteBuf buf) {
        return ExtraCodecUtils.nullable(BankAccountData.STREAM_CODEC).decode(buf);
    }
}
