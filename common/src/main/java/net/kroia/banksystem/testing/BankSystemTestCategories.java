package net.kroia.banksystem.testing;

import net.kroia.modutilities.testing.TestCategory;

public class BankSystemTestCategories {

    public static final TestCategory PERMISSION = new TestCategory(
            "permission", "Bank permission logic tests",
            TestCategory.ServerType.BOTH, false);

    public static final TestCategory BANK_ACCOUNT = new TestCategory(
            "bank_account", "Bank account creation and management tests",
            TestCategory.ServerType.MASTER_ONLY, false);

    public static final TestCategory BANK_MANAGER = new TestCategory(
            "bank_manager", "Bank manager operations tests",
            TestCategory.ServerType.MASTER_ONLY, false);

    public static final TestCategory MONEY = new TestCategory(
            "money", "Money transfer and balance tests",
            TestCategory.ServerType.MASTER_ONLY, false);

    public static final TestCategory ITEM_BANK = new TestCategory(
            "item_bank", "Item banking deposit/withdraw tests",
            TestCategory.ServerType.MASTER_ONLY, false);

    public static final TestCategory NETWORKING = new TestCategory(
            "networking", "Multi-server networking tests",
            TestCategory.ServerType.BOTH, true);

    public static final TestCategory COMMAND = new TestCategory(
            "command", "Command handler tests",
            TestCategory.ServerType.BOTH, true);

    public static final TestCategory DATA_PERSISTENCE = new TestCategory(
            "data_persistence", "Save/load data persistence tests",
            TestCategory.ServerType.MASTER_ONLY, true);

    public static final TestCategory SERIALIZATION = new TestCategory(
            "serialization", "Codec round-trip serialization tests",
            TestCategory.ServerType.BOTH, false);

    public static final TestCategory LIFECYCLE = new TestCategory(
            "lifecycle", "Memory, threading, and lifecycle regression tests",
            TestCategory.ServerType.BOTH, false);

    public static final TestCategory DATABASE = new TestCategory(
            "database", "SQL balance history persistence tests",
            TestCategory.ServerType.MASTER_ONLY, false);
}
