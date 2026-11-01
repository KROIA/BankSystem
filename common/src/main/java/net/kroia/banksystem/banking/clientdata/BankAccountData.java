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

    /**
     * Returns the raw permission bit mask the given user holds on this account.
     * The personal-bank owner implicitly holds all permissions; a non-member returns {@code 0}.
     *
     * @param userUUID the user to query
     * @return the held permission bits (0 if the user is not a member of this account)
     */
    public int getPermissions(UUID userUUID) {
        if (userUUID == null) return 0;
        BankUserData user = users.get(userUUID);
        if (user != null) return user.permissions;
        return (personalBankOwnerData != null && personalBankOwnerData.userUUID().equals(userUUID))
                ? BankPermission.getAllPermissions() : 0;
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

    /**
     * Type-safe overload of {@link #hasPermission(UUID, int)} taking a {@link BankPermission} enum.
     * Prefer this overload over the int variant.
     */
    public boolean hasPermission(UUID userUUID, BankPermission permission)
    {
        return hasPermission(userUUID, permission.getValue());
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

    /**
     * AND-semantics permission check (FR-001): returns true only when the user holds
     * <b>every</b> bit in {@code permission}. Contrast with {@link #hasAnyPermission(UUID, int)}
     * (OR — at least one bit). The personal-bank owner implicitly holds all permissions.
     *
     * @param userUUID   the user to check
     * @param permission a permission bit mask (e.g. {@code DEPOSIT | WITHDRAW})
     * @return true iff the user has all requested permission bits on this account
     */
    public boolean hasAllPermissions(UUID userUUID, int permission)
    {
        if (userUUID == null || permission < 0) {
            return false; // Invalid user UUID or permission
        }
        BankUserData user = users.get(userUUID);
        if (user != null) {
            return BankPermission.hasAllPermissions(user.permissions, permission);
        }
        return personalBankOwnerData != null && personalBankOwnerData.userUUID().equals(userUUID); // Personal bank owner has all permissions
    }
}
