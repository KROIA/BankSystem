package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.api.IBank;
import net.kroia.banksystem.banking.BankAccount;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.INetworkPayloadEncoder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

public class UpdateBankAccountRequest extends BankSystemGenericRequest<UpdateBankAccountRequest.InputData, BankAccountData> {

    public static class InputData implements INetworkPayloadEncoder
    {
        public static class BankData{
            public ItemID itemID;
            public long balance = 0;
            public boolean setBalance = false;
            public boolean resetLockedBalance = false;
            public boolean removeBank = false;
            public boolean createBank = false;


            public BankData(){}
            public BankData(FriendlyByteBuf buf) {
                fromBytes(buf);
            }

            public void toBytes(FriendlyByteBuf buf) {
                buf.writeItem(itemID.getStack());
                buf.writeLong(balance);
                buf.writeBoolean(setBalance);
                buf.writeBoolean(resetLockedBalance);
                buf.writeBoolean(removeBank);
                buf.writeBoolean(createBank);
            }


            public void fromBytes(FriendlyByteBuf buf) {
                itemID = new ItemID(buf.readItem());
                balance = buf.readLong();
                setBalance = buf.readBoolean();
                resetLockedBalance = buf.readBoolean();
                removeBank = buf.readBoolean();
                createBank = buf.readBoolean();
            }
        }
        public int accountNumber;
        public String accountName;
        public List<BankData> bankData;

        public Map<UUID, Integer> setUsers;

        public InputData() {
            this.bankData = null;
            this.setUsers = null;
        }
        public InputData(int accountNumber, String accountName,
                            List<BankData> bankData,
                            Map<UUID, Integer> setUsers) {
            this.accountNumber = accountNumber;
            this.accountName = accountName;
            this.bankData = bankData;
            this.setUsers = setUsers;
        }

        @Override
        public void encode(FriendlyByteBuf buf) {
            buf.writeInt(accountNumber);
            buf.writeUtf(accountName);
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
        public static InputData decode(FriendlyByteBuf buf) {
            InputData input = new InputData();
            input.accountNumber = buf.readInt();
            input.accountName = buf.readUtf(); // Read the account name, max length 32767 characters

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
        }
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

        BankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getBankAccount(input.accountNumber);
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
        if(canManage && input.setUsers != null) {
            Map<User, Integer> userList = new HashMap<>(input.setUsers.size());
            for (Map.Entry<UUID, Integer> entry : input.setUsers.entrySet()) {
                UUID userUUID = entry.getKey();
                int permissions = entry.getValue();
                User userToSet = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getUserByUUID(userUUID);
                if(userToSet != null)
                {
                    userList.put(userToSet, permissions);
                }
            }
            account.setUsers(userList);
        }
        return account.getAccountData();
    }

    @Override
    public void encodeInput(FriendlyByteBuf buf, InputData input) {
        input.encode(buf);
    }

    @Override
    public void encodeOutput(FriendlyByteBuf buf, BankAccountData output) {
        buf.writeBoolean(output != null);
        if(output != null) {
            output.encode(buf);
        }
    }

    @Override
    public InputData decodeInput(FriendlyByteBuf buf) {
        return InputData.decode(buf);
    }

    @Override
    public BankAccountData decodeOutput(FriendlyByteBuf buf) {
        if(buf.readBoolean()) {
            return BankAccountData.decode(buf);
        }
        return null;
    }


}
