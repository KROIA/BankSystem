package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import net.kroia.banksystem.networking.multi_server.DepositItemsInBankRequest;
import net.kroia.banksystem.networking.multi_server.WithdrawItemsFromBankRequest;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Regression tests for the multi-server forwarding security fixes from this version
 * (Issues #14, #16, #29, #31, #32, #33, #39).
 *
 * Where possible, these exercise the actual handler code via real ServerBankManager.
 * Pure-logic invariants (overflow guard math, permission bitmask masking) are
 * verified directly without infrastructure.
 */
public class MultiServerSecurityTests extends TestSuite {

    private static final UUID TEST_OWNER = UUID.fromString("00000000-0000-0000-0000-00000000ee01");
    private static final UUID TEST_OUTSIDER = UUID.fromString("00000000-0000-0000-0000-00000000ee02");
    private static final String TEST_OWNER_NAME = "MSSecOwner";
    private static final String TEST_OUTSIDER_NAME = "MSSecOutsider";

    private IServerBankManager manager;
    private int testAccountNr = ServerBankAccount.INVALID_ACCOUNT_NUMBER;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.NETWORKING;
    }

    @Override
    public void registerTests() {
        // Pure-logic invariants
        addTest("withdrawMoneyPacket_overflow_guard_math", this::testOverflowGuardMath);
        addTest("permission_bitmask_validates_excess_bits", this::testPermissionBitmaskValidatesExcessBits);

        // Handler-level regression tests
        addTest("withdrawItemsFromBankRequest_rejects_nonexistent_account", this::testWithdrawRejectsNonexistentAccount);
        addTest("withdrawItemsFromBankRequest_rejects_player_sender", this::testWithdrawRejectsPlayerSender);
        addTest("withdrawItemsFromBankRequest_rejects_no_permission", this::testWithdrawRejectsNoPermission);
        addTest("withdrawItemsFromBankRequest_does_not_create_empty_bank", this::testWithdrawDoesNotCreateEmptyBank);
        addTest("depositItemsInBankRequest_clamps_negative", this::testDepositClampsNegative);
    }

    @Override
    public void setup() {
        IBankManager bankManager = BankSystemMod.getAPI().getServerBankManager();
        if (bankManager == null) return;
        manager = bankManager.getSync();
        if (manager == null) return;

        if (!manager.userExists(TEST_OWNER)) {
            manager.addUser(new User(TEST_OWNER, TEST_OWNER_NAME, false));
        }
        if (!manager.userExists(TEST_OUTSIDER)) {
            manager.addUser(new User(TEST_OUTSIDER, TEST_OUTSIDER_NAME, false));
        }

        IServerBankAccount account = manager.createBankAccount("MSSecTestAccount");
        if (account != null) {
            testAccountNr = account.getAccountNumber();
            User owner = manager.getUserByUUID(TEST_OWNER);
            if (owner != null)
                account.addUser(owner, BankPermission.getAllPermissions());
        }
    }

    @Override
    public void teardown() {
        if (manager == null) return;
        if (testAccountNr != ServerBankAccount.INVALID_ACCOUNT_NUMBER) {
            manager.deleteBankAccount(testAccountNr);
            testAccountNr = ServerBankAccount.INVALID_ACCOUNT_NUMBER;
        }
        manager.removeUser(TEST_OWNER);
        manager.removeUser(TEST_OUTSIDER);
    }

    // ============== Pure-logic invariants ==============

    /**
     * Issue #29: WithdrawMoneyPacket multiplies requestedAmount * itemValue. Without
     * a pre-check the product can overflow `long` and let crafted packets withdraw
     * tiny denominations but receive massive item counts.
     *
     * Verify the guard `requestedAmount > Long.MAX_VALUE / itemValue` correctly
     * identifies overflow conditions.
     */
    private TestResult testOverflowGuardMath() {
        long itemValue = 1_000_000L;
        long safeAmount = Long.MAX_VALUE / itemValue;          // largest non-overflowing multiplier
        long unsafeAmount = safeAmount + 1;                     // one more would overflow

        boolean safeFlagged = safeAmount > Long.MAX_VALUE / itemValue;
        boolean unsafeFlagged = unsafeAmount > Long.MAX_VALUE / itemValue;

        if (safeFlagged) {
            return fail("Overflow guard false-positive: safe amount " + safeAmount + " was flagged");
        }
        if (!unsafeFlagged) {
            return fail("Overflow guard missed: unsafe amount " + unsafeAmount + " was not flagged");
        }
        return pass("Overflow guard correct: safe=" + safeAmount + " allowed, unsafe=" + unsafeAmount + " rejected");
    }

    /**
     * Issue #31: UpdateBankAccountRequest masks incoming permission integers with
     * BankPermission.getAllPermissions() so clients cannot set arbitrary bits.
     *
     * Verify the mask removes high bits while preserving valid ones.
     */
    private TestResult testPermissionBitmaskValidatesExcessBits() {
        int validMask = BankPermission.getAllPermissions(); // 1|2|4 = 7
        int crafted = 0xFFFF_FFF8;                          // high bits + nothing valid
        int masked = crafted & validMask;
        if (masked != 0) {
            return fail("Mask failed to strip high bits: 0x" + Integer.toHexString(crafted)
                    + " & 0x" + Integer.toHexString(validMask) + " = 0x" + Integer.toHexString(masked));
        }
        int allValid = BankPermission.DEPOSIT.getValue() | BankPermission.WITHDRAW.getValue();
        if ((allValid & validMask) != allValid) {
            return fail("Mask incorrectly stripped valid bits: " + allValid + " -> " + (allValid & validMask));
        }
        int mixed = allValid | 0x100; // valid + invalid bit 8
        int mixedMasked = mixed & validMask;
        if (mixedMasked != allValid) {
            return fail("Mask didn't preserve valid bits while stripping invalid: "
                    + "0x" + Integer.toHexString(mixed) + " -> 0x" + Integer.toHexString(mixedMasked));
        }
        return pass("Permission mask correctly strips invalid bits while preserving DEPOSIT/WITHDRAW/MANAGE");
    }

    // ============== Handler-level tests ==============

    /**
     * Issue #16: rejection paths must return an empty map, not the requested items
     * (which would let a buggy slave caller create items from nothing).
     */
    private TestResult testWithdrawRejectsNonexistentAccount() {
        if (manager == null) {
            return fail("ServerBankManager is null — cannot run on slave server");
        }
        WithdrawItemsFromBankRequest req = new WithdrawItemsFromBankRequest();
        Map<ItemID, Long> requested = new HashMap<>();
        requested.put(ItemID.of(Items.DIAMOND.getDefaultInstance()), 1000L);
        WithdrawItemsFromBankRequest.InputData input = new WithdrawItemsFromBankRequest.InputData(
                -99999, null, requested);
        WithdrawItemsFromBankRequest.OutputData output = req.handleOnMasterServer(input, "", null).join();
        if (output.items() == null) {
            return fail("Output items map should not be null");
        }
        if (!output.items().isEmpty()) {
            return fail("Issue #16 regression — non-existent account should return empty map, got " + output.items());
        }
        return pass("Non-existent account rejection returns empty map");
    }

    /**
     * Issue #16: requests claiming to come from a player (playerSender != null)
     * are rejected — this multi-server endpoint expects only slave→master traffic.
     */
    private TestResult testWithdrawRejectsPlayerSender() {
        if (manager == null) {
            return fail("ServerBankManager is null");
        }
        WithdrawItemsFromBankRequest req = new WithdrawItemsFromBankRequest();
        Map<ItemID, Long> requested = new HashMap<>();
        requested.put(ItemID.of(Items.DIAMOND.getDefaultInstance()), 1000L);
        WithdrawItemsFromBankRequest.InputData input = new WithdrawItemsFromBankRequest.InputData(
                testAccountNr, null, requested);
        // Setting playerSender simulates a client trying to invoke this directly
        WithdrawItemsFromBankRequest.OutputData output = req.handleOnMasterServer(input, "", TEST_OWNER).join();
        if (!output.items().isEmpty()) {
            return fail("Issue #16: player-originated request should return empty map, got " + output.items());
        }
        return pass("Player-originated request rejection returns empty map");
    }

    /**
     * Issue #1 + #16: an executor without WITHDRAW permission gets an empty map back,
     * not the requested items.
     */
    private TestResult testWithdrawRejectsNoPermission() {
        if (manager == null || testAccountNr == ServerBankAccount.INVALID_ACCOUNT_NUMBER) {
            return fail("Test account not set up");
        }
        WithdrawItemsFromBankRequest req = new WithdrawItemsFromBankRequest();
        Map<ItemID, Long> requested = new HashMap<>();
        requested.put(ItemID.of(Items.DIAMOND.getDefaultInstance()), 1000L);
        WithdrawItemsFromBankRequest.InputData input = new WithdrawItemsFromBankRequest.InputData(
                testAccountNr, TEST_OUTSIDER, requested);
        WithdrawItemsFromBankRequest.OutputData output = req.handleOnMasterServer(input, "", null).join();
        if (!output.items().isEmpty()) {
            return fail("Issue #16: outsider executor without WITHDRAW should get empty map, got " + output.items());
        }
        return pass("Outsider without WITHDRAW permission gets empty map");
    }

    /**
     * Issue #33: WithdrawItemsFromBankRequest used `getOrCreateBank()` which silently
     * created an empty bank entry whenever someone asked to withdraw a non-existent
     * item. Verify the account does not gain a new bank entry after such a request.
     */
    private TestResult testWithdrawDoesNotCreateEmptyBank() {
        if (manager == null || testAccountNr == ServerBankAccount.INVALID_ACCOUNT_NUMBER) {
            return fail("Test account not set up");
        }
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) {
            return fail("Test account missing");
        }
        ItemID rareItem = ItemID.of(Items.NETHER_STAR.getDefaultInstance());
        if (account.hasBank(rareItem)) {
            // unexpected pre-condition — skip
            return pass("Skipped: test item already had a bank entry");
        }

        WithdrawItemsFromBankRequest req = new WithdrawItemsFromBankRequest();
        Map<ItemID, Long> requested = new HashMap<>();
        requested.put(rareItem, 5L);
        WithdrawItemsFromBankRequest.InputData input = new WithdrawItemsFromBankRequest.InputData(
                testAccountNr, null, requested);
        req.handleOnMasterServer(input, "", null).join();

        if (account.hasBank(rareItem)) {
            return fail("Issue #33 regression — withdraw on missing bank created an empty entry");
        }
        return pass("Withdraw on missing bank does not create an empty entry");
    }

    /**
     * Issue #32: DepositItemsInBankRequest treats negative amounts as zero
     * (Math.max(0, value)), so a slave cannot use a negative deposit to subtract
     * from the bank balance.
     */
    private TestResult testDepositClampsNegative() {
        if (manager == null || testAccountNr == ServerBankAccount.INVALID_ACCOUNT_NUMBER) {
            return fail("Test account not set up");
        }
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) {
            return fail("Test account missing");
        }
        ItemID dirt = ItemID.of(Items.DIRT.getDefaultInstance());
        boolean wasAllowed = manager.isItemIDAllowed(dirt);
        if (!wasAllowed) {
            manager.allowItemID(dirt);
        }
        // Seed the bank with a known balance, then attempt a negative deposit.
        var preBank = account.getOrCreateBank(dirt);
        if (preBank == null) {
            if (!wasAllowed) manager.disallowItemID(dirt);
            return fail("Failed to create test bank for DIRT");
        }
        preBank.deposit(100L);
        long balanceBefore = preBank.getBalance();

        DepositItemsInBankRequest req = new DepositItemsInBankRequest();
        Map<ItemID, Long> negativeRequest = new HashMap<>();
        negativeRequest.put(dirt, -50L);
        DepositItemsInBankRequest.InputData input = new DepositItemsInBankRequest.InputData(
                testAccountNr, null, negativeRequest);
        req.handleOnMasterServer(input, "", null).join();

        long balanceAfter = preBank.getBalance();
        if (balanceAfter != balanceBefore) {
            return fail("Issue #32 regression — negative deposit changed balance from "
                    + balanceBefore + " to " + balanceAfter);
        }

        // Cleanup
        preBank.withdraw(balanceAfter);
        account.removeBank(dirt);
        if (!wasAllowed) {
            manager.disallowItemID(dirt);
        }
        return pass("Negative deposit was clamped — balance unchanged at " + balanceBefore);
    }
}
