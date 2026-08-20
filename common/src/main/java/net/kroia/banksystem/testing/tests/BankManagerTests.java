package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.bank.ServerBank;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import net.kroia.banksystem.banking.bankmanager.BankManager;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.UUID;

/**
 * Tests for {@link net.kroia.banksystem.banking.bankmanager.ServerBankManager}
 * covering conversion precision, user management, bank account CRUD,
 * item filter correctness, and admin status.
 *
 * These tests use the live server bank manager obtained via the API
 * because ServerBankManager requires full backend initialization.
 */
public class BankManagerTests extends TestSuite {

    private static final UUID TEST_USER_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TEST_USER_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String TEST_USER_A_NAME = "TestManagerUserA";
    private static final String TEST_USER_B_NAME = "TestManagerUserB";

    private IServerBankManager manager;

    /** Account numbers created during tests, to be cleaned up in teardown */
    private final List<Integer> createdAccountNumbers = new ArrayList<>();

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.BANK_MANAGER;
    }

    @Override
    public void registerTests() {
        addTest("convertToRealAmount_uses_double", this::testConvertToRealAmountUsesDouble);
        addTest("addUser_and_getUserByUUID", this::testAddUserAndGetUserByUUID);
        addTest("createBankAccount_returns_valid", this::testCreateBankAccountReturnsValid);
        addTest("deleteBankAccount_removes", this::testDeleteBankAccountRemoves);
        addTest("removeUser_no_concurrent_modification", this::testRemoveUserNoConcurrentModification);
        addTest("isBanksystemAdmin_after_set", this::testIsBanksystemAdminAfterSet);
        addTest("isBanksystemAdmin_after_revoke", this::testIsBanksystemAdminAfterRevoke);
        // Task #39 — ALLOW_ALL_ITEMS blacklist-only mode
        addTest("allow_all_items_off_preserves_current_behavior",
                this::testAllowAllItemsOffPreservesCurrentBehavior);
        addTest("allow_all_items_on_permits_any_non_blacklisted_item",
                this::testAllowAllItemsOnPermitsAnyNonBlacklistedItem);
        addTest("allow_all_items_on_still_refuses_blacklisted",
                this::testAllowAllItemsOnStillRefusesBlacklisted);
        addTest("banked_item_stays_allowed_after_allow_all_revert",
                this::testBankedItemStaysAllowedAfterAllowAllRevert);
        // Task #58 — creator-only account count backing the per-player cap
        addTest("count_accounts_created_by_counts_creator_only",
                this::testCountAccountsCreatedByCountsCreatorOnly);
        // Task #57 — item allow/blacklist overhaul
        addTest("money_blacklist_round_trip", this::testMoneyBlacklistRoundTrip);
        addTest("blacklist_beats_allow_all", this::testBlacklistBeatsAllowAll);
        addTest("disallow_clears_holders_with_amount_capture",
                this::testDisallowClearsHoldersWithAmountCapture);
        addTest("money_absent_crash_safety", this::testMoneyAbsentCrashSafety);
    }

    @Override
    public void setup() {
        IBankManager bankManager = BankSystemMod.getAPI().getServerBankManager();
        if (bankManager == null) {
            return;
        }
        manager = bankManager.getSync();
        if (manager == null) {
            return;
        }

        // Ensure test users are registered
        if (!manager.userExists(TEST_USER_A)) {
            manager.addUser(new User(TEST_USER_A, TEST_USER_A_NAME));
        }
        if (!manager.userExists(TEST_USER_B)) {
            manager.addUser(new User(TEST_USER_B, TEST_USER_B_NAME));
        }
    }

    @Override
    public void teardown() {
        if (manager == null) {
            return;
        }

        // Delete all test accounts we created
        for (int accountNr : createdAccountNumbers) {
            manager.deleteBankAccount(accountNr);
        }
        createdAccountNumbers.clear();

        // Remove test users (also cleans up any personal accounts they own)
        manager.removeUser(TEST_USER_A);
        manager.removeUser(TEST_USER_B);
    }

    // ========================= Conversion Precision =========================

    /**
     * Issue #20: convertToRealAmountStatic uses (float) cast instead of (double),
     * causing precision loss for large raw values.
     *
     * With ITEM_FRACTION_SCALE_FACTOR = 100:
     *   raw = 123456789L  =>  expected real = 1234567.89
     *   With float cast: (float)123456789 / (float)100 = 1234567.875 (precision loss)
     *   With double cast: (double)123456789 / (double)100 = 1234567.89
     */
    private TestResult testConvertToRealAmountUsesDouble() {
        long rawAmount = 123456789L;
        double expected = (double) rawAmount / (double) BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
        double actual = BankManager.convertToRealAmountStatic(rawAmount);

        // The float-based conversion would give 1234567.875 instead of 1234567.89
        double tolerance = 0.001;
        double diff = Math.abs(expected - actual);
        if (diff > tolerance) {
            return TestResult.fail("",
                    "convertToRealAmount should use double precision (Issue #20)",
                    String.valueOf(expected),
                    String.valueOf(actual));
        }
        return pass("convertToRealAmount returns correct double-precision result");
    }

    // ========================= User Management =========================

    private TestResult testAddUserAndGetUserByUUID() {
        if (manager == null) {
            return fail("ServerBankManager is null -- cannot run on slave server");
        }

        User user = manager.getUserByUUID(TEST_USER_A);
        if (user == null) {
            return fail("getUserByUUID returned null for a registered test user");
        }
        if (!user.getUUID().equals(TEST_USER_A)) {
            return TestResult.fail("",
                    "getUserByUUID returned wrong UUID",
                    TEST_USER_A.toString(),
                    user.getUUID().toString());
        }
        return pass("addUser registers user and getUserByUUID retrieves it correctly");
    }

    // ================= Task #58 — creator-only account count =================

    /**
     * {@code countAccountsCreatedBy} must count ONLY accounts whose creatorUUID equals the
     * given player — not shared-account membership. A second account with a different
     * creator, and membership on the first account, must not inflate the creator's count.
     */
    private TestResult testCountAccountsCreatedByCountsCreatorOnly() {
        if (manager == null) {
            return fail("ServerBankManager is null -- cannot run on slave server");
        }
        int baseA = manager.countAccountsCreatedBy(TEST_USER_A);
        int baseB = manager.countAccountsCreatedBy(TEST_USER_B);

        IServerBankAccount accA = manager.createBankAccount("CapTestA");
        if (accA == null) return fail("createBankAccount returned null");
        createdAccountNumbers.add(accA.getAccountNumber());
        accA.setCreatorUUID(TEST_USER_A);

        // Membership by B on A's account must NOT count against B.
        User userB = manager.getUserByUUID(TEST_USER_B);
        if (userB != null) {
            accA.addUser(userB, net.kroia.banksystem.banking.BankPermission.getAllPermissions());
        }

        IServerBankAccount accB = manager.createBankAccount("CapTestB");
        if (accB == null) return fail("createBankAccount returned null");
        createdAccountNumbers.add(accB.getAccountNumber());
        accB.setCreatorUUID(TEST_USER_B);

        int afterA = manager.countAccountsCreatedBy(TEST_USER_A);
        int afterB = manager.countAccountsCreatedBy(TEST_USER_B);
        if (afterA != baseA + 1) return fail("A creator count expected " + (baseA + 1) + " got " + afterA);
        if (afterB != baseB + 1) return fail("B creator count expected " + (baseB + 1) + " got " + afterB
                + " (membership must not count)");
        return pass("countAccountsCreatedBy counts creator only, not membership.");
    }

    // ========================= Bank Account CRUD =========================

    private TestResult testCreateBankAccountReturnsValid() {
        if (manager == null) {
            return fail("ServerBankManager is null -- cannot run on slave server");
        }

        IServerBankAccount account = manager.createBankAccount("TestCreateAccount");
        if (account == null) {
            return fail("createBankAccount returned null");
        }
        createdAccountNumbers.add(account.getAccountNumber());

        if (account.getAccountNumber() <= ServerBankAccount.INVALID_ACCOUNT_NUMBER) {
            return fail("Account number should be > INVALID_ACCOUNT_NUMBER but was "
                    + account.getAccountNumber());
        }
        return pass("createBankAccount returns a valid non-null account with a valid number");
    }

    private TestResult testDeleteBankAccountRemoves() {
        if (manager == null) {
            return fail("ServerBankManager is null -- cannot run on slave server");
        }

        IServerBankAccount account = manager.createBankAccount("TestDeleteAccount");
        if (account == null) {
            return fail("createBankAccount returned null");
        }
        int accountNr = account.getAccountNumber();
        // Do not add to createdAccountNumbers because we delete it here

        boolean deleted = manager.deleteBankAccount(accountNr);
        if (!deleted) {
            // Clean up if deletion failed
            createdAccountNumbers.add(accountNr);
            return fail("deleteBankAccount returned false");
        }

        boolean exists = manager.bankAccountExists(accountNr);
        return assertFalse("Bank account should not exist after deletion", exists);
    }

    // ========================= Concurrent Modification =========================

    /**
     * Issue #21: removeUser iterates over bankAccounts while potentially modifying
     * it through deleteBankAccount. This test creates a user with a personal bank
     * account (which gets deleted when the user is removed if it has no other users),
     * and verifies no ConcurrentModificationException is thrown.
     */
    private TestResult testRemoveUserNoConcurrentModification() {
        if (manager == null) {
            return fail("ServerBankManager is null -- cannot run on slave server");
        }

        // Create a fresh user
        UUID tempUUID = UUID.fromString("00000000-0000-0000-0000-00000000AAAA");
        String tempName = "TempConcurrentUser";

        if (!manager.userExists(tempUUID)) {
            manager.addUser(new User(tempUUID, tempName));
        }

        // Create a personal bank account so removeUser has accounts to iterate
        manager.createPersonalBankAccount(tempUUID);

        // Also add the user to a non-personal account to test more iteration
        IServerBankAccount extraAccount = manager.createBankAccount("ConcurrentTestAccount");
        int extraAccountNr = ServerBankAccount.INVALID_ACCOUNT_NUMBER;
        if (extraAccount != null) {
            extraAccountNr = extraAccount.getAccountNumber();
            User tempUser = manager.getUserByUUID(tempUUID);
            if (tempUser != null) {
                extraAccount.addUser(tempUser, 1);
            }
        }

        try {
            manager.removeUser(tempUUID);
        } catch (ConcurrentModificationException e) {
            // Clean up
            if (extraAccountNr != ServerBankAccount.INVALID_ACCOUNT_NUMBER) {
                manager.deleteBankAccount(extraAccountNr);
            }
            return fail("removeUser threw ConcurrentModificationException (Issue #21)");
        } catch (Exception e) {
            if (extraAccountNr != ServerBankAccount.INVALID_ACCOUNT_NUMBER) {
                manager.deleteBankAccount(extraAccountNr);
            }
            return fail("removeUser threw unexpected exception: "
                    + e.getClass().getSimpleName() + " - " + e.getMessage());
        }

        // Clean up the extra account if it still exists
        if (extraAccountNr != ServerBankAccount.INVALID_ACCOUNT_NUMBER
                && manager.bankAccountExists(extraAccountNr)) {
            manager.deleteBankAccount(extraAccountNr);
        }

        return pass("removeUser completed without ConcurrentModificationException");
    }

    // ========================= Admin Status =========================

    private TestResult testIsBanksystemAdminAfterSet() {
        if (manager == null) {
            return fail("ServerBankManager is null -- cannot run on slave server");
        }

        boolean setResult = manager.setBanksystemAdminMode(TEST_USER_A, true);
        if (!setResult) {
            return fail("setBanksystemAdminMode returned false -- user may not be registered");
        }

        boolean isAdmin = manager.isBanksystemAdmin(TEST_USER_A);

        // Clean up: revoke admin so teardown leaves a clean state
        manager.setBanksystemAdminMode(TEST_USER_A, false);

        return assertTrue("isBanksystemAdmin should return true after setBanksystemAdminMode(true)",
                isAdmin);
    }

    private TestResult testIsBanksystemAdminAfterRevoke() {
        if (manager == null) {
            return fail("ServerBankManager is null -- cannot run on slave server");
        }

        // First set admin
        manager.setBanksystemAdminMode(TEST_USER_A, true);
        // Then revoke
        manager.setBanksystemAdminMode(TEST_USER_A, false);

        boolean isAdmin = manager.isBanksystemAdmin(TEST_USER_A);
        return assertFalse("isBanksystemAdmin should return false after revoking admin",
                isAdmin);
    }

    // ============= Task #39 — ALLOW_ALL_ITEMS blacklist-only mode =============

    /**
     * Returns the master settings object, or null if the backend is not initialized
     * (e.g. slave-only test run). Callers must skip if null.
     */
    private BankSystemModSettings getSettings() {
        BankSystemModBackend.Instances instances = BankSystemModBackend.getInstances_forTesting();
        return instances == null ? null : instances.SERVER_SETTINGS;
    }

    /**
     * Task #39: with ALLOW_ALL_ITEMS at its default (false), the pre-Task-#39 whitelist
     * behavior must be preserved — an ItemID that is not in the explicit allow-list must
     * report as NOT allowed.
     */
    private TestResult testAllowAllItemsOffPreservesCurrentBehavior() {
        if (manager == null) {
            return fail("ServerBankManager is null -- cannot run on slave server");
        }
        BankSystemModSettings settings = getSettings();
        if (settings == null) {
            return fail("SERVER_SETTINGS is null -- backend not fully initialized");
        }

        // Register a vanilla item that is NOT in INITIAL_ALLOWED_ITEMS and NOT in
        // INITIAL_BLACKLIST_ITEMS. Dirt fits both requirements.
        ItemID freshItem = ItemID.getOrRegisterFromItemStackServerSide_direct(
                Items.DIRT.getDefaultInstance());
        if (!freshItem.isValid()) {
            return fail("Could not register DIRT for the allow-all-off test");
        }

        // Precondition: item must NOT be in the explicit allow-list. Snapshot + strip if
        // a previous test left it there; restore in finally.
        boolean wasInAllowList = manager.getAllowedItems().contains(freshItem);
        boolean savedAllowAll = settings.BANK.ALLOW_ALL_ITEMS.get();
        if (wasInAllowList) {
            manager.disallowItemID(freshItem);
        }

        try {
            settings.BANK.ALLOW_ALL_ITEMS.set(false);
            boolean allowed = manager.isItemIDAllowed(freshItem);
            return assertFalse(
                    "With ALLOW_ALL_ITEMS=false, an unlisted item must NOT be allowed",
                    allowed);
        } finally {
            settings.BANK.ALLOW_ALL_ITEMS.set(savedAllowAll);
            if (wasInAllowList) {
                manager.allowItemID(freshItem);
            }
        }
    }

    /**
     * Task #39: with ALLOW_ALL_ITEMS=true, a non-blacklisted ItemID that is NOT in the
     * explicit allow-list must report as allowed (allow-list bypass).
     */
    private TestResult testAllowAllItemsOnPermitsAnyNonBlacklistedItem() {
        if (manager == null) {
            return fail("ServerBankManager is null -- cannot run on slave server");
        }
        BankSystemModSettings settings = getSettings();
        if (settings == null) {
            return fail("SERVER_SETTINGS is null -- backend not fully initialized");
        }

        // Use dirt again — vanilla, not in INITIAL_ALLOWED_ITEMS, not in INITIAL_BLACKLIST_ITEMS.
        ItemID freshItem = ItemID.getOrRegisterFromItemStackServerSide_direct(
                Items.DIRT.getDefaultInstance());
        if (!freshItem.isValid()) {
            return fail("Could not register DIRT for the allow-all-on test");
        }

        // Precondition: ensure the item is NOT already in the explicit allow-list, so the
        // pass would come from the allow-all branch (not the allow-list membership check).
        boolean wasInAllowList = manager.getAllowedItems().contains(freshItem);
        boolean savedAllowAll = settings.BANK.ALLOW_ALL_ITEMS.get();
        if (wasInAllowList) {
            manager.disallowItemID(freshItem);
        }

        try {
            settings.BANK.ALLOW_ALL_ITEMS.set(true);
            boolean allowed = manager.isItemIDAllowed(freshItem);
            return assertTrue(
                    "With ALLOW_ALL_ITEMS=true, a non-blacklisted item must be allowed even without an explicit entry",
                    allowed);
        } finally {
            settings.BANK.ALLOW_ALL_ITEMS.set(savedAllowAll);
            if (wasInAllowList) {
                manager.allowItemID(freshItem);
            }
        }
    }

    /**
     * Opening a bank slot for an item registers that item in the explicit allow-list, so it
     * stays bankable — and stays offered by the Bank Download block's item picker — after
     * ALLOW_ALL_ITEMS is switched back off. Without this, an item deposited during allow-all
     * became unbankable and invisible to the picker the moment the setting was reverted.
     */
    private TestResult testBankedItemStaysAllowedAfterAllowAllRevert() {
        if (manager == null) {
            return fail("ServerBankManager is null -- cannot run on slave server");
        }
        BankSystemModSettings settings = getSettings();
        if (settings == null) {
            return fail("SERVER_SETTINGS is null -- backend not fully initialized");
        }

        // Gravel: vanilla, not in INITIAL_ALLOWED_ITEMS, not in INITIAL_BLACKLIST_ITEMS.
        ItemID freshItem = ItemID.getOrRegisterFromItemStackServerSide_direct(
                Items.GRAVEL.getDefaultInstance());
        if (!freshItem.isValid()) {
            return fail("Could not register GRAVEL for the banked-item test");
        }

        boolean wasInAllowList = manager.getAllowedItems().contains(freshItem);
        boolean savedAllowAll = settings.BANK.ALLOW_ALL_ITEMS.get();
        if (wasInAllowList) {
            manager.disallowItemID(freshItem);
        }

        try {
            // Deposit-equivalent: opening the bank slot is what a first deposit does.
            settings.BANK.ALLOW_ALL_ITEMS.set(true);
            if (ServerBank.create(freshItem, 0) == null) {
                return fail("ServerBank.create refused an allowed item under ALLOW_ALL_ITEMS=true");
            }
            if (!manager.getAllowedItems().contains(freshItem)) {
                return fail("Opening a bank slot did not add the item to the allowed-items list");
            }

            // The point of the fix: reverting the setting must not strand the balance.
            settings.BANK.ALLOW_ALL_ITEMS.set(false);
            return assertTrue(
                    "An item that holds a bank slot must stay allowed after ALLOW_ALL_ITEMS is turned off",
                    manager.isItemIDAllowed(freshItem));
        } finally {
            settings.BANK.ALLOW_ALL_ITEMS.set(savedAllowAll);
            if (!wasInAllowList) {
                manager.disallowItemID(freshItem);
            }
        }
    }

    /**
     * Task #39: blacklist ALWAYS wins over allow-all. With ALLOW_ALL_ITEMS=true, a
     * blacklisted ItemID (bedrock, taken from INITIAL_BLACKLIST_ITEMS) must STILL be
     * refused — same guarantee allowItemID() enforces at add-time.
     */
    private TestResult testAllowAllItemsOnStillRefusesBlacklisted() {
        if (manager == null) {
            return fail("ServerBankManager is null -- cannot run on slave server");
        }
        BankSystemModSettings settings = getSettings();
        if (settings == null) {
            return fail("SERVER_SETTINGS is null -- backend not fully initialized");
        }

        ItemID bedrock = ItemID.getOrRegisterFromItemStackServerSide_direct(
                Items.BEDROCK.getDefaultInstance());
        if (!bedrock.isValid()) {
            return fail("Could not register BEDROCK for the blacklist-wins test");
        }
        // Sanity: bedrock IS in INITIAL_BLACKLIST_ITEMS. If this assertion ever flips,
        // the test premise is broken and the check below tells us nothing useful.
        if (!manager.isItemIDBlacklisted(bedrock)) {
            return fail("Bedrock is expected to be blacklisted by default -- test premise broken");
        }

        boolean savedAllowAll = settings.BANK.ALLOW_ALL_ITEMS.get();
        try {
            settings.BANK.ALLOW_ALL_ITEMS.set(true);
            boolean allowed = manager.isItemIDAllowed(bedrock);
            return assertFalse(
                    "Blacklist must win over ALLOW_ALL_ITEMS -- bedrock stays refused",
                    allowed);
        } finally {
            settings.BANK.ALLOW_ALL_ITEMS.set(savedAllowAll);
        }
    }

    // ================= Task #57 — allow/blacklist overhaul =================

    /**
     * Acceptance A/B: money is no longer "not-removable". Disallowing it blacklists +
     * forbids it and a freshly seeded account gets NO money slot; re-allowing restores it.
     */
    private TestResult testMoneyBlacklistRoundTrip() {
        if (manager == null) return fail("ServerBankManager is null -- cannot run on slave server");
        ItemID money = MoneyItem.getItemID();
        if (money == null || !money.isValid()) return fail("money ItemID is not registered");
        boolean wasBlacklisted = manager.isItemIDBlacklisted(money);
        try {
            if (!manager.disallowItemID(money))
                return fail("disallowItemID(money) returned false -- money must be disallowable now");
            if (!manager.isItemIDBlacklisted(money))
                return fail("money not blacklisted after disallow");
            if (manager.isItemIDAllowed(money))
                return fail("money still reported allowed after disallow");

            // A freshly seeded account must have NO money slot while money is disallowed.
            IServerBankAccount acc = manager.createBankAccount("Task57MoneyRoundTrip");
            if (acc == null) return fail("createBankAccount returned null");
            createdAccountNumbers.add(acc.getAccountNumber());
            net.kroia.banksystem.banking.bankmanager.ServerBankManager.addDefaultBankSlots(acc);
            if (acc.hasBank(money))
                return fail("new account seeded a money slot despite money being disallowed");

            if (!manager.allowItemID(money))
                return fail("allowItemID(money) returned false after disallow");
            if (manager.isItemIDBlacklisted(money))
                return fail("money still blacklisted after re-allow");
            if (!manager.isItemIDAllowed(money))
                return fail("money not allowed after re-allow");
            return pass("money blacklist round-trip: disallow blacklists+forbids+skips seed, re-allow restores");
        } finally {
            if (!wasBlacklisted && manager.isItemIDBlacklisted(money))
                manager.allowItemID(money);
        }
    }

    /**
     * Acceptance D: a RUNTIME-disallowed item stays refused even with ALLOW_ALL_ITEMS=true
     * (blacklist beats allow-all), and ServerBank.create refuses to open a slot for it.
     */
    private TestResult testBlacklistBeatsAllowAll() {
        if (manager == null) return fail("ServerBankManager is null -- cannot run on slave server");
        BankSystemModSettings settings = getSettings();
        if (settings == null) return fail("SERVER_SETTINGS is null -- backend not fully initialized");
        ItemID dirt = ItemID.getOrRegisterFromItemStackServerSide_direct(Items.DIRT.getDefaultInstance());
        if (!dirt.isValid()) return fail("Could not register DIRT for the blacklist-beats-allow-all test");
        boolean wasBlacklisted = manager.isItemIDBlacklisted(dirt);
        boolean savedAllowAll = settings.BANK.ALLOW_ALL_ITEMS.get();
        try {
            manager.disallowItemID(dirt); // runtime-blacklist it
            settings.BANK.ALLOW_ALL_ITEMS.set(true);
            if (manager.isItemIDAllowed(dirt))
                return fail("a runtime-blacklisted item was reported allowed under ALLOW_ALL_ITEMS=true");
            if (ServerBank.create(dirt, 0) != null)
                return fail("ServerBank.create opened a slot for a blacklisted item under ALLOW_ALL");
            return pass("runtime blacklist beats ALLOW_ALL_ITEMS");
        } finally {
            settings.BANK.ALLOW_ALL_ITEMS.set(savedAllowAll);
            if (!wasBlacklisted) manager.allowItemID(dirt);
        }
    }

    /**
     * Acceptance C: disallowing a held item captures every holder's exact free + locked
     * balance in the report and clears the slot from the account.
     */
    private TestResult testDisallowClearsHoldersWithAmountCapture() {
        if (manager == null) return fail("ServerBankManager is null -- cannot run on slave server");
        ItemID gravel = ItemID.getOrRegisterFromItemStackServerSide_direct(Items.GRAVEL.getDefaultInstance());
        if (!gravel.isValid()) return fail("Could not register GRAVEL for the disallow-clears-holders test");
        boolean wasBlacklisted = manager.isItemIDBlacklisted(gravel);
        try {
            manager.allowItemID(gravel);
            IServerBankAccount acc = manager.createBankAccount("Task57DisallowClears");
            if (acc == null) return fail("createBankAccount returned null");
            createdAccountNumbers.add(acc.getAccountNumber());
            if (acc.createBank(gravel, 5000L) == null)
                return fail("could not open a gravel bank with a 5000 balance");

            List<ISyncServerBankManager.DisallowedHolder> report = manager.disallowItemIDAndReport(gravel);
            if (report == null) return fail("disallowItemIDAndReport returned null");
            ISyncServerBankManager.DisallowedHolder found = null;
            for (ISyncServerBankManager.DisallowedHolder h : report) {
                if (h.accountNr() == acc.getAccountNumber()) { found = h; break; }
            }
            if (found == null) return fail("holding account missing from the disallow report");
            if (found.free() != 5000L)
                return TestResult.fail("", "captured free balance mismatch", "5000", String.valueOf(found.free()));
            if (found.locked() != 0L)
                return TestResult.fail("", "captured locked balance mismatch", "0", String.valueOf(found.locked()));
            if (acc.hasBank(gravel))
                return fail("gravel slot was not cleared from the holder after disallow");
            return pass("disallow captured free+locked amounts and cleared the holder slot");
        } finally {
            if (!wasBlacklisted) manager.allowItemID(gravel);
        }
    }

    /**
     * Acceptance H: with money disallowed, seeding, resurrection and money getters degrade
     * gracefully (no slot, null bank, no exception) instead of crashing.
     */
    private TestResult testMoneyAbsentCrashSafety() {
        if (manager == null) return fail("ServerBankManager is null -- cannot run on slave server");
        ItemID money = MoneyItem.getItemID();
        if (money == null || !money.isValid()) return fail("money ItemID is not registered");
        boolean wasBlacklisted = manager.isItemIDBlacklisted(money);
        try {
            manager.disallowItemID(money);
            IServerBankAccount acc = manager.createBankAccount("Task57MoneyAbsent");
            if (acc == null) return fail("createBankAccount returned null");
            createdAccountNumbers.add(acc.getAccountNumber());
            // Seeding must not crash and must not create a money slot.
            net.kroia.banksystem.banking.bankmanager.ServerBankManager.addDefaultBankSlots(acc);
            if (acc.hasBank(money))
                return fail("money slot seeded despite blacklist");
            // Resurrection chokepoint must return null, not throw.
            if (manager.getOrCreatePersonalBank(TEST_USER_A, money) != null)
                return fail("getOrCreatePersonalBank(money) resurrected a blacklisted money bank");
            if (acc.getBank(money) != null)
                return fail("getBank(money) returned non-null on a money-less account");
            // Money circulation getter must degrade, not throw.
            manager.getRealMoneyCirculation();
            return pass("money-absent: no seed, null resurrection, money getters do not crash");
        } finally {
            if (!wasBlacklisted) manager.allowItemID(money);
        }
    }
}
