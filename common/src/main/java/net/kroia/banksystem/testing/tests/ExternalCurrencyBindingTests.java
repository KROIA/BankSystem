package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.banking.ledger.SuspicionChecks;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.ISyncServerBank;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.api.currency.ExternalAccount;
import net.kroia.banksystem.api.currency.ExternalAccountRef;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.bank.ServerBank;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import net.kroia.banksystem.banking.bankmanager.ServerBankManager;
import net.kroia.banksystem.banking.binding.BankAccountBindings;
import net.kroia.banksystem.banking.binding.BindingKey;
import net.kroia.banksystem.banking.binding.BindingRow;
import net.kroia.banksystem.networking.currency.BindExternalAccountRequest;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.testing.LiveStateGuard;
import net.kroia.banksystem.testing.StubCurrencyProvider;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * In-game tests for the Task #33 external-currency binding stack (v2.0.5).
 * <p>
 * These are the seven cases specified in the Task #33 deliverables — they
 * cover the full round-trip flow, the locked-balance protocol, drift-clamp,
 * overflow guard, provider-unavailable degraded state, personal↔shared
 * mismatch rejection, and cascade cleanup on account/slot deletion. Every
 * test relies on {@link StubCurrencyProvider} as the fake external mod so
 * no real third-party dependency is required.
 * <p>
 * <b>Server type:</b> MASTER_ONLY — bindings live in a master-authoritative
 * savedata subsystem, and the bind service ({@code ServerBankManager}) is
 * only reachable from a running master {@link ServerBankManager}. Every test
 * short-circuits with a pass-with-note if invoked on a slave.
 * <p>
 * <b>Run-order independence:</b> {@link #setup()} refreshes the stub, drops
 * every binding row belonging to the shared test account, and refreshes the
 * account's slot inventory. A prior case leaving stale accounts / bindings
 * cannot corrupt the current one.
 *
 * @since 2.0.5
 */
public class ExternalCurrencyBindingTests extends TestSuite {

    private static final UUID TEST_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000ec1");
    private static final UUID TEST_MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000ec2");
    private static final String TEST_OWNER_NAME = "ExtCurrOwner";
    private static final String TEST_MEMBER_NAME = "ExtCurrMember";

    // The shared test account (non-personal). One instance recycled across cases;
    // setup() zeroes and re-populates its slots each time.
    private static final String TEST_ACCOUNT_NAME = "ExtCurrTestAccount";

    /** Provider key for the primary bound account used by most tests. */
    private static final String STUB_KEY_A = "stub_account_a";
    /** Provider key for the secondary bound account used by the multi-slot case. */
    private static final String STUB_KEY_B = "stub_account_b";

    private ServerBankManager manager;
    /** Snapshot of the live allow-list / blacklist, restored in teardown. */
    private LiveStateGuard stateGuard;
    private int testAccountNr = ServerBankAccount.INVALID_ACCOUNT_NUMBER;
    private ItemID slotItemA;
    private ItemID slotItemB;

    @Override
    public TestCategory getCategory() {
        // MASTER_ONLY — bindings are master-authoritative; the slave has no
        // BankAccountBindings singleton to consult.
        return BankSystemTestCategories.BANK_MANAGER;
    }

    @Override
    public void registerTests() {
        addTest("round_trip_bind_deposit_withdraw", this::testRoundTrip);
        addTest("locked_balance_transactional_protocol", this::testLockedBalanceProtocol);
        addTest("overflow_guard_deposit_returns_failed_overflow", this::testOverflowGuard);
        addTest("provider_unavailable_degrades_reads_and_writes", this::testProviderUnavailable);
        addTest("shared_personal_ref_mismatch_rejected", this::testSharedPersonalMismatch);
        addTest("unbind_user_choice_keep_on_banksystem_or_provider", this::testUnbindUserChoice);
        // Hardening pass (5 additional cases; see doc-comment at each method):
        addTest("default_slot_seeding_money_plus_provider_currency", this::testDefaultSlotSeeding);
        addTest("bind_preserves_locked_balance_into_binding_row", this::testBindPreservesLocked);
        addTest("bind_overflow_leaves_local_state_untouched", this::testBindOverflowAtomicity);
        addTest("withdraw_refused_when_external_says_no", this::testWithdrawRefusedByExternal);
        addTest("membership_sync_propagates_to_external_account", this::testMembershipSyncPropagates);
        addTest("unbind_refused_while_unavailable_with_locked", this::testUnbindRefusedWhileUnavailableWithLocked);
        addTest("user_deletion_payout_counts_only_local_part", this::testPayoutCountsOnlyLocalPart); // Task #136
        addTest("bind_request_refuses_foreign_external_account", this::testBindRequestRefusesForeignRef);
        // Task #146: start-up check of bindings made before binds were checked
        addTest("startup_check_suspends_foreign_binding_and_flags", this::testForeignBindingSuspended);
        addTest("suspended_binding_refuses_value_paths", this::testSuspendedBindingRefusesValuePaths);
        addTest("startup_check_keeps_valid_binding", this::testValidBindingKept);
        addTest("startup_check_skips_unavailable_provider", this::testUnavailableProviderSkipped);
        addTest("admin_restore_lifts_suspension_and_survives_recheck", this::testAdminRestore);
        addTest("rebind_of_bound_slot_refused_without_moving_funds", this::testRebindOfBoundSlotRefused); // Task #147
        // Task #148: listing answers, and deletion of suspended bindings
        addTest("startup_check_suspends_on_empty_listing", () -> listingCase(false));
        addTest("startup_check_skips_throwing_listing", () -> listingCase(true));
        addTest("account_deletion_drops_suspended_binding_unpaid", this::testAccountDeletionDropsSuspendedUnpaid);
        addTest("user_deletion_pays_nothing_for_suspended_slot", this::testUserDeletionPaysNothingForSuspended);
        // Cascade cleanup must run LAST: it deletes the shared testAccountNr, which would
        // strand any subsequent test that references it.
        addTest("cascade_cleanup_on_account_and_bank_removal", this::testCascadeCleanup);
    }

    @Override
    public void setup() {
        IBankManager bankManager = BankSystemMod.getAPI().getServerBankManager();
        if (bankManager == null) return;
        if (!(bankManager.getSync() instanceof ServerBankManager serverManager)) return;
        manager = serverManager;

        // Capture BEFORE the allowItemID calls below so teardown can hand the live filter back.
        stateGuard = LiveStateGuard.capture(manager);

        // Refresh the stub — clears accounts, resets available=true, and re-registers.
        StubCurrencyProvider.reset();

        if (!manager.userExists(TEST_OWNER)) {
            manager.addUser(new User(TEST_OWNER, TEST_OWNER_NAME));
        }
        if (!manager.userExists(TEST_MEMBER)) {
            manager.addUser(new User(TEST_MEMBER, TEST_MEMBER_NAME));
        }

        // Register / allow the two test item slots. Using distinct non-money items keeps the
        // tests independent from the auto-created money bank on personal accounts.
        slotItemA = ItemIDManager.registerItemStackServerSide_direct(Items.STONE.getDefaultInstance());
        slotItemB = ItemIDManager.registerItemStackServerSide_direct(Items.IRON_INGOT.getDefaultInstance());
        manager.allowItemID(slotItemA);
        manager.allowItemID(slotItemB);

        // Recycle the shared non-personal test account. Purge any surviving bindings
        // + banks first so state cannot leak across cases.
        IServerBankAccount existing = manager.getBankAccountByName(TEST_ACCOUNT_NAME);
        if (existing != null) {
            manager.deleteBankAccount(existing.getAccountNumber());
        }
        IServerBankAccount account = manager.createBankAccount(TEST_ACCOUNT_NAME);
        if (account != null) {
            testAccountNr = account.getAccountNumber();
            // Fresh slots (both start at 0 balance).
            account.createBank(slotItemA, 0);
            account.createBank(slotItemB, 0);
        }
    }

    /**
     * Per-test state reset. The {@link TestSuite} framework calls
     * {@link #setup()} / {@link #teardown()} ONCE for the whole suite, so
     * bindings and stub accounts accumulated by a previous case would leak
     * into the next one otherwise. Each test calls this at the top to
     * guarantee a fresh baseline: empty binding table for the test account,
     * empty stub accounts (re-registered), local balances zeroed on both
     * test slots.
     */
    private void perTestReset() {
        if (manager == null) return;
        // Drop any binding rows from a previous test — must precede setBalance(0)
        // below so that the setBalance call routes through the unbound (local) path
        // rather than delegating to whatever stub balance still exists.
        BankAccountBindings bindings = BankAccountBindings.get();
        if (bindings != null && testAccountNr != ServerBankAccount.INVALID_ACCOUNT_NUMBER) {
            bindings.removeAllForAccount(testAccountNr);
        }
        // Wipe the stub — clears accounts, restores available=true, re-registers.
        StubCurrencyProvider.reset();
        // Zero the local balances on both test slots. writeLocalFieldsForUnbind_internal
        // isn't reached during teardown's removeAllForAccount (row is dropped without
        // going through the service method), so a previous test's non-zero external
        // balance is not materialized locally; but a prior test may still have deposited
        // through the unbound path in some edge case. Being explicit keeps this test-safe.
        IServerBankAccount account = testAccountNr != ServerBankAccount.INVALID_ACCOUNT_NUMBER
                ? manager.getBankAccount(testAccountNr) : null;
        if (account != null) {
            ISyncServerBank bankA = account.getBank(slotItemA);
            ISyncServerBank bankB = account.getBank(slotItemB);
            if (bankA != null) bankA.setBalance(0L);
            if (bankB != null) bankB.setBalance(0L);
        }
    }

    @Override
    public void teardown() {
        try {
            if (manager == null) return;
            // Drop any bindings still pointing at our accounts.
            BankAccountBindings bindings = BankAccountBindings.get();
            if (bindings != null && testAccountNr != ServerBankAccount.INVALID_ACCOUNT_NUMBER) {
                bindings.removeAllForAccount(testAccountNr);
            }
            if (testAccountNr != ServerBankAccount.INVALID_ACCOUNT_NUMBER) {
                manager.deleteBankAccount(testAccountNr);
                testAccountNr = ServerBankAccount.INVALID_ACCOUNT_NUMBER;
            }
            // Drop the stub from the live registry so it does NOT leak into the
            // production binding UI once the test suite finishes.
            StubCurrencyProvider.teardown();
            manager.removeUser(TEST_OWNER);
            manager.removeUser(TEST_MEMBER);
        } finally {
            // ALWAYS last: the cleanup above needs the registry the suite actually used.
            LiveStateGuard.restore(stateGuard);
            stateGuard = null;
        }
    }

    // -----------------------------------------------------------------------
    // 1. Round-trip: bind → external deposits show up → BankSystem writes propagate
    //    (Task #33 v2.0.5: also tests auto-transfer of local balance on bind)
    // -----------------------------------------------------------------------
    private TestResult testRoundTrip() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) return fail("Test account missing");

        // Pre-populate local balance (200) to test auto-transfer on bind.
        ISyncServerBank bank = account.getBank(slotItemA);
        if (bank == null) return fail("Bank slot A missing before bind");
        bank.setBalance(200L);

        StubCurrencyProvider.create(STUB_KEY_A, /*shared=*/true, /*initialBalance=*/0L);
        ExternalAccountRef ref = new ExternalAccountRef(
                StubCurrencyProvider.PROVIDER_ID, STUB_KEY_A, "Round-Trip", true);
        BankStatus bindStatus = manager.bindExternalAccount(testAccountNr, slotItemA, ref);
        if (bindStatus != BankStatus.SUCCESS) {
            return fail("bindExternalAccount returned " + bindStatus + " (expected SUCCESS)");
        }

        // Auto-transfer: local 200 should now be on the external side, local zeroed.
        StubCurrencyProvider.StubAccount stub = StubCurrencyProvider.getAccount(STUB_KEY_A);
        if (stub == null) return fail("Stub account was not created");
        if (stub.getBalance() != 200L) {
            return fail("After bind with local=200, stub balance = " + stub.getBalance()
                    + " (expected 200 from auto-transfer)");
        }
        if (bank.getBalance() != 200L) {
            // Bank reads stub external balance after bind, should see 200.
            return fail("After bind with auto-transfer, BankSystem getBalance() = "
                    + bank.getBalance() + " (expected 200)");
        }

        // External-side back-door deposit: BankSystem must read it through.
        stub.setBalance(100L);
        if (bank.getBalance() != 100L) {
            return fail("After external setBalance(100), BankSystem getBalance() = "
                    + bank.getBalance() + " (expected 100)");
        }

        // BankSystem-side deposit routes to the stub.
        BankStatus dep = bank.deposit(50L);
        if (dep != BankStatus.SUCCESS) return fail("deposit(50) returned " + dep);
        if (stub.getBalance() != 150L) {
            return fail("After BankSystem deposit(50), stub balance = "
                    + stub.getBalance() + " (expected 150)");
        }

        // BankSystem-side withdraw routes to the stub.
        BankStatus wd = bank.withdraw(30L);
        if (wd != BankStatus.SUCCESS) return fail("withdraw(30) returned " + wd);
        if (bank.getBalance() != 120L) {
            return fail("After BankSystem withdraw(30), BankSystem getBalance() = "
                    + bank.getBalance() + " (expected 120)");
        }
        if (stub.getBalance() != 120L) {
            return fail("After BankSystem withdraw(30), stub balance = "
                    + stub.getBalance() + " (expected 120)");
        }
        return pass("Round-trip: auto-transfer on bind + external deposit + BankSystem deposit + withdraw all consistent");
    }

    // -----------------------------------------------------------------------
    // 2. Locked-balance transactional protocol (Task #33 v2.0.5+):
    //    - lockAmount physically withdraws from external (whole-native portion);
    //      row.lockedBalance holds the reserved amount.
    //    - withdrawLocked is a local decrement — external was already reduced at
    //      lock time, so the funds are "consumed" without touching external.
    //    - unlockAmount deposits back to external + zeros out row.lockedBalance.
    // -----------------------------------------------------------------------
    private TestResult testLockedBalanceProtocol() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) return fail("Test account missing");

        StubCurrencyProvider.create(STUB_KEY_A, true, 100L);
        ExternalAccountRef ref = new ExternalAccountRef(
                StubCurrencyProvider.PROVIDER_ID, STUB_KEY_A, "Locked-Protocol", true);
        BankStatus bindStatus = manager.bindExternalAccount(testAccountNr, slotItemA, ref);
        if (bindStatus != BankStatus.SUCCESS) return fail("bind returned " + bindStatus);

        ISyncServerBank bank = account.getBank(slotItemA);
        if (bank == null) return fail("Bank slot A missing after bind");
        StubCurrencyProvider.StubAccount stub = StubCurrencyProvider.getAccount(STUB_KEY_A);
        if (stub == null) return fail("Stub account was not created");

        // lockAmount(40) — external is physically decreased by 40 (transactional lock).
        BankStatus lockStatus = bank.lockAmount(40L);
        if (lockStatus != BankStatus.SUCCESS) return fail("lockAmount(40) returned " + lockStatus);
        if (bank.getBalance() != 60L)
            return fail("Post-lock free = " + bank.getBalance() + " (expected 60 — reads external)");
        if (bank.getLockedBalance() != 40L)
            return fail("Post-lock locked = " + bank.getLockedBalance() + " (expected 40 — from row)");
        if (bank.getTotalBalance() != 100L)
            return fail("Post-lock total = " + bank.getTotalBalance() + " (expected 100 — external + locked)");
        if (stub.getBalance() != 60L)
            return fail("Post-lock stub balance = " + stub.getBalance()
                    + " (expected 60 — lockAmount physically withdrew 40 under the transactional protocol)");

        // withdrawLocked(30) — external stays put (already reduced at lock time); locked -30.
        BankStatus wlStatus = bank.withdrawLocked(30L);
        if (wlStatus != BankStatus.SUCCESS) return fail("withdrawLocked(30) returned " + wlStatus);
        if (bank.getBalance() != 60L)
            return fail("Post-withdrawLocked free = " + bank.getBalance() + " (expected 60)");
        if (bank.getLockedBalance() != 10L)
            return fail("Post-withdrawLocked locked = " + bank.getLockedBalance() + " (expected 10)");
        if (bank.getTotalBalance() != 70L)
            return fail("Post-withdrawLocked total = " + bank.getTotalBalance() + " (expected 70)");
        if (stub.getBalance() != 60L)
            return fail("Post-withdrawLocked stub balance = " + stub.getBalance()
                    + " (expected 60 — external unchanged, funds were already withdrawn at lock time)");

        // unlockAmount(10) — external gains 10 back (returned from the reserved pool).
        BankStatus unlockStatus = bank.unlockAmount(10L);
        if (unlockStatus != BankStatus.SUCCESS) return fail("unlockAmount(10) returned " + unlockStatus);
        if (bank.getBalance() != 70L)
            return fail("Post-unlock free = " + bank.getBalance() + " (expected 70)");
        if (bank.getLockedBalance() != 0L)
            return fail("Post-unlock locked = " + bank.getLockedBalance() + " (expected 0)");
        if (stub.getBalance() != 70L)
            return fail("Post-unlock stub balance = " + stub.getBalance()
                    + " (expected 70 — unlock deposits back to external)");
        return pass("Transactional-lock protocol: lock physically withdraws external, withdrawLocked "
                + "is local-only, unlock deposits back");
    }

    // -----------------------------------------------------------------------
    // 4. Overflow guard: deposit that would exceed the stub's ceiling fails
    //    with FAILED_OVERFLOW and leaves state untouched on both sides.
    // -----------------------------------------------------------------------
    private TestResult testOverflowGuard() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) return fail("Test account missing");

        StubCurrencyProvider.StubAccount stub = StubCurrencyProvider.create(STUB_KEY_A, true, 950L);
        stub.setOverflowCeiling(1000L);

        ExternalAccountRef ref = new ExternalAccountRef(
                StubCurrencyProvider.PROVIDER_ID, STUB_KEY_A, "Overflow", true);
        BankStatus bindStatus = manager.bindExternalAccount(testAccountNr, slotItemA, ref);
        if (bindStatus != BankStatus.SUCCESS) return fail("bind returned " + bindStatus);

        ISyncServerBank bank = account.getBank(slotItemA);
        if (bank == null) return fail("Bank slot A missing after bind");

        // Depositing 100 into a stub at 950 with ceiling 1000 must fail — no state change.
        BankStatus dep = bank.deposit(100L);
        if (dep != BankStatus.FAILED_OVERFLOW) {
            return fail("Overflow guard: deposit(100) into stub@950/ceiling=1000 returned "
                    + dep + " (expected FAILED_OVERFLOW)");
        }
        if (stub.getBalance() != 950L) {
            return fail("Overflow guard: stub balance changed to " + stub.getBalance()
                    + " (expected 950 — overflow must not mutate)");
        }
        if (bank.getBalance() != 950L) {
            return fail("Overflow guard: BankSystem free = " + bank.getBalance()
                    + " (expected 950 — no locked amount held, no change from stub)");
        }
        return pass("Overflow guard: deposit refused with FAILED_OVERFLOW, both sides unchanged");
    }

    // -----------------------------------------------------------------------
    // 5. Provider-unavailable degraded state: reads return 0/locked, writes
    //    fail with FAILED_EXTERNAL_UNAVAILABLE, external state untouched.
    // -----------------------------------------------------------------------
    private TestResult testProviderUnavailable() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) return fail("Test account missing");

        StubCurrencyProvider.StubAccount stub = StubCurrencyProvider.create(STUB_KEY_A, true, 500L);
        ExternalAccountRef ref = new ExternalAccountRef(
                StubCurrencyProvider.PROVIDER_ID, STUB_KEY_A, "Unavailable", true);
        BankStatus bindStatus = manager.bindExternalAccount(testAccountNr, slotItemA, ref);
        if (bindStatus != BankStatus.SUCCESS) return fail("bind returned " + bindStatus);

        ISyncServerBank bank = account.getBank(slotItemA);
        if (bank == null) return fail("Bank slot A missing after bind");

        // Simulate mod-removal mid-session.
        StubCurrencyProvider.getInstance().setAvailable(false);

        if (bank.getBalance() != 0L) {
            return fail("Degraded getBalance() = " + bank.getBalance() + " (expected 0)");
        }
        if (bank.getLockedBalance() != 0L) {
            return fail("Degraded getLockedBalance() = " + bank.getLockedBalance()
                    + " (expected 0 — locked tracked in binding row was 0)");
        }
        if (bank.getTotalBalance() != 0L) {
            return fail("Degraded getTotalBalance() = " + bank.getTotalBalance()
                    + " (expected 0 — locked was 0)");
        }
        BankStatus dep = bank.deposit(100L);
        if (dep != BankStatus.FAILED_EXTERNAL_UNAVAILABLE) {
            return fail("Degraded deposit(100) returned " + dep
                    + " (expected FAILED_EXTERNAL_UNAVAILABLE)");
        }
        BankStatus wd = bank.withdraw(100L);
        if (wd != BankStatus.FAILED_EXTERNAL_UNAVAILABLE) {
            return fail("Degraded withdraw(100) returned " + wd
                    + " (expected FAILED_EXTERNAL_UNAVAILABLE)");
        }
        if (stub.getBalance() != 500L) {
            return fail("Degraded state modified stub balance to " + stub.getBalance()
                    + " (expected 500 — external state must not change during degraded ops)");
        }

        // Bring the provider back — reads should resume returning the live external balance.
        StubCurrencyProvider.getInstance().setAvailable(true);
        if (bank.getBalance() != 500L) {
            return fail("Re-enabled provider: getBalance() = " + bank.getBalance()
                    + " (expected 500)");
        }
        return pass("Provider unavailable: reads degraded, writes rejected, external state untouched, "
                + "re-enable restores reads");
    }

    // -----------------------------------------------------------------------
    // 6. Personal/shared mismatch rejection: FAILED_WRONG_INSTANCE_TYPE + no row.
    // -----------------------------------------------------------------------
    private TestResult testSharedPersonalMismatch() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        BankAccountBindings bindings = BankAccountBindings.get();
        if (bindings == null) return fail("BankAccountBindings backend is not available");

        // (a) Personal account slot ↔ shared stub ref → refused.
        IServerBankAccount personal = manager.getOrCreatePersonalBankAccount(TEST_OWNER);
        if (personal == null) return fail("Personal account creation failed");
        int personalAccountNr = personal.getAccountNumber();
        // Create a fresh non-money slot on the personal account (money bank has a starting
        // balance in some setups; STONE starts at 0 so bind preconditions pass).
        personal.createBank(slotItemA, 0);

        StubCurrencyProvider.create(STUB_KEY_A, /*shared=*/true, 0L);
        ExternalAccountRef sharedRef = new ExternalAccountRef(
                StubCurrencyProvider.PROVIDER_ID, STUB_KEY_A, "Shared-Ref", true);
        BankStatus personalToSharedBind =
                manager.bindExternalAccount(personalAccountNr, slotItemA, sharedRef);

        boolean personalRowCreated = bindings.getBinding(personalAccountNr, slotItemA) != null;
        try {
            if (personalToSharedBind == BankStatus.SUCCESS) {
                return fail("Personal→shared bind should be refused, returned SUCCESS");
            }
            if (personalToSharedBind != BankStatus.FAILED_WRONG_INSTANCE_TYPE) {
                return fail("Personal→shared bind returned " + personalToSharedBind
                        + " (expected FAILED_WRONG_INSTANCE_TYPE)");
            }
            if (personalRowCreated) {
                return fail("Personal→shared bind refusal left a binding row behind");
            }
        } finally {
            // Personal accounts cannot be deleted via deleteBankAccount; drop the user
            // instead to purge it, then re-add for later tests. Actually removeUser also
            // deletes personal accounts, but we still need the user around — so just drop
            // the extra slot we added and leave the personal account alone.
            personal.removeBank(slotItemA);
        }

        // (b) Shared (non-personal) account slot ↔ non-shared stub ref → refused.
        StubCurrencyProvider.create(STUB_KEY_B, /*shared=*/false, 0L);
        ExternalAccountRef personalRef = new ExternalAccountRef(
                StubCurrencyProvider.PROVIDER_ID, STUB_KEY_B, "Personal-Ref", false);
        BankStatus sharedToPersonalBind =
                manager.bindExternalAccount(testAccountNr, slotItemB, personalRef);
        if (sharedToPersonalBind == BankStatus.SUCCESS) {
            return fail("Shared→personal bind should be refused, returned SUCCESS");
        }
        if (sharedToPersonalBind != BankStatus.FAILED_WRONG_INSTANCE_TYPE) {
            return fail("Shared→personal bind returned " + sharedToPersonalBind
                    + " (expected FAILED_WRONG_INSTANCE_TYPE)");
        }
        if (bindings.getBinding(testAccountNr, slotItemB) != null) {
            return fail("Shared→personal bind refusal left a binding row behind");
        }
        return pass("Shared/personal mismatch refused symmetrically with FAILED_WRONG_INSTANCE_TYPE, "
                + "no rows created");
    }

    // -----------------------------------------------------------------------
    // Bind request: a non-admin with MANAGE may only bind an external account that the
    // provider lists as bindable for them. Binding someone else's would let them drain it.
    // -----------------------------------------------------------------------
    private TestResult testBindRequestRefusesForeignRef() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        User owner = manager.getUserByUUID(TEST_OWNER);
        if (account == null || owner == null) return fail("Test account or owner missing");
        account.addUser(owner, BankPermission.getAllPermissions());
        try {
            StubCurrencyProvider.create(STUB_KEY_A, true, 500L).setOwner(TEST_MEMBER); // the victim's
            StubCurrencyProvider.create(STUB_KEY_B, true, 0L).setOwner(TEST_OWNER);
            BindExternalAccountRequest req = new BindExternalAccountRequest();

            BankStatus foreign = req.handleOnMasterServer(new BindExternalAccountRequest.InputData(testAccountNr, slotItemA,
                    new ExternalAccountRef(StubCurrencyProvider.PROVIDER_ID, STUB_KEY_A, "victim", true)), "", TEST_OWNER).join().status();
            if (foreign == BankStatus.SUCCESS) return fail("bound another player's external account");
            BankAccountBindings bindings = BankAccountBindings.get();
            if (bindings != null && bindings.hasBinding(testAccountNr, slotItemA))
                return fail("binding row written for another player's external account");

            BankStatus own = req.handleOnMasterServer(new BindExternalAccountRequest.InputData(testAccountNr, slotItemB,
                    new ExternalAccountRef(StubCurrencyProvider.PROVIDER_ID, STUB_KEY_B, "mine", true)), "", TEST_OWNER).join().status();
            if (own != BankStatus.SUCCESS) return fail("binding the sender's own external account returned " + own);
            return pass("foreign external account refused (" + foreign + "), own account bound");
        } finally {
            account.removeUser(TEST_OWNER);
        }
    }

    // -----------------------------------------------------------------------
    // Task #146: start-up check. Slot A of the shared test account (MANAGE: TEST_OWNER) is bound
    // straight through the manager (as before #137) to an external account only TEST_MEMBER may
    // bind. TEST_OWNER's own external account B keeps their bindable list non-empty.
    // -----------------------------------------------------------------------
    private static final long FOREIGN_BALANCE = 500L;
    private static final String FOREIGN_FLAG_REF = StubCurrencyProvider.PROVIDER_ID + ":" + STUB_KEY_A;

    /** @return the foreign binding row of slot A, or null if the bind failed */
    private BindingRow bindForeign(IServerBankAccount account) {
        account.addUser(manager.getUserByUUID(TEST_OWNER), BankPermission.getAllPermissions());
        StubCurrencyProvider.create(STUB_KEY_A, true, FOREIGN_BALANCE).setOwner(TEST_MEMBER);
        StubCurrencyProvider.create(STUB_KEY_B, true, 0L).setOwner(TEST_OWNER);
        BankStatus status = manager.bindExternalAccount(testAccountNr, slotItemA,
                new ExternalAccountRef(StubCurrencyProvider.PROVIDER_ID, STUB_KEY_A, "foreign", true));
        BankAccountBindings bindings = BankAccountBindings.get();
        return status == BankStatus.SUCCESS && bindings != null ? bindings.getBinding(testAccountNr, slotItemA) : null;
    }

    private TestResult testForeignBindingSuspended() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        var flags = net.kroia.banksystem.BankSystemModBackend.getInstances_forTesting().SUSPICION_FLAG_MANAGER;
        if (account == null || flags == null) return fail("Test account or flag manager missing");
        try {
            BindingRow row = bindForeign(account);
            if (row == null) return fail("bind of the foreign external account failed");
            long since = System.currentTimeMillis();
            if (!manager.suspendIfForeign(row) || !row.suspended()) return fail("foreign binding not suspended");
            if (!BindingRow.fromNbt(row.toNbt()).suspended()) return fail("suspension not persisted in the row NBT");
            for (var f : flags.getRecent(testAccountNr, since, 0).get(10, java.util.concurrent.TimeUnit.SECONDS))
                if (f.flagType() == net.kroia.banksystem.banking.ledger.SuspicionChecks.EXTERNAL_BINDING_SUSPENDED
                        && FOREIGN_FLAG_REF.equals(f.ref()))
                    return f.details() != null && f.details().contains(TEST_OWNER.toString())
                            ? pass("suspended + persisted, flag 7 ref " + f.ref())
                            : fail("flag details miss the manager: " + f.details());
            return fail("no EXTERNAL_BINDING_SUSPENDED flag with ref " + FOREIGN_FLAG_REF);
        } catch (Exception e) {
            return fail("flag query threw " + e);
        } finally {
            account.removeUser(TEST_OWNER);
        }
    }

    private TestResult testSuspendedBindingRefusesValuePaths() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) return fail("Test account missing");
        try {
            BindingRow row = bindForeign(account);
            if (row == null || !manager.suspendIfForeign(row)) return fail("setup: foreign binding not suspended");
            ISyncServerBank bank = account.getBank(slotItemA);
            if (bank == null) return fail("Bank slot A missing");
            long totalBefore = bank.getTotalBalance();
            Map<String, BankStatus> results = new java.util.LinkedHashMap<>();
            results.put("deposit", bank.deposit(100L));
            results.put("withdraw", bank.withdraw(100L));
            results.put("lock", bank.lockAmount(100L));
            results.put("unbind-keep", manager.unbindExternalAccount(testAccountNr, slotItemA, true));
            results.put("unbind", manager.unbindExternalAccount(testAccountNr, slotItemA, false));
            results.put("rebind", manager.bindExternalAccount(testAccountNr, slotItemA,
                    new ExternalAccountRef(StubCurrencyProvider.PROVIDER_ID, STUB_KEY_B, "own", true)));
            for (Map.Entry<String, BankStatus> e : results.entrySet())
                if (e.getValue() == BankStatus.SUCCESS) return fail(e.getKey() + " went through a suspended binding");
            long ext = StubCurrencyProvider.getAccount(STUB_KEY_A).getBalance();
            if (ext != FOREIGN_BALANCE) return fail("external balance moved: " + ext);
            if (bank.getTotalBalance() != totalBefore) return fail("BankSystem balance moved: " + bank.getTotalBalance());
            if (!BankAccountBindings.isSuspended(testAccountNr, slotItemA)) return fail("suspended binding was dropped");
            return pass("refused " + results + "; external " + ext + " unchanged");
        } finally {
            account.removeUser(TEST_OWNER);
        }
    }

    private TestResult testValidBindingKept() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        BankAccountBindings bindings = BankAccountBindings.get();
        if (account == null || bindings == null) return fail("Test account or bindings missing");
        try {
            bindForeign(account); // creates TEST_OWNER's account B
            BankStatus status = manager.bindExternalAccount(testAccountNr, slotItemB,
                    new ExternalAccountRef(StubCurrencyProvider.PROVIDER_ID, STUB_KEY_B, "own", true));
            BindingRow own = bindings.getBinding(testAccountNr, slotItemB);
            if (status != BankStatus.SUCCESS || own == null) return fail("bind of the manager's own account returned " + status);
            if (manager.suspendIfForeign(own) || own.suspended()) return fail("valid binding was suspended");
            return pass("binding to the manager's own external account kept");
        } finally {
            account.removeUser(TEST_OWNER);
        }
    }

    private TestResult testUnavailableProviderSkipped() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) return fail("Test account missing");
        try {
            BindingRow row = bindForeign(account);
            if (row == null) return fail("bind of the foreign external account failed");
            StubCurrencyProvider.getInstance().setAvailable(false);
            if (manager.suspendIfForeign(row) || row.suspended()) return fail("binding of an unavailable provider was suspended");
            return pass("unavailable provider skipped");
        } finally {
            StubCurrencyProvider.getInstance().setAvailable(true);
            account.removeUser(TEST_OWNER);
        }
    }

    private TestResult testAdminRestore() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) return fail("Test account missing");
        try {
            BindingRow row = bindForeign(account);
            if (row == null || !manager.suspendIfForeign(row)) return fail("setup: foreign binding not suspended");
            int restored = manager.restoreSuspendedBindings(testAccountNr, slotItemA.getName(),
                    net.kroia.banksystem.api.ledger.LedgerActor.console());
            if (restored != 1 || row.suspended() || !row.approved()) return fail("restore returned " + restored
                    + ", suspended=" + row.suspended() + ", approved=" + row.approved());
            BindingRow reloaded = BindingRow.fromNbt(row.toNbt());
            if (reloaded == null || reloaded.suspended() || !reloaded.approved()) return fail("approval not persisted in the row NBT");
            if (manager.suspendIfForeign(row)) return fail("re-check suspended an admin-restored binding");
            ISyncServerBank bank = account.getBank(slotItemA);
            BankStatus dep = bank == null ? null : bank.deposit(100L);
            if (dep != BankStatus.SUCCESS) return fail("deposit after restore returned " + dep);
            return pass("restored, approved + persisted, re-check keeps it, deposit works again");
        } finally {
            account.removeUser(TEST_OWNER);
        }
    }

    // -----------------------------------------------------------------------
    // Task #147: binding an already-bound slot must be refused. Before, the old external balance
    // was read as the slot's free balance and deposited into the new account (duplication).
    // -----------------------------------------------------------------------
    private TestResult testRebindOfBoundSlotRefused() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        BankAccountBindings bindings = BankAccountBindings.get();
        if (account == null || bindings == null) return fail("Test account or bindings missing");
        StubCurrencyProvider.create(STUB_KEY_A, true, 300L);
        StubCurrencyProvider.create(STUB_KEY_B, true, 0L);
        BankStatus bind = manager.bindExternalAccount(testAccountNr, slotItemA,
                new ExternalAccountRef(StubCurrencyProvider.PROVIDER_ID, STUB_KEY_A, "X", true));
        ISyncServerBank bank = account.getBank(slotItemA);
        if (bind != BankStatus.SUCCESS || bank == null) return fail("setup: bind to X returned " + bind);
        long totalBefore = bank.getTotalBalance();
        BankStatus rebind = manager.bindExternalAccount(testAccountNr, slotItemA,
                new ExternalAccountRef(StubCurrencyProvider.PROVIDER_ID, STUB_KEY_B, "Y", true));
        if (rebind == BankStatus.SUCCESS) return fail("rebind of a bound slot went through");
        long x = StubCurrencyProvider.getAccount(STUB_KEY_A).getBalance();
        long y = StubCurrencyProvider.getAccount(STUB_KEY_B).getBalance();
        if (x != 300L || y != 0L) return fail("external balances moved: X=" + x + ", Y=" + y);
        if (bank.getTotalBalance() != totalBefore) return fail("BankSystem total moved: " + totalBefore + " -> " + bank.getTotalBalance());
        BindingRow row = bindings.getBinding(testAccountNr, slotItemA);
        if (row == null || !STUB_KEY_A.equals(row.ref().accountKey())) return fail("binding row replaced: " + row);
        return pass("rebind refused (" + rebind + "); X=300, Y=0, total " + totalBefore + " unchanged, still bound to X");
    }

    // -----------------------------------------------------------------------
    // Task #148: an empty listing is an answer (suspend), a throwing one is not (skip). Slot A is bound to an
    // external account only TEST_MEMBER may bind; the manager TEST_OWNER has no external account at all.
    // -----------------------------------------------------------------------
    private TestResult listingCase(boolean listingThrows) {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) return fail("Test account missing");
        account.addUser(manager.getUserByUUID(TEST_OWNER), BankPermission.getAllPermissions());
        try {
            StubCurrencyProvider.create(STUB_KEY_A, true, FOREIGN_BALANCE).setOwner(TEST_MEMBER);
            BankStatus status = manager.bindExternalAccount(testAccountNr, slotItemA,
                    new ExternalAccountRef(StubCurrencyProvider.PROVIDER_ID, STUB_KEY_A, "foreign", true));
            BankAccountBindings bindings = BankAccountBindings.get();
            BindingRow row = status == BankStatus.SUCCESS && bindings != null ? bindings.getBinding(testAccountNr, slotItemA) : null;
            if (row == null) return fail("bind returned " + status);
            StubCurrencyProvider.getInstance().listingFails = listingThrows;
            boolean suspended = manager.suspendIfForeign(row);
            if (suspended == listingThrows || row.suspended() == listingThrows)
                return fail((listingThrows ? "throwing" : "empty") + " listing: suspended=" + row.suspended());
            return pass(listingThrows ? "throwing listing skipped, binding kept" : "empty listing suspended the binding");
        } finally {
            StubCurrencyProvider.getInstance().listingFails = false;
            account.removeUser(TEST_OWNER);
        }
    }

    // -----------------------------------------------------------------------
    // Task #148: deleting an account or a user drops a suspended binding without paying out the locked + dust
    // BankSystem held for it (one BANK_CLOSED row + an EXTERNAL_BINDING_SUSPENDED flag instead) and leaves the
    // external account alone.
    // -----------------------------------------------------------------------
    private static final long LEFT_LOCKED = 40L, LEFT_DUST = 3L;

    /** Binds slot A of {@code nr} to the foreign stub account, sets locked + dust, suspends. @return null, or why not */
    private String bindSuspended(int nr, boolean shared) {
        StubCurrencyProvider.create(STUB_KEY_A, shared, FOREIGN_BALANCE).setOwner(TEST_MEMBER);
        BankStatus status = manager.bindExternalAccount(nr, slotItemA,
                new ExternalAccountRef(StubCurrencyProvider.PROVIDER_ID, STUB_KEY_A, "foreign", shared));
        BankAccountBindings bindings = BankAccountBindings.get();
        BindingRow row = status == BankStatus.SUCCESS && bindings != null ? bindings.getBinding(nr, slotItemA) : null;
        if (row == null) return "bind returned " + status;
        bindings.setLocked(nr, slotItemA, LEFT_LOCKED);
        bindings.setDust(nr, slotItemA, LEFT_DUST);
        return manager.suspendIfForeign(row) ? null : "binding not suspended";
    }

    /** @return null if slot A of {@code nr} was dropped unpaid since {@code since}, else what is wrong */
    private String droppedUnpaid(int nr, long since) throws Exception {
        BankAccountBindings bindings = BankAccountBindings.get();
        if (bindings != null && bindings.hasBinding(nr, slotItemA)) return "binding row left behind";
        long ext = StubCurrencyProvider.getAccount(STUB_KEY_A).getBalance();
        if (ext != FOREIGN_BALANCE) return "external balance moved: " + ext;
        long left = LEFT_LOCKED + LEFT_DUST, closed = 0;
        int rows = 0;
        for (var r : BankSystemModBackend.getTransactionLogManager().getByAccount(nr, 0).get(10, TimeUnit.SECONDS)) {
            if (r.itemId() != slotItemA.getShort() || r.time() < since) continue;
            if (r.type() == LedgerType.ACCOUNT_DELETE_PAYOUT || r.type() == LedgerType.ACCOUNT_DELETE_LOSS) return "paid out: " + r;
            if (r.type() == LedgerType.BANK_CLOSED) { closed += r.amount(); rows++; }
        }
        if (rows != 1 || closed != left) return rows + " BANK_CLOSED row(s) summing " + closed + ", expected one of " + left;
        for (var f : BankSystemModBackend.getInstances_forTesting().SUSPICION_FLAG_MANAGER.getRecent(nr, since, 0).get(10, TimeUnit.SECONDS))
            if (f.flagType() == SuspicionChecks.EXTERNAL_BINDING_SUSPENDED && FOREIGN_FLAG_REF.equals(f.ref()) && f.amount() == left)
                return null;
        return "no EXTERNAL_BINDING_SUSPENDED flag of " + left;
    }

    private TestResult testAccountDeletionDropsSuspendedUnpaid() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        IServerBankAccount fresh = manager.createBankAccount(TEST_ACCOUNT_NAME + "_suspended");
        if (fresh == null) return fail("account creation failed");
        int nr = fresh.getAccountNumber();
        try {
            fresh.createBank(slotItemA, 0);
            fresh.addUser(manager.getUserByUUID(TEST_OWNER), BankPermission.getAllPermissions());
            String setup = bindSuspended(nr, true);
            if (setup != null) return fail("setup: " + setup);
            long since = System.currentTimeMillis();
            if (!manager.deleteBankAccount(nr)) return fail("deleteBankAccount returned false");
            String why = droppedUnpaid(nr, since);
            return why == null ? pass("account deleted: binding dropped, one BANK_CLOSED + flag of locked + dust, external untouched")
                    : fail(why);
        } catch (Exception e) {
            return fail("ledger/flag query threw " + e);
        } finally {
            if (manager.getBankAccount(nr) != null) manager.deleteBankAccount(nr);
        }
    }

    private TestResult testUserDeletionPaysNothingForSuspended() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        UUID doomed = UUID.fromString("00000000-0000-0000-0000-00000000f148");
        if (manager.userExists(doomed)) manager.removeUser(doomed);
        manager.addUser(new User(doomed, "ExtCurrSuspendedUser"));
        try {
            ServerBankAccount personal = manager.createPersonalBankAccount(doomed);
            if (personal == null) return fail("personal account not created");
            int nr = personal.getAccountNumber();
            personal.getAllBanks().values().forEach(b -> b.setBalance(0L)); // nothing else to hand out
            personal.createBank(slotItemA, 0);
            String setup = bindSuspended(nr, false);
            if (setup != null) return fail("setup: " + setup);
            Map<ItemID, Long> payout = new java.util.HashMap<>();
            manager.collectAccountBalances(personal, payout);
            if (payout.containsKey(slotItemA)) return fail("suspended slot counted for the payout: " + payout);
            long since = System.currentTimeMillis();
            var result = manager.removeUserWithPayout(doomed);
            if (result == null) return fail("user not removed");
            result.get(10, TimeUnit.SECONDS);
            String why = droppedUnpaid(nr, since);
            return why == null ? pass("user deleted: suspended slot paid nothing, one BANK_CLOSED + flag, external untouched")
                    : fail(why);
        } catch (Exception e) {
            return fail("threw " + e);
        } finally {
            if (manager.userExists(doomed)) manager.removeUser(doomed);
        }
    }

    // -----------------------------------------------------------------------
    // 7. Cascade cleanup:
    //    (a) delete BankSystem account → all its binding rows drop
    //    (b) removeBank on a bound slot → only that row drops; unrelated bindings survive
    // -----------------------------------------------------------------------
    private TestResult testCascadeCleanup() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        BankAccountBindings bindings = BankAccountBindings.get();
        if (bindings == null) return fail("BankAccountBindings backend is not available");

        // Pre-condition: no rows for the shared test account yet.
        if (!bindings.listBindingsFor(testAccountNr).isEmpty()) {
            return fail("Cascade test pre-condition failed: shared test account already has "
                    + bindings.listBindingsFor(testAccountNr).size() + " rows");
        }

        // (a) Bind two slots on the shared test account, delete the account, expect zero rows.
        // Use two different stub accounts so both rows are meaningful.
        StubCurrencyProvider.create(STUB_KEY_A, true, 0L);
        StubCurrencyProvider.create(STUB_KEY_B, true, 0L);
        ExternalAccountRef refA = new ExternalAccountRef(
                StubCurrencyProvider.PROVIDER_ID, STUB_KEY_A, "Cascade-A", true);
        ExternalAccountRef refB = new ExternalAccountRef(
                StubCurrencyProvider.PROVIDER_ID, STUB_KEY_B, "Cascade-B", true);
        BankStatus bA = manager.bindExternalAccount(testAccountNr, slotItemA, refA);
        BankStatus bB = manager.bindExternalAccount(testAccountNr, slotItemB, refB);
        if (bA != BankStatus.SUCCESS) return fail("bind slot A returned " + bA);
        if (bB != BankStatus.SUCCESS) return fail("bind slot B returned " + bB);
        if (bindings.listBindingsFor(testAccountNr).size() != 2) {
            return fail("Expected 2 binding rows after two binds, got "
                    + bindings.listBindingsFor(testAccountNr).size());
        }

        // Delete the account → cascade must drop both rows.
        boolean deleted = manager.deleteBankAccount(testAccountNr);
        if (!deleted) return fail("deleteBankAccount returned false");
        if (!bindings.listBindingsFor(testAccountNr).isEmpty()) {
            return fail("Cascade delete left "
                    + bindings.listBindingsFor(testAccountNr).size()
                    + " row(s) behind for account " + testAccountNr);
        }
        // Also verify via the full snapshot filtered on our account id — belt & suspenders.
        int leftover = 0;
        for (Map.Entry<BindingKey, BindingRow> e : bindings.snapshot().entrySet()) {
            if (e.getKey().bankAccountId() == testAccountNr) leftover++;
        }
        if (leftover != 0) {
            return fail("Snapshot still contains " + leftover + " entries for the deleted account");
        }
        // The account is gone — mark the field invalid so teardown's delete is a no-op.
        testAccountNr = ServerBankAccount.INVALID_ACCOUNT_NUMBER;

        // (b) Fresh account, bind slot A + slot B on it, then removeBank(A) → A's row drops
        //     while B's row survives.
        IServerBankAccount fresh = manager.createBankAccount(TEST_ACCOUNT_NAME + "_cascade2");
        if (fresh == null) return fail("Second cascade account creation failed");
        int freshAccountNr = fresh.getAccountNumber();
        try {
            fresh.createBank(slotItemA, 0);
            fresh.createBank(slotItemB, 0);
            StubCurrencyProvider.create(STUB_KEY_A, true, 0L); // recreate (cascade could have wiped state)
            StubCurrencyProvider.create(STUB_KEY_B, true, 0L);
            BankStatus bA2 = manager.bindExternalAccount(freshAccountNr, slotItemA, refA);
            BankStatus bB2 = manager.bindExternalAccount(freshAccountNr, slotItemB, refB);
            if (bA2 != BankStatus.SUCCESS) return fail("re-bind slot A returned " + bA2);
            if (bB2 != BankStatus.SUCCESS) return fail("re-bind slot B returned " + bB2);
            if (bindings.listBindingsFor(freshAccountNr).size() != 2) {
                return fail("Expected 2 rows on fresh account before removeBank, got "
                        + bindings.listBindingsFor(freshAccountNr).size());
            }

            fresh.removeBank(slotItemA);
            if (bindings.getBinding(freshAccountNr, slotItemA) != null) {
                return fail("removeBank(A) left A's binding row behind");
            }
            if (bindings.getBinding(freshAccountNr, slotItemB) == null) {
                return fail("removeBank(A) also dropped B's binding row — expected untouched");
            }
        } finally {
            manager.deleteBankAccount(freshAccountNr);
        }
        return pass("Cascade cleanup: account delete drops all its rows; removeBank drops only its slot");
    }

    // -----------------------------------------------------------------------
    // 8. Unbind user choice: keepOnBankSystem vs keepOnProvider (Task #33 v2.0.5)
    // -----------------------------------------------------------------------
    private TestResult testUnbindUserChoice() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) return fail("Test account missing");
        BankAccountBindings bindings = BankAccountBindings.get();
        if (bindings == null) return fail("BankAccountBindings backend is not available");

        // (a) keepOnBankSystem=true: recover all funds locally (ext + dust + locked).
        StubCurrencyProvider.StubAccount stubA = StubCurrencyProvider.create(STUB_KEY_A, true, 0L);
        ExternalAccountRef refA = new ExternalAccountRef(
                StubCurrencyProvider.PROVIDER_ID, STUB_KEY_A, "UnbindA", true);
        BankStatus bindA = manager.bindExternalAccount(testAccountNr, slotItemA, refA);
        if (bindA != BankStatus.SUCCESS) return fail("bind A returned " + bindA);

        ISyncServerBank bankA = account.getBank(slotItemA);
        if (bankA == null) return fail("Bank slot A missing after bind");

        // Populate: deposit(100) then lockAmount(40). Under the transactional protocol,
        // lock physically withdraws 40 from external, so: external=60, row.locked=40, total=100.
        bankA.deposit(100L);
        bankA.lockAmount(40L);
        if (stubA.getBalance() != 60L) {
            return fail("Pre-unbind A: stub balance = " + stubA.getBalance()
                    + " (expected 60 — lockAmount(40) physically withdrew 40 under transactional protocol)");
        }
        if (bindings.getLocked(testAccountNr, slotItemA) != 40L) {
            return fail("Pre-unbind A: locked in binding row = "
                    + bindings.getLocked(testAccountNr, slotItemA) + " (expected 40)");
        }

        // Unbind with keepOnBankSystem=true → withdraw external (60), restore locked (40).
        // Local: free=60, locked=40, total=100 preserved.
        BankStatus unbindA = manager.unbindExternalAccount(testAccountNr, slotItemA, true);
        if (unbindA != BankStatus.SUCCESS) return fail("unbind A returned " + unbindA);
        if (bindings.getBinding(testAccountNr, slotItemA) != null) {
            return fail("unbind A left binding row behind");
        }
        if (bankA.getBalance() != 60L) {
            return fail("After unbind A (keepOnBankSystem=true), free = " + bankA.getBalance()
                    + " (expected 60 — external held only free portion under transactional protocol)");
        }
        if (bankA.getLockedBalance() != 40L) {
            return fail("After unbind A (keepOnBankSystem=true), locked = " + bankA.getLockedBalance()
                    + " (expected 40)");
        }
        // Stub should be 0 (withdrew everything remaining externally).
        if (stubA.getBalance() != 0L) {
            return fail("After unbind A (keepOnBankSystem=true), stub balance = "
                    + stubA.getBalance() + " (expected 0)");
        }

        // (b) keepOnBankSystem=false: deposit (dust + locked) back to external, accept fractional loss.
        // Bind slot B with external starting at 0.
        StubCurrencyProvider.StubAccount stubB = StubCurrencyProvider.create(STUB_KEY_B, true, 0L);
        ExternalAccountRef refB = new ExternalAccountRef(
                StubCurrencyProvider.PROVIDER_ID, STUB_KEY_B, "UnbindB", true);
        BankStatus bindB = manager.bindExternalAccount(testAccountNr, slotItemB, refB);
        if (bindB != BankStatus.SUCCESS) return fail("bind B returned " + bindB);

        ISyncServerBank bankB = account.getBank(slotItemB);
        if (bankB == null) return fail("Bank slot B missing after bind");

        // Populate: deposit(200) → external=200. lockAmount(50) withdraws 50 from external
        // under transactional protocol → external=150, row.locked=50, total=200.
        bankB.deposit(200L);
        bankB.lockAmount(50L);
        if (stubB.getBalance() != 150L) {
            return fail("Pre-unbind B: stub balance = " + stubB.getBalance()
                    + " (expected 150 — lockAmount(50) physically withdrew 50 under transactional protocol)");
        }
        if (bindings.getLocked(testAccountNr, slotItemB) != 50L) {
            return fail("Pre-unbind B: locked in binding row = "
                    + bindings.getLocked(testAccountNr, slotItemB) + " (expected 50)");
        }

        // Unbind with keepOnBankSystem=false → external UNTOUCHED (free stays there), locked
        // comes home as local locked, dust discarded. Depositing locked back to external
        // would orphan the pending orders that reference it.
        BankStatus unbindB = manager.unbindExternalAccount(testAccountNr, slotItemB, false);
        if (unbindB != BankStatus.SUCCESS) return fail("unbind B returned " + unbindB);
        if (bindings.getBinding(testAccountNr, slotItemB) != null) {
            return fail("unbind B left binding row behind");
        }
        // Local free = 0 (dust=0, discarded).
        if (bankB.getBalance() != 0L) {
            return fail("After unbind B (keepOnBankSystem=false), free = " + bankB.getBalance()
                    + " (expected 0)");
        }
        // Local locked = 50 (carried back home for pending orders).
        if (bankB.getLockedBalance() != 50L) {
            return fail("After unbind B (keepOnBankSystem=false), locked = " + bankB.getLockedBalance()
                    + " (expected 50 — carried back for pending orders)");
        }
        // External stays at 150 — the free portion never moves during keepOnProvider unbind.
        if (stubB.getBalance() != 150L) {
            return fail("After unbind B (keepOnBankSystem=false), stub balance = "
                    + stubB.getBalance() + " (expected 150 — external must not gain locked)");
        }

        return pass("Unbind user choice: keepOnBankSystem recovers everything locally, "
                + "keepOnProvider leaves free on external and returns locked to local");
    }

    // =======================================================================
    // Hardening pass — v2.0.5 late-stage additions covering the "hard-to-test"
    // correctness questions that would otherwise be gameplay-only checks:
    //   1. default-slot seeding (money + provider currency)
    //   2. bind carrying locked balance over into the binding row
    //   3. bind atomicity on FAILED_OVERFLOW
    //   4. withdraw refused by external
    //   5. membership sync propagation
    // =======================================================================

    /**
     * H1. Default-slot seeding. {@link ServerBankManager#addDefaultBankSlots} must
     * add the base {@code banksystem:money} slot plus one slot per available
     * external-currency provider that declares a base-currency item. This is the
     * shared code path exercised by both {@code /bank create} and personal-account
     * creation on player join.
     */
    private TestResult testDefaultSlotSeeding() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        // Configure the stub to declare emerald as its base currency. reset() in the
        // NEXT test's perTestReset will null this back out, so no cross-test leak.
        StubCurrencyProvider.getInstance().setBaseCurrencyItemId("minecraft:emerald");

        // Fresh account with NO slots — verifies the helper actually seeds them.
        String helperAccountName = TEST_ACCOUNT_NAME + "_seed";
        IServerBankAccount existing = manager.getBankAccountByName(helperAccountName);
        if (existing != null) manager.deleteBankAccount(existing.getAccountNumber());
        IServerBankAccount fresh = manager.createBankAccount(helperAccountName);
        if (fresh == null) return fail("createBankAccount returned null");
        int freshAccountNr = fresh.getAccountNumber();
        try {
            // Sanity: the account starts with no banks.
            if (fresh.getAccountData().bankData.size() != 0) {
                return fail("Fresh account has " + fresh.getAccountData().bankData.size()
                        + " banks pre-seed (expected 0)");
            }

            ServerBankManager.addDefaultBankSlots(fresh);

            ItemID moneyId = net.kroia.banksystem.minecraft.item.custom.money.MoneyItem.getItemID();
            ItemID emeraldId = ItemIDManager.registerItemStackServerSide_direct(
                    Items.EMERALD.getDefaultInstance());

            if (fresh.getBank(moneyId) == null) {
                return fail("addDefaultBankSlots did not seed the base money slot");
            }
            if (fresh.getBank(emeraldId) == null) {
                return fail("addDefaultBankSlots did not seed the stub provider's base-currency "
                        + "slot (emerald)");
            }
            if (!manager.isItemIDAllowed(emeraldId)) {
                return fail("addDefaultBankSlots did not allowlist the stub provider's "
                        + "base-currency item");
            }

            // Idempotence: calling it a second time is a no-op (createBank short-circuits
            // on existing keys, allowItemID is set-add). Balance stays 0.
            fresh.getBank(emeraldId).setBalance(42L);
            ServerBankManager.addDefaultBankSlots(fresh);
            if (fresh.getBank(emeraldId).getBalance() != 42L) {
                return fail("addDefaultBankSlots second call clobbered emerald balance "
                        + "(got " + fresh.getBank(emeraldId).getBalance() + ", expected 42)");
            }
        } finally {
            manager.deleteBankAccount(freshAccountNr);
        }
        return pass("Default-slot seeding: money + provider-declared item slot added, allowlist "
                + "updated, second call is idempotent");
    }

    /**
     * Task #119 (D4). Unbind while the provider is unavailable (mod missing or disabled in
     * Integrations settings) must be refused while the binding row holds locked funds: they
     * were already taken out of the external account, so zeroing them would destroy money.
     * Once the provider is back, the normal unbind brings them home.
     */
    private TestResult testUnbindRefusedWhileUnavailableWithLocked() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) return fail("Test account missing");
        BankAccountBindings bindings = BankAccountBindings.get();
        if (bindings == null) return fail("BankAccountBindings backend is not available");
        ISyncServerBank bank = account.getBank(slotItemA);
        if (bank == null) return fail("Bank slot A missing");

        bank.setBalance(80L);
        BankStatus lockStatus = bank.lockAmount(30L);
        if (lockStatus != BankStatus.SUCCESS) return fail("lockAmount(30) returned " + lockStatus);
        long locked = bank.getLockedBalance();
        StubCurrencyProvider.create(STUB_KEY_A, true, 0L);
        ExternalAccountRef ref = new ExternalAccountRef(
                StubCurrencyProvider.PROVIDER_ID, STUB_KEY_A, "UnbindGuard", true);
        BankStatus bindStatus = manager.bindExternalAccount(testAccountNr, slotItemA, ref);
        if (bindStatus != BankStatus.SUCCESS) return fail("bind returned " + bindStatus);

        StubCurrencyProvider.getInstance().setAvailable(false);
        BankStatus unbind = manager.unbindExternalAccount(testAccountNr, slotItemA, true);
        if (unbind != BankStatus.FAILED_EXTERNAL_UNAVAILABLE) {
            return fail("unbind while unavailable with locked=" + locked + " returned " + unbind
                    + " (expected FAILED_EXTERNAL_UNAVAILABLE)");
        }
        if (bindings.getBinding(testAccountNr, slotItemA) == null) {
            return fail("refused unbind removed the binding row");
        }
        if (bindings.getLocked(testAccountNr, slotItemA) != locked) {
            return fail("refused unbind changed BindingRow.lockedBalance to "
                    + bindings.getLocked(testAccountNr, slotItemA) + " (expected " + locked + ")");
        }

        StubCurrencyProvider.getInstance().setAvailable(true);
        unbind = manager.unbindExternalAccount(testAccountNr, slotItemA, true);
        if (unbind != BankStatus.SUCCESS) return fail("unbind after provider is back returned " + unbind);
        if (bank.getLockedBalance() != locked) {
            return fail("After unbind: locked = " + bank.getLockedBalance() + " (expected " + locked + ")");
        }
        bank.unlockAmount(30L); // leave the slot as perTestReset expects
        return pass("Unbind refused while the provider is unavailable and locked=" + locked
                + "; row kept; unbind after re-enable restored the locked funds");
    }

    /**
     * Task #136: a deleted user's bound slot pays out only what BankSystem holds (locked L + dust D). The free
     * balance E is in the external account, which the deletion leaves alone, so it is not counted.
     */
    private TestResult testPayoutCountsOnlyLocalPart() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        if (!(manager.getBankAccount(testAccountNr) instanceof ServerBankAccount account)) return fail("Test account missing");
        BankAccountBindings bindings = BankAccountBindings.get();
        if (bindings == null) return fail("BankAccountBindings backend is not available");
        ISyncServerBank bank = account.getBank(slotItemA);
        if (bank == null) return fail("Bank slot A missing");
        bank.setBalance(80L);
        if (bank.lockAmount(30L) != BankStatus.SUCCESS) return fail("lockAmount(30) failed"); // L = 30
        StubCurrencyProvider.StubAccount stub = StubCurrencyProvider.create(STUB_KEY_A, true, 0L);
        BankStatus bindStatus = manager.bindExternalAccount(testAccountNr, slotItemA,
                new ExternalAccountRef(StubCurrencyProvider.PROVIDER_ID, STUB_KEY_A, "Payout", true));
        if (bindStatus != BankStatus.SUCCESS) return fail("bind returned " + bindStatus);
        stub.setBalance(500L); // E = 500
        bindings.setDust(testAccountNr, slotItemA, 7L); // D = 7
        if (bank.getTotalBalance() != 537L) return fail("setup: total " + bank.getTotalBalance() + ", expected 537");
        Map<ItemID, Long> balances = new java.util.HashMap<>();
        manager.collectAccountBalances(account, balances);
        if (!Long.valueOf(37L).equals(balances.get(slotItemA)))
            return fail("payout balance " + balances.get(slotItemA) + ", expected L + D = 37");
        if (stub.getBalance() != 500L) return fail("external balance changed to " + stub.getBalance());
        return pass("bound slot E=500 L=30 D=7: payout counts 37, external untouched");
    }

    /**
     * H2. Bind carries local locked balance into the binding row (v2.0.5 refinement).
     * Locked funds represent pending orders that reference the local slot — bind must
     * preserve them in {@link BindingRow#lockedBalance()} rather than losing them or
     * refusing the bind.
     */
    private TestResult testBindPreservesLocked() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) return fail("Test account missing");
        BankAccountBindings bindings = BankAccountBindings.get();
        if (bindings == null) return fail("BankAccountBindings backend is not available");

        // Populate local: free=80, locked=30 (total 110).
        ISyncServerBank bank = account.getBank(slotItemA);
        if (bank == null) return fail("Bank slot A missing");
        bank.setBalance(80L);
        BankStatus lockStatus = bank.lockAmount(30L);
        if (lockStatus != BankStatus.SUCCESS) return fail("lockAmount(30) returned " + lockStatus);
        if (bank.getBalance() != 50L)
            return fail("Setup: expected free=50 after lockAmount, got " + bank.getBalance());
        if (bank.getLockedBalance() != 30L)
            return fail("Setup: expected locked=30, got " + bank.getLockedBalance());

        StubCurrencyProvider.StubAccount stub = StubCurrencyProvider.create(STUB_KEY_A, true, 0L);
        ExternalAccountRef ref = new ExternalAccountRef(
                StubCurrencyProvider.PROVIDER_ID, STUB_KEY_A, "LockedBind", true);
        BankStatus bindStatus = manager.bindExternalAccount(testAccountNr, slotItemA, ref);
        if (bindStatus != BankStatus.SUCCESS) {
            return fail("bind returned " + bindStatus + " (expected SUCCESS — locked funds must "
                    + "not block binding)");
        }

        // Only local FREE (50) transferred to external; locked (30) carried into the row.
        if (stub.getBalance() != 50L) {
            return fail("After bind: stub balance = " + stub.getBalance()
                    + " (expected 50 — only free portion should transfer)");
        }
        if (bindings.getLocked(testAccountNr, slotItemA) != 30L) {
            return fail("After bind: BindingRow.lockedBalance = "
                    + bindings.getLocked(testAccountNr, slotItemA)
                    + " (expected 30 — must carry over from local)");
        }
        // Bound-slot view: free reads external (50), locked reads binding row (30).
        if (bank.getBalance() != 50L) {
            return fail("Bound-slot getBalance() = " + bank.getBalance() + " (expected 50)");
        }
        if (bank.getLockedBalance() != 30L) {
            return fail("Bound-slot getLockedBalance() = " + bank.getLockedBalance()
                    + " (expected 30)");
        }

        // Unbind (keepOnBankSystem=true) — everything comes home: free=50, locked=30.
        BankStatus unbind = manager.unbindExternalAccount(testAccountNr, slotItemA, true);
        if (unbind != BankStatus.SUCCESS) return fail("unbind returned " + unbind);
        if (bank.getBalance() != 50L) {
            return fail("After unbind: free = " + bank.getBalance() + " (expected 50)");
        }
        if (bank.getLockedBalance() != 30L) {
            return fail("After unbind: locked = " + bank.getLockedBalance() + " (expected 30)");
        }
        return pass("Bind preserved locked=30 through the binding row; unbind restored it locally");
    }

    /**
     * H3. Bind atomicity on FAILED_OVERFLOW. When {@code external.deposit} refuses the
     * whole-native portion of the local free balance, bind must return FAILED_OVERFLOW
     * WITHOUT touching local state — free, locked, and dust all remain as they were.
     */
    private TestResult testBindOverflowAtomicity() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) return fail("Test account missing");
        BankAccountBindings bindings = BankAccountBindings.get();
        if (bindings == null) return fail("BankAccountBindings backend is not available");

        ISyncServerBank bank = account.getBank(slotItemA);
        if (bank == null) return fail("Bank slot A missing");
        // Local: free=100, locked=25 → total 125.
        bank.setBalance(100L);
        BankStatus lockStatus = bank.lockAmount(25L);
        if (lockStatus != BankStatus.SUCCESS) return fail("lockAmount(25) returned " + lockStatus);
        long preFree = bank.getBalance();
        long preLocked = bank.getLockedBalance();

        // Stub configured to reject any deposit above ceiling (matches Numismatics'
        // Integer.MAX_VALUE cap — deposit-time overflow refusal).
        StubCurrencyProvider.StubAccount stub = StubCurrencyProvider.create(STUB_KEY_A, true, 0L);
        stub.setOverflowCeiling(50L); // any deposit > 50 fails
        ExternalAccountRef ref = new ExternalAccountRef(
                StubCurrencyProvider.PROVIDER_ID, STUB_KEY_A, "BindOverflow", true);

        BankStatus bindStatus = manager.bindExternalAccount(testAccountNr, slotItemA, ref);
        if (bindStatus != BankStatus.FAILED_OVERFLOW) {
            return fail("bind returned " + bindStatus + " (expected FAILED_OVERFLOW — local free "
                    + "100 exceeds stub ceiling 50)");
        }
        // No binding row committed.
        if (bindings.getBinding(testAccountNr, slotItemA) != null) {
            return fail("bind FAILED_OVERFLOW created a binding row (should not commit)");
        }
        // Local state untouched.
        if (bank.getBalance() != preFree) {
            return fail("After bind FAILED_OVERFLOW: free = " + bank.getBalance()
                    + " (expected " + preFree + " — must not mutate on failure)");
        }
        if (bank.getLockedBalance() != preLocked) {
            return fail("After bind FAILED_OVERFLOW: locked = " + bank.getLockedBalance()
                    + " (expected " + preLocked + " — must not mutate on failure)");
        }
        if (stub.getBalance() != 0L) {
            return fail("After bind FAILED_OVERFLOW: stub balance = " + stub.getBalance()
                    + " (expected 0 — external must not mutate on failure)");
        }
        return pass("Bind FAILED_OVERFLOW: no row committed, local free/locked and external "
                + "balance all unchanged");
    }

    /**
     * H4. Withdraw refused by external. When the underlying mod's {@code withdraw}
     * returns {@code false} (funds already spent through the mod's own UI, drift
     * beyond the clamp, etc.), {@link ServerBank#withdraw} must surface a failure
     * status without falsely debiting the local view.
     */
    private TestResult testWithdrawRefusedByExternal() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) return fail("Test account missing");

        StubCurrencyProvider.StubAccount stub = StubCurrencyProvider.create(STUB_KEY_A, true, 100L);
        ExternalAccountRef ref = new ExternalAccountRef(
                StubCurrencyProvider.PROVIDER_ID, STUB_KEY_A, "WithdrawRefusal", true);
        BankStatus bindStatus = manager.bindExternalAccount(testAccountNr, slotItemA, ref);
        if (bindStatus != BankStatus.SUCCESS) return fail("bind returned " + bindStatus);

        ISyncServerBank bank = account.getBank(slotItemA);
        if (bank == null) return fail("Bank slot A missing after bind");

        // Player drained the external balance behind BankSystem's back (mod's own UI).
        stub.setBalance(20L);

        // Try to withdraw more than the external actually has.
        BankStatus wd = bank.withdraw(50L);
        if (wd == BankStatus.SUCCESS) {
            return fail("withdraw(50) succeeded — should have been refused (external only holds 20)");
        }
        // External must be unchanged.
        if (stub.getBalance() != 20L) {
            return fail("Refused withdraw mutated external balance to " + stub.getBalance()
                    + " (expected 20 — external unchanged on refusal)");
        }
        // The next getBalance() reads through the stub and reflects the drift down to 20.
        if (bank.getBalance() != 20L) {
            return fail("Post-refusal getBalance() = " + bank.getBalance()
                    + " (expected 20 — drift read reflects current external)");
        }
        // A withdraw within the current external balance still works — degradation is per-op.
        BankStatus wdSmall = bank.withdraw(15L);
        if (wdSmall != BankStatus.SUCCESS) {
            return fail("withdraw(15) returned " + wdSmall + " (expected SUCCESS — within budget)");
        }
        if (stub.getBalance() != 5L) {
            return fail("After withdraw(15): stub balance = " + stub.getBalance() + " (expected 5)");
        }
        return pass("Withdraw refusal by external: BankSystem propagates failure, external state "
                + "untouched, subsequent smaller withdraw still succeeds");
    }

    /**
     * H5. Membership sync propagation. Calling {@link ExternalAccount#syncMembership}
     * on the opened external account must update the provider's authoritative member
     * set — the same code path Numismatics uses to keep BLAZE_BANKER trust lists in
     * sync with BankSystem's per-account user set.
     */
    private TestResult testMembershipSyncPropagates() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        StubCurrencyProvider.StubAccount stub = StubCurrencyProvider.create(STUB_KEY_A, true, 0L);
        ExternalAccountRef ref = new ExternalAccountRef(
                StubCurrencyProvider.PROVIDER_ID, STUB_KEY_A, "Membership", true);
        BankStatus bindStatus = manager.bindExternalAccount(testAccountNr, slotItemA, ref);
        if (bindStatus != BankStatus.SUCCESS) return fail("bind returned " + bindStatus);

        // Initial state: empty membership.
        if (!stub.currentMembers().isEmpty()) {
            return fail("Stub started with " + stub.currentMembers().size()
                    + " members (expected 0)");
        }

        // Sync a fresh set of 2 members through the ExternalAccount handle.
        java.util.Set<UUID> membersA = new java.util.HashSet<>();
        membersA.add(TEST_OWNER);
        membersA.add(TEST_MEMBER);
        net.kroia.banksystem.api.currency.ExternalCurrencyProvider provider =
                BankSystemMod.getAPI().getCurrencyProvider(StubCurrencyProvider.PROVIDER_ID);
        if (provider == null) return fail("Stub provider not registered");
        net.kroia.banksystem.api.currency.ExternalAccount ext = provider.open(ref);
        if (ext == null) return fail("Stub provider could not open ref");
        ext.syncMembership(membersA);

        java.util.Set<UUID> afterA = stub.currentMembers();
        if (afterA.size() != 2 || !afterA.contains(TEST_OWNER) || !afterA.contains(TEST_MEMBER)) {
            return fail("Sync A: stub members = " + afterA + " (expected {TEST_OWNER, TEST_MEMBER})");
        }

        // Second sync with a smaller set — the stub must REPLACE, not merge.
        java.util.Set<UUID> membersB = new java.util.HashSet<>();
        membersB.add(TEST_OWNER);
        ext.syncMembership(membersB);

        java.util.Set<UUID> afterB = stub.currentMembers();
        if (afterB.size() != 1 || !afterB.contains(TEST_OWNER)) {
            return fail("Sync B: stub members = " + afterB + " (expected {TEST_OWNER} — replace, "
                    + "not merge)");
        }
        if (afterB.contains(TEST_MEMBER)) {
            return fail("Sync B did not remove TEST_MEMBER (still present after replace)");
        }
        return pass("Membership sync: replace semantics observed, TEST_MEMBER removed on second "
                + "sync as expected");
    }
}
