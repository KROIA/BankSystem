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
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import net.kroia.banksystem.banking.ledger.AdminAudit;
import net.kroia.banksystem.banking.ledger.SuspicionChecks;
import net.kroia.banksystem.banking.ledger.TransactionLedger;
import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.data.table.SuspicionFlagManager;
import net.kroia.banksystem.data.table.TransactionLogManager;
import net.kroia.banksystem.data.table.record.SuspicionFlagRecord;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.minecraft.command.ServerBankSystemCommandHandler;
import net.kroia.banksystem.networking.general.AuditPageRequest;
import net.kroia.banksystem.networking.multi_server.ServerNetworkInfoRequest;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

    private ISyncServerBankManager manager;
    private TransactionLogManager ledger;
    private LiveStateGuard stateGuard;
    private int accountNr = ServerBankAccount.INVALID_ACCOUNT_NUMBER;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.BANK_MANAGER;
    }

    @Override
    public void registerTests() {
        addTest("paged_query_filters_and_order", this::pagedQuery);
        addTest("admin_row_writes_audit_file_and_alert", this::adminRowAudit);
        addTest("alerts_toggle_persists_and_defaults", this::alertsToggle);
        addTest("suspicion_flag_raise_and_read", this::flagRaise);
        addTest("suspicion_flag_migration_idempotent", this::flagMigration);
        addTest("audit_page_refuses_non_admin", this::pageAccess);
        addTest("suspicion_flagged_event_once", this::flagEvent); // Task #119
        addTest("server_network_info_admin_only", this::networkInfoAccess); // Task #142
    }

    @Override
    public void setup() {
        IBankManager bm = BankSystemMod.getAPI().getServerBankManager();
        if (bm == null || bm.getSync() == null) return;
        manager = bm.getSync();
        ledger = BankSystemModBackend.getTransactionLogManager();
        stateGuard = LiveStateGuard.capture(manager);
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
            if (!alert.contains("ADMIN_SET") || !alert.contains(MARKER + "-admin")) return fail("alert text: " + alert);
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
                if (captured.size() != 1 || !captured.get(0).startsWith("FLAG 9999")) return fail("alert " + captured);
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
                for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) if (!l.contains(MARKER)) keep.add(l);
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
