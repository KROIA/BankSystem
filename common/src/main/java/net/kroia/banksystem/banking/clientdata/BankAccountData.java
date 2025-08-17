package net.kroia.banksystem.banking.clientdata;

import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.INetworkPayloadEncoder;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class BankAccountData implements INetworkPayloadEncoder {


    public final int accountNumber;
    public final String accountName;

    public final @Nullable ItemID accountIcon;
    public final @Nullable UserData personalBankOwnerData; // The creator of the bank account, usually the owner of the bank
    public final Map<UUID, BankUserData> users = new HashMap<>();
    public final Map<ItemID, BankData> bankData = new HashMap<>();


    public BankAccountData(int accountNumber,
                           String accountName,
                           @Nullable ItemID accountIcon,
                           @Nullable UserData personalBankOwnerData,
                           Map<UUID, BankUserData> users,
                           Map<ItemID, BankData> bankData) {
        this.accountNumber = accountNumber;
        this.accountName = accountName;
        this.accountIcon = accountIcon;
        this.personalBankOwnerData = personalBankOwnerData;
        this.users.putAll(users);
        this.bankData.putAll(bankData);
    }
    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(accountNumber);
        buf.writeUtf(accountName);
        buf.writeBoolean(accountIcon != null);
        if(accountIcon != null)
            accountIcon.encode(buf);
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

    public List<String> getAllUserNames()
    {
        List<String> names = new ArrayList<>();
        if(personalBankOwnerData != null)
            names.add(personalBankOwnerData.userName);
        for(BankUserData data : this.users.values())
        {
            names.add(data.userName);
        }
        return names;
    }
    public List<String> getSearchTexts()
    {
        List<String> texts = getAllUserNames();
        texts.add(accountName);
        texts.add(String.valueOf(accountNumber));
        return texts;
    }

    public static BankAccountData decode(FriendlyByteBuf buf) {
        int accountNumber = buf.readInt();
        String accountName = buf.readUtf();
        ItemID accountIcon = null;
        if(buf.readBoolean()) {
            accountIcon = ItemID.createFomBytes(buf);
        }

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
        return new BankAccountData(accountNumber, accountName, accountIcon, creator, users, bankData);
    }
}
