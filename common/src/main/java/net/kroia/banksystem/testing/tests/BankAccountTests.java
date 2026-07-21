package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;

import java.util.ConcurrentModificationException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Tests for {@link ServerBankAccount} covering user management,
 * async correctness, account properties, and listener management.
 */
public class BankAccountTests extends TestSuite {

    private static final UUID TEST_USER_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TEST_USER_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String TEST_USER_A_NAME = "TestUserA";
    private static final String TEST_USER_B_NAME = "TestUserB";

    private IServerBankManager manager;
    private int testAccountNr = ServerBankAccount.INVALID_ACCOUNT_NUMBER;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.BANK_ACCOUNT;
    }

    @Override
    public void registerTests() {
        // User Management
        addTest("addUser_and_hasUser", this::testAddUserAndHasUser);
        addTest("hasUser_nonexistent_returns_false", this::testHasUserNonexistentReturnsFalse);
        addTest("removeUser_removes", this::testRemoveUserRemoves);
        addTest("getPermission_returns_correct", this::testGetPermissionReturnsCorrect);
        addTest("setPermission_updates", this::testSetPermissionUpdates);

        // Async Bug Verification
        addTest("hasUserAsync_checks_specific_user", this::testHasUserAsyncChecksSpecificUser);
        addTest("hasBankAsync_checks_specific_bank", this::testHasBankAsyncChecksSpecificBank);

        // R2: Type-safe Permissions wrapper
        addTest("hasPermission_enum_overload_matches_int", this::testHasPermissionEnumOverloadMatchesInt);

        // Account Properties
        addTest("setAccountName_valid", this::testSetAccountNameValid);
        addTest("setAccountName_null_becomes_empty", this::testSetAccountNameNullBecomesEmpty);
        addTest("setAccountName_same_value_no_change", this::testSetAccountNameSameValueNoChange);

        // Listener Management
        addTest("unsubscribe_doesnt_throw", this::testUnsubscribeDoesntThrow);

        // Manage-invariant (orphan-prevention) helper — pure, no manager needed
        addTest("manageInvariant_promotes_when_no_manage", this::testManageInvariantPromotesWhenNoManage);
        addTest("manageInvariant_promotes_deterministic_pick", this::testManageInvariantPromotesDeterministicPick);
        addTest("manageInvariant_empty_refuses_orphan", this::testManageInvariantEmptyRefusesOrphan);
        addTest("manageInvariant_ok_when_manage_present", this::testManageInvariantOkWhenManagePresent);
        addTest("manageInvariant_personal_owner_always_ok", this::testManageInvariantPersonalOwnerAlwaysOk);
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

        // Register test users if they do not already exist
        if (!manager.userExists(TEST_USER_A)) {
            manager.addUser(new User(TEST_USER_A, TEST_USER_A_NAME, false));
        }
        if (!manager.userExists(TEST_USER_B)) {
            manager.addUser(new User(TEST_USER_B, TEST_USER_B_NAME, false));
        }

        // Create a non-personal bank account for testing
        IServerBankAccount account = manager.createBankAccount("BankAccountTestAccount");
        if (account != null) {
            testAccountNr = account.getAccountNumber();
        }
    }

    @Override
    public void teardown() {
        if (manager == null) {
            return;
        }

        // Clean up the test bank account
        if (testAccountNr != ServerBankAccount.INVALID_ACCOUNT_NUMBER) {
            manager.deleteBankAccount(testAccountNr);
            testAccountNr = ServerBankAccount.INVALID_ACCOUNT_NUMBER;
        }

        // Remove test users
        manager.removeUser(TEST_USER_A);
        manager.removeUser(TEST_USER_B);
    }

    // ========================= User Management Tests =========================

    private TestResult testAddUserAndHasUser() {
        if (manager == null) {
            return fail("ServerBankManager is null -- cannot run on slave server");
        }
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) {
            return fail("Test bank account was not created");
        }

        User userA = manager.getUserByUUID(TEST_USER_A);
        if (userA == null) {
            return fail("Test user A was not registered");
        }

        account.addUser(userA, BankPermission.DEPOSIT.getValue());
        boolean hasUser = account.hasUser(TEST_USER_A);
        return assertTrue("hasUser should return true after addUser", hasUser);
    }

    private TestResult testHasUserNonexistentReturnsFalse() {
        if (manager == null) {
            return fail("ServerBankManager is null -- cannot run on slave server");
        }
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) {
            return fail("Test bank account was not created");
        }

        UUID unknownUUID = UUID.fromString("00000000-0000-0000-0000-0000000000FF");
        boolean hasUser = account.hasUser(unknownUUID);
        return assertFalse("hasUser for unknown UUID should return false", hasUser);
    }

    private TestResult testRemoveUserRemoves() {
        if (manager == null) {
            return fail("ServerBankManager is null -- cannot run on slave server");
        }
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) {
            return fail("Test bank account was not created");
        }

        User userB = manager.getUserByUUID(TEST_USER_B);
        if (userB == null) {
            return fail("Test user B was not registered");
        }

        account.addUser(userB, BankPermission.DEPOSIT.getValue());
        if (!account.hasUser(TEST_USER_B)) {
            return fail("User B should exist after addUser");
        }

        account.removeUser(TEST_USER_B);
        boolean hasUserAfterRemove = account.hasUser(TEST_USER_B);
        return assertFalse("hasUser should return false after removeUser", hasUserAfterRemove);
    }

    private TestResult testGetPermissionReturnsCorrect() {
        if (manager == null) {
            return fail("ServerBankManager is null -- cannot run on slave server");
        }
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) {
            return fail("Test bank account was not created");
        }

        User userA = manager.getUserByUUID(TEST_USER_A);
        if (userA == null) {
            return fail("Test user A was not registered");
        }

        int expectedPermission = BankPermission.DEPOSIT.getValue() | BankPermission.WITHDRAW.getValue();
        account.addUser(userA, expectedPermission);

        int actualPermission = account.getPermission(TEST_USER_A);
        return assertEquals("getPermission should return the set permission value",
                expectedPermission, actualPermission);
    }

    /**
     * Task #R2: hasPermission(UUID, BankPermission) must agree with the int variant.
     * Enum overload exists so callers don't have to remember .getValue() vs .ordinal().
     */
    private TestResult testHasPermissionEnumOverloadMatchesInt() {
        if (manager == null) {
            return fail("ServerBankManager is null -- cannot run on slave server");
        }
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) {
            return fail("Test bank account was not created");
        }
        User userA = manager.getUserByUUID(TEST_USER_A);
        if (userA == null) {
            return fail("Test user A not registered");
        }
        // Grant DEPOSIT only
        account.addUser(userA, BankPermission.DEPOSIT.getValue());

        for (BankPermission p : BankPermission.values()) {
            boolean viaInt = account.hasPermission(TEST_USER_A, p.getValue());
            boolean viaEnum = account.hasPermission(TEST_USER_A, p);
            if (viaInt != viaEnum) {
                return fail("Enum overload disagreed with int form for "
                        + p.name() + ": int=" + viaInt + " enum=" + viaEnum);
            }
        }
        return pass("hasPermission(UUID, BankPermission) agrees with int form for all BankPermission values");
    }

    private TestResult testSetPermissionUpdates() {
        if (manager == null) {
            return fail("ServerBankManager is null -- cannot run on slave server");
        }
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) {
            return fail("Test bank account was not created");
        }

        User userA = manager.getUserByUUID(TEST_USER_A);
        if (userA == null) {
            return fail("Test user A was not registered");
        }

        // Add user with DEPOSIT only
        account.addUser(userA, BankPermission.DEPOSIT.getValue());

        // Update to all permissions
        int newPermission = BankPermission.getAllPermissions();
        account.setPermission(TEST_USER_A, newPermission);

        int actualPermission = account.getPermission(TEST_USER_A);
        return assertEquals("setPermission should update the user's permission",
                newPermission, actualPermission);
    }

    // ========================= Async Bug Verification =========================

    /**
     * Issue #9: hasUserAsync delegates to hasAnyUser() instead of hasUser(uuid).
     * When only userA is added, hasUserAsync(userB) should return false,
     * but the buggy implementation returns true because hasAnyUser() is true.
     */
    private TestResult testHasUserAsyncChecksSpecificUser() {
        if (manager == null) {
            return fail("ServerBankManager is null -- cannot run on slave server");
        }
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) {
            return fail("Test bank account was not created");
        }

        User userA = manager.getUserByUUID(TEST_USER_A);
        if (userA == null) {
            return fail("Test user A was not registered");
        }

        // Ensure clean state: remove both users first
        account.removeUser(TEST_USER_A);
        account.removeUser(TEST_USER_B);

        // Add only userA
        account.addUser(userA, BankPermission.DEPOSIT.getValue());

        // hasUserAsync(userB) should return false because userB was not added
        CompletableFuture<Boolean> future = account.hasUserAsync(TEST_USER_B);
        boolean result = future.join();
        return assertFalse(
                "hasUserAsync(userB) should return false when only userA exists (Issue #9)",
                result);
    }

    /**
     * Issue #10: hasBankAsync delegates to hasAnyBank() instead of hasBank(itemID).
     * When only itemA bank exists, hasBankAsync(itemB) should return false,
     * but the buggy implementation returns true because hasAnyBank() is true.
     */
    private TestResult testHasBankAsyncChecksSpecificBank() {
        if (manager == null) {
            return fail("ServerBankManager is null -- cannot run on slave server");
        }
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) {
            return fail("Test bank account was not created");
        }

        // Remove all banks first so we have a clean slate
        account.removeAllBanks();

        // Get a known allowed ItemID from the manager
        java.util.List<ItemID> allowedItems = manager.getAllowedItems();
        if (allowedItems.size() < 2) {
            return pass("SKIPPED: requires at least 2 allowed items to test hasBankAsync specificity");
        }

        ItemID itemA = allowedItems.get(0);
        ItemID itemB = allowedItems.get(1);

        // Create a bank for itemA only
        account.createBank(itemA, 0);

        // hasBankAsync(itemB) should return false
        CompletableFuture<Boolean> future = account.hasBankAsync(itemB);
        boolean result = future.join();
        return assertFalse(
                "hasBankAsync(itemB) should return false when only itemA bank exists (Issue #10)",
                result);
    }

    // ========================= Account Properties Tests =========================

    private TestResult testSetAccountNameValid() {
        if (manager == null) {
            return fail("ServerBankManager is null -- cannot run on slave server");
        }
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) {
            return fail("Test bank account was not created");
        }

        String newName = "MyTestBankAccount";
        account.setAccountName(newName);
        String storedName = account.getAccountName();
        return assertEquals("setAccountName should store the given name",
                newName, storedName);
    }

    /**
     * Issue #44: setAccountName(null) should result in an empty string,
     * not a NullPointerException.
     */
    private TestResult testSetAccountNameNullBecomesEmpty() {
        if (manager == null) {
            return fail("ServerBankManager is null -- cannot run on slave server");
        }
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) {
            return fail("Test bank account was not created");
        }

        try {
            account.setAccountName(null);
        } catch (Exception e) {
            return fail("setAccountName(null) threw an exception: " + e.getClass().getSimpleName());
        }

        String storedName = account.getAccountName();
        return assertEquals("setAccountName(null) should result in empty string (Issue #44)",
                "", storedName);
    }

    private TestResult testSetAccountNameSameValueNoChange() {
        if (manager == null) {
            return fail("ServerBankManager is null -- cannot run on slave server");
        }
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) {
            return fail("Test bank account was not created");
        }

        String name = "SameNameTest";
        account.setAccountName(name);
        // Clear the change flag after the first set
        account.clearChangeFlag();

        // Set the same name again
        account.setAccountName(name);

        boolean hasChanges = account.hasChanges();
        return assertFalse(
                "Setting the same account name should not mark hasChanges as true",
                hasChanges);
    }

    // ========================= Listener Management Tests =========================

    /**
     * Issue #41: unsubscribeBankChanges iterates over changeListeners and calls
     * remove() on the list during iteration, which can throw ConcurrentModificationException.
     * This test verifies the operation completes without throwing.
     */
    private TestResult testUnsubscribeDoesntThrow() {
        if (manager == null) {
            return fail("ServerBankManager is null -- cannot run on slave server");
        }
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) {
            return fail("Test bank account was not created");
        }

        Consumer<BankAccountData> listener = data -> {};
        account.subscribeBankChanges(listener);

        try {
            account.unsubscribeBankChanges(listener);
        } catch (ConcurrentModificationException e) {
            return fail("unsubscribeBankChanges threw ConcurrentModificationException (Issue #41)");
        } catch (Exception e) {
            return fail("unsubscribeBankChanges threw unexpected exception: "
                    + e.getClass().getSimpleName() + " - " + e.getMessage());
        }

        return pass("unsubscribeBankChanges completed without throwing");
    }

    // ================= Manage-Invariant Helper Tests =================
    // These exercise the pure static ServerBankAccount.enforceManageInvariant helper
    // directly (no manager / server state required). It guarantees a non-personal
    // account never loses its last MANAGE holder.

    private static User makeUser(UUID uuid, String name) {
        return new User(uuid, name, false);
    }

    /**
     * Non-empty user set with no MANAGE holder → PROMOTED, and the map now has a MANAGE holder.
     */
    private TestResult testManageInvariantPromotesWhenNoManage() {
        java.util.Map<User, Integer> proposed = new java.util.HashMap<>();
        proposed.put(makeUser(TEST_USER_A, TEST_USER_A_NAME), BankPermission.DEPOSIT.getValue());
        proposed.put(makeUser(TEST_USER_B, TEST_USER_B_NAME), BankPermission.WITHDRAW.getValue());

        ServerBankAccount.ManageInvariantOutcome outcome =
                ServerBankAccount.enforceManageInvariant(proposed, false);
        if (outcome != ServerBankAccount.ManageInvariantOutcome.PROMOTED) {
            return fail("Expected PROMOTED, got " + outcome);
        }
        long manageHolders = proposed.values().stream()
                .filter(mask -> BankPermission.hasPermission(mask, BankPermission.MANAGE.getValue()))
                .count();
        return assertTrue("At least one user must hold MANAGE after promotion (got "
                + manageHolders + ")", manageHolders >= 1);
    }

    /**
     * Deterministic pick: the user with the most set permission bits is promoted.
     * User A has 2 bits (DEPOSIT|WITHDRAW), User B has 1 bit (DEPOSIT) → A must be chosen.
     */
    private TestResult testManageInvariantPromotesDeterministicPick() {
        User userA = makeUser(TEST_USER_A, TEST_USER_A_NAME);
        User userB = makeUser(TEST_USER_B, TEST_USER_B_NAME);
        java.util.Map<User, Integer> proposed = new java.util.HashMap<>();
        proposed.put(userA, BankPermission.DEPOSIT.getValue() | BankPermission.WITHDRAW.getValue());
        proposed.put(userB, BankPermission.DEPOSIT.getValue());

        ServerBankAccount.ManageInvariantOutcome outcome =
                ServerBankAccount.enforceManageInvariant(proposed, false);
        if (outcome != ServerBankAccount.ManageInvariantOutcome.PROMOTED) {
            return fail("Expected PROMOTED, got " + outcome);
        }
        boolean aHasManage = BankPermission.hasPermission(proposed.get(userA), BankPermission.MANAGE.getValue());
        boolean bHasManage = BankPermission.hasPermission(proposed.get(userB), BankPermission.MANAGE.getValue());
        if (!aHasManage) {
            return fail("User A (most permission bits) should have been promoted to MANAGE");
        }
        return assertFalse("User B should NOT have been promoted (A wins on bit count)", bHasManage);
    }

    /**
     * Empty set → REFUSED_ORPHAN, map left unchanged (still empty).
     */
    private TestResult testManageInvariantEmptyRefusesOrphan() {
        java.util.Map<User, Integer> proposed = new java.util.HashMap<>();
        ServerBankAccount.ManageInvariantOutcome outcome =
                ServerBankAccount.enforceManageInvariant(proposed, false);
        if (outcome != ServerBankAccount.ManageInvariantOutcome.REFUSED_ORPHAN) {
            return fail("Expected REFUSED_ORPHAN, got " + outcome);
        }
        return assertTrue("Map should remain empty after refusal", proposed.isEmpty());
    }

    /**
     * Set already containing a MANAGE holder → OK, map unchanged.
     */
    private TestResult testManageInvariantOkWhenManagePresent() {
        User userA = makeUser(TEST_USER_A, TEST_USER_A_NAME);
        java.util.Map<User, Integer> proposed = new java.util.HashMap<>();
        int originalMask = BankPermission.DEPOSIT.getValue() | BankPermission.MANAGE.getValue();
        proposed.put(userA, originalMask);

        ServerBankAccount.ManageInvariantOutcome outcome =
                ServerBankAccount.enforceManageInvariant(proposed, false);
        if (outcome != ServerBankAccount.ManageInvariantOutcome.OK) {
            return fail("Expected OK, got " + outcome);
        }
        return assertEquals("Mask must be unchanged when MANAGE already present",
                originalMask, (int) proposed.get(userA));
    }

    /**
     * Personal account (hasOwner==true) with a no-MANAGE set → OK, map unchanged.
     * The owner implicitly holds MANAGE, so no promotion is needed.
     */
    private TestResult testManageInvariantPersonalOwnerAlwaysOk() {
        User userA = makeUser(TEST_USER_A, TEST_USER_A_NAME);
        java.util.Map<User, Integer> proposed = new java.util.HashMap<>();
        int originalMask = BankPermission.DEPOSIT.getValue();
        proposed.put(userA, originalMask);

        ServerBankAccount.ManageInvariantOutcome outcome =
                ServerBankAccount.enforceManageInvariant(proposed, true);
        if (outcome != ServerBankAccount.ManageInvariantOutcome.OK) {
            return fail("Expected OK for personal account, got " + outcome);
        }
        return assertEquals("Mask must be unchanged for a personal account",
                originalMask, (int) proposed.get(userA));
    }
}
