package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.ISyncServerBank;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.api.currency.ExternalAccountRef;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.bank.ServerBank;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import net.kroia.banksystem.banking.bankmanager.ServerBankManager;
import net.kroia.banksystem.banking.binding.BankAccountBindings;
import net.kroia.banksystem.banking.binding.BindingKey;
import net.kroia.banksystem.banking.binding.BindingRow;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.testing.StubCurrencyProvider;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.UUID;

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
        addTest("locked_balance_protocol_no_external_write", this::testLockedBalanceProtocol);
        addTest("drift_clamp_when_external_drops_below_locked", this::testDriftClamp);
        addTest("overflow_guard_deposit_returns_failed_overflow", this::testOverflowGuard);
        addTest("provider_unavailable_degrades_reads_and_writes", this::testProviderUnavailable);
        addTest("shared_personal_ref_mismatch_rejected", this::testSharedPersonalMismatch);
        addTest("cascade_cleanup_on_account_and_bank_removal", this::testCascadeCleanup);
    }

    @Override
    public void setup() {
        IBankManager bankManager = BankSystemMod.getAPI().getServerBankManager();
        if (bankManager == null) return;
        if (!(bankManager.getSync() instanceof ServerBankManager serverManager)) return;
        manager = serverManager;

        // Refresh the stub — clears accounts, resets available=true, and re-registers.
        StubCurrencyProvider.reset();

        if (!manager.userExists(TEST_OWNER)) {
            manager.addUser(new User(TEST_OWNER, TEST_OWNER_NAME, false));
        }
        if (!manager.userExists(TEST_MEMBER)) {
            manager.addUser(new User(TEST_MEMBER, TEST_MEMBER_NAME, false));
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
        // Reset the stub last so a lingering registration doesn't reach into future suites.
        StubCurrencyProvider.reset();
        manager.removeUser(TEST_OWNER);
        manager.removeUser(TEST_MEMBER);
    }

    // -----------------------------------------------------------------------
    // 1. Round-trip: bind → external deposits show up → BankSystem writes propagate
    // -----------------------------------------------------------------------
    private TestResult testRoundTrip() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) return fail("Test account missing");

        StubCurrencyProvider.create(STUB_KEY_A, /*shared=*/true, /*initialBalance=*/0L);
        ExternalAccountRef ref = new ExternalAccountRef(
                StubCurrencyProvider.PROVIDER_ID, STUB_KEY_A, "Round-Trip", true);
        BankStatus bindStatus = manager.bindExternalAccount(testAccountNr, slotItemA, ref);
        if (bindStatus != BankStatus.SUCCESS) {
            return fail("bindExternalAccount returned " + bindStatus + " (expected SUCCESS)");
        }

        ISyncServerBank bank = account.getBank(slotItemA);
        if (bank == null) return fail("Bank slot A missing after bind");

        // External-side back-door deposit: BankSystem must read it through.
        StubCurrencyProvider.StubAccount stub = StubCurrencyProvider.getAccount(STUB_KEY_A);
        if (stub == null) return fail("Stub account was not created");
        stub.setBalance(100L);
        if (bank.getBalance() != 100L) {
            return fail("After external deposit of 100, BankSystem getBalance() = "
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
        return pass("Round-trip: bind + external deposit + BankSystem deposit + BankSystem withdraw all consistent");
    }

    // -----------------------------------------------------------------------
    // 2. Locked-balance protocol: lock is local, withdrawLocked hits external,
    //    unlock is local.
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

        // lockAmount(40) — external must NOT be touched.
        BankStatus lockStatus = bank.lockAmount(40L);
        if (lockStatus != BankStatus.SUCCESS) return fail("lockAmount(40) returned " + lockStatus);
        if (bank.getBalance() != 60L)
            return fail("Post-lock free balance = " + bank.getBalance() + " (expected 60)");
        if (bank.getLockedBalance() != 40L)
            return fail("Post-lock locked = " + bank.getLockedBalance() + " (expected 40)");
        if (bank.getTotalBalance() != 100L)
            return fail("Post-lock total = " + bank.getTotalBalance() + " (expected 100)");
        if (stub.getBalance() != 100L)
            return fail("Post-lock stub balance = " + stub.getBalance() + " (expected 100 — lock is local only)");

        // withdrawLocked(30) — external must decrease by 30, locked drops by 30.
        BankStatus wlStatus = bank.withdrawLocked(30L);
        if (wlStatus != BankStatus.SUCCESS) return fail("withdrawLocked(30) returned " + wlStatus);
        if (bank.getBalance() != 60L)
            return fail("Post-withdrawLocked free = " + bank.getBalance() + " (expected 60)");
        if (bank.getLockedBalance() != 10L)
            return fail("Post-withdrawLocked locked = " + bank.getLockedBalance() + " (expected 10)");
        if (bank.getTotalBalance() != 70L)
            return fail("Post-withdrawLocked total = " + bank.getTotalBalance() + " (expected 70)");
        if (stub.getBalance() != 70L)
            return fail("Post-withdrawLocked stub balance = " + stub.getBalance() + " (expected 70)");

        // unlockAmount(10) — external must NOT be touched.
        BankStatus unlockStatus = bank.unlockAmount(10L);
        if (unlockStatus != BankStatus.SUCCESS) return fail("unlockAmount(10) returned " + unlockStatus);
        if (bank.getBalance() != 70L)
            return fail("Post-unlock free = " + bank.getBalance() + " (expected 70)");
        if (bank.getLockedBalance() != 0L)
            return fail("Post-unlock locked = " + bank.getLockedBalance() + " (expected 0)");
        if (stub.getBalance() != 70L)
            return fail("Post-unlock stub balance = " + stub.getBalance() + " (expected 70)");
        return pass("Locked-balance protocol observed: lock/unlock local, withdrawLocked routed externally");
    }

    // -----------------------------------------------------------------------
    // 3. Drift-clamp: external balance drops below locked → clamp to external
    //    on the next read + WARN.
    // -----------------------------------------------------------------------
    private TestResult testDriftClamp() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) return fail("Test account missing");
        BankAccountBindings bindings = BankAccountBindings.get();
        if (bindings == null) return fail("BankAccountBindings backend is not available");

        StubCurrencyProvider.create(STUB_KEY_A, true, 100L);
        ExternalAccountRef ref = new ExternalAccountRef(
                StubCurrencyProvider.PROVIDER_ID, STUB_KEY_A, "Drift-Clamp", true);
        BankStatus bindStatus = manager.bindExternalAccount(testAccountNr, slotItemA, ref);
        if (bindStatus != BankStatus.SUCCESS) return fail("bind returned " + bindStatus);

        ISyncServerBank bank = account.getBank(slotItemA);
        if (bank == null) return fail("Bank slot A missing after bind");
        StubCurrencyProvider.StubAccount stub = StubCurrencyProvider.getAccount(STUB_KEY_A);
        if (stub == null) return fail("Stub account was not created");

        BankStatus lockStatus = bank.lockAmount(80L);
        if (lockStatus != BankStatus.SUCCESS) return fail("lockAmount(80) returned " + lockStatus);
        if (bindings.getLocked(testAccountNr, slotItemA) != 80L) {
            return fail("Pre-drift locked in binding row = "
                    + bindings.getLocked(testAccountNr, slotItemA) + " (expected 80)");
        }

        // Player used the external mod's UI behind our back — stub drops to 20.
        stub.setBalance(20L);

        // Any read triggers the drift-clamp in ServerBank.resolveBound().
        long postDriftFree = bank.getBalance();
        long postDriftLockedFromBinding = bindings.getLocked(testAccountNr, slotItemA);

        if (postDriftLockedFromBinding != 20L) {
            return fail("Drift-clamp: binding row locked should be clamped to 20, got "
                    + postDriftLockedFromBinding);
        }
        if (postDriftFree != 0L) {
            return fail("Drift-clamp: post-clamp free = " + postDriftFree + " (expected 0)");
        }
        if (bank.getLockedBalance() != 20L) {
            return fail("Drift-clamp: post-clamp locked = " + bank.getLockedBalance() + " (expected 20)");
        }
        return pass("Drift-clamp: locked clamped from 80 to 20 to match external, free = 0");
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
}
