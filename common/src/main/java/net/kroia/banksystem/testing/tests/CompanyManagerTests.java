package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.banking.company.Company;
import net.kroia.banksystem.banking.company.CompanyManager;
import net.kroia.banksystem.banking.company.ShareVisuals;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Task #43 (v2.0.8) Phase 1 — {@link CompanyManager} persistence + validation tests.
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
        addTest("is_founder_of_accounts", this::testIsFounderOfAccounts);
        addTest("delete_company_removes_from_indices", this::testDeleteCompanyRemovesFromIndices);
        addTest("get_by_name_case_insensitive", this::testGetByNameCaseInsensitive);
        addTest("list_all_companies_returns_all", this::testListAllCompaniesReturnsAll);
        addTest("list_companies_foundered_by_filters", this::testListCompaniesFounderedByFilters);
        addTest("list_companies_managed_by_falls_back_to_founder", this::testListCompaniesManagedByFallsBackToFounder);
    }

    private CompanyManager fresh() {
        return new CompanyManager();
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
}
