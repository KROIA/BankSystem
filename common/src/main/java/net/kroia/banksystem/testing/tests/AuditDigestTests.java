package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.ledger.AdminAudit;
import net.kroia.banksystem.banking.ledger.AuditDigest;
import net.kroia.banksystem.banking.ledger.SuspicionChecks;
import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.data.table.BalanceHistoryManager;
import net.kroia.banksystem.data.table.SuspicionFlagManager;
import net.kroia.banksystem.data.table.TransactionLogManager;
import net.kroia.banksystem.data.table.record.BalanceHistoryRecord;
import net.kroia.banksystem.data.table.record.SuspicionFlagRecord;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.general.BalanceHistoryRequest;
import net.kroia.banksystem.networking.general.FlagMarkersRequest;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.testing.LiveStateGuard;
import net.kroia.modutilities.setting.Setting;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Tasks #112 / #113 / #116 (v2.2.0) — whole-server history aggregate, money-supply flag, audit
 * digest and auditreport, flag-marker access.
 * <p>
 * <b>Server type:</b> MASTER_ONLY (BANK_MANAGER category). Aggregate and digest content run on
 * private in-memory databases; the money-supply test uses synthetic account numbers on the live
 * database and removes its rows, flags and audit lines afterwards.
 */
public class AuditDigestTests extends TestSuite {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000f113");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-00000000f114");
    /** Synthetic account numbers for the money-supply test (never created as accounts). */
    private static final int[] SUPPLY_ACCOUNTS = {2_000_001_131, 2_000_001_132};
    /** Baseline 2 x 61'728 raw = 1'234.56, used to find this suite's audit lines. */
    private static final long SUPPLY_BASE = 61_728L;
    private static final String SUPPLY_NEEDLE = "from 1'234.56 to";

    private ISyncServerBankManager manager;
    private LiveStateGuard stateGuard;
    private final Map<Setting<?>, Object> savedSettings = new HashMap<>();
    private final List<String> alerts = new ArrayList<>();

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.BANK_MANAGER;
    }

    @Override
    public void registerTests() {
        addTest("aggregate_carries_values_forward", this::aggregate);
        addTest("money_supply_flag_threshold_and_cooldown", this::moneySupply);
        addTest("digest_sections_order_and_flags", this::digestContent);
        addTest("digest_daily_written_once", this::digestOnce);
        addTest("share_excess_details", this::shareExcessDetails); // BS-235
        addTest("auditreport_path_and_hours_bounds", this::reportPath);
        addTest("flag_markers_admin_only", this::markerAccess);
    }

    @Override
    public void setup() {
        IBankManager bm = BankSystemMod.getAPI().getServerBankManager();
        if (bm == null || bm.getSync() == null) return;
        manager = bm.getSync();
        stateGuard = LiveStateGuard.capture(manager);
        for (UUID u : List.of(PLAYER, ADMIN))
            if (!manager.userExists(u)) manager.addUser(new User(u, u.equals(ADMIN) ? "DigestTestAdmin" : "DigestTestPlayer"));
        manager.setBanksystemAdminMode(ADMIN, true);
        BankSystemModSettings.Audit s = BankSystemModBackend.getInstances_forTesting().SERVER_SETTINGS.AUDIT;
        for (Setting<?> st : List.of(s.MONEY_SUPPLY_FLAG_ENABLED, s.MONEY_SUPPLY_GROWTH_PERCENT,
                s.MONEY_SUPPLY_GROWTH_AMOUNT, s.MONEY_SUPPLY_COOLDOWN_HOURS))
            savedSettings.put(st, st.get());
        AdminAudit.alertCapture = line -> { synchronized (alerts) { alerts.add(line); } };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public void teardown() {
        try {
            if (manager == null) return;
            for (UUID u : List.of(PLAYER, ADMIN)) {
                IServerBankAccount personal = manager.getPersonalBankAccount(u);
                if (personal != null) manager.deleteBankAccount(personal.getAccountNumber());
                manager.removeUser(u);
            }
        } finally {
            for (Map.Entry<Setting<?>, Object> e : savedSettings.entrySet()) ((Setting) e.getKey()).set(e.getValue());
            savedSettings.clear();
            AdminAudit.alertCapture = null;
            synchronized (alerts) { alerts.clear(); }
            LiveStateGuard.restore(stateGuard);
            stateGuard = null;
        }
    }

    // ------------------------------------------------------------------ #112 aggregate

    private TestResult aggregate() {
        Connection c = null;
        try {
            c = TransactionLedgerTests.openMemoryDb();
            schema(c, "BalanceHistory");
            BalanceHistoryManager hm = new BalanceHistoryManager(new TransactionLedgerTests.MemoryDb(c));
            short item = 1;
            // Account A snapshots at 1000 and 3000, account B only at 2000 (offset).
            hm.save(List.of(new BalanceHistoryRecord(1001, item, 100, 0, 1000),
                    new BalanceHistoryRecord(1002, item, 40, 10, 2000),
                    new BalanceHistoryRecord(1001, item, 120, 0, 3000))).get(10, TimeUnit.SECONDS);
            // Bucket width 1000: naive per-bucket sums would be 100, 50, 120.
            String all = points(hm.getServerHistoryBucketed(0, 4000, 4).get(10, TimeUnit.SECONDS));
            if (!all.equals("1000=100 2000=150 3000=170")) return fail("window sums " + all);
            String anchored = points(hm.getServerHistoryBucketed(1500, 4000, 5).get(10, TimeUnit.SECONDS));
            if (!anchored.equals("1500=100 2000=150 3000=170")) return fail("anchored sums " + anchored);
            String sentinel = points(hm.getServerHistoryBucketed(Long.MIN_VALUE, Long.MAX_VALUE, 2).get(10, TimeUnit.SECONDS));
            if (!sentinel.equals("1000=100 2000=150 3000=170")) return fail("all-history sums " + sentinel);
            return pass("carry-forward sums: " + all);
        } catch (Exception e) {
            return fail("threw: " + e);
        } finally {
            try { if (c != null) c.close(); } catch (SQLException ignored) {}
        }
    }

    private static String points(List<BalanceHistoryRecord> rows) {
        StringBuilder sb = new StringBuilder();
        for (BalanceHistoryRecord r : rows) {
            if (r.accountNumber() != BalanceHistoryManager.SERVER_WIDE_ACCOUNT) return "bad account " + r.accountNumber();
            if (sb.length() > 0) sb.append(' ');
            sb.append(r.time()).append('=').append(r.balance() + r.lockedBalance());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ #112 money supply

    private TestResult moneySupply() {
        DatabaseManager db = BankSystemModBackend.getDatabaseManager();
        if (manager == null || db == null) return fail("live setup missing");
        BankSystemModSettings.Audit s = BankSystemModBackend.getInstances_forTesting().SERVER_SETTINGS.AUDIT;
        long[] maxId = {0};
        try {
            sql(db, c -> {
                try (var rs = c.createStatement().executeQuery("SELECT COALESCE(MAX(id), 0) FROM SuspicionFlag")) {
                    if (rs.next()) maxId[0] = rs.getLong(1);
                }
            });
            long now = System.currentTimeMillis();
            short money = MoneyItem.getItemID().getShort();
            BalanceHistoryManager hm = BankSystemModBackend.getInstances_forTesting().BALANCE_HISTORY_MANAGER;
            List<BalanceHistoryRecord> rows = new ArrayList<>();
            for (int nr : SUPPLY_ACCOUNTS) rows.add(new BalanceHistoryRecord(nr, money, SUPPLY_BASE, 0, now - 2 * 3_600_000L));
            hm.save(rows).get(10, TimeUnit.SECONDS);
            List<Integer> accounts = List.of(SUPPLY_ACCOUNTS[0], SUPPLY_ACCOUNTS[1]);
            long base = 2 * SUPPLY_BASE;
            s.MONEY_SUPPLY_FLAG_ENABLED.set(true);
            s.MONEY_SUPPLY_GROWTH_PERCENT.set(10);
            s.MONEY_SUPPLY_GROWTH_AMOUNT.set(1L);       // $1 = 100 raw
            s.MONEY_SUPPLY_COOLDOWN_HOURS.set(0);       // ignore flags raised before this test

            if (SuspicionChecks.checkMoneySupply(accounts, base + base / 20, now).get(10, TimeUnit.SECONDS))
                return fail("5% growth flagged");
            s.MONEY_SUPPLY_GROWTH_AMOUNT.set(100_000L);
            if (SuspicionChecks.checkMoneySupply(accounts, base * 2, now).get(10, TimeUnit.SECONDS))
                return fail("growth below the amount flagged");
            s.MONEY_SUPPLY_GROWTH_AMOUNT.set(1L);
            if (!SuspicionChecks.checkMoneySupply(accounts, base * 2, now).get(10, TimeUnit.SECONDS))
                return fail("100% growth not flagged");
            s.MONEY_SUPPLY_COOLDOWN_HOURS.set(6);
            if (SuspicionChecks.checkMoneySupply(accounts, base * 3, now + 1).get(10, TimeUnit.SECONDS))
                return fail("flagged again inside the cooldown");
            int[] count = {0};
            sql(db, c -> {
                try (var ps = c.prepareStatement("SELECT COUNT(*) FROM SuspicionFlag WHERE id > ? AND account_number = 0 AND flag_type = ?")) {
                    ps.setLong(1, maxId[0]);
                    ps.setInt(2, SuspicionChecks.MONEY_SUPPLY_GROWTH);
                    try (var rs = ps.executeQuery()) { if (rs.next()) count[0] = rs.getInt(1); }
                }
            });
            if (count[0] != 1) return fail("expected 1 money-supply flag row, got " + count[0]);
            return pass("quiet below percent and amount, fired once, cooldown held");
        } catch (Exception e) {
            return fail("threw: " + e);
        } finally {
            sql(db, c -> {
                try (var h = c.prepareStatement("DELETE FROM BalanceHistory WHERE account_number = ?");
                     var f = c.prepareStatement("DELETE FROM SuspicionFlag WHERE id > ? AND account_number = 0 AND flag_type = ?")) {
                    for (int nr : SUPPLY_ACCOUNTS) { h.setInt(1, nr); h.executeUpdate(); }
                    f.setLong(1, maxId[0]);
                    f.setInt(2, SuspicionChecks.MONEY_SUPPLY_GROWTH);
                    f.executeUpdate();
                }
            });
            removeSupplyAuditLines();
        }
    }

    // ------------------------------------------------------------------ #113 digest

    private TestResult digestContent() {
        Connection c = null;
        try {
            c = TransactionLedgerTests.openMemoryDb();
            TransactionLedgerTests.MemoryDb mem = digestDb(c);
            long from = 1_000_000_000_000L, to = from + 86_400_000L;
            String md = mem.getDatabaseThread().submit(() -> AuditDigest.build(mem, from, to, 2, MONEY)).get(10, TimeUnit.SECONDS);
            for (String section : List.of("# BankSystem audit digest", "## Money supply", "## Top 2 cash gainers",
                    "## Flags (2)", "## Admin actions (3)", "### Last 3"))
                if (!md.contains(section)) return fail("missing \"" + section + "\":\n" + md);
            if (!md.contains("| Start | 6.00 |") || !md.contains("| End | 19.50 |")) return fail("money supply wrong:\n" + md);
            int first = md.indexOf("| 1 | 12 |"), second = md.indexOf("| 2 | 11 |");
            if (first < 0 || second < first || md.contains("| 3 | 13 |")) return fail("top-N order wrong:\n" + md);
            if (!md.contains("digest-flag-a") || !md.contains("digest-flag-b") || md.contains("digest-flag-outside"))
                return fail("flags wrong:\n" + md);
            if (!md.contains("| ADMIN_ADD | 2 |") || !md.contains("| ADMIN_SET | 1 |") || md.contains("BANK_CLOSED"))
                return fail("admin counts wrong:\n" + md);
            // BS-226: gainers carry the account name (escaped), "#nr" when the map lacks it.
            String named = mem.getDatabaseThread().submit(() -> AuditDigest.build(mem, from, to, 2, MONEY,
                    java.util.Map.of(12, "Corner|Shop", 20, "DigestCo"), java.util.Map.of(7, 20))).get(10, TimeUnit.SECONDS);
            if (!named.contains("| 1 | 12 | Corner\\|Shop |") || !named.contains("| 2 | 11 | #11 |"))
                return fail("gainer names wrong:\n" + named);
            // BS-233: paying legs only; the legacy pair needs the company map, the signed run does not.
            if (!md.contains("## Payouts & dividends (1)") || !md.contains("| 20 | #20 | DIVIDEND | money | 5.00 | 1 | 2 |"))
                return fail("payout section (no company map) wrong:\n" + md);
            if (!named.contains("## Payouts & dividends (2)") || !named.contains("| 20 | DigestCo | DIVIDEND | money | 5.00 | 1 | 2 |")
                    || !named.contains("| 20 | DigestCo | PAYOUT | money | 0.50 | 1 | 1 |") || named.contains("| 11 | #11 | DIVIDEND"))
                return fail("payout section wrong:\n" + named);
            // BS-230: company stats classify by type + sign: -300, -200 and the legacy +50 payout are out, +100 is in.
            net.kroia.banksystem.banking.company.CompanyStatsQuery.CompanyHeadlineMetrics m = mem.getDatabaseThread().submit(() ->
                    net.kroia.banksystem.banking.company.CompanyStatsQuery.getHeadlineMetrics(mem.getConnection(), 20, MONEY, 0L))
                    .get(10, TimeUnit.SECONDS);
            if (m.totalEarnings() != 100 || m.totalSpendings() != 550)
                return fail("company stats in/out wrong: earn " + m.totalEarnings() + " spend " + m.totalSpendings());
            return pass("sections, top-N order, gainer names, payouts, flags and admin counts present");
        } catch (Exception e) {
            return fail("threw: " + e);
        } finally {
            try { if (c != null) c.close(); } catch (SQLException ignored) {}
        }
    }

    private TestResult digestOnce() {
        Connection c = null;
        Path dir = null;
        try {
            c = TransactionLedgerTests.openMemoryDb();
            TransactionLedgerTests.MemoryDb mem = digestDb(c);
            dir = Files.createTempDirectory("banksystem-digest-test");
            LocalDate day = LocalDate.of(2001, 9, 9);
            Path firstWrite = AuditDigest.writeDaily(mem, dir, day, 10, MONEY).get(10, TimeUnit.SECONDS);
            if (firstWrite == null || !Files.exists(firstWrite)) return fail("first write missing");
            if (!firstWrite.getFileName().toString().equals("digest-2001-09-09.md")) return fail("name " + firstWrite.getFileName());
            Files.writeString(firstWrite, "kept", StandardCharsets.UTF_8);
            Path again = AuditDigest.writeDaily(mem, dir, day, 10, MONEY).get(10, TimeUnit.SECONDS);
            if (again != null) return fail("second write for the same day");
            if (!Files.readString(firstWrite, StandardCharsets.UTF_8).equals("kept")) return fail("existing digest overwritten");
            return pass("written once, existing file kept");
        } catch (Exception e) {
            return fail("threw: " + e);
        } finally {
            try { if (c != null) c.close(); } catch (SQLException ignored) {}
            deleteTree(dir);
        }
    }

    private TestResult reportPath() {
        if (AdminAudit.auditDir() == null) return fail("no audit dir");
        long now = System.currentTimeMillis();
        for (int bad : new int[]{0, AuditDigest.MAX_REPORT_HOURS + 1}) {
            CompletableFuture<Path> f = AuditDigest.report(bad, now);
            try {
                f.get(10, TimeUnit.SECONDS);
                return fail("hours " + bad + " accepted");
            } catch (ExecutionException e) {
                if (!(e.getCause() instanceof IllegalArgumentException)) return fail("hours " + bad + ": " + e.getCause());
            } catch (Exception e) {
                return fail("threw: " + e);
            }
        }
        Path file = null;
        try {
            file = AuditDigest.report(AuditDigest.MAX_REPORT_HOURS, now).get(30, TimeUnit.SECONDS);
            Path dir = AdminAudit.auditDir().toAbsolutePath().normalize();
            String name = file.getFileName().toString();
            if (!file.isAbsolute() || !file.getParent().equals(dir)) return fail("path " + file + " not in " + dir);
            if (!name.matches("digest-\\d{4}-\\d{2}-\\d{2}_\\d{4}-720h\\.md") || !name.equals(AuditDigest.reportFileName(720, now)))
                return fail("name " + name);
            if (!Files.readString(file, StandardCharsets.UTF_8).startsWith("# BankSystem audit digest")) return fail("content");
            return pass("bounds 1..720 enforced; " + name);
        } catch (Exception e) {
            return fail("threw: " + e);
        } finally {
            try { if (file != null) Files.deleteIfExists(file); } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------------ #116 / #112 access

    private TestResult markerAccess() {
        if (manager == null) return fail("live setup missing");
        FlagMarkersRequest req = new FlagMarkersRequest();
        if (!req.handleOnMasterServer(1, "", PLAYER).join().denied()) return fail("non-admin got markers");
        if (!req.handleOnMasterServer(1, "digest-test-untrusted-slave", ADMIN).join().denied()) return fail("untrusted slave got markers");
        if (req.handleOnMasterServer(1, "", ADMIN).join().denied()) return fail("admin denied");
        BalanceHistoryRequest hist = new BalanceHistoryRequest();
        var q = new BalanceHistoryRequest.Query(BalanceHistoryRequest.Query.SERVER_WIDE_ACCOUNT,
                BalanceHistoryRequest.Query.ALL_HISTORY_SENTINEL, System.currentTimeMillis(), 50);
        if (!hist.handleOnMasterServer(q, "", PLAYER).join().isEmpty()) return fail("non-admin got whole-server history");
        if (!hist.handleOnMasterServer(q, "digest-test-untrusted-slave", ADMIN).join().isEmpty())
            return fail("untrusted slave got whole-server history");
        return pass("markers and whole-server history refused to non-admin and untrusted slave; admin served");
    }

    // ------------------------------------------------------------------ helpers

    private static final short MONEY = 5;

    /**
     * Fills a private database: cash snapshots of accounts 11..14 around the period
     * [1e12, 1e12 + 1 day), three flags (one outside) and five ledger rows (3 admin actions).
     */
    /** BS-235: alert body lists deposits since the last consistent time and a signed per-player hint. */
    private TestResult shareExcessDetails() {
        Connection c = null;
        try {
            c = TransactionLedgerTests.openMemoryDb();
            schema(c, "TransactionLog");
            TransactionLedgerTests.MemoryDb mem = new TransactionLedgerTests.MemoryDb(c);
            short share = 4242;
            long since = 1_000_000_000_000L;
            net.kroia.banksystem.api.ledger.LedgerActor dup = net.kroia.banksystem.api.ledger.LedgerActor.player(PLAYER, "DupeTester");
            net.kroia.banksystem.api.ledger.LedgerActor adm = net.kroia.banksystem.api.ledger.LedgerActor.admin(ADMIN, "Withdrawer");
            List<TransactionLogRecord> rows = List.of(
                    TransactionLogRecord.ofAt(11, LedgerType.DEPOSIT, share, 900, dup, null, null, since - 10, null, null, null), // before since
                    TransactionLogRecord.ofAt(11, LedgerType.DEPOSIT, share, 500, dup, null, null, since + 1, null, null, null),
                    TransactionLogRecord.ofAt(20, LedgerType.SHARE_STAMP, (short) 0, 1, dup, null, 9, since + 2, null, null, null),
                    TransactionLogRecord.ofAt(20, LedgerType.WITHDRAW, share, 300, adm, null, 9, since + 3, null, null, null),
                    TransactionLogRecord.ofAt(20, LedgerType.ADMIN_REMOVE, share, 100, adm, null, 9, since + 4, null, "shares given to editor", null),
                    TransactionLogRecord.ofAt(20, LedgerType.ADMIN_REMOVE, share, 100, adm, null, 9, since + 5, null, "shares destroyed; issued 5->4", null),
                    TransactionLogRecord.ofAt(12, LedgerType.SHARE_TRADE, share, -200, adm, null, 9, since + 6, null, null, null));
            new TransactionLogManager(mem).save(rows).get(10, TimeUnit.SECONDS);
            String d = mem.getDatabaseThread().submit(() -> net.kroia.banksystem.banking.company.ShareIntegrity.details(
                    mem.getConnection(), 9, new short[]{share}, since)).get(10, TimeUnit.SECONDS);
            if (!d.contains("DupeTester +5 deposit -> account 11") || d.contains("+9 ")) return fail("deposit list wrong:\n" + d);
            if (!d.contains("Hint only, not proof") || !d.contains("Withdrawer +6") || !d.contains("DupeTester -4"))
                return fail("hint wrong:\n" + d);
            return pass("deposits since the consistent time, hint +6 / -4");
        } catch (Exception e) {
            return fail("threw: " + e);
        } finally {
            try { if (c != null) c.close(); } catch (SQLException ignored) {}
        }
    }

    private static TransactionLedgerTests.MemoryDb digestDb(Connection c) throws Exception {
        for (String t : List.of("BalanceHistory", "SuspicionFlag", "TransactionLog")) schema(c, t);
        TransactionLedgerTests.MemoryDb mem = new TransactionLedgerTests.MemoryDb(c);
        long from = 1_000_000_000_000L, mid = from + 3_600_000L, before = from - 3_600_000L;
        new BalanceHistoryManager(mem).save(List.of(
                new BalanceHistoryRecord(11, MONEY, 100, 0, before), new BalanceHistoryRecord(11, MONEY, 500, 0, mid),
                new BalanceHistoryRecord(12, MONEY, 1000, 0, mid),
                new BalanceHistoryRecord(13, MONEY, 300, 0, before), new BalanceHistoryRecord(13, MONEY, 350, 0, mid),
                new BalanceHistoryRecord(14, MONEY, 200, 0, before), new BalanceHistoryRecord(14, MONEY, 100, 0, mid)))
                .get(10, TimeUnit.SECONDS);
        SuspicionFlagManager flags = new SuspicionFlagManager(mem);
        flags.save(new SuspicionFlagRecord(-1, mid, 11, SuspicionChecks.WEALTH_JUMP, 1, 400, null, "digest-flag-a", false)).get(10, TimeUnit.SECONDS);
        flags.save(new SuspicionFlagRecord(-1, mid + 1, 0, SuspicionChecks.MONEY_SUPPLY_GROWTH, 1, 1350, null, "digest-flag-b", false)).get(10, TimeUnit.SECONDS);
        flags.save(new SuspicionFlagRecord(-1, before, 12, SuspicionChecks.WEALTH_JUMP, 1, 1, null, "digest-flag-outside", false)).get(10, TimeUnit.SECONDS);
        List<TransactionLogRecord> rows = new ArrayList<>();
        LedgerType[] types = {LedgerType.ADMIN_ADD, LedgerType.ADMIN_ADD, LedgerType.ADMIN_SET, LedgerType.DEPOSIT, LedgerType.BANK_CLOSED};
        for (int i = 0; i < types.length; i++)
            rows.add(new TransactionLogRecord(TransactionLogRecord.UNSAVED_ID, 11, null, types[i], MONEY, 10 + i, null, null, mid + i, "digest-test"));
        // BS-233: company 7 pays from account 20. Signed dividend run (2 recipients) + one legacy unsigned payout pair.
        net.kroia.banksystem.api.ledger.LedgerActor co = new net.kroia.banksystem.api.ledger.LedgerActor(null, "DigestCo",
                net.kroia.banksystem.api.ledger.LedgerActor.Source.SYSTEM);
        rows.add(TransactionLogRecord.ofAt(20, LedgerType.DIVIDEND, MONEY, -300, co, 11, 7, mid, "run-a", null, 700L));
        rows.add(TransactionLogRecord.ofAt(11, LedgerType.DIVIDEND, MONEY, 300, co, 20, 7, mid, "run-a", null, 800L));
        rows.add(TransactionLogRecord.ofAt(20, LedgerType.DIVIDEND, MONEY, -200, co, 12, 7, mid, "run-a", null, 500L));
        rows.add(TransactionLogRecord.ofAt(12, LedgerType.DIVIDEND, MONEY, 200, co, 20, 7, mid, "run-a", null, 1200L));
        rows.add(new TransactionLogRecord(TransactionLogRecord.UNSAVED_ID, 20, null, LedgerType.PAYOUT, MONEY, 50, 13, 7, mid + 10, null));
        rows.add(new TransactionLogRecord(TransactionLogRecord.UNSAVED_ID, 13, null, LedgerType.PAYOUT, MONEY, 50, 20, 7, mid + 10, null));
        rows.add(TransactionLogRecord.transfer(20, null, MONEY, 100, 11, mid + 20)); // BS-230: inflow for the stats check
        new TransactionLogManager(mem).save(rows).get(10, TimeUnit.SECONDS);
        return mem;
    }

    /** Runs the statements of {@code /sql/<table>.sql} on a private connection. */
    private static void schema(Connection c, String table) throws Exception {
        try (InputStream in = AuditDigestTests.class.getResourceAsStream("/sql/" + table + ".sql");
             Statement st = c.createStatement()) {
            if (in == null) throw new IllegalStateException("schema " + table + " not found");
            for (String part : new String(in.readAllBytes(), StandardCharsets.UTF_8).split(";"))
                if (!part.isBlank()) st.execute(part);
        }
        c.commit();
    }

    private interface SqlWork { void run(Connection c) throws SQLException; }

    private static void sql(DatabaseManager db, SqlWork work) {
        if (db == null) return;
        try {
            db.getDatabaseThread().submit(() -> {
                try {
                    work.run(db.getConnection());
                    db.commitTransaction();
                } catch (SQLException ignored) {}
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    private static void removeSupplyAuditLines() {
        for (LocalDate d : List.of(LocalDate.now(), LocalDate.now().minusDays(1))) {
            Path file = AdminAudit.auditFile(d);
            try {
                if (file == null || !Files.exists(file)) continue;
                List<String> keep = new ArrayList<>();
                for (String l : Files.readAllLines(file, StandardCharsets.UTF_8))
                    if (!(l.contains("FLAG " + SuspicionChecks.MONEY_SUPPLY_GROWTH + " account 0 ") && l.contains(SUPPLY_NEEDLE))) keep.add(l);
                if (keep.isEmpty()) Files.delete(file);
                else Files.write(file, keep, StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
        }
    }

    private static void deleteTree(Path dir) {
        if (dir == null) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
        } catch (Exception ignored) {}
    }
}
