package net.kroia.banksystem.networking.packet.client_sender.update;

import net.kroia.banksystem.banking.BankUser;
import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDataPacket;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.NetworkPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.UUID;

public class UpdateBankAccountPacket extends NetworkPacket {

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
    UUID playerUUID;
    ArrayList<BankData> bankData;


    public UpdateBankAccountPacket(UUID playerUUID, ArrayList<BankData> bankData) {
        super();
        this.playerUUID = playerUUID;
        this.bankData = bankData;
    }

    public UpdateBankAccountPacket(FriendlyByteBuf buf) {
        super(buf);
    }

    public static void sendPacket(UUID playerUUID, ArrayList<BankData> bankData) {
        UpdateBankAccountPacket packet = new UpdateBankAccountPacket(playerUUID, bankData);
        BankSystemNetworking.sendToServer(packet);
    }


    @Override
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeUUID(playerUUID);
        buf.writeInt(bankData.size());
        for (BankData data : bankData) {
            data.toBytes(buf);
        }

    }

    @Override
    public void fromBytes(FriendlyByteBuf buf) {
        playerUUID = buf.readUUID();
        int size = buf.readInt();
        bankData = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            bankData.add(new BankData(buf));
        }
    }

    @Override
    protected void handleOnServer(ServerPlayer sender) {
        // Check if the player is a admin
        boolean isAdmin = sender.hasPermissions(2);
        if (!isAdmin) {
            return;
        }
        BankUser bankUser = ServerBankManager.getUser(playerUUID);
        if(bankUser == null)
            return;
        for (BankData data : bankData) {
            if (data.removeBank) {
                bankUser.removeBank(data.itemID);
                continue;
            }
            Bank bank = bankUser.getBank(data.itemID);
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
                    bankUser.createItemBank(data.itemID, data.balance);
                }
            }
        }
        SyncBankDataPacket.sendPacket(sender, playerUUID);
    }
}
