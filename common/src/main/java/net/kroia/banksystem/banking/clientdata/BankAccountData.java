package net.kroia.banksystem.banking.clientdata;

import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.INetworkPayloadEncoder;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BankAccountData implements INetworkPayloadEncoder {


    public final int accountNumber;
    public final String accountName;
    public final @Nullable UserData personalBankOwnerData; // The creator of the bank account, usually the owner of the bank
    public final Map<UUID, BankUserData> users = new HashMap<>();
    public final Map<ItemID, BankData> bankData = new HashMap<>();


    public BankAccountData(int accountNumber,
                           String accountName,
                           @Nullable UserData personalBankOwnerData,
                           Map<UUID, BankUserData> users,
                           Map<ItemID, BankData> bankData) {
        this.accountNumber = accountNumber;
        this.accountName = accountName;
        this.personalBankOwnerData = personalBankOwnerData;
        this.users.putAll(users);
        this.bankData.putAll(bankData);
    }
    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(accountNumber);
        buf.writeBoolean(personalBankOwnerData != null);
        if(personalBankOwnerData != null)
            personalBankOwnerData.encode(buf);
        buf.writeInt(users.size());
        for (Map.Entry<UUID, BankUserData> entry : users.entrySet()) {
            entry.getValue().encode(buf);
        }
        buf.writeInt(bankData.size());
        for (Map.Entry<ItemID, BankData> entry : bankData.entrySet()) {
            entry.getValue().encode(buf);
        }
    }


    public static BankAccountData decode(FriendlyByteBuf buf) {
        int accountNumber = buf.readInt();
        UserData creator = null;
        if(buf.readBoolean()) {
            creator = UserData.decode(buf);
        }
        int userCount = buf.readInt();
        Map<UUID, BankUserData> users = new HashMap<>();
        for (int i = 0; i < userCount; i++) {
            BankUserData userData = BankUserData.decode(buf);
            users.put(userData.userUUID, userData);
        }

        int bankDataCount = buf.readInt();
        Map<ItemID, BankData> bankData = new HashMap<>();
        for (int i = 0; i < bankDataCount; i++) {
            BankData data = BankData.decode(buf);
            bankData.put(data.itemID, data);
        }
        return new BankAccountData(accountNumber, creator, users, bankData);
    }
}
