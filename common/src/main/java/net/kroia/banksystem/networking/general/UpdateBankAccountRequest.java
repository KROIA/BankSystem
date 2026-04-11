package net.kroia.banksystem.networking.general;

import net.kroia.banksystem.api.bank.ISyncServerBank;
import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class UpdateBankAccountRequest extends BankSystemGenericRequest<UpdateBankAccountRequest.InputData, @Nullable BankAccountData> {

    public record InputData(int accountNumber,
                            String accountName,
                            @Nullable ItemStack accountIcon,
                            List<BankData> bankData,
                            Map<UUID, Integer> setUsers)
    {

        public record BankData(ItemID itemID,
                               long balance,
                               boolean setBalance,
                               boolean resetLockedBalance,
                               boolean removeBank,
                               boolean createBank)
        {

            public static final StreamCodec<RegistryFriendlyByteBuf, BankData> STREAM_CODEC = StreamCodec.composite(
                    ItemID.STREAM_CODEC, BankData::itemID,
                    ByteBufCodecs.VAR_LONG, BankData::balance,
                    ByteBufCodecs.BOOL, BankData::setBalance,
                    ByteBufCodecs.BOOL, BankData::resetLockedBalance,
                    ByteBufCodecs.BOOL, BankData::removeBank,
                    ByteBufCodecs.BOOL, BankData::createBank,
                    BankData::new
            );
        }

        public static final StreamCodec<RegistryFriendlyByteBuf, InputData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, InputData::accountNumber,
                ByteBufCodecs.STRING_UTF8, InputData::accountName,
                ExtraCodecUtils.nullable(ItemStack.STREAM_CODEC), InputData::accountIcon,
                ExtraCodecUtils.listStreamCodec(BankData.STREAM_CODEC), InputData::bankData,
                ExtraCodecUtils.mapStreamCodec(UUIDUtil.STREAM_CODEC, ByteBufCodecs.INT, HashMap<UUID, Integer>::new), InputData::setUsers,
                InputData::new
        );


        public InputData(int accountNumber,
                         String accountName,
                         @Nullable ItemStack accountIcon,
                         List<BankData> bankData,
                         Map<UUID, Integer> setUsers)
        {
            this.accountNumber = accountNumber;
            this.accountName = accountName;
            if(accountIcon != null)
            {
                if(accountIcon.isEmpty())
                {
                    this.accountIcon = null;
                }
                else
                {
                    this.accountIcon = accountIcon;
                }
            }
            else
                this.accountIcon = null;
            this.bankData = bankData;
            this.setUsers = setUsers;
        }


    }

    @Override
    public String getRequestTypeID() {
        return UpdateBankAccountRequest.class.getSimpleName();
    }

    @Override
    public CompletableFuture<BankAccountData> handleOnServer(InputData input, ServerPlayer sender) {
        return handleOnMasterServer(input, "", sender.getUUID());
    }
    @Override
    public CompletableFuture<@Nullable BankAccountData> handleOnMasterServer(InputData input, String slaveID, UUID sender) {
        CompletableFuture<@Nullable BankAccountData>  future = new CompletableFuture<>();
        ISyncServerBankManager bankManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        // Check if the player is a admin
        boolean isAdmin = playerIsAdmin(sender);

        ISyncServerBankAccount account = bankManager.getBankAccount(input.accountNumber);
        if(account == null) {
            // If the account does not exist, we cannot update it
            future.complete(null);
            return future;
        }
        boolean canManage = account.hasPermission(sender, BankPermission.MANAGE.getValue());
        if (!isAdmin && !canManage) {
            future.complete(null);
            return future;
        }

        if(input.accountName != null && !input.accountName.isEmpty()) {
            account.setAccountName(input.accountName);
        }

        if(isAdmin && input.bankData != null) {
            for (InputData.BankData data : input.bankData) {
                if (data.removeBank) {
                    account.removeBank(data.itemID);
                    continue;
                }
                ISyncServerBank bank = account.getBank(data.itemID);
                if (bank != null) {
                    if (data.resetLockedBalance)
                        bank.unlockAll();
                    if (data.setBalance)
                        bank.setBalance(data.balance);
                } else {
                    if (data.createBank) {
                        account.createBank(data.itemID, data.balance);
                    }
                }
            }
        }

        if (input.setUsers != null) {
            Map<User, Integer> userList = new HashMap<>(input.setUsers.size());
            for (Map.Entry<UUID, Integer> entry : input.setUsers.entrySet()) {
                UUID userUUID = entry.getKey();
                int permissions = entry.getValue();
                User userToSet = bankManager.getUserByUUID(userUUID);
                if (userToSet != null) {
                    userList.put(userToSet, permissions);
                }
            }
            account.setUsers(userList);
        }
        if(input.accountIcon == null)
        {
            account.setAccountIcon(null);
        }
        else {
            ItemID iconID = ItemID.getOrRegisterFromItemStackServerSide_direct(input.accountIcon);
            account.setAccountIcon(iconID);
        }
        if(!account.hasAnyUser())
        {
            // If the account has no users, we remove it
            bankManager.deleteBankAccount(input.accountNumber);
            future.complete(null);
            return future; // The account was deleted
        }

        future.complete(account.getAccountData());
        return future;
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
