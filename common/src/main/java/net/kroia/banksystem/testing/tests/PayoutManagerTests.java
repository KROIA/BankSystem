package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.banking.company.Company;
import net.kroia.banksystem.banking.company.CompanyManager;
import net.kroia.banksystem.banking.company.PayoutSchedule;
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
 * Task #45 (v2.1.0) — Company + CompanyManager payout schedule persistence, mutation,
 * cascade-strip, and interval-floor validation. All tests use fresh in-memory managers.
 */
public class PayoutManagerTests extends TestSuite {

    private static final UUID CALLER  = UUID.fromString("00000000-0000-0000-0000-0000CAFE0001");
    private static final UUID WORKER1 = UUID.fromString("00000000-0000-0000-0000-0000CAFE0002");
    private static final UUID WORKER2 = UUID.fromString("00000000-0000-0000-0000-0000CAFE0003");

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.COMPANY;
    }

    @Override
    public void registerTests() {
        addTest("schedule_nbt_round_trip", this::testScheduleNbtRoundTrip);
        addTest("schedule_ids_monotonic", this::testScheduleIdsMonotonic);
        addTest("create_enforces_min_interval", this::testCreateEnforcesMinInterval);
        addTest("update_enforces_min_interval", this::testUpdateEnforcesMinInterval);
        addTest("update_amount_must_be_positive", this::testUpdateAmountMustBePositive);
        addTest("pause_toggles_flag", this::testPauseTogglesFlag);
        addTest("delete_removes_schedule", this::testDeleteRemovesSchedule);
        addTest("cascade_strip_removes_target_schedules", this::testCascadeStripRemovesTargetSchedules);
        addTest("cascade_strip_leaves_others_alone", this::testCascadeStripLeavesOthersAlone);
        addTest("advance_updates_next_run", this::testAdvanceUpdatesNextRun);
        addTest("schedule_immutable_copy_with", this::testScheduleImmutableCopyWith);
    }

    /** Detached from the live bank manager — see CompanyManagerTests#fresh(). */
    private CompanyManager fresh() {
        CompanyManager cm = new CompanyManager();
        cm.detachBankManager();
        return cm;
    }

    private Company make(CompanyManager cm, String name, int account) {
        return cm.createCompany(name, account, CALLER, 100L).company;
    }

    private TestResult testScheduleNbtRoundTrip() {
        CompanyManager cm = fresh();
        Company c = make(cm, "Acme", 1);
        cm.createSchedule(c.getCompanyId(), WORKER1, 500L, 60L, 0L, CALLER);
        cm.createSchedule(c.getCompanyId(), WORKER2, 800L, 100L, 0L, CALLER);
        Map<String, ListTag> data = new HashMap<>();
        cm.save(data);

        CompanyManager restored = fresh();
        restored.load(data);
        Company loaded = restored.getById(c.getCompanyId());
        if (loaded == null) return fail("Company missing after reload");
        if (loaded.getPayoutSchedules().size() != 2) return fail("expected 2 schedules, got " + loaded.getPayoutSchedules().size());
        boolean found1 = false, found2 = false;
        for (PayoutSchedule s : loaded.getPayoutSchedules()) {
            if (WORKER1.equals(s.getTargetUUID()) && s.getAmount() == 500L && s.getIntervalTicks() == 60L) found1 = true;
            if (WORKER2.equals(s.getTargetUUID()) && s.getAmount() == 800L && s.getIntervalTicks() == 100L) found2 = true;
        }
        if (!found1 || !found2) return fail("schedules did not round-trip through NBT");
        return pass("Schedule NBT round-trip preserved every field.");
    }

    private TestResult testScheduleIdsMonotonic() {
        CompanyManager cm = fresh();
        Company c = make(cm, "A", 1);
        long id1 = cm.createSchedule(c.getCompanyId(), WORKER1, 100L, 20L, 0L, CALLER).schedule.getScheduleId();
        long id2 = cm.createSchedule(c.getCompanyId(), WORKER2, 100L, 20L, 0L, CALLER).schedule.getScheduleId();
        return assertTrue("schedule ids should be monotonic (id1=" + id1 + " id2=" + id2 + ")", id2 > id1);
    }

    private TestResult testCreateEnforcesMinInterval() {
        CompanyManager cm = fresh();
        Company c = make(cm, "Floor", 1);
        CompanyManager.ScheduleCreateOutcome tooFast =
                cm.createSchedule(c.getCompanyId(), WORKER1, 100L, 19L, 0L, CALLER);
        if (tooFast.result != CompanyManager.PayoutMutation.INVALID_INPUT) {
            return fail("intervalTicks=19 must be rejected (got " + tooFast.result + ")");
        }
        CompanyManager.ScheduleCreateOutcome floor =
                cm.createSchedule(c.getCompanyId(), WORKER1, 100L, 20L, 0L, CALLER);
        return assertTrue("intervalTicks=20 (hard-floor) must be accepted (got " + floor.result + ")",
                floor.result == CompanyManager.PayoutMutation.OK);
    }

    private TestResult testUpdateEnforcesMinInterval() {
        CompanyManager cm = fresh();
        Company c = make(cm, "UpdFloor", 1);
        long id = cm.createSchedule(c.getCompanyId(), WORKER1, 100L, 40L, 0L, CALLER).schedule.getScheduleId();
        CompanyManager.PayoutMutation r = cm.updateSchedule(c.getCompanyId(), id, 200L, 10L);
        return assertTrue("update below min interval must be rejected (got " + r + ")",
                r == CompanyManager.PayoutMutation.INVALID_INPUT);
    }

    private TestResult testUpdateAmountMustBePositive() {
        CompanyManager cm = fresh();
        Company c = make(cm, "AmtPos", 1);
        long id = cm.createSchedule(c.getCompanyId(), WORKER1, 100L, 40L, 0L, CALLER).schedule.getScheduleId();
        CompanyManager.PayoutMutation r = cm.updateSchedule(c.getCompanyId(), id, 0L, 40L);
        return assertTrue("update amount=0 must be rejected (got " + r + ")",
                r == CompanyManager.PayoutMutation.INVALID_INPUT);
    }

    private TestResult testPauseTogglesFlag() {
        CompanyManager cm = fresh();
        Company c = make(cm, "Pause", 1);
        long id = cm.createSchedule(c.getCompanyId(), WORKER1, 100L, 40L, 0L, CALLER).schedule.getScheduleId();
        if (c.findSchedule(id).isPaused()) return fail("schedule should not start paused");
        cm.pauseSchedule(c.getCompanyId(), id, true);
        if (!c.findSchedule(id).isPaused()) return fail("pause=true did not stick");
        cm.pauseSchedule(c.getCompanyId(), id, false);
        if (c.findSchedule(id).isPaused()) return fail("pause=false did not stick");
        return pass("pause toggle works.");
    }

    private TestResult testDeleteRemovesSchedule() {
        CompanyManager cm = fresh();
        Company c = make(cm, "Del", 1);
        long id = cm.createSchedule(c.getCompanyId(), WORKER1, 100L, 40L, 0L, CALLER).schedule.getScheduleId();
        if (cm.deleteSchedule(c.getCompanyId(), id) != CompanyManager.PayoutMutation.OK) return fail("delete failed");
        if (c.findSchedule(id) != null) return fail("schedule still present after delete");
        if (cm.deleteSchedule(c.getCompanyId(), id) != CompanyManager.PayoutMutation.SCHEDULE_MISSING) {
            return fail("second delete should report SCHEDULE_MISSING");
        }
        return pass("delete removes schedule and is idempotent.");
    }

    private TestResult testCascadeStripRemovesTargetSchedules() {
        CompanyManager cm = fresh();
        Company c = make(cm, "Cascade", 5);
        cm.createSchedule(c.getCompanyId(), WORKER1, 100L, 40L, 0L, CALLER);
        cm.createSchedule(c.getCompanyId(), WORKER1, 200L, 40L, 0L, CALLER);
        cm.createSchedule(c.getCompanyId(), WORKER2, 300L, 40L, 0L, CALLER);
        int removed = cm.cascadeStripPayoutsForRemovedUser(5, WORKER1);
        if (removed != 2) return fail("expected 2 schedules stripped, got " + removed);
        if (c.getPayoutSchedules().size() != 1) return fail("expected 1 remaining schedule");
        if (!WORKER2.equals(c.getPayoutSchedules().get(0).getTargetUUID())) return fail("wrong survivor");
        return pass("cascade-strip removed WORKER1 schedules only.");
    }

    private TestResult testCascadeStripLeavesOthersAlone() {
        CompanyManager cm = fresh();
        Company c = make(cm, "Untouched", 5);
        cm.createSchedule(c.getCompanyId(), WORKER1, 100L, 40L, 0L, CALLER);
        // Wrong account number — no-op.
        int removed = cm.cascadeStripPayoutsForRemovedUser(999, WORKER1);
        if (removed != 0) return fail("expected 0 for wrong account, got " + removed);
        if (c.getPayoutSchedules().size() != 1) return fail("schedules were touched incorrectly");
        return pass("cascade-strip is scoped to the account under mutation.");
    }

    private TestResult testAdvanceUpdatesNextRun() {
        CompanyManager cm = fresh();
        Company c = make(cm, "Adv", 1);
        PayoutSchedule s = cm.createSchedule(c.getCompanyId(), WORKER1, 100L, 40L, 0L, CALLER).schedule;
        cm.advanceSchedule(c.getCompanyId(), s.getScheduleId(), 1234L);
        PayoutSchedule updated = c.findSchedule(s.getScheduleId());
        return assertTrue("advanceSchedule must swap nextRunTick (got " + updated.getNextRunTick() + ")",
                updated.getNextRunTick() == 1234L);
    }

    private TestResult testScheduleImmutableCopyWith() {
        PayoutSchedule s = new PayoutSchedule(1L, WORKER1, 500L, 40L, 100L, false, 0L, CALLER);
        PayoutSchedule paused = s.withPaused(true);
        if (s == paused) return fail("withPaused must return a new instance");
        if (s.isPaused()) return fail("original mutated");
        if (!paused.isPaused()) return fail("copy did not flip flag");
        PayoutSchedule advanced = s.withNextRunTick(999L);
        if (advanced.getNextRunTick() != 999L) return fail("withNextRunTick did not update");
        if (s.getNextRunTick() != 100L) return fail("original nextRunTick mutated");
        PayoutSchedule tweaked = s.withAmountAndInterval(700L, 60L);
        if (tweaked.getAmount() != 700L || tweaked.getIntervalTicks() != 60L) {
            return fail("withAmountAndInterval did not apply");
        }
        return pass("PayoutSchedule copy-with helpers do not mutate the original.");
    }
}
