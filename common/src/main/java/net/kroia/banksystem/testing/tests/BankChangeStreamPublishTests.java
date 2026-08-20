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
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.testing.StubCurrencyProvider;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.world.item.Items;

import java.util.UUID;

/**
 * Issue #67 regression suite (v2.0.6): every ServerBank balance-mutation method
 * must flip {@code changeFlag = true} on the bound path so the
 * {@code BANKSYSTEM_ACCOUNT_CHANGE_STREAM} publishes to open BankTerminalScreens.
 * Prior to the fix, the Task #33 bound branches mutated external state without
 * touching any local field, so {@code hasChanges()} stayed false and no packet
 * ever fired — the terminal showed a stale snapshot until it was reopened.
 * <p>
 * The suite also covers the 1 Hz drift watchdog that catches out-of-band
 * external-mod writes (player used Numismatics' own Bank Terminal or Lightman's
 * ATM while a BankSystem terminal was open): it must flip the flag exactly
 * when external drifts, and be silent on a matching poll.
 * <p>
 * <b>Server type.</b> BANK_ACCOUNT (MASTER_ONLY) — bindings are
 * master-authoritative; the slave has no {@link BankAccountBindings} singleton
 * to consult.
 *
 * @since 2.0.6
 */
public class BankChangeStreamPublishTests extends TestSuite {

    private static final UUID TEST_OWNER = UUID.fromString("00000000-0000-0000-0000-0000000067a1");
    private static final String TEST_OWNER_NAME = "Issue67Owner";
    private static final String TEST_ACCOUNT_NAME = "Issue67TestAccount";
    private static final String STUB_KEY = "issue67_stub";

    private ServerBankManager manager;
    private int testAccountNr = ServerBankAccount.INVALID_ACCOUNT_NUMBER;
    private ItemID slotItem;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.BANK_ACCOUNT;
    }

    @Override
    public void registerTests() {
        addTest("unbound_deposit_flips_change_flag", this::unboundDepositFlipsChangeFlag);
        addTest("bound_deposit_flips_change_flag", this::boundDepositFlipsChangeFlag);
        addTest("bound_withdraw_flips_change_flag", this::boundWithdrawFlipsChangeFlag);
        addTest("bound_lockAmount_flips_change_flag", this::boundLockAmountFlipsChangeFlag);
        addTest("bound_withdrawLocked_flips_change_flag", this::boundWithdrawLockedFlipsChangeFlag);
        addTest("bound_setBalance_flips_change_flag", this::boundSetBalanceFlipsChangeFlag);
        addTest("external_drift_flips_change_flag_via_watchdog", this::externalDriftFlipsChangeFlagViaWatchdog);
        addTest("no_drift_no_flag_flip", this::noDriftNoFlagFlip);
    }

    @Override
    public void setup() {
        IBankManager bankManager = BankSystemMod.getAPI().getServerBankManager();
        if (bankManager == null) return;
        if (!(bankManager.getSync() instanceof ServerBankManager serverManager)) return;
        manager = serverManager;

        StubCurrencyProvider.reset();

        if (!manager.userExists(TEST_OWNER)) {
            manager.addUser(new User(TEST_OWNER, TEST_OWNER_NAME));
        }

        // Distinct non-money item avoids collisions with auto-created money banks.
        slotItem = ItemIDManager.registerItemStackServerSide_direct(Items.STONE.getDefaultInstance());
        manager.allowItemID(slotItem);

        IServerBankAccount existing = manager.getBankAccountByName(TEST_ACCOUNT_NAME);
        if (existing != null) {
            manager.deleteBankAccount(existing.getAccountNumber());
        }
        IServerBankAccount account = manager.createBankAccount(TEST_ACCOUNT_NAME);
        if (account != null) {
            testAccountNr = account.getAccountNumber();
            account.createBank(slotItem, 0);
        }
    }

    /**
     * Per-test baseline: drop bindings, reset the stub, zero the slot. Every
     * assertion below relies on a known-clean starting flag. See
     * {@code ExternalCurrencyBindingTests#perTestReset} for the original pattern.
     */
    private void perTestReset() {
        if (manager == null) return;
        BankAccountBindings bindings = BankAccountBindings.get();
        if (bindings != null && testAccountNr != ServerBankAccount.INVALID_ACCOUNT_NUMBER) {
            bindings.removeAllForAccount(testAccountNr);
        }
        StubCurrencyProvider.reset();
        IServerBankAccount account = testAccountNr != ServerBankAccount.INVALID_ACCOUNT_NUMBER
                ? manager.getBankAccount(testAccountNr) : null;
        if (account != null) {
            ISyncServerBank bank = account.getBank(slotItem);
            if (bank != null) {
                // Route through the (now unbound) local path to zero out.
                bank.setBalance(0L);
                bank.unlockAll();
                bank.clearChangeFlag();
            }
        }
    }

    @Override
    public void teardown() {
        if (manager == null) return;
        BankAccountBindings bindings = BankAccountBindings.get();
        if (bindings != null && testAccountNr != ServerBankAccount.INVALID_ACCOUNT_NUMBER) {
            bindings.removeAllForAccount(testAccountNr);
        }
        if (testAccountNr != ServerBankAccount.INVALID_ACCOUNT_NUMBER) {
            manager.deleteBankAccount(testAccountNr);
            testAccountNr = ServerBankAccount.INVALID_ACCOUNT_NUMBER;
        }
        StubCurrencyProvider.teardown();
        manager.removeUser(TEST_OWNER);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Binds {@code slotItem} to a fresh {@link StubCurrencyProvider} account
     * seeded with {@code initialBalance}. Returns the bound {@link ServerBank}
     * with its {@code changeFlag} cleared so the caller can begin from a known
     * baseline. Fails the test with an error message if any step fails.
     */
    private ServerBank bindWithStub(long initialBalance) {
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        StubCurrencyProvider.create(STUB_KEY, /*shared=*/true, initialBalance);
        ExternalAccountRef ref = new ExternalAccountRef(
                StubCurrencyProvider.PROVIDER_ID, STUB_KEY, "Issue67", true);
        BankStatus bindStatus = manager.bindExternalAccount(testAccountNr, slotItem, ref);
        if (bindStatus != BankStatus.SUCCESS) return null;
        ISyncServerBank bank = account.getBank(slotItem);
        if (!(bank instanceof ServerBank sb)) return null;
        sb.clearChangeFlag();
        return sb;
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /**
     * Baseline: on an unbound slot the local {@code addBalanceInternal} path
     * flips {@code changeFlag} as it always has. If this ever fails, the
     * regression is broader than Issue #67.
     */
    private TestResult unboundDepositFlipsChangeFlag() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        IServerBankAccount account = manager.getBankAccount(testAccountNr);
        if (account == null) return fail("Test account missing");
        ISyncServerBank bank = account.getBank(slotItem);
        if (!(bank instanceof ServerBank sb)) return fail("Bank slot missing or wrong type");

        sb.clearChangeFlag();
        BankStatus st = sb.deposit(100L);
        if (st != BankStatus.SUCCESS) return fail("Unbound deposit returned " + st);
        if (!sb.hasChanges()) {
            return fail("Unbound deposit did NOT flip changeFlag — baseline broken");
        }
        return pass("Unbound deposit flips changeFlag (baseline unchanged)");
    }

    /**
     * Direct Issue #67 regression: on a bound slot, {@code deposit} used to skip
     * every {@code changeFlag = true} because it never called
     * {@code addBalanceInternal}. Verify the flag is now set.
     */
    private TestResult boundDepositFlipsChangeFlag() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        ServerBank sb = bindWithStub(100L);
        if (sb == null) return fail("bindWithStub failed");

        BankStatus st = sb.deposit(50L);
        if (st != BankStatus.SUCCESS) return fail("Bound deposit returned " + st);
        if (!sb.hasChanges()) {
            return fail("Bound deposit did NOT flip changeFlag — Issue #67 regression");
        }
        return pass("Bound deposit flips changeFlag (Issue #67 direct regression covered)");
    }

    private TestResult boundWithdrawFlipsChangeFlag() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        ServerBank sb = bindWithStub(200L);
        if (sb == null) return fail("bindWithStub failed");

        BankStatus st = sb.withdraw(50L);
        if (st != BankStatus.SUCCESS) return fail("Bound withdraw returned " + st);
        if (!sb.hasChanges()) {
            return fail("Bound withdraw did NOT flip changeFlag");
        }
        return pass("Bound withdraw flips changeFlag");
    }

    private TestResult boundLockAmountFlipsChangeFlag() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        ServerBank sb = bindWithStub(200L);
        if (sb == null) return fail("bindWithStub failed");

        BankStatus st = sb.lockAmount(50L);
        if (st != BankStatus.SUCCESS) return fail("Bound lockAmount returned " + st);
        if (!sb.hasChanges()) {
            return fail("Bound lockAmount did NOT flip changeFlag");
        }
        return pass("Bound lockAmount flips changeFlag");
    }

    private TestResult boundWithdrawLockedFlipsChangeFlag() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        ServerBank sb = bindWithStub(200L);
        if (sb == null) return fail("bindWithStub failed");

        BankStatus lockSt = sb.lockAmount(60L);
        if (lockSt != BankStatus.SUCCESS) return fail("Setup lockAmount returned " + lockSt);
        sb.clearChangeFlag();

        BankStatus st = sb.withdrawLocked(30L);
        if (st != BankStatus.SUCCESS) return fail("Bound withdrawLocked returned " + st);
        if (!sb.hasChanges()) {
            return fail("Bound withdrawLocked did NOT flip changeFlag");
        }
        return pass("Bound withdrawLocked flips changeFlag");
    }

    private TestResult boundSetBalanceFlipsChangeFlag() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        ServerBank sb = bindWithStub(100L);
        if (sb == null) return fail("bindWithStub failed");

        boolean ok = sb.setBalance(150L);
        if (!ok) return fail("Bound setBalance(150) returned false");
        if (!sb.hasChanges()) {
            return fail("Bound setBalance did NOT flip changeFlag");
        }
        return pass("Bound setBalance flips changeFlag");
    }

    /**
     * Watchdog case: player used the external mod's own UI to change the balance
     * while BankSystem was oblivious. The 1 Hz poll must detect the drift and
     * flip {@code changeFlag} so the ARRS change stream fires. Bypasses
     * {@link ServerBank} entirely by mutating {@link StubCurrencyProvider.StubAccount#setBalance(long)}.
     */
    private TestResult externalDriftFlipsChangeFlagViaWatchdog() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        ServerBank sb = bindWithStub(100L);
        if (sb == null) return fail("bindWithStub failed");
        StubCurrencyProvider.StubAccount stub = StubCurrencyProvider.getAccount(STUB_KEY);
        if (stub == null) return fail("Stub account missing after bind");

        // Prime the cache by running one poll — bind already primed it, this is a
        // belt-and-suspenders no-op that also demonstrates the silent branch.
        sb.pollExternalDrift();
        sb.clearChangeFlag();
        if (sb.hasChanges()) {
            return fail("Cache was primed at bind but a subsequent poll flipped the flag — "
                    + "watchdog is over-firing");
        }

        // Now simulate the Numismatics-behind-our-back case.
        stub.setBalance(75L);
        sb.pollExternalDrift();
        if (!sb.hasChanges()) {
            return fail("Watchdog did NOT flip changeFlag after external drift 100 -> 75");
        }
        return pass("Watchdog detects out-of-band external mutation and flips changeFlag");
    }

    /**
     * Regression guard: the watchdog must be silent on a matching poll — no
     * flag flip, no cache thrash. Otherwise every 1 Hz tick would trigger a
     * redundant BANK_ACCOUNT_CHANGE_STREAM publish for every bound slot.
     */
    private TestResult noDriftNoFlagFlip() {
        if (manager == null) return fail("ServerBankManager is null — cannot run on slave server");
        perTestReset();
        ServerBank sb = bindWithStub(100L);
        if (sb == null) return fail("bindWithStub failed");

        // First poll — cache already primed at bind time to 100, should not flip.
        sb.pollExternalDrift();
        if (sb.hasChanges()) {
            return fail("First poll flipped changeFlag despite no drift (cache primed at bind)");
        }

        // Second poll — same story.
        sb.clearChangeFlag();
        sb.pollExternalDrift();
        if (sb.hasChanges()) {
            return fail("Second consecutive no-drift poll flipped changeFlag");
        }
        return pass("Two consecutive no-drift polls leave changeFlag clear");
    }
}
