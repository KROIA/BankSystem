package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.banking.company.Company;
import net.kroia.banksystem.banking.company.CompanyManager;
import net.kroia.banksystem.banking.company.ShareVisuals;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.banking.company.PayoutExecutor;
import net.kroia.banksystem.banking.company.PayoutFailureReason;
import net.kroia.banksystem.banking.company.PayoutSchedule;
import net.kroia.banksystem.banking.company.ShareIntegrity;
import net.kroia.banksystem.data.table.record.PayoutHistoryRecord;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Task #43 (v2.1.0) Phase 1 — {@link CompanyManager} persistence + validation tests.
 * Master-only. All tests use a fresh in-memory {@link CompanyManager} — no world state.
 */
public class CompanyManagerTests extends TestSuite {

    private static final UUID FOUNDER_A = UUID.fromString("00000000-0000-0000-0000-0000AAAA0001");
    private static final UUID FOUNDER_B = UUID.fromString("00000000-0000-0000-0000-0000AAAA0002");

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.COMPANY;
    }

    @Override
    public void registerTests() {
        addTest("case_insensitive_name_uniqueness", this::testCaseInsensitiveNameUniqueness);
        addTest("max_supply_validation", this::testMaxSupplyValidation);
        addTest("monotonic_ids", this::testMonotonicIds);
        addTest("nbt_round_trip_preserves_fields", this::testNbtRoundTripPreservesFields);
        addTest("nbt_load_tolerates_missing_optional_fields", this::testNbtLoadTolerantMissingFields);
        addTest("load_drops_duplicate_name_keeping_older", this::testLoadDropsDuplicateNameKeepingOlder);
        addTest("transfer_founder_moves_uuid", this::testTransferFounderMovesUuid);
        addTest("transfer_founder_requires_worker", this::testTransferFounderRequiresWorker); // BS-251
        addTest("is_founder_of_accounts", this::testIsFounderOfAccounts);
        addTest("delete_company_removes_from_indices", this::testDeleteCompanyRemovesFromIndices);
        addTest("get_by_name_case_insensitive", this::testGetByNameCaseInsensitive);
        addTest("list_all_companies_returns_all", this::testListAllCompaniesReturnsAll);
        addTest("list_companies_foundered_by_filters", this::testListCompaniesFounderedByFilters);
        addTest("list_companies_managed_by_falls_back_to_founder", this::testListCompaniesManagedByFallsBackToFounder);
        // Task #58 — per-player company cap
        addTest("per_player_company_cap_at_over_unlimited", this::testPerPlayerCompanyCap);
        // BS-235 — share integrity
        addTest("share_excess_raised_once_per_level", this::testShareExcessRaisedOnce);
        addTest("share_dividends_paused_until_acknowledge", this::testShareDividendsPaused);
        addTest("share_destroy_lowers_issued", this::testShareDestroyLowersIssued);
        addTest("share_excess_refuses_dividend_schedules", this::testShareExcessRefusesDividendSchedules); // BS-241
        addTest("share_acknowledge_rights_and_suggestions", this::testShareAcknowledgeRights);
        // BS-238 — dividends count every share variant
        addTest("dividend_holdings_sum_all_share_variants", this::testDividendHoldingsSumAllVariants);
    }

    @Nullable
    private BankSystemModSettings settings() {
        BankSystemModBackend.Instances i = BankSystemModBackend.getInstances_forTesting();
        return i == null ? null : i.SERVER_SETTINGS;
    }

    /**
     * Task #58 — company-count gate in {@code createCompany}: at cap rejects, unlimited (-1)
     * and 0 behave correctly. Uses a detached manager (bank-account-count gate skipped) so
     * only the company-count gate is exercised. Restores the live setting afterwards.
     */
    private TestResult testPerPlayerCompanyCap() {
        BankSystemModSettings s = settings();
        if (s == null) return fail("SERVER_SETTINGS null -- cannot run on slave server");
        int original = s.PLAYER.MAX_COMPANIES_PER_PLAYER.get();
        try {
            // cap = 2 -> third company for the same founder is rejected
            s.PLAYER.MAX_COMPANIES_PER_PLAYER.set(2);
            CompanyManager cm = fresh();
            if (cm.createCompany("Cap1", 1, FOUNDER_A, 100L).result != CompanyManager.CreateResult.OK)
                return fail("1st create under cap=2 should be OK");
            if (cm.createCompany("Cap2", 2, FOUNDER_A, 100L).result != CompanyManager.CreateResult.OK)
                return fail("2nd create under cap=2 should be OK");
            CompanyManager.CreateResult third = cm.createCompany("Cap3", 3, FOUNDER_A, 100L).result;
            if (third != CompanyManager.CreateResult.PER_PLAYER_COMPANY_LIMIT)
                return fail("3rd create at cap=2 should be PER_PLAYER_COMPANY_LIMIT, got " + third);
            // A different founder is unaffected by A's count
            if (cm.createCompany("OtherFounder", 4, FOUNDER_B, 100L).result != CompanyManager.CreateResult.OK)
                return fail("different founder should not be blocked by A's cap");

            // cap = 0 -> even the first is rejected
            s.PLAYER.MAX_COMPANIES_PER_PLAYER.set(0);
            CompanyManager cmZero = fresh();
            if (cmZero.createCompany("Zero1", 1, FOUNDER_A, 100L).result != CompanyManager.CreateResult.PER_PLAYER_COMPANY_LIMIT)
                return fail("cap=0 should reject the first create");

            // cap = -1 (unlimited) -> many allowed
            s.PLAYER.MAX_COMPANIES_PER_PLAYER.set(-1);
            CompanyManager cmUnlimited = fresh();
            for (int n = 1; n <= 5; n++) {
                if (cmUnlimited.createCompany("Unl" + n, n, FOUNDER_A, 100L).result != CompanyManager.CreateResult.OK)
                    return fail("unlimited (-1) should allow create #" + n);
            }
            return pass("Per-player company cap enforced at/over/zero/unlimited.");
        } finally {
            s.PLAYER.MAX_COMPANIES_PER_PLAYER.set(original);
        }
    }

    /**
     * A throwaway manager detached from the live bank manager. These tests use
     * synthetic bank account numbers (2, 3, 5, 42, 77, ...) that do not exist in
     * the running world; without detaching, every create but account 1 would be
     * rejected with BANK_ACCOUNT_MISSING.
     */
    private CompanyManager fresh() {
        CompanyManager cm = new CompanyManager();
        cm.detachBankManager();
        return cm;
    }

    // ------------------------------------------------------------------
    private TestResult testCaseInsensitiveNameUniqueness() {
        CompanyManager cm = fresh();
        CompanyManager.CreateOutcome first = cm.createCompany("Acme", 1, FOUNDER_A, 100L);
        if (first.result != CompanyManager.CreateResult.OK) return fail("First create failed: " + first.result);
        CompanyManager.CreateOutcome dup = cm.createCompany("acme", 2, FOUNDER_A, 100L);
        return assertTrue("Case-insensitive collision should be rejected (got " + dup.result + ")",
                dup.result == CompanyManager.CreateResult.NAME_TAKEN);
    }

    private TestResult testMaxSupplyValidation() {
        CompanyManager cm = fresh();
        if (cm.createCompany("Zero", 1, FOUNDER_A, 0L).result != CompanyManager.CreateResult.INVALID_MAX_SUPPLY) {
            return fail("Zero maxSupply must be rejected");
        }
        if (cm.createCompany("Negative", 2, FOUNDER_A, -1L).result != CompanyManager.CreateResult.INVALID_MAX_SUPPLY) {
            return fail("Negative maxSupply must be rejected");
        }
        if (cm.createCompany("TooBig", 3, FOUNDER_A, 1_000_000_001L).result != CompanyManager.CreateResult.INVALID_MAX_SUPPLY) {
            return fail("maxSupply > 1_000_000_000 must be rejected");
        }
        return pass("maxSupply bounds enforced.");
    }

    private TestResult testMonotonicIds() {
        CompanyManager cm = fresh();
        int a = cm.createCompany("A", 1, FOUNDER_A, 100L).company.getCompanyId();
        int b = cm.createCompany("B", 2, FOUNDER_A, 100L).company.getCompanyId();
        int c = cm.createCompany("C", 3, FOUNDER_A, 100L).company.getCompanyId();
        return assertTrue("ids must be monotonic (a=" + a + " b=" + b + " c=" + c + ")",
                a < b && b < c);
    }

    private TestResult testNbtRoundTripPreservesFields() {
        CompanyManager cm = fresh();
        Company original = cm.createCompany("Trip", 42, FOUNDER_A, 12345L).company;
        cm.updateDescription(original.getCompanyId(), "hello world");

        Map<String, ListTag> data = new HashMap<>();
        cm.save(data);

        CompanyManager restored = fresh();
        restored.load(data);
        Company loaded = restored.getById(original.getCompanyId());
        if (loaded == null) return fail("Restored manager missing company id " + original.getCompanyId());
        if (!loaded.getName().equals("Trip")) return fail("name mismatch");
        if (loaded.getBankAccountNr() != 42) return fail("bankAccountNr mismatch");
        if (loaded.getMaxSupply() != 12345L) return fail("maxSupply mismatch");
        if (!"hello world".equals(loaded.getDescription())) return fail("description mismatch");
        if (!loaded.isFounder(FOUNDER_A)) return fail("founder set not preserved");
        if (loaded.getTotalSharesIssued() != 0L) return fail("totalSharesIssued default not preserved");
        if (loaded.getPayoutSchedules().size() != 0) return fail("payoutSchedules default not empty");
        if (loaded.getShareVisuals() == null) return fail("shareVisuals null after load");
        return pass("Company NBT round-trip preserved all fields.");
    }

    private TestResult testNbtLoadTolerantMissingFields() {
        // Craft a minimal Company tag by hand — omit shareVisuals / payoutSchedules.
        CompoundTag tag = new CompoundTag();
        tag.putInt("companyId", 7);
        tag.putString("name", "Minimal");
        tag.putInt("bankAccountNr", 9);
        tag.putLong("maxSupply", 500L);
        tag.putLong("createdAt", 111L);
        Company c = Company.load(tag);
        if (c == null) return fail("Company.load returned null on minimal tag");
        if (c.getShareVisuals() != ShareVisuals.EMPTY) return fail("shareVisuals default must be EMPTY");
        if (!c.getPayoutSchedules().isEmpty()) return fail("payoutSchedules default must be empty");
        if (c.getTotalSharesIssued() != 0L) return fail("totalSharesIssued default must be 0");
        return pass("Company.load tolerated missing optional fields with defaults.");
    }

    private TestResult testLoadDropsDuplicateNameKeepingOlder() {
        // Build a raw NBT map with two entries that collide on name (different createdAt).
        Map<String, ListTag> data = new HashMap<>();
        ListTag meta = new ListTag();
        CompoundTag metaTag = new CompoundTag();
        metaTag.putInt("nextCompanyId", 3);
        meta.add(metaTag);
        data.put("meta", meta);

        ListTag companies = new ListTag();
        CompoundTag older = new CompoundTag();
        older.putInt("companyId", 1);
        older.putString("name", "Dup");
        older.putInt("bankAccountNr", 1);
        older.putLong("maxSupply", 100);
        older.putLong("createdAt", 100L);
        companies.add(older);
        CompoundTag newer = new CompoundTag();
        newer.putInt("companyId", 2);
        newer.putString("name", "dup"); // case-insensitive collision
        newer.putInt("bankAccountNr", 2);
        newer.putLong("maxSupply", 100);
        newer.putLong("createdAt", 200L);
        companies.add(newer);
        data.put("companies", companies);

        CompanyManager cm = fresh();
        cm.load(data);
        if (cm.size() != 1) return fail("Expected 1 company after dup collision, got " + cm.size());
        Company kept = cm.getById(1);
        if (kept == null) return fail("Older company (id=1) should have been kept");
        return pass("Duplicate-name load kept older entry.");
    }

    private TestResult testTransferFounderMovesUuid() {
        CompanyManager cm = fresh();
        Company c = cm.createCompany("Trans", 1, FOUNDER_A, 100L).company;
        CompanyManager.TransferResult r = cm.transferFounder(c.getCompanyId(), FOUNDER_A, FOUNDER_B);
        if (r != CompanyManager.TransferResult.OK) return fail("Transfer failed: " + r);
        if (c.isFounder(FOUNDER_A)) return fail("Old founder should have been removed");
        if (!c.isFounder(FOUNDER_B)) return fail("New founder should have been added");
        return pass("Founder transfer moved UUID correctly.");
    }

    /**
     * BS-251: the founder role only goes to a worker (a user of the company bank account).
     * A non-worker is refused without any change; a worker is accepted and gets MANAGE.
     * Live bank manager; the test account and synthetic users are removed afterwards.
     */
    private TestResult testTransferFounderRequiresWorker() {
        BankSystemModBackend.Instances i = BankSystemModBackend.getInstances_forTesting();
        IServerBankManager bm = i == null || i.SERVER_BANK_MANAGER == null ? null : i.SERVER_BANK_MANAGER.getSync();
        if (bm == null) return fail("no master bank manager");
        UUID worker = UUID.fromString("00000000-0000-0000-0000-0000AAAA0005");
        UUID outsider = UUID.fromString("00000000-0000-0000-0000-0000AAAA0006");
        List<UUID> addedUsers = new ArrayList<>();
        for (UUID u : List.of(FOUNDER_A, worker, outsider)) {
            if (bm.userExists(u)) continue;
            bm.addUser(new User(u, "Bs251User" + u.toString().substring(32)));
            addedUsers.add(u);
        }
        net.kroia.banksystem.api.bankaccount.IServerBankAccount account = bm.createBankAccount("__bs251_transfer");
        try {
            if (account == null) return fail("could not create test account");
            int manage = net.kroia.banksystem.banking.BankPermission.MANAGE.getValue();
            account.addUser(bm.getUserByUUID(FOUNDER_A), manage);
            account.addUser(bm.getUserByUUID(worker), net.kroia.banksystem.banking.BankPermission.DEPOSIT.getValue());
            CompanyManager cm = new CompanyManager(); // not detached: the worker check needs the live account
            Company c = cm.createCompany("Bs251Transfer", account.getAccountNumber(), FOUNDER_A, 100L).company;
            if (c == null) return fail("test company not created");

            CompanyManager.TransferResult r = cm.transferFounder(c.getCompanyId(), FOUNDER_A, outsider);
            if (r != CompanyManager.TransferResult.NOT_A_WORKER) return fail("non-worker target: expected NOT_A_WORKER, got " + r);
            if (!c.isFounder(FOUNDER_A) || c.isFounder(outsider) || account.hasUser(outsider))
                return fail("refused transfer changed founders or account users");

            r = cm.transferFounder(c.getCompanyId(), FOUNDER_A, worker);
            if (r != CompanyManager.TransferResult.OK) return fail("worker target: expected OK, got " + r);
            if (!c.isFounder(worker) || c.isFounder(FOUNDER_A)) return fail("founder not moved to the worker");
            if ((account.getPermission(worker) & manage) == 0) return fail("new founder has no MANAGE on the account");
            return pass("non-worker refused, worker became founder with MANAGE");
        } finally {
            if (account != null) bm.deleteBankAccount(account.getAccountNumber());
            for (UUID u : addedUsers) bm.removeUser(u);
        }
    }

    private TestResult testIsFounderOfAccounts() {
        CompanyManager cm = fresh();
        Company c = cm.createCompany("Query", 77, FOUNDER_A, 100L).company;
        if (!cm.isFounderOf(77, FOUNDER_A)) return fail("Expected isFounderOf(77,A) true");
        if (cm.isFounderOf(77, FOUNDER_B)) return fail("Expected isFounderOf(77,B) false");
        if (cm.isFounderOf(78, FOUNDER_A)) return fail("Expected isFounderOf(78,A) false (no company)");
        return pass("isFounderOf answers correctly for present and absent companies.");
    }

    // ------------------------------------------------------------------
    // Task #43h — name-lookup + rights-scoped enumeration helpers.
    // ------------------------------------------------------------------
    private TestResult testGetByNameCaseInsensitive() {
        CompanyManager cm = fresh();
        Company created = cm.createCompany("Acme", 1, FOUNDER_A, 100L).company;
        if (cm.getByName("Acme") != created) return fail("exact-case lookup failed");
        if (cm.getByName("acme") != created) return fail("lowercase lookup failed");
        if (cm.getByName("ACME") != created) return fail("uppercase lookup failed");
        if (cm.getByName("acMe") != created) return fail("mixed-case lookup failed");
        if (cm.getByName("Other") != null) return fail("unknown name should return null");
        if (cm.getByName(null) != null) return fail("null name should return null");
        return pass("getByName is case-insensitive and null-safe.");
    }

    private TestResult testListAllCompaniesReturnsAll() {
        CompanyManager cm = fresh();
        cm.createCompany("Alpha", 1, FOUNDER_A, 100L);
        cm.createCompany("Beta",  2, FOUNDER_B, 100L);
        cm.createCompany("Gamma", 3, FOUNDER_A, 100L);
        java.util.Set<Company> all = cm.listAllCompanies();
        if (all.size() != 3) return fail("expected 3 companies, got " + all.size());
        return pass("listAllCompanies returned every entry.");
    }

    private TestResult testListCompaniesFounderedByFilters() {
        CompanyManager cm = fresh();
        Company a = cm.createCompany("Alpha", 1, FOUNDER_A, 100L).company;
        Company b = cm.createCompany("Beta",  2, FOUNDER_B, 100L).company;
        Company c = cm.createCompany("Gamma", 3, FOUNDER_A, 100L).company;

        java.util.Set<Company> forA = cm.listCompaniesFounderedBy(FOUNDER_A);
        if (forA.size() != 2 || !forA.contains(a) || !forA.contains(c)) {
            return fail("FOUNDER_A should see Alpha+Gamma, got " + forA);
        }
        java.util.Set<Company> forB = cm.listCompaniesFounderedBy(FOUNDER_B);
        if (forB.size() != 1 || !forB.contains(b)) {
            return fail("FOUNDER_B should see only Beta, got " + forB);
        }
        if (!cm.listCompaniesFounderedBy(null).isEmpty()) {
            return fail("null UUID should return empty set");
        }
        return pass("listCompaniesFounderedBy filtered correctly.");
    }

    private TestResult testListCompaniesManagedByFallsBackToFounder() {
        // In the test harness bank manager is null, so listCompaniesManagedBy falls
        // back to the founder membership check for every entry.
        CompanyManager cm = fresh();
        Company a = cm.createCompany("Alpha", 1, FOUNDER_A, 100L).company;
        cm.createCompany("Beta",  2, FOUNDER_B, 100L);
        java.util.Set<Company> forA = cm.listCompaniesManagedBy(FOUNDER_A);
        if (forA.size() != 1 || !forA.contains(a)) {
            return fail("FOUNDER_A should see only Alpha via founder fallback, got " + forA);
        }
        return pass("listCompaniesManagedBy falls back to founder set when bank manager is null.");
    }

    private TestResult testDeleteCompanyRemovesFromIndices() {
        CompanyManager cm = fresh();
        Company c = cm.createCompany("Del", 5, FOUNDER_A, 100L).company;
        if (!cm.deleteCompany(c.getCompanyId())) return fail("deleteCompany returned false");
        if (cm.getById(c.getCompanyId()) != null) return fail("byId not cleared");
        if (cm.getByBankAccount(5) != null) return fail("byBankAccount not cleared");
        if (cm.getByName("Del") != null) return fail("byNameLower not cleared");
        if (cm.isNameTaken("del")) return fail("isNameTaken still true after delete");
        return pass("deleteCompany cleared all indices.");
    }

    // ------------------------------------------------------------------
    // BS-235 — share integrity (pure state transitions; no flags, no DB)
    // ------------------------------------------------------------------

    /** Company id far above live ones, so supply broadcasts of these tests touch no real company. */
    private static final int SHARE_TEST_ID = 900_001;

    /** Fresh manager holding one company with {@code issued} stamped shares and no bank account. */
    private CompanyManager freshWithIssued(long issued) {
        CompanyManager cm = fresh();
        Map<String, ListTag> data = new HashMap<>();
        ListTag meta = new ListTag();
        CompoundTag metaTag = new CompoundTag();
        metaTag.putInt("nextCompanyId", SHARE_TEST_ID + 1);
        meta.add(metaTag);
        data.put("meta", meta);
        ListTag companies = new ListTag();
        CompoundTag c = new CompoundTag();
        c.putInt("companyId", SHARE_TEST_ID);
        c.putString("name", "ShareIntegrityTest");
        c.putInt("bankAccountNr", 987_654_321);
        c.putLong("maxSupply", 1000);
        c.putLong("createdAt", 1L);
        c.putLong("totalSharesIssued", issued);
        companies.add(c);
        data.put("companies", companies);
        cm.load(data);
        return cm;
    }

    private TestResult testShareExcessRaisedOnce() {
        CompanyManager cm = freshWithIssued(10);
        Company c = cm.getById(SHARE_TEST_ID);
        if (c == null) return fail("test company did not load");
        if (ShareIntegrity.apply(cm, c, 10, 100L) || c.isShareExcessOpen())
            return fail("banked == stamped must not raise");
        if (c.getShareConsistentTs() != 100L) return fail("consistent time not stored at equality");
        if (!ShareIntegrity.apply(cm, c, 12, 200L) || !c.isShareExcessOpen() || c.getShareExcessLevel() != 2)
            return fail("excess 2 must raise once and open the alert");
        if (ShareIntegrity.apply(cm, c, 12, 300L)) return fail("same excess raised twice");
        if (!ShareIntegrity.apply(cm, c, 13, 400L) || c.getShareExcessLevel() != 3)
            return fail("growing excess must raise again");
        if (c.getShareConsistentTs() != 100L) return fail("consistent time moved while in excess");
        if (ShareIntegrity.acknowledge(cm, c, 13, 500L) != 3 || c.isShareExcessOpen())
            return fail("acknowledge must close the alert at excess 3");
        if (ShareIntegrity.apply(cm, c, 13, 600L)) return fail("acknowledged excess raised again");
        if (!ShareIntegrity.apply(cm, c, 14, 700L)) return fail("excess past the acknowledged level must raise");
        if (ShareIntegrity.apply(cm, c, 9, 800L) || c.isShareExcessOpen() || c.getShareExcessLevel() != 0)
            return fail("back under stamped must close and reset");
        return pass("raised at 2 and 3, not repeated, acknowledged at 3, reset when consistent");
    }

    private TestResult testShareDividendsPaused() {
        BankSystemModBackend.Instances i = BankSystemModBackend.getInstances_forTesting();
        ISyncServerBankManager bm = i == null || i.SERVER_BANK_MANAGER == null ? null : i.SERVER_BANK_MANAGER.getSync();
        if (bm == null) return fail("no master bank manager");
        CompanyManager cm = freshWithIssued(5);
        Company c = cm.getById(SHARE_TEST_ID);
        if (c == null) return fail("test company did not load");
        PayoutSchedule dividend = new PayoutSchedule(1L, FOUNDER_A, 100L, 1000L, 0L, false, 1L, FOUNDER_A,
                PayoutSchedule.NO_TARGET_ACCOUNT, "", "", PayoutSchedule.Mode.DIVIDEND, PayoutSchedule.MONEY_CURRENCY, 0L, 0);
        PayoutSchedule fixed = new PayoutSchedule(2L, FOUNDER_A, 100L, 1000L, 0L, false, 1L, FOUNDER_A);
        ShareIntegrity.apply(cm, c, 7, 100L);
        List<TransactionLogRecord> rows = new ArrayList<>();
        PayoutExecutor.Outcome paused = PayoutExecutor.executeOne(c, dividend, 100L, bm, 200L, rows);
        if (paused.reason != PayoutFailureReason.SHARES_UNDER_REVIEW)
            return fail("open excess: dividend reason " + paused.reason);
        if (paused.reason.toStatus() != PayoutHistoryRecord.Status.PAUSED) return fail("history status not PAUSED");
        if (PayoutExecutor.executeOne(c, fixed, 100L, bm, 200L, rows).reason == PayoutFailureReason.SHARES_UNDER_REVIEW)
            return fail("fixed payout must not be paused");
        ShareIntegrity.acknowledge(cm, c, 7, 300L);
        PayoutExecutor.Outcome after = PayoutExecutor.executeOne(c, dividend, 100L, bm, 400L, rows);
        if (after.reason == PayoutFailureReason.SHARES_UNDER_REVIEW) return fail("still paused after acknowledge");
        if (!rows.isEmpty()) return fail("test moved value: " + rows.size() + " ledger rows");
        return pass("dividend paused while open, fixed payout not, resumed after acknowledge (" + after.reason + ")");
    }

    private TestResult testShareExcessRefusesDividendSchedules() {
        CompanyManager cm = freshWithIssued(5);
        Company c = cm.getById(SHARE_TEST_ID);
        if (c == null) return fail("test company did not load");
        byte dividend = (byte) PayoutSchedule.Mode.DIVIDEND.ordinal(), fixed = (byte) PayoutSchedule.Mode.FIXED_PAYOUT.ordinal();
        if (net.kroia.banksystem.banking.company.AsyncCompanyManager.dividendUnderReview(SHARE_TEST_ID, dividend, cm))
            return fail("refused without an open excess");
        ShareIntegrity.apply(cm, c, 7, 100L);
        if (!net.kroia.banksystem.banking.company.AsyncCompanyManager.dividendUnderReview(SHARE_TEST_ID, dividend, cm))
            return fail("dividend schedule allowed while the excess is open");
        if (net.kroia.banksystem.banking.company.AsyncCompanyManager.dividendUnderReview(SHARE_TEST_ID, fixed, cm))
            return fail("fixed payout refused");
        ShareIntegrity.acknowledge(cm, c, 7, 200L);
        if (net.kroia.banksystem.banking.company.AsyncCompanyManager.dividendUnderReview(SHARE_TEST_ID, dividend, cm))
            return fail("still refused after acknowledge");
        return pass("dividend schedules refused only while the excess is open; fixed payouts allowed");
    }

    private TestResult testShareDestroyLowersIssued() {
        CompanyManager cm = freshWithIssued(10);
        if (!cm.destroyShares(SHARE_TEST_ID, 3)) return fail("destroyShares refused");
        Company c = cm.getById(SHARE_TEST_ID);
        if (c == null || c.getTotalSharesIssued() != 7) return fail("issued not 7 after destroying 3");
        cm.destroyShares(SHARE_TEST_ID, 50);
        if (c.getTotalSharesIssued() != 0) return fail("issued not floored at 0: " + c.getTotalSharesIssued());
        return pass("10 - 3 = 7, then floored at 0");
    }

    /**
     * BS-235: /company shares acknowledge uses the company admin gate (MANAGE, founder or BankSystem
     * admin) on the master, and its suggestions list only administered companies (all for an admin
     * not coming through an untrusted slave). Synthetic admin user, removed afterwards.
     */
    private TestResult testShareAcknowledgeRights() {
        BankSystemModBackend.Instances i = BankSystemModBackend.getInstances_forTesting();
        IServerBankManager bm = i == null || i.SERVER_BANK_MANAGER == null ? null : i.SERVER_BANK_MANAGER.getSync();
        if (bm == null) return fail("no master bank manager");
        CompanyManager cm = fresh();
        Company alpha = cm.createCompany("ShareAckAlpha", 987_654_321, FOUNDER_A, 100L).company;
        Company beta = cm.createCompany("ShareAckBeta", 987_654_322, FOUNDER_B, 100L).company;
        if (alpha == null || beta == null) return fail("test companies not created");
        UUID outsider = UUID.fromString("00000000-0000-0000-0000-0000AAAA0003");
        UUID admin = UUID.fromString("00000000-0000-0000-0000-0000AAAA0004");
        boolean added = !bm.userExists(admin);
        if (added) bm.addUser(new User(admin, "ShareAckTestAdmin"));
        try {
            bm.setBanksystemAdminMode(admin, true);
            int id = alpha.getCompanyId();
            if (AsyncCompanyManager.gateManage(id, FOUNDER_A, bm, cm) != AsyncCompanyManager.CODE_OK)
                return fail("company admin (founder) refused");
            if (AsyncCompanyManager.gateManage(id, outsider, bm, cm) != AsyncCompanyManager.CODE_NO_PERMISSION)
                return fail("unrelated player allowed");
            if (AsyncCompanyManager.gateManage(id, FOUNDER_B, bm, cm) != AsyncCompanyManager.CODE_NO_PERMISSION)
                return fail("admin of another company allowed");
            if (AsyncCompanyManager.gateManage(id, admin, bm, cm) != AsyncCompanyManager.CODE_OK)
                return fail("BankSystem admin refused");
            if (!cm.listCompaniesAdministeredBy(FOUNDER_A, false).equals(Set.of(alpha)))
                return fail("company admin suggestions: " + cm.listCompaniesAdministeredBy(FOUNDER_A, false));
            if (!cm.listCompaniesAdministeredBy(outsider, false).isEmpty())
                return fail("unrelated player got suggestions");
            if (!cm.listCompaniesAdministeredBy(admin, true).equals(Set.of(alpha, beta)))
                return fail("BankSystem admin did not get every company");
            if (!cm.listCompaniesAdministeredBy(admin, false).isEmpty())
                return fail("admin flag counted without trust");
            return pass("founder + BS admin allowed, outsider + other founder refused; suggestions filtered");
        } finally {
            if (added) bm.removeUser(admin);
            else bm.setBanksystemAdminMode(admin, false);
        }
    }

    /**
     * BS-238: the per-account holdings both dividend paths use count every share ItemID of the
     * company (an anvil-renamed share is its own ItemID), free + locked. Account A holds only the
     * renamed variant and must still be counted. Registry and item filters are restored afterwards.
     */
    private TestResult testDividendHoldingsSumAllVariants() {
        BankSystemModBackend.Instances i = BankSystemModBackend.getInstances_forTesting();
        ISyncServerBankManager bm = i == null || i.SERVER_BANK_MANAGER == null ? null : i.SERVER_BANK_MANAGER.getSync();
        if (bm == null) return fail("no master bank manager");
        if (net.kroia.banksystem.minecraft.item.BankSystemItems.STAMPED_SHARE.get() == null)
            return fail("STAMPED_SHARE not registered");
        final int companyId = 900_238;
        net.kroia.banksystem.testing.LiveStateGuard guard = net.kroia.banksystem.testing.LiveStateGuard.capture(bm);
        List<Integer> accounts = new ArrayList<>();
        try {
            net.minecraft.world.item.ItemStack base = net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem
                    .ofCompany(net.kroia.banksystem.minecraft.item.BankSystemItems.STAMPED_SHARE.get(), companyId);
            net.minecraft.world.item.ItemStack renamed = base.copy();
            renamed.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    net.minecraft.network.chat.Component.literal("Renamed share"));
            net.kroia.banksystem.util.ItemID baseId = net.kroia.banksystem.util.ItemIDManager.registerItemStackServerSide_direct(base);
            net.kroia.banksystem.util.ItemID variantId = net.kroia.banksystem.util.ItemIDManager.registerItemStackServerSide_direct(renamed);
            if (!baseId.isValid() || !variantId.isValid() || baseId.equals(variantId))
                return fail("renamed share did not get its own ItemID: " + baseId + " / " + variantId);
            List<net.kroia.banksystem.util.ItemID> ids = net.kroia.banksystem.banking.company.CompanyStatsQuery.shareItemIds(companyId);
            if (!ids.contains(baseId) || !ids.contains(variantId)) return fail("shareItemIds misses a variant: " + ids);
            bm.allowItemID(baseId);
            bm.allowItemID(variantId);

            net.kroia.banksystem.api.bankaccount.IServerBankAccount a = bm.createBankAccount("__bs238_variant_only");
            net.kroia.banksystem.api.bankaccount.IServerBankAccount b = bm.createBankAccount("__bs238_mixed");
            if (a == null || b == null) return fail("could not create test accounts");
            accounts.add(a.getAccountNumber());
            accounts.add(b.getAccountNumber());
            if (a.createBank(variantId, 300L) == null || b.createBank(baseId, 200L) == null)
                return fail("could not create share banks");
            net.kroia.banksystem.api.bank.IServerBank bVariant = b.createBank(variantId, 100L);
            if (bVariant == null) return fail("could not create variant bank on mixed account");
            bVariant.lockAmount(40L); // locked shares still earn dividends

            Map<Integer, Long> held = net.kroia.banksystem.banking.company.CompanyStatsQuery.sharesRawPerAccount(bm, companyId);
            long heldA = held.getOrDefault(a.getAccountNumber(), 0L);
            long heldB = held.getOrDefault(b.getAccountNumber(), 0L);
            if (heldA != 300L) return fail("variant-only account counted " + heldA + ", expected 300");
            if (heldB != 300L) return fail("mixed account counted " + heldB + ", expected 300 (200 base + 100 variant incl. 40 locked)");
            return pass("variant-only account 300, mixed account 300 (base + variant, locked included)");
        } finally {
            for (int nr : accounts) {
                net.kroia.banksystem.api.bankaccount.IServerBankAccount acct = bm.getBankAccount(nr);
                if (acct != null) {
                    for (net.kroia.banksystem.util.ItemID id : net.kroia.banksystem.banking.company.CompanyStatsQuery.shareItemIds(companyId)) {
                        net.kroia.banksystem.api.bank.IServerBank bank = acct.getBank(id);
                        if (bank != null) { bank.unlockAll(); bank.setBalance(0L); }
                    }
                }
                bm.deleteBankAccount(nr);
            }
            net.kroia.banksystem.testing.LiveStateGuard.restore(guard);
        }
    }
}
