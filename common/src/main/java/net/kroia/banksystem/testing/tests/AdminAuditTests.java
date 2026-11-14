package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.api.event.SuspicionFlaggedEvent;
import net.kroia.banksystem.api.ledger.LedgerActor;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.banking.company.Company;
import net.kroia.banksystem.banking.company.CompanyManager;
import net.kroia.banksystem.banking.company.ShareIntegrity;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import net.kroia.banksystem.banking.ledger.AdminAudit;
import net.kroia.banksystem.banking.ledger.SuspicionChecks;
import net.kroia.banksystem.banking.ledger.TransactionLedger;
import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.data.table.SuspicionFlagManager;
import net.kroia.banksystem.data.table.TransactionLogManager;
import net.kroia.banksystem.data.table.record.SuspicionFlagRecord;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.minecraft.command.BankSystemCommandsRegistration;
import net.kroia.banksystem.minecraft.command.ServerBankSystemCommandHandler;
import net.kroia.banksystem.util.BankSystemPermissions;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kroia.banksystem.networking.general.AuditPageRequest;
import net.kroia.banksystem.networking.multi_server.ClientConsoleMessagePacket;
import net.kroia.banksystem.networking.multi_server.ServerNetworkInfoRequest;
import net.kroia.banksystem.api.bankmanager.IAsyncBankManager;
import net.kroia.banksystem.util.MultiServerUtils;
import net.kroia.banksystem.testing.AdminFlagTestRequest;
import net.kroia.banksystem.testing.LedgerByRefRequest;
import net.kroia.banksystem.data.TransferJournal;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.multi_server.TransferParkedRequest;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.testing.LiveStateGuard;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Task #110 (v2.2.0) — admin audit: paged ledger query, audit file + alert hook, alert toggle,
 * SuspicionFlag raise/read/migration and the audit page request's access check.
 * <p>
 * <b>Server type:</b> MASTER_ONLY (BANK_MANAGER category). Synthetic users and accounts only;
 * ledger rows, flag rows and audit-file lines written by the tests are removed afterwards.
 */
public class AdminAuditTests extends TestSuite {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000f110");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-00000000f111");
    private static final String MARKER = "audit-test-marker-f110";
    /** BS-257: lines naming the synthetic users (actor or subject) are this suite's too. */
    private static final String TEST_UUID_PREFIX = "00000000-0000-0000-0000-00000000f11";
    private static final String SYNTHETIC_KEY = "banksystem_audit_test";
    private static final long WAIT_MS = 10_000;
    /** BS-209: the slave half of the admin-flag gate (flag lives on the master). */
    private static final TestCategory SLAVE_ADMIN = new TestCategory(
            "banksystem", "admin_slave", "Admin-flag commands on a slave ask the master",
            TestCategory.ServerType.SLAVE_ONLY, true);

    private final boolean slave;

    public AdminAuditTests(boolean slave) {
        this.slave = slave;
    }

    private ISyncServerBankManager manager;
    private TransactionLogManager ledger;
    private LiveStateGuard stateGuard;
    private int accountNr = ServerBankAccount.INVALID_ACCOUNT_NUMBER;

    @Override
    public TestCategory getCategory() {
        return slave ? SLAVE_ADMIN : BankSystemTestCategories.BANK_MANAGER;
    }

    @Override
    public void registerTests() {
        if (slave) {
            // BS-209: positive cases need slave-trusted / spslave-trusted, negative cases plain slave / spslave
            addTest("slave_admin_gate_asks_master", () -> onSlave(this::slaveGate));
            addTest("slave_symbols_list_master_only", () -> onSlave(this::slaveSymbolsList));
            addTest("slave_auditreport_trusted_only", () -> onSlave(this::slaveAuditReport));
            addTest("slave_transfer_parked_flag_trusted_only", this::slaveParkedFlag); // BS-267
            return;
        }
        addTest("paged_query_filters_and_order", this::pagedQuery);
        addTest("admin_row_writes_audit_file_and_alert", this::adminRowAudit);
        addTest("alerts_toggle_persists_and_defaults", this::alertsToggle);
        addTest("suspicion_flag_raise_and_read", this::flagRaise);
        addTest("suspicion_flag_migration_idempotent", this::flagMigration);
        addTest("audit_page_refuses_non_admin", this::pageAccess);
        addTest("suspicion_flagged_event_once", this::flagEvent); // Task #119
        addTest("alert_chat_readable", this::alertChatReadable); // BS-241
        addTest("server_network_info_admin_only", this::networkInfoAccess); // Task #142
        // BS-196: flag-gated admin commands
        addTest("permission_nodes_fallback_levels", this::nodeFallbackLevels);
        addTest("admin_command_non_player_uses_op_level", this::gateNonPlayer);
        addTest("admin_command_non_op_with_flag_runs", () -> gate(true, 0, true));
        addTest("admin_command_op_without_flag_refused", () -> gate(false, 2, false));
        addTest("admin_command_non_op_without_flag_refused", () -> gate(false, 0, false));
        addTest("symbols_list_runs_for_flagged_non_op", this::symbolsListMaster); // BS-209
        addTest("admin_changes_write_audit_lines", this::adminChangeLines); // BS-257
    }

    /** BS-209: collects what a command sends to its source (success and failure lines). */
    private static final class Capture implements CommandSource {
        final List<String> lines = new CopyOnWriteArrayList<>();
        @Override public void sendSystemMessage(Component c) { lines.add(c.getString()); addKeys(c); }
        /** SM-312: translatable lines also record their key (a dedicated server may not have the mod's lang text). */
        private void addKeys(Component c) {
            if (c.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t) lines.add(t.getKey());
            c.getSiblings().forEach(this::addKeys);
        }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
        boolean has(String s) { return lines.stream().anyMatch(l -> l.contains(s)); }
    }

    /** A level-0 (non-op) source of {@code player} whose messages go to {@code out}. */
    private static CommandSourceStack nonOp(ServerPlayer player, Capture out) {
        return player.createCommandSourceStack().withPermission(0).withSource(out);
    }

    /** BS-209: on the master a flagged non-op runs {@code /banksystem symbols list}. */
    private TestResult symbolsListMaster() {
        if (manager == null || getServer() == null) return skip("no master server");
        List<ServerPlayer> players = getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) return skip("no online player");
        ServerPlayer player = players.get(0);
        boolean before = manager.isBanksystemAdmin(player.getUUID());
        try {
            if (!manager.setBanksystemAdminMode(player.getUUID(), true)) return fail("player has no master user");
            Capture out = new Capture();
            getServer().getCommands().performPrefixedCommand(nonOp(player, out), "banksystem symbols list");
            if (!pumpUntil(() -> out.has("Symbols (revision"), 5_000)) return fail("flagged non-op got no symbols list: " + out.lines);
            return pass("flagged non-op ran /banksystem symbols list on the master");
        } finally {
            manager.setBanksystemAdminMode(player.getUUID(), before);
        }
    }

    // ------------------------------------------------------------------ slave (BS-209)

    private interface SlaveBody { TestResult run(ServerPlayer player, boolean trusted); }

    /**
     * Runs {@code body} on a connected slave with its online player. A trusted slave sets the player's admin flag on
     * the master for the body (cleared after) through the test-only {@link AdminFlagTestRequest}; an untrusted slave
     * can't, so its player stays unflagged.
     */
    private TestResult onSlave(SlaveBody body) {
        if (getServer() == null || !BankSystemModBackend.getInstances_forTesting().isSlaveServer) return skip("not a slave");
        if (!MultiServerUtils.canInteractWithBankSystem()) return skip("slave not connected to its master");
        List<ServerPlayer> players = getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) return skip("no online player");
        ServerPlayer player = players.get(0);
        IAsyncBankManager mgr = BankSystemModBackend.getInstances_forTesting().SERVER_BANK_MANAGER.getAsync();
        boolean trusted = Boolean.TRUE.equals(await(mgr.isSlaveServerTrustedAsync(""), WAIT_MS));
        if (Boolean.TRUE.equals(await(mgr.isBanksystemAdminAsync(player.getUUID()), WAIT_MS)))
            return skip("player already has the admin flag on the master");
        boolean set = trusted && Boolean.TRUE.equals(await(setFlagFromSlave(player.getUUID(), true), WAIT_MS));
        try {
            if (trusted && !set) return fail("trusted slave could not set the player's admin flag on the master");
            return body.run(player, trusted);
        } finally {
            if (set) await(setFlagFromSlave(player.getUUID(), false), WAIT_MS);
        }
    }

    private static CompletableFuture<Boolean> setFlagFromSlave(UUID player, boolean admin) {
        return AdminFlagTestRequest.INSTANCE.sendRequestToMaster(new AdminFlagTestRequest.Input(player, admin));
    }

    /**
     * The gate asks the master: flagged non-op runs (trusted cell); on an untrusted slave the player gets only the
     * untrusted line (SM-312), not the admin-only one.
     */
    private TestResult slaveGate(ServerPlayer player, boolean trusted) {
        Capture out = new Capture();
        CompletableFuture<Boolean> ran = BankSystemCommandsRegistration.runAsAdmin(nonOp(player, out), () -> {});
        if (!pumpUntil(ran::isDone, WAIT_MS)) return fail("master gave no answer to the admin check");
        if (ran.join() != trusted) return fail("action ran=" + ran.join() + ", expected " + trusted + "; messages " + out.lines);
        if (!trusted && !out.has(BankSystemCommandsRegistration.UNTRUSTED_SLAVE_KEY))
            return fail("untrusted slave: no untrusted message: " + out.lines);
        if (!trusted && out.has(ServerBankSystemCommandHandler.NO_ADMIN_MESSAGE))
            return fail("untrusted slave: admin-only line follows the untrusted one: " + out.lines);
        return pass(trusted ? "trusted slave: flag set on the master lets a non-op run"
                : "untrusted slave refused with the untrusted message only");
    }

    /** {@code /banksystem symbols list} on a slave: flagged player is told it is master-only, untrusted slave gets the untrusted message. */
    private TestResult slaveSymbolsList(ServerPlayer player, boolean trusted) {
        Capture out = new Capture();
        getServer().getCommands().performPrefixedCommand(nonOp(player, out), "banksystem symbols list");
        String expected = trusted ? "[symbols] Must run on the master server." : BankSystemCommandsRegistration.UNTRUSTED_SLAVE_KEY;
        if (!pumpUntil(() -> out.has(expected), WAIT_MS)) return fail("expected '" + expected + "', got " + out.lines);
        if (out.has("Symbols (revision")) return fail("the slave listed symbols: " + out.lines);
        return pass("slave replied: " + expected);
    }

    /** BS-203: auditreport from a trusted slave returns the master's report path; an untrusted slave is told it is not trusted. */
    private TestResult slaveAuditReport(ServerPlayer player, boolean trusted) {
        List<String> seen = new CopyOnWriteArrayList<>();
        ClientConsoleMessagePacket.testSink = seen::add;
        try {
            String path = await(BankSystemModBackend.getInstances_forTesting().COMMAND_HANDLER.getAsync()
                    .banksystem_auditreport_async(player.getUUID(), 1), 60_000);
            if (trusted) return path.isEmpty() ? fail("trusted slave admin got no audit report") : pass("report written on the master: " + path);
            if (!path.isEmpty()) return fail("untrusted slave got an audit report: " + path);
            if (!pumpUntil(() -> seen.stream().anyMatch(m -> m.contains("not trusted")), WAIT_MS))
                return fail("no 'not trusted' message from the master: " + seen);
            return pass("untrusted slave refused with the 'not trusted' message");
        } finally {
            ClientConsoleMessagePacket.testSink = null;
        }
    }

    /**
     * BS-267: a transfer parked on a trusted slave raises one TRANSFER_PARKED flag on the master (ref = transfer id,
     * amount = raw money), also when the report arrives twice; an untrusted slave's report is refused.
     * Trusted cell leaves one flag row on the master (fake account), like the other slave rows tests.
     */
    private TestResult slaveParkedFlag() {
        if (getServer() == null || !BankSystemModBackend.getInstances_forTesting().isSlaveServer) return skip("not a slave");
        if (!MultiServerUtils.canInteractWithBankSystem()) return skip("slave not connected to its master");
        IAsyncBankManager mgr = BankSystemModBackend.getInstances_forTesting().SERVER_BANK_MANAGER.getAsync();
        boolean trusted = Boolean.TRUE.equals(await(mgr.isSlaveServerTrustedAsync(""), WAIT_MS));
        int account = Integer.MAX_VALUE - 2676;
        ItemID money = MoneyItem.getItemID();
        String tid = TransferJournal.newTransferId();
        TransferParkedRequest.Input report = new TransferParkedRequest.Input(account, money, 500L, tid, "bs267 slave test repeat");
        if (!trusted) {
            Boolean ok = await(BankSystemNetworking.TRANSFER_PARKED_REQUEST.sendRequestToMaster(report), WAIT_MS);
            return Boolean.FALSE.equals(ok) ? pass("untrusted slave: report refused") : fail("untrusted slave's report answered " + ok);
        }
        AdminAudit.parkTransfer(account, money, 500L, tid, null, "bs267 slave test", null);
        // the same report again (a retry after a lost answer): same FIFO link, so it arrives after the first one
        Boolean again = await(BankSystemNetworking.TRANSFER_PARKED_REQUEST.sendRequestToMaster(report), WAIT_MS);
        if (!Boolean.TRUE.equals(again)) return fail("repeated report answered " + again);
        List<LedgerByRefRequest.Row> flags = await(LedgerByRefRequest.INSTANCE.sendRequestToMaster(LedgerByRefRequest.FLAGS + tid), WAIT_MS);
        if (flags == null || flags.size() != 1) return fail("master flags with ref " + tid + ": " + flags);
        LedgerByRefRequest.Row f = flags.get(0);
        if (f.account() != account || f.itemId() != SuspicionChecks.TRANSFER_PARKED || f.amount() != 500L) return fail("flag " + f);
        return pass("trusted slave: one TRANSFER_PARKED flag on the master, ref " + tid);
    }

    /** BS-196: flag-gated nodes fall back to level 0 (the admin flag decides), every other node to op level 2. */
    private TestResult nodeFallbackLevels() {
        java.util.Map<String, Integer> registered = net.kroia.modutilities.PermissionCompat.getRegisteredNodes();
        for (String node : BankSystemPermissions.ALL_NODES) {
            int expected = BankSystemPermissions.FLAG_GATED_NODES.contains(node) ? 0 : 2;
            TestResult r = assertEquals("fallback level of " + node, expected, registered.get(node));
            if (!r.passed()) return r;
        }
        return pass(BankSystemPermissions.ALL_NODES.length + " nodes registered with their fallback level");
    }

    /** BS-196: console and other non-player sources keep the op-level check on the flag-gated commands. */
    private TestResult gateNonPlayer() {
        if (getServer() == null) return skip("no server");
        CommandSourceStack console = getServer().createCommandSourceStack();
        TestResult r = assertTrue("console (level 4) passes",
                BankSystemPermissions.canUseFlagGatedCommand(console, BankSystemPermissions.COMMAND_RESTORE_BINDING));
        if (!r.passed()) return r;
        return assertFalse("level-0 non-player source is refused",
                BankSystemPermissions.canUseFlagGatedCommand(console.withPermission(0), BankSystemPermissions.COMMAND_RESTORE_BINDING));
    }

    /** BS-196: a player's flag-gated command runs if and only if they have the BankSystem admin flag, op or not. */
    private TestResult gate(boolean flag, int opLevel, boolean expectedRan) {
        if (manager == null || getServer() == null) return skip("no master server");
        List<ServerPlayer> players = getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) return skip("no online player");
        UUID uuid = players.get(0).getUUID();
        boolean before = manager.isBanksystemAdmin(uuid);
        try {
            manager.setBanksystemAdminMode(uuid, flag);
            Capture out = new Capture();
            CommandSourceStack source = players.get(0).createCommandSourceStack().withPermission(opLevel).withSource(out);
            TestResult r = assertTrue("player passes the requires check",
                    BankSystemPermissions.canUseFlagGatedCommand(source, BankSystemPermissions.COMMAND_SYMBOLS));
            if (!r.passed()) return r;
            AtomicBoolean ran = new AtomicBoolean();
            CompletableFuture<Boolean> result = BankSystemCommandsRegistration.runAsAdmin(source, () -> ran.set(true));
            if (!pumpUntil(result::isDone, 5_000)) return fail("admin check did not complete");
            r = assertEquals("action ran (flag=" + flag + ", level=" + opLevel + ")", expectedRan, ran.get());
            if (!r.passed()) return r;
            r = assertEquals("result matches", expectedRan, result.join());
            if (!r.passed() || expectedRan) return r;
            return assertTrue("refused player gets the admin-only message, got " + out.lines,
                    out.has(ServerBankSystemCommandHandler.NO_ADMIN_MESSAGE)); // BS-209
        } finally {
            manager.setBanksystemAdminMode(uuid, before);
        }
    }

    @Override
    public void setup() {
        if (slave) return;
        IBankManager bm = BankSystemMod.getAPI().getServerBankManager();
        if (bm == null || bm.getSync() == null) return;
        manager = bm.getSync();
        ledger = BankSystemModBackend.getTransactionLogManager();
        stateGuard = LiveStateGuard.capture(manager).withSyntheticMarker(SYNTHETIC_KEY);
        for (UUID u : List.of(PLAYER, ADMIN))
            if (!manager.userExists(u)) manager.addUser(new User(u, u.equals(ADMIN) ? "AuditTestAdmin" : "AuditTestPlayer"));
        manager.setBanksystemAdminMode(ADMIN, true);
        IServerBankAccount account = manager.createBankAccount("AuditTestAccount");
        if (account != null) accountNr = account.getAccountNumber();
    }

    @Override
    public void teardown() {
        try {
            if (manager == null) return;
            List<Integer> accounts = new ArrayList<>();
            if (accountNr != ServerBankAccount.INVALID_ACCOUNT_NUMBER) {
                accounts.add(accountNr);
                manager.deleteBankAccount(accountNr);
            }
            for (UUID u : List.of(PLAYER, ADMIN)) {
                IServerBankAccount personal = manager.getPersonalBankAccount(u);
                if (personal != null) accounts.add(personal.getAccountNumber());
                manager.removeUser(u);
            }
            deleteRows(accounts);
            removeMarkedAuditLines();
        } finally {
            AdminAudit.alertCapture = null;
            accountNr = ServerBankAccount.INVALID_ACCOUNT_NUMBER;
            LiveStateGuard.restore(stateGuard);
            stateGuard = null;
        }
    }

    private boolean ready() {
        return manager != null && ledger != null && accountNr != ServerBankAccount.INVALID_ACCOUNT_NUMBER;
    }

    private TestResult pagedQuery() {
        if (!ready()) return fail("live setup missing");
        try {
            long t = System.currentTimeMillis() - 10_000L;
            LedgerType[] types = {LedgerType.DEPOSIT, LedgerType.ADMIN_ADD, LedgerType.WITHDRAW, LedgerType.DEPOSIT, LedgerType.ADMIN_ADD};
            List<TransactionLogRecord> rows = new ArrayList<>();
            for (int i = 0; i < types.length; i++)
                rows.add(new TransactionLogRecord(TransactionLogRecord.UNSAVED_ID, accountNr, null, types[i], (short) 0,
                        i + 1, null, null, t + i * 1000L, MARKER));
            ledger.save(rows).get(10, TimeUnit.SECONDS);
            List<TransactionLogRecord> p1 = ledger.getPage(accountNr, -1, 0, 0, 2).get(10, TimeUnit.SECONDS);
            List<TransactionLogRecord> p2 = ledger.getPage(accountNr, -1, 0, 2, 2).get(10, TimeUnit.SECONDS);
            List<TransactionLogRecord> p3 = ledger.getPage(accountNr, -1, 0, 4, 2).get(10, TimeUnit.SECONDS);
            if (p1.size() != 2 || p2.size() != 2 || p3.size() != 1) return fail("page sizes " + p1.size() + "/" + p2.size() + "/" + p3.size());
            long[] expected = {5, 4, 3, 2, 1};
            List<TransactionLogRecord> all = new ArrayList<>(p1);
            all.addAll(p2);
            all.addAll(p3);
            for (int i = 0; i < 5; i++)
                if (all.get(i).amount() != expected[i]) return fail("order: row " + i + " amount " + all.get(i).amount());
            List<TransactionLogRecord> admin = ledger.getPage(accountNr, LedgerType.ADMIN_ADD.code(), 0, 0, 10).get(10, TimeUnit.SECONDS);
            if (admin.size() != 2 || admin.get(0).amount() != 5 || admin.get(1).amount() != 2) return fail("type filter " + admin.size());
            List<TransactionLogRecord> recent = ledger.getPage(accountNr, -1, t + 2500L, 0, 10).get(10, TimeUnit.SECONDS);
            if (recent.size() != 2) return fail("time filter " + recent.size());
            return pass("newest first across pages; type and time filters");
        } catch (Exception e) {
            return fail("threw: " + e);
        }
    }

    private TestResult adminRowAudit() {
        if (!ready()) return fail("live setup missing");
        List<String> captured = new ArrayList<>();
        AdminAudit.alertCapture = line -> { synchronized (captured) { captured.add(line); } };
        try {
            TransactionLedger.write(TransactionLogRecord.of(accountNr, LedgerType.ADMIN_SET, (short) 0, 42L,
                    LedgerActor.admin(ADMIN, "AuditTestAdmin"), null, null, null, MARKER + "-admin", null));
            TransactionLedger.write(TransactionLogRecord.of(accountNr, LedgerType.DEPOSIT, (short) 0, 1L,
                    LedgerActor.player(PLAYER, "AuditTestPlayer"), null, null, null, MARKER + "-player", null));
            awaitAlerts(captured, 1);
            String alert;
            synchronized (captured) {
                if (captured.size() != 1) return fail("expected 1 alert, got " + captured);
                alert = captured.get(0);
            }
            // BS-241: readable chat line: actor and note, no ledger type code, no UUID
            if (alert.contains("ADMIN_SET") || !alert.contains("AuditTestAdmin") || !alert.contains(MARKER + "-admin")
                    || alert.contains(ADMIN.toString())) return fail("alert text: " + alert);
            List<String> lines = auditLines(MARKER);
            if (lines.size() != 1 || !lines.get(0).contains("ADMIN_SET")) return fail("audit file lines: " + lines);
            return pass("admin row -> 1 file line + 1 alert; player row ignored");
        } finally {
            AdminAudit.alertCapture = null;
        }
    }

    private TestResult alertsToggle() {
        if (manager == null) return fail("live setup missing");
        var settings = BankSystemModBackend.getInstances_forTesting().SERVER_SETTINGS.UTILITIES.ALERTS_DEFAULT_ON;
        boolean before = settings.get();
        try {
            User admin = manager.getUserByUUID(ADMIN);
            admin.getCustomData().remove(AdminAudit.ALERTS_KEY);
            settings.set(true);
            if (!AdminAudit.alertsEnabled(admin)) return fail("default true not used");
            settings.set(false);
            if (AdminAudit.alertsEnabled(admin)) return fail("default false not used");
            ServerBankSystemCommandHandler h = new ServerBankSystemCommandHandler();
            if (h.banksystem_alerts(PLAYER, true)) return fail("non-admin toggled alerts");
            if (!h.banksystem_alerts(ADMIN, true) || !AdminAudit.alertsEnabled(admin)) return fail("on");
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            if (!admin.save(tag)) return fail("save");
            User loaded = User.createFromTag(tag);
            if (loaded == null || !AdminAudit.alertsEnabled(loaded)) return fail("toggle lost on save/load");
            settings.set(true);
            if (!h.banksystem_alerts(ADMIN, false) || AdminAudit.alertsEnabled(admin)) return fail("off must beat default on");
            return pass("default from config; explicit toggle wins and survives save/load; admin only");
        } finally {
            settings.set(before);
        }
    }

    private TestResult flagRaise() {
        if (!ready()) return fail("live setup missing");
        SuspicionFlagManager flags = BankSystemModBackend.getInstances_forTesting().SUSPICION_FLAG_MANAGER;
        if (flags == null) return fail("no SuspicionFlagManager");
        List<String> captured = new ArrayList<>();
        AdminAudit.alertCapture = line -> { synchronized (captured) { captured.add(line); } };
        try {
            AdminAudit.raise(accountNr, 9999, 2, 123L, "ref-" + MARKER, MARKER + "-flag");
            List<SuspicionFlagRecord> rows = flags.getRecent(accountNr, 0, 10).get(10, TimeUnit.SECONDS);
            if (rows.size() != 1) return fail("rows " + rows.size());
            SuspicionFlagRecord r = rows.get(0);
            if (r.flagType() != 9999 || r.severity() != 2 || r.amount() != 123L || !("ref-" + MARKER).equals(r.ref())
                    || !(MARKER + "-flag").equals(r.details()) || r.acknowledged()) return fail("row " + r);
            flags.acknowledge(r.id()).get(10, TimeUnit.SECONDS);
            if (!flags.getRecent(accountNr, 0, 10).get(10, TimeUnit.SECONDS).get(0).acknowledged()) return fail("acknowledge");
            awaitAlerts(captured, 1);
            synchronized (captured) {
                if (captured.size() != 1 || !captured.get(0).contains("Flag 9999") || captured.get(0).contains("ref-"))
                    return fail("alert " + captured);
            }
            if (auditLines(MARKER + "-flag").size() != 1) return fail("audit file line missing");
            return pass("raise -> row + file line + alert; acknowledge");
        } catch (Exception e) {
            return fail("threw: " + e);
        } finally {
            AdminAudit.alertCapture = null;
        }
    }

    private TestResult flagMigration() {
        Connection c = null;
        try {
            c = TransactionLedgerTests.openMemoryDb();
            try (Statement st = c.createStatement()) {
                // oldest possible shape: only the columns that can never be added later
                st.execute("CREATE TABLE SuspicionFlag (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, "
                        + "account_number INTEGER NOT NULL, flag_type INTEGER NOT NULL)");
                st.execute("INSERT INTO SuspicionFlag (ts, account_number, flag_type) VALUES (1000, 7, 1)");
            }
            List<String> first = DatabaseManager.migrateSuspicionFlag(c);
            List<String> second = DatabaseManager.migrateSuspicionFlag(c);
            c.commit();
            if (first.size() != 5 || !second.isEmpty()) return fail("added " + first + " then " + second);
            SuspicionFlagManager mgr = new SuspicionFlagManager(new TransactionLedgerTests.MemoryDb(c));
            List<SuspicionFlagRecord> rows = mgr.getRecent(7, 0, 0).get(10, TimeUnit.SECONDS);
            if (rows.size() != 1 || rows.get(0).severity() != 0 || rows.get(0).acknowledged()) return fail("old row " + rows);
            return pass("columns added once; old rows read with defaults");
        } catch (Exception e) {
            return fail("threw: " + e);
        } finally {
            try { if (c != null) c.close(); } catch (SQLException ignored) {}
        }
    }

    private TestResult pageAccess() {
        if (!ready()) return fail("live setup missing");
        AuditPageRequest req = new AuditPageRequest();
        AuditPageRequest.Query q = new AuditPageRequest.Query(accountNr, -1, 0, 0, 10);
        if (!req.handleOnMasterServer(q, "", PLAYER).join().denied()) return fail("non-admin got a page");
        if (!req.handleOnMasterServer(q, "audit-test-untrusted-slave", ADMIN).join().denied()) return fail("untrusted slave got a page");
        if (req.handleOnMasterServer(q, "", ADMIN).join().denied()) return fail("admin denied");
        return pass("non-admin and untrusted slave refused; admin served");
    }

    /**
     * Task #142: the server network list (addresses, ports, players) is refused to non-admins and to anything
     * arriving through an untrusted slave. A refusal is an immediately completed empty list; the admin call may
     * still be waiting on slave answers, so it is checked with getNow instead of join.
     */
    private TestResult networkInfoAccess() {
        if (!ready()) return fail("live setup missing");
        ServerNetworkInfoRequest req = new ServerNetworkInfoRequest();
        ServerNetworkInfoRequest.InputData in = new ServerNetworkInfoRequest.InputData(false);
        if (!refused(req.handleOnMasterServer(in, "", PLAYER))) return fail("non-admin got the server list");
        if (!refused(req.handleOnMasterServer(in, "audit-test-untrusted-slave", ADMIN))) return fail("untrusted slave got the server list");
        if (refused(req.handleOnMasterServer(in, "", ADMIN))) return fail("admin refused");
        return pass("non-admin and untrusted slave refused; admin served");
    }

    private static boolean refused(CompletableFuture<ServerNetworkInfoRequest.OutputData> reply) {
        ServerNetworkInfoRequest.OutputData out = reply.getNow(null);
        return out != null && out.servers().isEmpty();
    }

    /** BS-241: the chat text names the flag, says the severity, scales the amount and hides UUIDs / refs / codes. */
    private TestResult alertChatReadable() {
        String uuid = "00000000-0000-0000-0000-00000000b241";
        String chat = AdminAudit.flagChat(4, SuspicionChecks.SHARE_CREATED, 2, 600L,
                "Tester (" + uuid + ") created 6 share(s)\nline 2\nline 3 " + uuid + "\nline 4\nline 5");
        if (!chat.contains("MEDIUM") || !chat.contains(AdminAudit.flagTitle(SuspicionChecks.SHARE_CREATED)))
            return fail("severity / title missing:\n" + chat);
        if (chat.contains("FLAG ") || chat.contains("severity") || chat.contains(uuid) || chat.contains("600"))
            return fail("raw code, UUID or raw amount in chat:\n" + chat);
        if (!chat.contains("6") || !chat.contains("Tester created 6 share(s)") || chat.contains("line 5")
                || !chat.contains("/banksystem audit 4")) return fail("amount, details or cap wrong:\n" + chat);
        String money = AdminAudit.flagChat(0, SuspicionChecks.MONEY_SUPPLY_GROWTH, 1, 123_456L, null);
        if (!money.contains("LOW") || !money.contains(net.kroia.banksystem.api.MoneyFormat.formatMoney(123_456L)))
            return fail("money flag:\n" + money);
        if (!AdminAudit.flagChat(4, 9999, 3, 0L, null).contains("Flag 9999")) return fail("unknown type fallback");
        return pass("readable title, severity, scaled amounts, UUIDs stripped, 4 detail lines then a pointer");
    }

    /** Task #119: SUSPICION_FLAGGED fires once per raise, with the flag's constant name. */
    private TestResult flagEvent() {
        if (!ready()) return fail("live setup missing");
        List<SuspicionFlaggedEvent> events = new ArrayList<>();
        java.util.function.Consumer<SuspicionFlaggedEvent> listener = e -> {
            if (e.accountNumber() == accountNr) synchronized (events) { events.add(e); }
        };
        var event = BankSystemMod.getAPI().getEvents().getSuspicionFlaggedEvent();
        List<String> captured = new ArrayList<>();
        AdminAudit.alertCapture = line -> { synchronized (captured) { captured.add(line); } };
        event.addListener(listener);
        try {
            long before = System.currentTimeMillis();
            AdminAudit.raise(accountNr, SuspicionChecks.WEALTH_JUMP, 3, 456L, "ref-" + MARKER, MARKER + "-event");
            long after = System.currentTimeMillis();
            awaitAlerts(captured, 1); // keep the capture until the (possibly deferred) alert arrived
            synchronized (events) {
                if (events.size() != 1) return fail("expected 1 event, got " + events);
                SuspicionFlaggedEvent e = events.get(0);
                if (e.flagType() != SuspicionChecks.WEALTH_JUMP || !"WEALTH_JUMP".equals(e.flagName())
                        || e.severity() != 3 || e.amount() != 456L || !("ref-" + MARKER).equals(e.ref())
                        || !(MARKER + "-event").equals(e.details()) || e.timeMs() < before || e.timeMs() > after)
                    return fail("event " + e);
            }
            return pass("raise -> one event with flagName WEALTH_JUMP");
        } finally {
            event.removeListener(listener);
            AdminAudit.alertCapture = null;
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Alerts are dispatched on the server main thread; off that thread they arrive later.
     * Polls up to 5 s (returns at once when the test itself runs on the server thread, where
     * dispatch is inline), then lets one more tick pass so a wrong extra alert would show.
     */
    /**
     * BS-257: op/deop, trust/untrust, allow/disallow through the master command handler and a share-excess
     * acknowledge each write exactly one {@code ADMIN <type> ...} line; repeats (no change) and an automatic
     * allow (no admin actor) write none.
     */
    private TestResult adminChangeLines() {
        if (!ready()) return fail("live setup missing");
        ServerBankSystemCommandHandler h = new ServerBankSystemCommandHandler();
        String by = " by AuditTestAdmin (" + ADMIN + ")";
        String player = "player " + PLAYER + " \"AuditTestPlayer\" state ";
        String slaveId = MARKER + "-slave";
        boolean playerWasAdmin = manager.isBanksystemAdmin(PLAYER);
        try {
            if (playerWasAdmin) manager.setBanksystemAdminMode(PLAYER, false);
            h.banksystem_setBankSystemAdminMode_user(ADMIN, "AuditTestPlayer", true);
            h.banksystem_setBankSystemAdminMode_user(ADMIN, "AuditTestPlayer", true); // no change
            h.banksystem_setBankSystemAdminMode_user(ADMIN, "AuditTestPlayer", false);
            h.banksystem_setSlaveServerTrusted(ADMIN, slaveId, true);
            h.banksystem_setSlaveServerTrusted(ADMIN, slaveId, false);

            ItemStack paper = new ItemStack(Items.PAPER);
            CompoundTag tag = new CompoundTag();
            tag.putString(SYNTHETIC_KEY, "audit-item");
            paper.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            ItemID item = stateGuard.register(paper);
            if (!item.isValid()) return fail("could not register the test item");
            String it = "item " + item.getShort() + " " + AdminAudit.quote(item.getName()) + " state ";
            h.banksystem_allowItem(ADMIN, item);
            h.banksystem_allowItem(ADMIN, item); // no change
            h.banksystem_disallowItem(ADMIN, item);
            manager.allowItemID(item); // automatic allow, no admin actor: no line

            CompanyManager cm = new CompanyManager();
            cm.detachBankManager();
            Map<String, ListTag> data = new HashMap<>();
            ListTag meta = new ListTag();
            CompoundTag metaTag = new CompoundTag();
            metaTag.putInt("nextCompanyId", 900_258);
            meta.add(metaTag);
            data.put("meta", meta);
            ListTag companies = new ListTag();
            CompoundTag ct = new CompoundTag();
            ct.putInt("companyId", 900_257);
            ct.putString("name", MARKER + "-company");
            ct.putInt("bankAccountNr", accountNr);
            ct.putLong("maxSupply", 1000);
            ct.putLong("createdAt", 1L);
            ct.putLong("totalSharesIssued", 5);
            companies.add(ct);
            data.put("companies", companies);
            cm.load(data);
            Company c = cm.getById(900_257);
            if (c == null) return fail("test company did not load");
            ShareIntegrity.apply(cm, c, 7, 100L); // opens the share-excess alert
            // caller null = console: no rights gate, so no bank manager needed
            AsyncCompanyManager.acknowledgeShareExcess(MARKER + "-company", null, null, cm);
            AsyncCompanyManager.acknowledgeShareExcess(MARKER + "-company", null, null, cm); // nothing open: no line

            String[] expected = {
                    "ADMIN RIGHTS_OP " + player + "admin" + by,
                    "ADMIN RIGHTS_DEOP " + player + "not_admin" + by,
                    "ADMIN SLAVE_TRUST slave \"" + slaveId + "\" state trusted" + by,
                    "ADMIN SLAVE_UNTRUST slave \"" + slaveId + "\" state untrusted" + by,
                    "ADMIN ITEM_ALLOW " + it + "allowed" + by,
                    "ADMIN ITEM_DISALLOW " + it + "disallowed" + by,
                    "ADMIN FLAG_ACK company 900257 \"" + MARKER + "-company\" account " + accountNr
                            + " flag SHARE_EXCESS excess 0 state acknowledged by Console"};
            List<String> lines = auditLines("ADMIN ");
            for (String e : expected) {
                long n = lines.stream().filter(l -> l.endsWith(e)).count();
                if (n != 1) return fail(n + " lines for '" + e + "'; file lines: " + lines);
            }
            long items = lines.stream().filter(l -> l.contains(" " + it)).count();
            if (items != 2) return fail("expected 2 item lines (no repeat, no automatic allow), got " + items);
            return pass(expected.length + " admin-change lines, none for repeats or the automatic allow");
        } finally {
            manager.setBanksystemAdminMode(PLAYER, playerWasAdmin);
            if (manager.isSlaveServerTrusted(slaveId)) manager.untrustSlaveServer(slaveId);
        }
    }

    private static void awaitAlerts(List<String> captured, int n) {
        net.minecraft.server.MinecraftServer srv = dev.architectury.utils.GameInstance.getServer();
        if (srv == null || srv.isSameThread()) return;
        long end = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < end) {
            synchronized (captured) { if (captured.size() >= n) break; }
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        try { srv.submit(() -> {}).get(5, TimeUnit.SECONDS); } catch (Exception ignored) {}
    }

    private static List<String> auditLines(String marker) {
        List<String> out = new ArrayList<>();
        Path file = AdminAudit.auditFile(LocalDate.now());
        try {
            if (file != null && Files.exists(file))
                for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) if (l.contains(marker)) out.add(l);
        } catch (Exception ignored) {}
        return out;
    }

    /** Strips this suite's lines from today's (and yesterday's, across midnight) audit file. */
    private static void removeMarkedAuditLines() {
        for (LocalDate d : List.of(LocalDate.now(), LocalDate.now().minusDays(1))) {
            Path file = AdminAudit.auditFile(d);
            try {
                if (file == null || !Files.exists(file)) continue;
                List<String> keep = new ArrayList<>();
                for (String l : Files.readAllLines(file, StandardCharsets.UTF_8))
                    if (!l.contains(MARKER) && !l.contains(TEST_UUID_PREFIX)) keep.add(l);
                if (keep.isEmpty()) Files.delete(file);
                else Files.write(file, keep, StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
        }
    }

    /** Deletes the ledger and suspicion-flag rows of {@code accounts} (also used by DiscordFeedTests). */
    static void deleteRows(List<Integer> accounts) {
        DatabaseManager db = BankSystemModBackend.getDatabaseManager();
        if (db == null) return;
        try {
            db.getDatabaseThread().submit(() -> {
                try (var a = db.getConnection().prepareStatement("DELETE FROM TransactionLog WHERE account_number = ?");
                     var b = db.getConnection().prepareStatement("DELETE FROM SuspicionFlag WHERE account_number = ?")) {
                    for (int nr : accounts) {
                        a.setInt(1, nr); a.executeUpdate();
                        b.setInt(1, nr); b.executeUpdate();
                    }
                    db.commitTransaction();
                } catch (SQLException ignored) {}
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }
}
