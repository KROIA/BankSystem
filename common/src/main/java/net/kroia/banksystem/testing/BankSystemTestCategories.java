package net.kroia.banksystem.testing;

import net.kroia.modutilities.testing.TestCategory;

public class BankSystemTestCategories {

    public static final TestCategory PERMISSION = new TestCategory(
            "banksystem", "permission", "Bank permission logic tests",
            TestCategory.ServerType.BOTH, false);

    public static final TestCategory BANK_ACCOUNT = new TestCategory(
            "banksystem", "bank_account", "Bank account creation and management tests",
            TestCategory.ServerType.MASTER_ONLY, false);

    public static final TestCategory BANK_MANAGER = new TestCategory(
            "banksystem", "bank_manager", "Bank manager operations tests",
            TestCategory.ServerType.MASTER_ONLY, false);

    public static final TestCategory MONEY = new TestCategory(
            "banksystem", "money", "Money transfer and balance tests",
            TestCategory.ServerType.MASTER_ONLY, false);

    public static final TestCategory ITEM_BANK = new TestCategory(
            "banksystem", "item_bank", "Item banking deposit/withdraw tests",
            TestCategory.ServerType.MASTER_ONLY, false);

    public static final TestCategory NETWORKING = new TestCategory(
            "banksystem", "networking", "Multi-server networking tests",
            TestCategory.ServerType.BOTH, true);

    public static final TestCategory COMMAND = new TestCategory(
            "banksystem", "command", "Command handler tests",
            TestCategory.ServerType.BOTH, true);

    public static final TestCategory DATA_PERSISTENCE = new TestCategory(
            "banksystem", "data_persistence", "Save/load data persistence tests",
            TestCategory.ServerType.MASTER_ONLY, true);

    public static final TestCategory SERIALIZATION = new TestCategory(
            "banksystem", "serialization", "Codec round-trip serialization tests",
            TestCategory.ServerType.BOTH, false);

    public static final TestCategory LIFECYCLE = new TestCategory(
            "banksystem", "lifecycle", "Memory, threading, and lifecycle regression tests",
            TestCategory.ServerType.BOTH, false);

    public static final TestCategory DATABASE = new TestCategory(
            "banksystem", "database", "SQL balance history persistence tests",
            TestCategory.ServerType.MASTER_ONLY, false);

    public static final TestCategory ITEM_ID = new TestCategory(
            "banksystem", "item_id", "ItemID identity and volatile-component normalization tests",
            TestCategory.ServerType.MASTER_ONLY, true);
}
