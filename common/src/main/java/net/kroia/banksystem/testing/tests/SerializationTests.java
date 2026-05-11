package net.kroia.banksystem.testing.tests;

import io.netty.buffer.Unpooled;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.clientdata.*;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.*;

public class SerializationTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.SERIALIZATION;
    }

    @Override
    public void registerTests() {
        addTest("bankData_codec_round_trip", this::testBankDataCodecRoundTrip);
        addTest("bankAccountData_codec_round_trip", this::testBankAccountDataCodecRoundTrip);
        addTest("bankManagerData_codec_round_trip", this::testBankManagerDataCodecRoundTrip);
        addTest("bankUserData_codec_round_trip", this::testBankUserDataCodecRoundTrip);
        addTest("userData_codec_round_trip", this::testUserDataCodecRoundTrip);
        addTest("itemInfoData_codec_round_trip", this::testItemInfoDataCodecRoundTrip);
    }

    @Override
    public void setup() {}

    @Override
    public void teardown() {}

    private TestResult testBankDataCodecRoundTrip() {
        @SuppressWarnings({"rawtypes", "unchecked"})
        StreamCodec rawCodec = (StreamCodec) BankData.STREAM_CODEC;
        BankData original = new BankData(ItemID.INVALID_ID, 1234L, 567L);
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        try {
            rawCodec.encode(buf, original);
            Object decoded = rawCodec.decode(buf);
            if (!(decoded instanceof BankData d)) {
                return fail("Decoded object is not BankData: " + (decoded == null ? "null" : decoded.getClass().getName()));
            }
            TestResult r;
            r = assertEquals("itemID", original.itemID(), d.itemID());
            if (!r.passed()) return r;
            r = assertEquals("balance", original.balance(), d.balance());
            if (!r.passed()) return r;
            r = assertEquals("lockedBalance", original.lockedBalance(), d.lockedBalance());
            if (!r.passed()) return r;
            return pass("BankData codec round-trip preserved all fields");
        } finally {
            buf.release();
        }
    }

    private TestResult testBankAccountDataCodecRoundTrip() {
        @SuppressWarnings({"rawtypes", "unchecked"})
        StreamCodec rawCodec = (StreamCodec) BankAccountData.STREAM_CODEC;
        BankAccountData original = new BankAccountData(42, "TestAccount", null, null, new HashMap<>(), new HashMap<>());
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        try {
            rawCodec.encode(buf, original);
            Object decoded = rawCodec.decode(buf);
            if (!(decoded instanceof BankAccountData d)) {
                return fail("Decoded object is not BankAccountData: " + (decoded == null ? "null" : decoded.getClass().getName()));
            }
            TestResult r;
            r = assertEquals("accountNumber", original.accountNumber, d.accountNumber);
            if (!r.passed()) return r;
            r = assertEquals("accountName", original.accountName, d.accountName);
            if (!r.passed()) return r;
            r = assertEquals("accountIcon", original.accountIcon, d.accountIcon);
            if (!r.passed()) return r;
            r = assertEquals("personalBankOwnerData", original.personalBankOwnerData, d.personalBankOwnerData);
            if (!r.passed()) return r;
            r = assertEquals("users.size()", original.users.size(), d.users.size());
            if (!r.passed()) return r;
            r = assertEquals("bankData.size()", original.bankData.size(), d.bankData.size());
            if (!r.passed()) return r;
            return pass("BankAccountData codec round-trip preserved all fields");
        } finally {
            buf.release();
        }
    }

    private TestResult testBankManagerDataCodecRoundTrip() {
        @SuppressWarnings({"rawtypes", "unchecked"})
        StreamCodec rawCodec = (StreamCodec) BankManagerData.STREAM_CODEC;
        BankManagerData original = new BankManagerData(
                new BankManagerData.UserMapData(new HashMap<>()),
                new BankManagerData.BankAccountsData(new HashMap<>()),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        try {
            rawCodec.encode(buf, original);
            Object decoded = rawCodec.decode(buf);
            if (!(decoded instanceof BankManagerData d)) {
                return fail("Decoded object is not BankManagerData: " + (decoded == null ? "null" : decoded.getClass().getName()));
            }
            TestResult r;
            r = assertEquals("userMapData.userMap.size()", original.userMapData().userMap().size(), d.userMapData().userMap().size());
            if (!r.passed()) return r;
            r = assertEquals("bankAccountsData.bankAccountMap.size()", original.bankAccountsData().bankAccountMap().size(), d.bankAccountsData().bankAccountMap().size());
            if (!r.passed()) return r;
            r = assertEquals("allowedItems.size()", original.allowedItems().size(), d.allowedItems().size());
            if (!r.passed()) return r;
            r = assertEquals("blacklistedItems.size()", original.blacklistedItems().size(), d.blacklistedItems().size());
            if (!r.passed()) return r;
            r = assertEquals("notRemovableItems.size()", original.notRemovableItems().size(), d.notRemovableItems().size());
            if (!r.passed()) return r;
            return pass("BankManagerData codec round-trip preserved all fields");
        } finally {
            buf.release();
        }
    }

    private TestResult testBankUserDataCodecRoundTrip() {
        @SuppressWarnings({"rawtypes", "unchecked"})
        StreamCodec rawCodec = (StreamCodec) BankUserData.STREAM_CODEC;
        BankUserData original = new BankUserData(UUID.randomUUID(), "TestUser", true,
                BankPermission.DEPOSIT.getValue() | BankPermission.WITHDRAW.getValue());
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        try {
            rawCodec.encode(buf, original);
            Object decoded = rawCodec.decode(buf);
            if (!(decoded instanceof BankUserData d)) {
                return fail("Decoded object is not BankUserData: " + (decoded == null ? "null" : decoded.getClass().getName()));
            }
            TestResult r;
            r = assertEquals("userUUID", original.userUUID, d.userUUID);
            if (!r.passed()) return r;
            r = assertEquals("userName", original.userName, d.userName);
            if (!r.passed()) return r;
            r = assertEquals("enableBankNotifications", original.enableBankNotifications, d.enableBankNotifications);
            if (!r.passed()) return r;
            r = assertEquals("permissions", original.permissions, d.permissions);
            if (!r.passed()) return r;
            return pass("BankUserData codec round-trip preserved all fields");
        } finally {
            buf.release();
        }
    }

    private TestResult testUserDataCodecRoundTrip() {
        @SuppressWarnings({"rawtypes", "unchecked"})
        StreamCodec rawCodec = (StreamCodec) UserData.STREAM_CODEC;
        UserData original = new UserData(UUID.randomUUID(), "TestUser", false, new net.minecraft.nbt.CompoundTag());
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        try {
            rawCodec.encode(buf, original);
            Object decoded = rawCodec.decode(buf);
            if (!(decoded instanceof UserData d)) {
                return fail("Decoded object is not UserData: " + (decoded == null ? "null" : decoded.getClass().getName()));
            }
            TestResult r;
            r = assertEquals("userUUID", original.userUUID(), d.userUUID());
            if (!r.passed()) return r;
            r = assertEquals("userName", original.userName(), d.userName());
            if (!r.passed()) return r;
            r = assertEquals("enableBankNotifications", original.enableBankNotifications(), d.enableBankNotifications());
            if (!r.passed()) return r;
            return pass("UserData codec round-trip preserved all fields");
        } finally {
            buf.release();
        }
    }

    private TestResult testItemInfoDataCodecRoundTrip() {
        @SuppressWarnings({"rawtypes", "unchecked"})
        StreamCodec rawCodec = (StreamCodec) ItemInfoData.STREAM_CODEC;
        ItemInfoData original = new ItemInfoData(ItemID.INVALID_ID, 1000.0, 250.0, new ArrayList<>());
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        try {
            rawCodec.encode(buf, original);
            Object decoded = rawCodec.decode(buf);
            if (!(decoded instanceof ItemInfoData d)) {
                return fail("Decoded object is not ItemInfoData: " + (decoded == null ? "null" : decoded.getClass().getName()));
            }
            TestResult r;
            r = assertEquals("itemID", original.itemID(), d.itemID());
            if (!r.passed()) return r;
            r = assertEquals("totalSupply", original.totalSupply(), d.totalSupply());
            if (!r.passed()) return r;
            r = assertEquals("totalLocked", original.totalLocked(), d.totalLocked());
            if (!r.passed()) return r;
            r = assertEquals("bankAccounts.size()", original.bankAccounts().size(), d.bankAccounts().size());
            if (!r.passed()) return r;
            return pass("ItemInfoData codec round-trip preserved all fields");
        } finally {
            buf.release();
        }
    }
}
