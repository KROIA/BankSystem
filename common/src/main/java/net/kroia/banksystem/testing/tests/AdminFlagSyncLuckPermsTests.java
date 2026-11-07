package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.minecraft.command.ServerBankSystemCommandHandler;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.AdminFlagSync;
import net.kroia.banksystem.util.BankSystemPermissions;
import net.kroia.modutilities.permission.LuckPermsBridge;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.kroia.modutilities.testing.autotest.AutoTest;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.GroupManager;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Task #108: {@link AdminFlagSync} against a real LuckPerms (dev runs with {@code -Pwith_luckperms=true}).
 * Every test skips without LuckPerms. Uses fake BankSystem users ({@code LpSyncTest_*}), temporary LuckPerms groups
 * ({@code bs_test_*}) and, for the live listener test, the online test player; all are restored in {@code finally}
 * with the sync setting turned off first, so cleanup edits never flow back into flags.
 *
 * <p>Limit: the "still admin through a LuckPerms group" message goes to the executor through
 * ClientConsoleMessagePacket, which the server cannot read back; the group-deop test asserts the restored flag only.
 */
public class AdminFlagSyncLuckPermsTests extends TestSuite {
    static final String GROUP_PREFIX = "bs_test_";
    private static final String NODE = BankSystemPermissions.ADMIN;
    private static final long TIMEOUT = 10_000;
    private static final UUID EXECUTOR = UUID.fromString("00000000-0000-0000-0000-00000000b108");

    private final List<UUID> fakeUsers = new ArrayList<>();
    private final List<String> groups = new ArrayList<>();
    private Boolean settingBefore;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.BANK_MANAGER;
    }

    @Override
    public void registerTests() {
        addTest("lp_op_writes_node", lp(this::testOpWritesNode));
        addTest("lp_set_unset_flag_follows_live", lp(this::testFlagFollowsLive));
        addTest("lp_group_grant_deop_flag_stays", lp(this::testGroupGrantDeop));
        addTest("lp_user_node_only_deop_clears_flag", lp(this::testUserNodeDeop));
        addTest("lp_new_user_in_granted_group_becomes_admin", lp(this::testNewUserInGrantedGroup));
        addTest("lp_migration_runs_once", lp(this::testMigrationOnce));
        addTest("lp_setting_off_nothing_syncs", lp(this::testSettingOff));
    }

    private Supplier<TestResult> lp(Supplier<TestResult> test) {
        return () -> {
            if (!LuckPermsBridge.isAvailable())
                return skip("LuckPerms not installed");
            BankSystemModBackend.Instances inst = BankSystemModBackend.getInstances_forTesting();
            if (getServer() == null || inst.SERVER_SETTINGS == null || manager() == null || inst.isSlaveServer)
                return skip("no master server");
            settingBefore = inst.SERVER_SETTINGS.UTILITIES.SYNC_ADMIN_FLAG_WITH_PERMISSION.get();
            try {
                return test.get();
            } finally {
                setSetting(false); // first: cleanup edits below must not flow back into flags
                for (UUID u : fakeUsers) {
                    await(Lp.deleteUser(u), TIMEOUT);
                    manager().removeUser(u);
                }
                for (String g : groups)
                    await(Lp.deleteGroup(g), TIMEOUT);
                fakeUsers.clear();
                groups.clear();
                pump(500); // drain queued listener calls while the setting is off
                setSetting(Boolean.TRUE.equals(settingBefore));
            }
        };
    }

    // ------------------------------------------------------------------ tests

    private TestResult testOpWritesNode() {
        setSetting(true);
        User u = fakeUser("Op", false);
        if (!handler().banksystem_setBankSystemAdminMode_user(EXECUTOR, u.getName(), true))
            return fail("op returned false");
        TestResult r = assertTrue("flag set by op", manager().isBanksystemAdmin(u.getUUID()));
        if (!r.passed())
            return r;
        return assertTrue("LuckPerms node true after op", effectiveEventually(u.getUUID(), true));
    }

    /** {@code lp user <p> permission set|unset banksystem.admin}: the flag follows through the listener. */
    private TestResult testFlagFollowsLive() {
        ServerPlayer p = player();
        if (p == null)
            return skip("no online player (the listener only sees users LuckPerms has loaded)");
        UUID u = p.getUUID();
        if (!manager().userExists(u))
            return skip("online player is not a BankSystem user");
        boolean flagBefore = manager().isBanksystemAdmin(u);
        Optional<Boolean> nodeBefore = await(Lp.userNode(u), TIMEOUT);
        try {
            setSetting(false);
            await(Lp.unsetUserNode(u), TIMEOUT);
            pump(1000);
            manager().setBanksystemAdminMode(u, false);
            setSetting(true);
            await(Lp.setUserNode(u, true), TIMEOUT);
            if (!pumpUntil(() -> manager().isBanksystemAdmin(u), 5000))
                return fail("flag did not follow 'permission set " + NODE + " true'");
            await(Lp.unsetUserNode(u), TIMEOUT);
            if (!pumpUntil(() -> !manager().isBanksystemAdmin(u), 5000))
                return fail("flag did not follow 'permission unset " + NODE + "'");
            return pass("flag followed set and unset");
        } finally {
            setSetting(false);
            if (nodeBefore.isPresent())
                await(Lp.setUserNode(u, nodeBefore.get()), TIMEOUT);
            else
                await(Lp.unsetUserNode(u), TIMEOUT);
            await(Lp.clearTestGroups(u), TIMEOUT);
            pump(500);
            manager().setBanksystemAdminMode(u, flagBefore);
        }
    }

    private TestResult testGroupGrantDeop() {
        setSetting(true);
        User u = fakeUser("GroupDeop", true);
        String g = group();
        await(Lp.addToGroup(u.getUUID(), g), TIMEOUT);
        if (!effectiveEventually(u.getUUID(), true))
            return fail("group grant not effective");
        pump(500);
        if (!handler().banksystem_setBankSystemAdminMode_user(EXECUTOR, u.getName(), false))
            return fail("deop returned false");
        // deop clears the flag at once, then the group-wins follow-up sets it back
        if (!pumpUntil(() -> manager().isBanksystemAdmin(u.getUUID()), 5000))
            return fail("flag not restored after deop while a LuckPerms group grants " + NODE);
        pump(1000);
        TestResult r = assertTrue("flag stays after the follow-up settled", manager().isBanksystemAdmin(u.getUUID()));
        if (!r.passed())
            return r;
        return pass("flag stays (executor message not observable server-side, see class docs)");
    }

    private TestResult testUserNodeDeop() {
        setSetting(true);
        User u = fakeUser("NodeDeop", false);
        handler().banksystem_setBankSystemAdminMode_user(EXECUTOR, u.getName(), true);
        if (!effectiveEventually(u.getUUID(), true))
            return fail("op did not write the node");
        if (!handler().banksystem_setBankSystemAdminMode_user(EXECUTOR, u.getName(), false))
            return fail("deop returned false");
        if (!effectiveEventually(u.getUUID(), false))
            return fail("deop did not remove the user node");
        pump(1500); // a wrong group-wins follow-up would set the flag back in this window
        return assertFalse("flag cleared after deop (user node only)", manager().isBanksystemAdmin(u.getUUID()));
    }

    private TestResult testNewUserInGrantedGroup() {
        setSetting(true);
        UUID u = UUID.randomUUID();
        fakeUsers.add(u);
        String g = group();
        await(Lp.addToGroup(u, g), TIMEOUT);
        pump(1000); // listener for a not-yet BankSystem user: must be harmless
        manager().addUser(new User(u, name("NewUser")));
        return assertTrue("new user in a granted group becomes admin",
                pumpUntil(() -> manager().isBanksystemAdmin(u), 5000));
    }

    private TestResult testMigrationOnce() {
        Path marker = BankSystemModBackend.getInstances_forTesting().SERVER_DATA_HANDLER
                .getAbsoluteSavePath(AdminFlagSync.MIGRATION_MARKER);
        if (!AutoTest.isEnabled()) {
            for (UUID id : manager().getBankManagerUserMapData().userMap().keySet())
                if (manager().isBanksystemAdmin(id))
                    return skip("world has real admins: the migration would push them to LuckPerms (autotest only)");
        }
        byte[] backup = null;
        try {
            if (Files.exists(marker)) {
                backup = Files.readAllBytes(marker);
                Files.delete(marker);
            }
            User first = fakeUser("Migrate1", true);
            setSetting(true);
            AdminFlagSync.migrateIfNeeded();
            if (!pumpUntil(() -> Files.exists(marker), TIMEOUT))
                return fail("migration wrote no marker");
            TestResult r = assertEquals("flagged admin pushed by the migration", Optional.of(true),
                    await(LuckPermsBridge.getEffective(first.getUUID(), NODE), TIMEOUT));
            if (!r.passed())
                return r;
            User second = fakeUser("Migrate2", true);
            AdminFlagSync.migrateIfNeeded();
            pump(1500);
            return assertEquals("second run (marker present) pushes nothing", Optional.of(false),
                    await(LuckPermsBridge.getEffective(second.getUUID(), NODE), TIMEOUT));
        } catch (Exception e) {
            return fail("marker IO: " + e);
        } finally {
            try {
                if (backup != null)
                    Files.write(marker, backup);
                else
                    Files.deleteIfExists(marker);
            } catch (Exception e) {
                // reported through the test result of the next run; nothing else to do here
            }
        }
    }

    private TestResult testSettingOff() {
        setSetting(false);
        User op = fakeUser("OffOp", false);
        handler().banksystem_setBankSystemAdminMode_user(EXECUTOR, op.getName(), true);
        pump(1500);
        TestResult r = assertEquals("op with setting off writes no node", Optional.of(false),
                await(LuckPermsBridge.getEffective(op.getUUID(), NODE), TIMEOUT));
        if (!r.passed())
            return r;
        User follow = fakeUser("OffFollow", false);
        await(LuckPermsBridge.setNode(follow.getUUID(), NODE, true), TIMEOUT);
        pump(1500);
        r = assertFalse("LuckPerms grant with setting off leaves the flag", manager().isBanksystemAdmin(follow.getUUID()));
        if (!r.passed())
            return r;
        UUID fresh = UUID.randomUUID();
        fakeUsers.add(fresh);
        await(Lp.addToGroup(fresh, group()), TIMEOUT);
        manager().addUser(new User(fresh, name("OffNew")));
        pump(1500);
        return assertFalse("new user in a granted group with setting off stays non-admin", manager().isBanksystemAdmin(fresh));
    }

    // ------------------------------------------------------------------ helpers

    private static IServerBankManager manager() {
        BankSystemModBackend.Instances inst = BankSystemModBackend.getInstances_forTesting();
        return inst.SERVER_BANK_MANAGER == null ? null : inst.SERVER_BANK_MANAGER.getSync();
    }

    private static ServerBankSystemCommandHandler handler() {
        return new ServerBankSystemCommandHandler();
    }

    private static void setSetting(boolean on) {
        BankSystemModBackend.getInstances_forTesting().SERVER_SETTINGS.UTILITIES.SYNC_ADMIN_FLAG_WITH_PERMISSION.set(on);
    }

    private User fakeUser(String tag, boolean admin) {
        User u = new User(UUID.randomUUID(), name(tag));
        fakeUsers.add(u.getUUID());
        manager().addUser(u);
        manager().setBanksystemAdminMode(u.getUUID(), admin);
        return u;
    }

    private static String name(String tag) {
        return "LpSyncTest_" + tag + "_" + UUID.randomUUID().toString().substring(0, 6);
    }

    /** Creates a temporary group granting {@link BankSystemPermissions#ADMIN}; deleted after the test. */
    private String group() {
        String g = GROUP_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        groups.add(g);
        await(Lp.createGroupWith(g, NODE), TIMEOUT);
        return g;
    }

    /**
     * Polls with a fresh load from storage each time, not {@code LuckPermsBridge.getEffective}: that reuses an
     * already loaded user, and a poll whose load raced the command's in-flight write can leave a stale copy loaded.
     */
    private boolean effectiveEventually(UUID u, boolean expected) {
        long end = System.currentTimeMillis() + 5000;
        while (true) {
            if (expected == await(Lp.loadEffective(u), TIMEOUT))
                return true;
            if (System.currentTimeMillis() > end)
                return false;
            pump(200);
        }
    }

    private @Nullable ServerPlayer player() {
        List<ServerPlayer> players = getServer().getPlayerList().getPlayers();
        return players.isEmpty() ? null : players.get(0);
    }

    /** Direct LuckPerms API calls the bridge does not offer. Only loaded when LuckPerms is available. */
    static final class Lp {
        static LuckPerms api() {
            return LuckPermsProvider.get();
        }

        static CompletableFuture<Boolean> loadEffective(UUID u) {
            return api().getUserManager().loadUser(u)
                    .thenApply(user -> user.getCachedData().getPermissionData().checkPermission(NODE).asBoolean());
        }

        /** The user's own global {@code banksystem.admin} entry, if any. */
        static CompletableFuture<Optional<Boolean>> userNode(UUID u) {
            return api().getUserManager().loadUser(u).thenApply(user -> user.getNodes().stream()
                    .filter(n -> n.getKey().equalsIgnoreCase(NODE) && n.getContexts().isEmpty())
                    .findFirst().map(Node::getValue));
        }

        /** Like {@code lp user <u> permission set banksystem.admin <value>}. */
        static CompletableFuture<Void> setUserNode(UUID u, boolean value) {
            return api().getUserManager().modifyUser(u, user -> {
                user.data().clear(n -> n.getKey().equalsIgnoreCase(NODE) && n.getContexts().isEmpty());
                user.data().add(Node.builder(NODE).value(value).build());
            });
        }

        /** Like {@code lp user <u> permission unset banksystem.admin}. */
        static CompletableFuture<Void> unsetUserNode(UUID u) {
            return api().getUserManager().modifyUser(u, user ->
                    user.data().clear(n -> n.getKey().equalsIgnoreCase(NODE) && n.getContexts().isEmpty()));
        }

        static CompletableFuture<Void> clearTestGroups(UUID u) {
            return api().getUserManager().modifyUser(u, user ->
                    user.data().clear(n -> n.getKey().startsWith("group." + GROUP_PREFIX)));
        }

        static CompletableFuture<Void> createGroupWith(String group, String node) {
            GroupManager gm = api().getGroupManager();
            return gm.createAndLoadGroup(group).thenCompose(g -> {
                g.data().add(Node.builder(node).build());
                return gm.saveGroup(g);
            });
        }

        static CompletableFuture<Void> addToGroup(UUID u, String group) {
            return api().getUserManager().modifyUser(u, user -> user.data().add(InheritanceNode.builder(group).build()));
        }

        static CompletableFuture<Void> deleteGroup(String group) {
            GroupManager gm = api().getGroupManager();
            return gm.loadGroup(group).thenCompose(g -> g.map(gm::deleteGroup)
                    .orElseGet(() -> CompletableFuture.completedFuture(null)));
        }

        static CompletableFuture<Void> deleteUser(UUID u) {
            return api().getUserManager().deletePlayerData(u);
        }
    }
}
