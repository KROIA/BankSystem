package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.api.IBank;
import net.kroia.banksystem.api.IBankAccount;
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
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class UpdateBankAccountRequest extends BankSystemGenericRequest<UpdateBankAccountRequest.InputData, BankAccountData> {

    public record InputData(int accountNumber,
                            String accountName,
                            @Nullable ItemID accountIcon,
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
            /*public BankData(){
                this(null, 0, false, false, false, false);
            }*/
            /*public BankData(RegistryFriendlyByteBuf buf) {
                fromBytes(buf);
            }

            public void toBytes(RegistryFriendlyByteBuf buf) {
                buf.writeItem(itemID.getStack());
                buf.writeLong(balance);
                buf.writeBoolean(setBalance);
                buf.writeBoolean(resetLockedBalance);
                buf.writeBoolean(removeBank);
                buf.writeBoolean(createBank);
            }


            public void fromBytes(RegistryFriendlyByteBuf buf) {
                itemID = new ItemID(buf.readItem());
                balance = buf.readLong();
                setBalance = buf.readBoolean();
                resetLockedBalance = buf.readBoolean();
                removeBank = buf.readBoolean();
                createBank = buf.readBoolean();
            }*/
        }

        public static final StreamCodec<RegistryFriendlyByteBuf, InputData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, InputData::accountNumber,
                ByteBufCodecs.STRING_UTF8, InputData::accountName,
                ExtraCodecUtils.nullable(ItemID.STREAM_CODEC), InputData::accountIcon,
                ExtraCodecUtils.listStreamCodec(BankData.STREAM_CODEC), InputData::bankData,
                ExtraCodecUtils.mapStreamCodec(UUIDUtil.STREAM_CODEC, ByteBufCodecs.INT, HashMap<UUID, Integer>::new), InputData::setUsers,
                InputData::new
        );




        /*public int accountNumber;
        public String accountName;
        public @Nullable ItemID accountIcon;
        public List<BankData> bankData;

        public Map<UUID, Integer> setUsers;


        public InputData() {
            this.bankData = null;
            this.setUsers = null;
        }
        public InputData(int accountNumber, String accountName, ItemID accountIcon,
                            List<BankData> bankData,
                            Map<UUID, Integer> setUsers) {
            this.accountNumber = accountNumber;
            this.accountName = accountName;
            this.accountIcon = accountIcon;
            this.bankData = bankData;
            this.setUsers = setUsers;
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf) {
            buf.writeInt(accountNumber);
            buf.writeUtf(accountName);
            buf.writeBoolean(accountIcon != null);
            if(accountIcon != null) {
                accountIcon.encode(buf);
            }
            buf.writeBoolean(bankData != null);
            if(bankData != null) {
                buf.writeInt(bankData.size());
                for (BankData data : bankData) {
                    data.toBytes(buf);
                }
            }

            buf.writeBoolean(setUsers != null);
            if(setUsers != null) {
                buf.writeInt(setUsers.size());
                for (Map.Entry<UUID, Integer> entry : setUsers.entrySet()) {
                    buf.writeUUID(entry.getKey());
                    buf.writeInt(entry.getValue());
                }
            }
        }
        public static InputData decode(RegistryFriendlyByteBuf buf) {
            InputData input = new InputData();
            input.accountNumber = buf.readInt();
            input.accountName = buf.readUtf();
            if(buf.readBoolean()) {
                input.accountIcon = ItemID.createFomBytes(buf);
            } else {
                input.accountIcon = null;
            }

            input.bankData = null;
            if(buf.readBoolean()) {
                int size = buf.readInt();
                input.bankData = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    input.bankData.add(new BankData(buf));
                }
            }

            input.setUsers = null;
            if(buf.readBoolean()) {
                int addUsersSize = buf.readInt();
                input.setUsers = new java.util.HashMap<>(addUsersSize);
                for (int i = 0; i < addUsersSize; i++) {
                    UUID userUUID = buf.readUUID();
                    int permissions = buf.readInt();
                    input.setUsers.put(userUUID, permissions);
                }
            }
            return input;
        }*/
    }

    @Override
    public String getRequestTypeID() {
        return UpdateBankAccountRequest.class.getSimpleName();
    }

    @Override
    public BankAccountData handleOnClient(InputData input) {
        return null;
    }

    @Override
    public BankAccountData handleOnServer(InputData input, ServerPlayer sender) {
        // Check if the player is a admin
        boolean isAdmin = playerIsAdmin(sender);

        IBankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getBankAccount(input.accountNumber);
        if(account == null) {
            // If the account does not exist, we cannot update it
            return null;
        }
        boolean canManage = account.hasPermission(sender.getUUID(), BankPermission.MANAGE.getValue());
        if (!isAdmin && !canManage) {
            return null;
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
                IBank bank = account.getBank(data.itemID);
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
        if(canManage) {
            if (input.setUsers != null) {
                Map<User, Integer> userList = new HashMap<>(input.setUsers.size());
                for (Map.Entry<UUID, Integer> entry : input.setUsers.entrySet()) {
                    UUID userUUID = entry.getKey();
                    int permissions = entry.getValue();
                    User userToSet = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getUserByUUID(userUUID);
                    if (userToSet != null) {
                        userList.put(userToSet, permissions);
                    }
                }
                account.setUsers(userList);
            }
            account.setAccountIcon(input.accountIcon);
            if(!account.hasAnyUser())
            {
                // If the account has no users, we remove it
                BACKEND_INSTANCES.SERVER_BANK_MANAGER.deleteBankAccount(input.accountNumber);
                return null; // The account was deleted
            }
        }
        return account.getAccountData();
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
