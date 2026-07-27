package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import net.kroia.banksystem.banking.bankmanager.BankManager;
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
        addTest("getNotRemovableItems_returns_correct_list", this::testGetNotRemovableItemsReturnsCorrectList);
        addTest("isBanksystemAdmin_after_set", this::testIsBanksystemAdminAfterSet);
        addTest("isBanksystemAdmin_after_revoke", this::testIsBanksystemAdminAfterRevoke);
        // Task #39 — ALLOW_ALL_ITEMS blacklist-only mode
        addTest("allow_all_items_off_preserves_current_behavior",
                this::testAllowAllItemsOffPreservesCurrentBehavior);
        addTest("allow_all_items_on_permits_any_non_blacklisted_item",
                this::testAllowAllItemsOnPermitsAnyNonBlacklistedItem);
        addTest("allow_all_items_on_still_refuses_blacklisted",
                this::testAllowAllItemsOnStillRefusesBlacklisted);
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
            manager.addUser(new User(TEST_USER_A, TEST_USER_A_NAME, false));
        }
        if (!manager.userExists(TEST_USER_B)) {
            manager.addUser(new User(TEST_USER_B, TEST_USER_B_NAME, false));
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
            manager.addUser(new User(tempUUID, tempName, false));
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

    // ========================= Item Filter Correctness =========================

    /**
     * Issue #24: getNotRemovableItems() is supposed to return items from
     * INITIAL_NOT_REMOVABLE_ITEMS but currently reads from INITIAL_BLACKLIST_ITEMS
     * instead. The not-removable list should be a subset (typically just the money item),
     * while the blacklist is much larger.
     *
     * We verify the returned list size matches the expected NOT_REMOVABLE list, not
     * the BLACKLIST. If the blacklist is larger than the not-removable list, the bug
     * is detectable by comparing sizes.
     */
    private TestResult testGetNotRemovableItemsReturnsCorrectList() {
        if (manager == null) {
            return fail("ServerBankManager is null -- cannot run on slave server");
        }

        List<ItemID> notRemovable = manager.getNotRemovableItems();
        List<ItemID> blacklisted = manager.getBlacklistedItems();

        // The NOT_REMOVABLE list should contain fewer items than the BLACKLIST
        // (INITIAL_NOT_REMOVABLE_ITEMS has 1 item: money;
        //  INITIAL_BLACKLIST_ITEMS has 13+ items)
        // If getNotRemovableItems() incorrectly reads from BLACKLIST,
        // its size will equal the blacklist size.
        if (notRemovable.size() == blacklisted.size() && blacklisted.size() > 1) {
            return TestResult.fail("",
                    "getNotRemovableItems returns same list as getBlacklistedItems -- " +
                    "likely reading from INITIAL_BLACKLIST_ITEMS instead of INITIAL_NOT_REMOVABLE_ITEMS (Issue #24)",
                    "size <= " + 1 + " (not-removable items)",
                    "size = " + notRemovable.size() + " (matches blacklist size)");
        }

        if (notRemovable.isEmpty()) {
            return fail("getNotRemovableItems returned an empty list -- expected at least the money item");
        }

        return pass("getNotRemovableItems returns the correct NOT_REMOVABLE list (size="
                + notRemovable.size() + "), distinct from blacklist (size=" + blacklisted.size() + ")");
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
}
