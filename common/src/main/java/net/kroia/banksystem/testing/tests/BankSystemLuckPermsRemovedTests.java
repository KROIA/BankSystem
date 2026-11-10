package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.minecraft.command.ServerBankSystemCommandHandler;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.AdminFlagSync;
import net.kroia.banksystem.util.BankSystemPermissions;
import net.kroia.modutilities.PermissionCompat;
import net.kroia.modutilities.permission.LuckPermsBridge;
import net.kroia.modutilities.testing.LuckPermsPhase;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.kroia.modutilities.testing.autotest.AutoTest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Task #108: "LuckPerms removed from a save" (tools/autotest {@code --luckperms removed}, see {@link LuckPermsPhase}).
 * Phase 1 (LuckPerms present, autotest runs only, it leaves the sync setting on): turns the sync setting on, runs
 * the migration (marker), flags a fake admin and records all of it. Phase 2 (same world, LuckPerms gone): checks it
 * survived and that op/deop, admin checks and {@link AdminFlagSync} work without LuckPerms. Phase-2 tests skip
 * outside phase 2.
 */
public class BankSystemLuckPermsRemovedTests extends TestSuite {
    private static final String MOD = "banksystem";
    private static final String[] NODES = {BankSystemPermissions.COMMAND_TRUST, BankSystemPermissions.COMMAND_OP,
            BankSystemPermissions.COMMAND_BACKUP, BankSystemPermissions.COMMAND_SYMBOLS,
            BankSystemPermissions.COMMAND_EXPORTRECIPES, BankSystemPermissions.ADMIN_MODE,
            BankSystemPermissions.SOFTWARE, BankSystemPermissions.ADMIN};
    private static final UUID EXECUTOR = UUID.fromString("00000000-0000-0000-0000-00000000b109");

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.BANK_MANAGER;
    }

    @Override
    public void registerTests() {
        addTest("lprm_phase1_record_state", this::recordPhase1);
        addTest("lprm_clean_start", phase2(this::testCleanStart));
        addTest("lprm_setting_and_marker_kept", phase2(this::testSettingAndMarkerKept));
        addTest("lprm_admin_flag_kept", phase2(this::testAdminFlagKept));
        addTest("lprm_op_deop_toggle_flag", phase2(this::testOpDeop));
        addTest("lprm_admin_checks_fall_back", phase2(this::testFallback));
        addTest("lprm_admin_flag_sync_noop", phase2(this::testSyncNoop));
    }

    private Supplier<TestResult> phase2(Supplier<TestResult> test) {
        return () -> {
            MinecraftServer s = getServer();
            if (s == null || manager() == null)
                return skip("no master server");
            if (!LuckPermsPhase.isPhase2(s))
                return skip(LuckPermsPhase.phase1Exists(s) ? "LuckPerms is installed" : "world has no LuckPerms phase-1 file");
            return test.get();
        };
    }

    // ------------------------------------------------------------------ phase 1

    private TestResult recordPhase1() {
        MinecraftServer s = getServer();
        if (!LuckPermsBridge.isAvailable())
            return skip("LuckPerms not installed");
        if (s == null || manager() == null)
            return skip("no master server");
        if (!AutoTest.isEnabled())
            return skip("autotest runs only: phase 1 leaves the sync setting on");
        BankSystemModBackend.Instances inst = BankSystemModBackend.getInstances_forTesting();
        inst.SERVER_SETTINGS.INTEGRATIONS.SYNC_ADMIN_FLAG_WITH_PERMISSION.set(true);
        User admin = new User(UUID.randomUUID(), "LpRemovedAdmin");
        manager().addUser(admin);
        manager().setBanksystemAdminMode(admin.getUUID(), true);
        Path marker = marker();
        AdminFlagSync.migrateIfNeeded();
        if (!pumpUntil(() -> Files.exists(marker), 10_000))
            return fail("migration wrote no marker");
        if (!inst.SERVER_DATA_HANDLER.saveAll())
            return fail("saveAll failed");
        LuckPermsPhase.record(s, MOD, "syncSetting", "on");
        LuckPermsPhase.record(s, MOD, "migrationMarker", "1");
        LuckPermsPhase.record(s, MOD, "admin." + admin.getUUID(), "true");
        return assertEquals("phase file readable", "on", LuckPermsPhase.expected(s, MOD, "syncSetting"));
    }

    // ------------------------------------------------------------------ phase 2

    /** Reaching this at all means the server started; check the backend came up as master, without LuckPerms. */
    private TestResult testCleanStart() {
        BankSystemModBackend.Instances inst = BankSystemModBackend.getInstances_forTesting();
        TestResult r = assertFalse("LuckPermsBridge.isAvailable()", LuckPermsBridge.isAvailable());
        if (!r.passed())
            return r;
        r = assertFalse("master server", inst.isSlaveServer);
        if (!r.passed())
            return r;
        r = assertTrue("settings and data handler loaded", inst.SERVER_SETTINGS != null && inst.SERVER_DATA_HANDLER != null);
        if (!r.passed())
            return r;
        return assertFalse("phase 1 recorded BankSystem state", LuckPermsPhase.expectations(getServer(), MOD).isEmpty());
    }

    private TestResult testSettingAndMarkerKept() {
        TestResult r = assertEquals("phase 1 recorded the setting", "on", LuckPermsPhase.expected(getServer(), MOD, "syncSetting"));
        if (!r.passed())
            return r;
        r = assertTrue("SYNC_ADMIN_FLAG_WITH_PERMISSION still on", AdminFlagSync.isEnabled());
        if (!r.passed())
            return r;
        return assertTrue("migration marker still present", Files.exists(marker()));
    }

    private TestResult testAdminFlagKept() {
        int n = 0;
        for (Map.Entry<String, String> e : LuckPermsPhase.expectations(getServer(), MOD).entrySet()) {
            if (!e.getKey().startsWith("admin."))
                continue;
            UUID u = UUID.fromString(e.getKey().substring("admin.".length()));
            TestResult r = assertEquals("admin flag of " + u, Boolean.parseBoolean(e.getValue()), manager().isBanksystemAdmin(u));
            if (!r.passed())
                return r;
            n++;
        }
        return n == 0 ? fail("phase 1 recorded no admin") : pass(n + " admin flag(s) kept");
    }

    private TestResult testOpDeop() {
        User u = new User(UUID.randomUUID(), "LpRemovedOpTest");
        manager().addUser(u);
        try {
            ServerBankSystemCommandHandler h = new ServerBankSystemCommandHandler();
            if (!h.banksystem_setBankSystemAdminMode_user(EXECUTOR, u.getName(), true))
                return fail("op returned false");
            pump(500);
            TestResult r = assertTrue("op sets the flag", manager().isBanksystemAdmin(u.getUUID()));
            if (!r.passed())
                return r;
            if (!h.banksystem_setBankSystemAdminMode_user(EXECUTOR, u.getName(), false))
                return fail("deop returned false");
            pump(500);
            return assertFalse("deop clears the flag", manager().isBanksystemAdmin(u.getUUID()));
        } catch (Exception e) {
            return fail("op/deop threw " + e);
        } finally {
            manager().removeUser(u.getUUID());
        }
    }

    private TestResult testFallback() {
        for (Map.Entry<String, String> e : LuckPermsPhase.expectations(getServer(), MOD).entrySet()) {
            if (!e.getKey().startsWith("admin."))
                continue;
            UUID u = UUID.fromString(e.getKey().substring("admin.".length()));
            TestResult r = assertTrue("isPlayerAdmin falls back to the flag for " + u,
                    ServerBankSystemCommandHandler.isPlayerAdmin(u));
            if (!r.passed())
                return r;
        }
        ServerPlayer p = player();
        if (p == null)
            return pass("flag fallback ok (no online player, node checks not compared)");
        for (String node : NODES) {
            boolean expected = PermissionCompat.hasPermission(p, BankSystemPermissions.FALLBACK_LEVEL);
            if (expected != PermissionCompat.hasPermission(p, node, BankSystemPermissions.FALLBACK_LEVEL))
                return TestResult.fail("", node + " equals the op-level check", String.valueOf(expected), String.valueOf(!expected));
            if (expected != PermissionCompat.hasPermission(p.createCommandSourceStack(), node, BankSystemPermissions.FALLBACK_LEVEL))
                return TestResult.fail("", node + " (source) equals the op-level check", String.valueOf(expected), String.valueOf(!expected));
        }
        return pass("flag and op-level fallback ok");
    }

    private TestResult testSyncNoop() {
        Path marker = marker();
        try {
            String markerBefore = Files.readString(marker);
            Map<String, String> exp = LuckPermsPhase.expectations(getServer(), MOD);
            AdminFlagSync.init();
            AdminFlagSync.migrateIfNeeded();
            AdminFlagSync.onUserAdded(UUID.randomUUID());
            for (String key : exp.keySet()) {
                if (!key.startsWith("admin."))
                    continue;
                UUID u = UUID.fromString(key.substring("admin.".length()));
                AdminFlagSync.pushToLuckPerms(EXECUTOR, u, "LpRemovedAdmin", false);
            }
            pump(1000);
            TestResult r = assertEquals("marker unchanged", markerBefore, Files.readString(marker));
            if (!r.passed())
                return r;
            return testAdminFlagKept();
        } catch (Exception e) {
            return fail("AdminFlagSync threw " + e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static IServerBankManager manager() {
        BankSystemModBackend.Instances inst = BankSystemModBackend.getInstances_forTesting();
        return inst.SERVER_BANK_MANAGER == null || inst.isSlaveServer ? null : inst.SERVER_BANK_MANAGER.getSync();
    }

    private static Path marker() {
        return BankSystemModBackend.getInstances_forTesting().SERVER_DATA_HANDLER.getAbsoluteSavePath(AdminFlagSync.MIGRATION_MARKER);
    }

    private ServerPlayer player() {
        List<ServerPlayer> players = getServer().getPlayerList().getPlayers();
        return players.isEmpty() ? null : players.get(0);
    }
}
