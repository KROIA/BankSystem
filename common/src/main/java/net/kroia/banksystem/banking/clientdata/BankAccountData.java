package net.kroia.banksystem.banking.clientdata;

import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class BankAccountData {

    public static final StreamCodec<RegistryFriendlyByteBuf, BankAccountData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.INT, p -> p.accountNumber,
            ByteBufCodecs.STRING_UTF8, p -> p.accountName,
            ExtraCodecUtils.nullable(ItemID.STREAM_CODEC), p -> p.accountIcon,
            ExtraCodecUtils.nullable(UserData.STREAM_CODEC), p -> p.personalBankOwnerData,
            ExtraCodecUtils.mapStreamCodec(UUIDUtil.STREAM_CODEC, BankUserData.STREAM_CODEC, HashMap::new), p -> p.users,
            ExtraCodecUtils.mapStreamCodec(ItemID.STREAM_CODEC, BankData.STREAM_CODEC, HashMap::new), p -> p.bankData,
            BankAccountData::new
    );

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


    public List<String> getAllUserNames()
    {
        List<String> names = new ArrayList<>();
        if(personalBankOwnerData != null)
            names.add(personalBankOwnerData.userName());
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

    public boolean hasPermission(UUID userUUID, int permission)
    {
        if (userUUID == null || permission < 0) {
            return false; // Invalid user UUID or permission
        }
        BankUserData user = users.get(userUUID);
        if (user != null) {
            return BankPermission.hasPermission(user.permissions, permission); // Check user's permissions
        }
        return personalBankOwnerData != null && personalBankOwnerData.userUUID().equals(userUUID); // Personal bank owner has all permissions
    }
    public boolean hasAnyPermission(UUID userUUID, int permission)
    {
        if (userUUID == null || permission < 0) {
            return false; // Invalid user UUID or permission
        }
        BankUserData user = users.get(userUUID);
        if (user != null) {
            return BankPermission.hasAnyPermission(user.permissions, permission); // Check user's permissions
        }
        return personalBankOwnerData != null && personalBankOwnerData.userUUID().equals(userUUID); // Personal bank owner has all permissions
    }
}
