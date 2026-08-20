package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Task #43 (v2.1.0) Phase 1 — {@link ServerBankAccount#enforceManageInvariant(Map, Map, boolean, int)}
 * founder-aware overload. Tests are pure: no world state, no manager.
 */
public class CompanyFounderInvariantTests extends TestSuite {

    private static final UUID FOUNDER = UUID.fromString("00000000-0000-0000-0000-0000BBBB0001");
    private static final UUID NON_FOUNDER = UUID.fromString("00000000-0000-0000-0000-0000BBBB0002");
    private static final int TEST_ACCOUNT_NR = 999;

    private BiPredicate<Integer, UUID> savedChecker;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.COMPANY;
    }

    @Override
    public void registerTests() {
        addTest("founder_manage_removal_refused", this::testFounderManageRemovalRefused);
        addTest("founder_dropped_from_set_refused", this::testFounderDroppedFromSetRefused);
        addTest("non_founder_manage_removal_ok", this::testNonFounderManageRemovalOk);
        addTest("founder_still_manages_ok", this::testFounderStillManagesOk);
        addTest("no_founder_checker_falls_through", this::testNoFounderCheckerFallsThrough);
        addTest("personal_owner_short_circuits", this::testPersonalOwnerShortCircuits);
    }

    @Override
    public void setup() {
        savedChecker = ServerBankAccount.getFounderChecker();
        ServerBankAccount.setFounderChecker((accountNr, uuid) ->
                accountNr != null && accountNr == TEST_ACCOUNT_NR && FOUNDER.equals(uuid));
    }

    @Override
    public void teardown() {
        ServerBankAccount.setFounderChecker(savedChecker);
    }

    private static User u(UUID uuid, String name) { return new User(uuid, name); }

    // ------------------------------------------------------------------
    // Removing MANAGE from a founder → REFUSED_FOUNDER
    private TestResult testFounderManageRemovalRefused() {
        Map<User, Integer> previous = new HashMap<>();
        previous.put(u(FOUNDER, "F"), BankPermission.MANAGE.getValue() | BankPermission.DEPOSIT.getValue());
        Map<User, Integer> proposed = new HashMap<>();
        proposed.put(u(FOUNDER, "F"), BankPermission.DEPOSIT.getValue()); // MANAGE stripped
        ServerBankAccount.ManageInvariantOutcome r =
                ServerBankAccount.enforceManageInvariant(proposed, previous, false, TEST_ACCOUNT_NR);
        return assertTrue("Expected REFUSED_FOUNDER, got " + r,
                r == ServerBankAccount.ManageInvariantOutcome.REFUSED_FOUNDER);
    }

    // Dropping the founder from the set entirely → REFUSED_FOUNDER
    private TestResult testFounderDroppedFromSetRefused() {
        Map<User, Integer> previous = new HashMap<>();
        previous.put(u(FOUNDER, "F"), BankPermission.MANAGE.getValue());
        previous.put(u(NON_FOUNDER, "N"), BankPermission.DEPOSIT.getValue());
        Map<User, Integer> proposed = new HashMap<>();
        proposed.put(u(NON_FOUNDER, "N"), BankPermission.MANAGE.getValue() | BankPermission.DEPOSIT.getValue());
        // Founder no longer in proposed at all.
        ServerBankAccount.ManageInvariantOutcome r =
                ServerBankAccount.enforceManageInvariant(proposed, previous, false, TEST_ACCOUNT_NR);
        return assertTrue("Expected REFUSED_FOUNDER when founder dropped, got " + r,
                r == ServerBankAccount.ManageInvariantOutcome.REFUSED_FOUNDER);
    }

    // Removing MANAGE from a non-founder → OK (falls through to the pure invariant)
    private TestResult testNonFounderManageRemovalOk() {
        Map<User, Integer> previous = new HashMap<>();
        previous.put(u(FOUNDER, "F"), BankPermission.MANAGE.getValue());
        previous.put(u(NON_FOUNDER, "N"), BankPermission.MANAGE.getValue());
        Map<User, Integer> proposed = new HashMap<>();
        proposed.put(u(FOUNDER, "F"), BankPermission.MANAGE.getValue());
        proposed.put(u(NON_FOUNDER, "N"), BankPermission.DEPOSIT.getValue());
        ServerBankAccount.ManageInvariantOutcome r =
                ServerBankAccount.enforceManageInvariant(proposed, previous, false, TEST_ACCOUNT_NR);
        return assertTrue("Expected OK (founder retains MANAGE), got " + r,
                r == ServerBankAccount.ManageInvariantOutcome.OK);
    }

    // Founder keeps MANAGE → OK
    private TestResult testFounderStillManagesOk() {
        Map<User, Integer> previous = new HashMap<>();
        previous.put(u(FOUNDER, "F"), BankPermission.MANAGE.getValue());
        Map<User, Integer> proposed = new HashMap<>();
        proposed.put(u(FOUNDER, "F"), BankPermission.MANAGE.getValue() | BankPermission.DEPOSIT.getValue());
        ServerBankAccount.ManageInvariantOutcome r =
                ServerBankAccount.enforceManageInvariant(proposed, previous, false, TEST_ACCOUNT_NR);
        return assertTrue("Expected OK, got " + r,
                r == ServerBankAccount.ManageInvariantOutcome.OK);
    }

    // No founder checker installed → behaves like the 3-arg overload (OK here)
    private TestResult testNoFounderCheckerFallsThrough() {
        BiPredicate<Integer, UUID> prior = ServerBankAccount.getFounderChecker();
        ServerBankAccount.setFounderChecker(null);
        try {
            Map<User, Integer> previous = new HashMap<>();
            previous.put(u(FOUNDER, "F"), BankPermission.MANAGE.getValue());
            Map<User, Integer> proposed = new HashMap<>();
            proposed.put(u(FOUNDER, "F"), BankPermission.MANAGE.getValue());
            ServerBankAccount.ManageInvariantOutcome r =
                    ServerBankAccount.enforceManageInvariant(proposed, previous, false, TEST_ACCOUNT_NR);
            return assertTrue("Expected OK with no founder checker, got " + r,
                    r == ServerBankAccount.ManageInvariantOutcome.OK);
        } finally {
            ServerBankAccount.setFounderChecker(prior);
        }
    }

    // Personal owner short-circuits regardless of founder state.
    private TestResult testPersonalOwnerShortCircuits() {
        Map<User, Integer> previous = new HashMap<>();
        previous.put(u(FOUNDER, "F"), BankPermission.MANAGE.getValue());
        Map<User, Integer> proposed = new HashMap<>();
        // Empty proposed on a personal account → still OK.
        ServerBankAccount.ManageInvariantOutcome r =
                ServerBankAccount.enforceManageInvariant(proposed, previous, true, TEST_ACCOUNT_NR);
        return assertTrue("Personal-owner short-circuit expected OK, got " + r,
                r == ServerBankAccount.ManageInvariantOutcome.OK);
    }
}
