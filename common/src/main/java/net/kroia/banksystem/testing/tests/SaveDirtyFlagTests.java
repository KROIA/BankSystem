package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.currency.ExternalAccountRef;
import net.kroia.banksystem.banking.bankmanager.ServerBankManager;
import net.kroia.banksystem.banking.binding.BankAccountBindings;
import net.kroia.banksystem.banking.company.Company;
import net.kroia.banksystem.banking.company.CompanyManager;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.BankSystemDataHandler;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.nbt.ListTag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Task #55 (v2.1.1) — persistence dirty-flag contract tests.
 * <p>
 * These guard the invariant that makes the timer-gated save safe: every mutation of a save
 * unit's persisted state marks it dirty (a missed mark = silent data loss on the timer path),
 * a freshly LOADED unit is clean (so an idle world is not rewritten every interval), and the
 * flag resets after a write. World-free — {@link CompanyManager} is exercised with a detached
 * throwaway instance and {@link ItemIDManager}'s flag is a static primitive.
 */
public class SaveDirtyFlagTests extends TestSuite {

    private static final UUID PLAYER_A = UUID.fromString("00000000-0000-0000-0000-0000DDDD0001");
    private static final UUID PLAYER_B = UUID.fromString("00000000-0000-0000-0000-0000DDDD0002");

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.DATA_PERSISTENCE;
    }

    @Override
    public void registerTests() {
        addTest("company_fresh_manager_is_clean", this::testCompanyFreshIsClean);
        addTest("company_create_marks_dirty", this::testCompanyCreateMarksDirty);
        addTest("company_clear_resets_flag", this::testCompanyClearResets);
        addTest("company_each_mutation_marks_dirty", this::testCompanyMutationsMarkDirty);
        addTest("company_load_leaves_clean", this::testCompanyLoadLeavesClean);
        addTest("itemid_flag_mechanics", this::testItemIdFlagMechanics);
        addTest("bank_write_failure_keeps_dirty", this::testBankWriteFailureKeepsDirty);
        addTest("bindings_write_failure_keeps_changes", this::testBindingsWriteFailureKeepsChanges);
    }

    private CompanyManager fresh() {
        CompanyManager cm = new CompanyManager();
        cm.detachBankManager();
        return cm;
    }

    private TestResult testCompanyFreshIsClean() {
        return assertTrue("A freshly constructed CompanyManager must be clean (nothing to save)",
                !fresh().isPersistDirty());
    }

    private TestResult testCompanyCreateMarksDirty() {
        CompanyManager cm = fresh();
        cm.createCompany("Acme", 1, PLAYER_A, 100L);
        return assertTrue("createCompany must mark the Companies save unit dirty", cm.isPersistDirty());
    }

    private TestResult testCompanyClearResets() {
        CompanyManager cm = fresh();
        cm.createCompany("Acme", 1, PLAYER_A, 100L);
        cm.clearPersistDirty();
        return assertTrue("clearPersistDirty must reset the flag (post-write state)",
                !cm.isPersistDirty());
    }

    private TestResult testCompanyMutationsMarkDirty() {
        CompanyManager cm = fresh();
        Company c = cm.createCompany("Acme", 1, PLAYER_A, 1000L).company;
        if (c == null) return fail("setup: createCompany returned no company");
        int id = c.getCompanyId();

        // Each mutating API, checked in isolation from a clean baseline.
        cm.clearPersistDirty();
        cm.updateDescription(id, "desc");
        if (!cm.isPersistDirty()) return fail("updateDescription did not mark dirty");

        cm.clearPersistDirty();
        cm.updateCompanyCurrency(id, (short) 3);
        if (!cm.isPersistDirty()) return fail("updateCompanyCurrency did not mark dirty");

        cm.clearPersistDirty();
        CompanyManager.ScheduleCreateOutcome sc =
                cm.createSchedule(id, PLAYER_B, 100L, CompanyManager.MIN_INTERVAL_TICKS, 0L, PLAYER_A);
        if (sc.result != CompanyManager.PayoutMutation.OK)
            return fail("setup: createSchedule failed: " + sc.result);
        if (!cm.isPersistDirty()) return fail("createSchedule did not mark dirty");

        cm.clearPersistDirty();
        cm.transferFounder(id, PLAYER_A, PLAYER_B);
        if (!cm.isPersistDirty()) return fail("transferFounder did not mark dirty");

        cm.clearPersistDirty();
        cm.deleteCompany(id);
        if (!cm.isPersistDirty()) return fail("deleteCompany did not mark dirty");

        return pass("Every CompanyManager mutation marked the save unit dirty.");
    }

    private TestResult testCompanyLoadLeavesClean() {
        CompanyManager src = fresh();
        Company c = src.createCompany("Trip", 42, PLAYER_A, 12345L).company;
        src.updateDescription(c.getCompanyId(), "hello");
        Map<String, ListTag> data = new HashMap<>();
        src.save(data);

        CompanyManager restored = fresh();
        restored.load(data);
        if (restored.isPersistDirty())
            return fail("A freshly LOADED CompanyManager must be clean so the timer save skips it");

        // And a mutation after load must still mark dirty.
        restored.updateDescription(c.getCompanyId(), "changed");
        return assertTrue("A mutation after load must mark the loaded unit dirty",
                restored.isPersistDirty());
    }

    private TestResult testItemIdFlagMechanics() {
        // Static flag — snapshot and restore so we do not perturb a live world's dirty state.
        boolean prev = ItemIDManager.isPersistDirty();
        try {
            ItemIDManager.clearPersistDirty();
            if (ItemIDManager.isPersistDirty()) return fail("clearPersistDirty did not clear");
            ItemIDManager.markPersistDirty();
            if (!ItemIDManager.isPersistDirty()) return fail("markPersistDirty did not set");
            ItemIDManager.clearPersistDirty();
            if (ItemIDManager.isPersistDirty()) return fail("clearPersistDirty did not reset after mark");
            return pass("ItemIDManager dirty-flag mark/clear mechanics hold.");
        } finally {
            if (prev) ItemIDManager.markPersistDirty();
        }
    }

    /**
     * Issue #80: a failed Bank_data write must return false and keep the bank dirty, so the
     * next timer save retries it. Real failure through the live handler: the save folder is
     * pointed at a regular file, so creating Bank_data/ under it fails (nothing real is touched).
     */
    private TestResult testBankWriteFailureKeepsDirty() {
        BankSystemModBackend.Instances inst = BankSystemModBackend.getInstances_forTesting();
        if (inst == null || inst.isSlaveServer || inst.SERVER_DATA_HANDLER == null || inst.SERVER_BANK_MANAGER == null
                || !(inst.SERVER_BANK_MANAGER.getSync() instanceof ServerBankManager bm))
            return skip("needs a master server with a bank manager");
        BankSystemDataHandler handler = inst.SERVER_DATA_HANDLER;

        // Control: a normal forced save succeeds and clears the flag (also proves the unit is saveable).
        bm.markPersistDirty();
        if (!handler.save_bank(true))
            return fail("setup: a normal bank save failed");
        if (bm.isPersistDirty())
            return fail("setup: a successful bank save did not clear the dirty flag");

        return withFailingDisk(handler, () -> {
            bm.markPersistDirty();
            TestResult r = assertFalse("save_bank must report a failed disk write", handler.save_bank(true));
            if (!r.passed()) return r;
            return assertTrue("a failed disk write must keep the bank dirty for the next save", bm.isPersistDirty());
        });
    }

    /**
     * Issue #80, bindings file (locked/dust balances of external-currency bindings): a failed
     * write must return false and keep the changes pending. A throwaway table is swapped in, so
     * the real bindings are never touched.
     */
    private TestResult testBindingsWriteFailureKeepsChanges() {
        BankSystemModBackend.Instances inst = BankSystemModBackend.getInstances_forTesting();
        if (inst == null || inst.isSlaveServer || inst.SERVER_DATA_HANDLER == null || inst.BANK_ACCOUNT_BINDINGS == null)
            return skip("needs a master server with the bindings table");
        BankSystemDataHandler handler = inst.SERVER_DATA_HANDLER;

        // Control on the real table: a normal forced save succeeds (also proves the unit is saveable).
        if (!handler.save_bankAccountBindings(true))
            return fail("setup: a normal bindings save failed");

        BankAccountBindings real = inst.BANK_ACCOUNT_BINDINGS;
        BankAccountBindings scratch = new BankAccountBindings();
        try {
            inst.BANK_ACCOUNT_BINDINGS = scratch;
            scratch.bind(-80, new ItemID((short) 1),
                    new ExternalAccountRef("banksystem_test", "issue80", "Issue 80", false));
            return withFailingDisk(handler, () -> {
                TestResult r = assertFalse("save_bankAccountBindings must report a failed disk write",
                        handler.save_bankAccountBindings(true));
                if (!r.passed()) return r;
                return assertTrue("a failed disk write must keep the binding changes pending", scratch.hasChanges());
            });
        } finally {
            inst.BANK_ACCOUNT_BINDINGS = real;
        }
    }

    /**
     * Runs {@code body} while the handler's save folder points at a regular file, so every write
     * under it fails like a full or read-only disk. Nothing real is written; the path is restored.
     */
    private TestResult withFailingDisk(BankSystemDataHandler handler, Supplier<TestResult> body) {
        Path savedRelative = handler.getRelativeSavePath();
        Path blocker = null;
        try {
            blocker = Files.createTempFile("banksystem_savefail", ".tmp");
            handler.setRelativeSavePath(blocker); // absolute: getAbsoluteSavePath() resolves to it as-is
            return body.get();
        } catch (IOException e) {
            return fail("setup: could not create the blocking file: " + e);
        } finally {
            handler.setRelativeSavePath(savedRelative);
            if (blocker != null) {
                try {
                    Files.deleteIfExists(blocker);
                } catch (IOException ignored) {
                }
            }
        }
    }
}
