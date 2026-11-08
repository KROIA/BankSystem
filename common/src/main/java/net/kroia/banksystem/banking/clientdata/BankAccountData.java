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

    private static final StreamCodec<RegistryFriendlyByteBuf, Map<UUID, BankUserData>> USERS_CODEC =
            ExtraCodecUtils.mapStreamCodec(UUIDUtil.STREAM_CODEC, BankUserData.STREAM_CODEC, HashMap::new);
    private static final StreamCodec<RegistryFriendlyByteBuf, Map<ItemID, BankData>> BANKS_CODEC =
            ExtraCodecUtils.mapStreamCodec(ItemID.STREAM_CODEC, BankData.STREAM_CODEC, HashMap::new);

    // Hand-written: 7 fields (Task #117 added "frozen") exceed StreamCodec.composite's arity.
    public static final StreamCodec<RegistryFriendlyByteBuf, BankAccountData> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                ByteBufCodecs.INT.encode(buf, p.accountNumber);
                ByteBufCodecs.STRING_UTF8.encode(buf, p.accountName);
                ExtraCodecUtils.nullable(ItemID.STREAM_CODEC).encode(buf, p.accountIcon);
                ExtraCodecUtils.nullable(UserData.STREAM_CODEC).encode(buf, p.personalBankOwnerData);
                USERS_CODEC.encode(buf, p.users);
                BANKS_CODEC.encode(buf, p.bankData);
                ByteBufCodecs.BOOL.encode(buf, p.frozen);
            },
            buf -> new BankAccountData(
                    ByteBufCodecs.INT.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ExtraCodecUtils.nullable(ItemID.STREAM_CODEC).decode(buf),
                    ExtraCodecUtils.nullable(UserData.STREAM_CODEC).decode(buf),
                    USERS_CODEC.decode(buf),
                    BANKS_CODEC.decode(buf),
                    ByteBufCodecs.BOOL.decode(buf))
    );

    public final int accountNumber;
    public final String accountName;

    public final @Nullable ItemID accountIcon;
    public final @Nullable UserData personalBankOwnerData; // The creator of the bank account, usually the owner of the bank
    public final Map<UUID, BankUserData> users = new HashMap<>();
    public final Map<ItemID, BankData> bankData = new HashMap<>();
    /** Task #117 — account frozen by an admin. */
    public final boolean frozen;


    public BankAccountData(int accountNumber,
                           String accountName,
                           @Nullable ItemID accountIcon,
                           @Nullable UserData personalBankOwnerData,
                           Map<UUID, BankUserData> users,
                           Map<ItemID, BankData> bankData) {
        this(accountNumber, accountName, accountIcon, personalBankOwnerData, users, bankData, false);
    }

    public BankAccountData(int accountNumber,
                           String accountName,
                           @Nullable ItemID accountIcon,
                           @Nullable UserData personalBankOwnerData,
                           Map<UUID, BankUserData> users,
                           Map<ItemID, BankData> bankData,
                           boolean frozen) {
        this.frozen = frozen;
        this.accountNumber = accountNumber;
        this.accountName = accountName;
        this.accountIcon = accountIcon;
        this.personalBankOwnerData = personalBankOwnerData;
        this.users.putAll(users);
        this.bankData.putAll(bankData);
    }


    /** True if the user is a member of this account or its personal-bank owner. */
    public boolean hasUser(@Nullable UUID userUUID)
    {
        return userUUID != null && (users.containsKey(userUUID)
                || (personalBankOwnerData != null && userUUID.equals(personalBankOwnerData.userUUID())));
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
