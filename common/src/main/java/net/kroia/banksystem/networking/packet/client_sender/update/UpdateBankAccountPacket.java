package net.kroia.banksystem.networking.packet.client_sender.update;

import net.kroia.banksystem.api.IBank;
import net.kroia.banksystem.banking.BankAccount;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;

public class UpdateBankAccountPacket extends BankSystemNetworkPacket {

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
    int accountNumber;
    ArrayList<BankData> bankData;

    public UpdateBankAccountPacket(int accountNumber, ArrayList<BankData> bankData) {
        super();
        this.accountNumber = accountNumber;
        this.bankData = bankData;
    }

    public UpdateBankAccountPacket(FriendlyByteBuf buf) {
        super(buf);
    }

    public static void sendPacket(int accountNumber, ArrayList<BankData> bankData) {
        UpdateBankAccountPacket packet = new UpdateBankAccountPacket(accountNumber, bankData);
        packet.sendToServer();
    }


    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(accountNumber);
        buf.writeInt(bankData.size());
        for (BankData data : bankData) {
            data.toBytes(buf);
        }

    }

    @Override
    public void decode(FriendlyByteBuf buf) {
        accountNumber = buf.readInt();
        int size = buf.readInt();
        bankData = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            bankData.add(new BankData(buf));
        }
    }

    @Override
    protected void handleOnServer(ServerPlayer sender) {
        // Check if the player is a admin
        boolean isAdmin = sender.hasPermissions(BACKEND_INSTANCES.SERVER_SETTINGS.UTILITIES.ADMIN_PERMISSION_LEVEL.get());
        if (!isAdmin) {
            return;
        }
        BankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getBankAccount(accountNumber);
        if(account == null)
            return;
        for (BankData data : bankData) {
            if (data.removeBank) {
                account.removeBank(data.itemID);
                continue;
            }
            IBank bank = account.getBank(data.itemID);
            if(bank != null) {
                if (data.resetLockedBalance)
                    bank.unlockAll();
                if (data.setBalance)
                    bank.setBalance(data.balance);
            }
            else
            {
                if(data.createBank)
                {
                    account.createBank(data.itemID, data.balance);
                }
            }
        }
    }
}
