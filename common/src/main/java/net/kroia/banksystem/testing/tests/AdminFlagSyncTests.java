package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.AdminFlagSync;
import net.kroia.banksystem.util.AdminFlagSync.DeopFollowUp;
import net.kroia.banksystem.util.AdminFlagSync.ListenerAction;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;

import java.util.List;
import java.util.Optional;

/**
 * Task #107: pure-logic tests for the admin-flag / LuckPerms sync decisions in {@link AdminFlagSync},
 * every input combination of each rule.
 */
public class AdminFlagSyncTests extends TestSuite {

    private static final boolean[] BOOLS = {false, true};

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.NETWORKING;
    }

    @Override
    public void registerTests() {
        addTest("push_to_luckperms_all_combinations", this::pushToLuckPerms);
        addTest("listener_action_all_combinations", this::listenerAction);
        addTest("migrate_all_combinations", this::migrate);
        addTest("deop_follow_up_group_wins", this::deopFollowUp);
        addTest("new_user_apply_only_true", this::newUserApply);
    }

    private TestResult pushToLuckPerms() {
        for (boolean setting : BOOLS)
            for (boolean lp : BOOLS) {
                TestResult res = assertEquals("setting=" + setting + " lp=" + lp, setting && lp,
                        AdminFlagSync.shouldPushToLuckPerms(setting, lp));
                if (!res.passed()) return res;
            }
        return pass("ok");
    }

    private TestResult listenerAction() {
        for (boolean setting : BOOLS)
            for (boolean master : BOOLS)
                for (boolean trusted : BOOLS) {
                    ListenerAction expected = !setting ? ListenerAction.IGNORE
                            : master ? ListenerAction.WRITE_LOCAL
                            : trusted ? ListenerAction.FORWARD : ListenerAction.IGNORE;
                    TestResult res = assertEquals("setting=" + setting + " master=" + master + " trusted=" + trusted,
                            expected, AdminFlagSync.listenerAction(setting, master, trusted));
                    if (!res.passed()) return res;
                }
        // Untrusted slave never writes or forwards, whatever the setting.
        TestResult res = assertEquals("untrusted slave, setting on", ListenerAction.IGNORE,
                AdminFlagSync.listenerAction(true, false, false));
        if (!res.passed()) return res;
        return pass("ok");
    }

    private TestResult migrate() {
        for (boolean setting : BOOLS)
            for (boolean lp : BOOLS)
                for (boolean migrated : BOOLS) {
                    TestResult res = assertEquals("setting=" + setting + " lp=" + lp + " migrated=" + migrated,
                            setting && lp && !migrated, AdminFlagSync.shouldMigrate(setting, lp, migrated));
                    if (!res.passed()) return res;
                }
        return pass("ok");
    }

    private static final List<Optional<Boolean>> EFFECTIVE = List.of(Optional.empty(), Optional.of(false), Optional.of(true));

    private TestResult deopFollowUp() {
        for (Optional<Boolean> effective : EFFECTIVE) {
            DeopFollowUp expected = effective.equals(Optional.of(true)) ? DeopFollowUp.RESTORE_AND_NOTE : DeopFollowUp.NONE;
            TestResult res = assertEquals("effective=" + effective, expected, AdminFlagSync.deopFollowUp(effective));
            if (!res.passed()) return res;
        }
        return pass("ok");
    }

    private TestResult newUserApply() {
        for (Optional<Boolean> effective : EFFECTIVE) {
            TestResult res = assertEquals("effective=" + effective, effective.equals(Optional.of(true)),
                    AdminFlagSync.newUserApply(effective));
            if (!res.passed()) return res;
        }
        return pass("ok");
    }
}
