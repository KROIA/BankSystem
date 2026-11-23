package net.kroia.banksystem.banking.ledger;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.banking.company.CompanyManager;
import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.data.table.BalanceHistoryManager;
import net.kroia.banksystem.data.table.TransactionLogManager;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.util.MoneyFormat;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * Task #113 (v2.2.0) — compact markdown audit digest: money supply start/end, top cash gainers,
 * every suspicion flag and the admin actions of a period. Master only; built on the DB worker.
 * <ul>
 *     <li>Daily: {@link #tick} writes {@code audit/digest-<yyyy-mm-dd>.md} for the previous day on
 *     the first master tick after the date changes. An existing file is never rewritten, so a
 *     restart does not write the same day twice.</li>
 *     <li>On demand: {@link #report} ({@code /banksystem auditreport <hours>}).</li>
 * </ul>
 * Cash = money item, free + locked, from the balance snapshots (each account's last snapshot at
 * or before the period edge; deleted accounts keep their last value).
 */
public final class AuditDigest {

    public static final int MAX_REPORT_HOURS = 720;
    public static final int LAST_ADMIN_ACTIONS = 20;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm");
    private static final int[] ADMIN_TYPES = {LedgerType.ADMIN_SET.code(), LedgerType.ADMIN_ADD.code(),
            LedgerType.ADMIN_REMOVE.code(), LedgerType.ADMIN_UNLOCK.code(), LedgerType.ADMIN_ACCOUNT_EDIT.code(),
            LedgerType.BANK_CLOSED.code(), LedgerType.ACCOUNT_FREEZE.code(), LedgerType.ADMIN_DISALLOW_ITEM.code(),
            LedgerType.ACCOUNT_DELETE_LOSS.code()};
    private static final int[] PAYOUT_TYPES = {LedgerType.PAYOUT.code(), LedgerType.DIVIDEND.code()};

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    /** Next time {@link #tick} looks at the date (start of tomorrow). */
    private static volatile long nextDailyCheckMs = 0;

    private AuditDigest() {}

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
        nextDailyCheckMs = 0;
    }

    /** Master tick hook: cheap compare, acts once per day. */
    public static void tick(long nowMs) {
        if (nowMs < nextDailyCheckMs || BACKEND_INSTANCES == null || BACKEND_INSTANCES.isSlaveServer) return;
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
        nextDailyCheckMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        net.kroia.banksystem.banking.company.ShareIntegrity.checkAll(); // BS-235: daily share check
        DatabaseManager db = BankSystemModBackend.getDatabaseManager();
        Path dir = AdminAudit.auditDir();
        if (db == null || dir == null || BACKEND_INSTANCES.SERVER_SETTINGS == null
                || !BACKEND_INSTANCES.SERVER_SETTINGS.AUDIT.DAILY_DIGEST_ENABLED.get()) return;
        writeDaily(db, dir, today.minusDays(1), topN(), MoneyItem.getItemID().getShort(), accountNames(), companyAccounts())
                .exceptionally(t -> {
                    if (BACKEND_INSTANCES.LOGGER != null) BACKEND_INSTANCES.LOGGER.warn("[AuditDigest] daily digest failed: " + t);
                    return null;
                });
    }

    /**
     * Writes {@code digest-<day>.md} for {@code day} (00:00 to 24:00 server time) unless it exists.
     *
     * @return the file, or null when it already existed
     */
    public static CompletableFuture<Path> writeDaily(DatabaseManager db, Path dir, LocalDate day, int topN, short money) {
        return writeDaily(db, dir, day, topN, money, Map.of(), Map.of());
    }

    /**
     * As {@link #writeDaily(DatabaseManager, Path, LocalDate, int, short)}; {@code names} from
     * {@link #accountNames()}, {@code companyAccounts} from {@link #companyAccounts()}.
     */
    public static CompletableFuture<Path> writeDaily(DatabaseManager db, Path dir, LocalDate day, int topN, short money,
                                                     Map<Integer, String> names, Map<Integer, Integer> companyAccounts) {
        Path file = dir.resolve("digest-" + day + ".md").toAbsolutePath().normalize();
        if (Files.exists(file)) return CompletableFuture.completedFuture(null);
        ZoneId zone = ZoneId.systemDefault();
        long from = day.atStartOfDay(zone).toInstant().toEpochMilli();
        long to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        return write(db, file, from, to, topN, money, names, companyAccounts, true);
    }

    /**
     * {@code /banksystem auditreport <hours>}: digest of the last {@code hours} hours, written to
     * {@code digest-<yyyy-mm-dd_HHmm>-<hours>h.md}. Fails for hours outside 1..720.
     *
     * @return the absolute file path
     */
    public static CompletableFuture<Path> report(int hours, long nowMs) {
        if (hours < 1 || hours > MAX_REPORT_HOURS)
            return CompletableFuture.failedFuture(new IllegalArgumentException("hours must be 1.." + MAX_REPORT_HOURS));
        DatabaseManager db = BankSystemModBackend.getDatabaseManager();
        Path dir = AdminAudit.auditDir();
        if (db == null || dir == null || BACKEND_INSTANCES == null || BACKEND_INSTANCES.isSlaveServer)
            return CompletableFuture.failedFuture(new IllegalStateException("audit report needs the master server"));
        Path file = dir.resolve(reportFileName(hours, nowMs)).toAbsolutePath().normalize();
        return write(db, file, nowMs - hours * 3_600_000L, nowMs, topN(), MoneyItem.getItemID().getShort(), accountNames(), companyAccounts(), false);
    }

    public static String reportFileName(int hours, long nowMs) {
        return "digest-" + FILE_TIME.format(Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault())) + "-" + hours + "h.md";
    }

    private static int topN() {
        return BACKEND_INSTANCES != null && BACKEND_INSTANCES.SERVER_SETTINGS != null
                ? Math.max(1, BACKEND_INSTANCES.SERVER_SETTINGS.AUDIT.DIGEST_TOP_N.get()) : 10;
    }

    /**
     * Account number to name for every account with a money bank. Call on the server thread: the
     * digest itself is built on the DB thread and must not read live accounts.
     */
    private static Map<Integer, String> accountNames() {
        Map<Integer, String> names = new HashMap<>();
        IServerBankManager bm = BACKEND_INSTANCES != null && BACKEND_INSTANCES.SERVER_BANK_MANAGER != null
                ? BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync() : null;
        if (bm == null) return names;
        for (IServerBankAccount a : bm.getBankAccounts(MoneyItem.getItemID()))
            if (a != null && a.getAccountName() != null) names.put(a.getAccountNumber(), a.getAccountName());
        return names;
    }

    /** Company id to its bank account number. Call on the server thread (live company state). */
    private static Map<Integer, Integer> companyAccounts() {
        Map<Integer, Integer> accounts = new HashMap<>();
        CompanyManager cm = CompanyManager.get();
        if (cm != null) cm.forEach(c -> accounts.put(c.getCompanyId(), c.getBankAccountNr()));
        return accounts;
    }

    private static CompletableFuture<Path> write(DatabaseManager db, Path file, long from, long to, int topN, short money,
                                                 Map<Integer, String> names, Map<Integer, Integer> companyAccounts,
                                                 boolean createOnly) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String md = build(db, from, to, topN, money, names, companyAccounts);
                Files.createDirectories(file.getParent());
                if (createOnly) {
                    if (Files.exists(file)) return null;
                    Files.writeString(file, md, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
                } else {
                    Files.writeString(file, md, StandardCharsets.UTF_8);
                }
                return file;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, db.getDatabaseThread());
    }

    /** Builds the markdown for {@code [from, to)}. Call on the DB thread. */
    public static String build(DatabaseManager db, long from, long to, int topN, short money) throws SQLException {
        return build(db, from, to, topN, money, Map.of(), Map.of());
    }

    /**
     * As {@link #build(DatabaseManager, long, long, int, short)}; {@code names} (account number to
     * name, snapshot taken on the server thread) fills the Name columns, "#nr" when absent.
     * {@code companyAccounts} (company id to account number, same snapshot) finds the paying leg of
     * legacy unsigned PAYOUT / DIVIDEND rows; without it those rows are left out.
     */
    public static String build(DatabaseManager db, long from, long to, int topN, short money, Map<Integer, String> names,
                               Map<Integer, Integer> companyAccounts) throws SQLException {
        Connection c = db.getConnection();
        StringBuilder sb = new StringBuilder();
        sb.append("# BankSystem audit digest\n\n")
                .append("Period: ").append(time(from)).append(" to ").append(time(to))
                .append(" (").append(ZoneId.systemDefault().getId()).append(")\n\n");

        // Money supply
        Map<Integer, Long> start = BalanceHistoryManager.totalsAt(c, money, from - 1);
        Map<Integer, Long> end = BalanceHistoryManager.totalsAt(c, money, to - 1);
        long s = sum(start), e = sum(end);
        sb.append("## Money supply\n\n| | Amount |\n|---|---:|\n")
                .append("| Start | ").append(MoneyFormat.format(s)).append(" |\n")
                .append("| End | ").append(MoneyFormat.format(e)).append(" |\n")
                .append("| Change | ").append(MoneyFormat.format(e - s))
                .append(s > 0 ? String.format(" (%+.1f%%)", (e - s) * 100.0 / s) : "").append(" |\n\n");

        // Top gainers by cash delta
        List<long[]> gains = new ArrayList<>(); // {account, start, end}
        for (Map.Entry<Integer, Long> en : end.entrySet()) {
            long st = start.getOrDefault(en.getKey(), 0L);
            if (en.getValue() > st) gains.add(new long[]{en.getKey(), st, en.getValue()});
        }
        gains.sort((a, b) -> Long.compare(b[2] - b[1], a[2] - a[1]));
        sb.append("## Top ").append(topN).append(" cash gainers\n\n");
        if (gains.isEmpty()) sb.append("None.\n\n");
        else {
            sb.append("| # | Account | Name | Start | End | Change |\n|---:|---:|---|---:|---:|---:|\n");
            for (int i = 0; i < gains.size() && i < topN; i++) {
                long[] g = gains.get(i);
                sb.append("| ").append(i + 1).append(" | ").append(g[0])
                        .append(" | ").append(cell(names.getOrDefault((int) g[0], "#" + g[0]))).append(" | ").append(MoneyFormat.format(g[1]))
                        .append(" | ").append(MoneyFormat.format(g[2])).append(" | ").append(MoneyFormat.format(g[2] - g[1])).append(" |\n");
            }
            sb.append('\n');
        }

        // Flags
        List<String> flagRows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT ts, account_number, flag_type, amount, details FROM SuspicionFlag "
                + "WHERE ts >= ? AND ts < ? ORDER BY ts ASC, id ASC")) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    flagRows.add("| " + time(rs.getLong(1)) + " | " + SuspicionChecks.typeName(rs.getInt(3)) + " | " + rs.getInt(2)
                            + " | " + MoneyFormat.format(rs.getLong(4)) + " | " + cell(rs.getString(5)) + " |\n");
            }
        }
        db.commitTransaction();
        sb.append("## Flags (").append(flagRows.size()).append(")\n\n");
        if (flagRows.isEmpty()) sb.append("None.\n\n");
        else {
            sb.append("| Time | Type | Account | Amount | Details |\n|---|---|---:|---:|---|\n");
            flagRows.forEach(sb::append);
            sb.append('\n');
        }

        // Payouts & dividends (BS-233): paying legs only, so a run is not counted twice. Signed rows:
        // the negative leg. Legacy unsigned rows (no balance_after): the leg on the company's account.
        TransactionLogManager log = new TransactionLogManager(db);
        Map<String, PayoutSum> payouts = new LinkedHashMap<>();
        for (TransactionLogRecord r : log.getByTypesInRangeSync(PAYOUT_TYPES, from, to)) {
            Integer payer = r.companyId() == null ? null : companyAccounts.get(r.companyId());
            boolean paying = r.amount() < 0 || (r.balanceAfter() == null && payer != null && payer == r.accountNumber());
            if (!paying) continue;
            PayoutSum p = payouts.computeIfAbsent(r.accountNumber() + ":" + r.type().code() + ":" + r.itemId(),
                    k -> new PayoutSum(r.accountNumber(), r.type(), r.itemId()));
            p.total += Math.abs(r.amount());
            p.runs.add(r.ref() != null ? r.ref() : String.valueOf(r.time())); // legacy legs of a run share the time
            if (r.otherAccount() != null) p.recipients.add(r.otherAccount());
        }
        sb.append("## Payouts & dividends (").append(payouts.size()).append(")\n\n");
        if (payouts.isEmpty()) sb.append("None.\n\n");
        else {
            sb.append("| Account | Name | Type | Item | Paid | Payouts | Recipients |\n|---:|---|---|---|---:|---:|---:|\n");
            List<PayoutSum> sorted = new ArrayList<>(payouts.values());
            sorted.sort((a, b) -> Long.compare(b.total, a.total));
            for (PayoutSum p : sorted)
                sb.append("| ").append(p.account).append(" | ").append(cell(names.getOrDefault(p.account, "#" + p.account)))
                        .append(" | ").append(p.type.name()).append(" | ").append(p.item == money ? "money" : "item " + p.item)
                        .append(" | ").append(MoneyFormat.format(p.total)).append(" | ").append(p.runs.size())
                        .append(" | ").append(p.recipients.size()).append(" |\n");
            sb.append('\n');
        }

        // Admin actions
        List<TransactionLogRecord> admin = new ArrayList<>();
        for (TransactionLogRecord r : log.getByTypesInRangeSync(ADMIN_TYPES, from, to))
            if (AdminAudit.isAdminRow(r)) admin.add(r);
        Map<String, Integer> counts = new TreeMap<>();
        for (TransactionLogRecord r : admin) counts.merge(r.type().name(), 1, Integer::sum);
        sb.append("## Admin actions (").append(admin.size()).append(")\n\n");
        if (admin.isEmpty()) sb.append("None.\n");
        else {
            sb.append("| Type | Count |\n|---|---:|\n");
            counts.forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(v).append(" |\n"));
            sb.append("\n### Last ").append(Math.min(LAST_ADMIN_ACTIONS, admin.size())).append("\n\n")
                    .append("| Time | Type | Account | Item | Amount | Actor | Note |\n|---|---|---:|---:|---:|---|---|\n");
            for (int i = 0; i < admin.size() && i < LAST_ADMIN_ACTIONS; i++) {
                TransactionLogRecord r = admin.get(i);
                sb.append("| ").append(time(r.time())).append(" | ").append(r.type().name()).append(" | ").append(r.accountNumber())
                        .append(" | ").append(r.itemId()).append(" | ").append(r.amount())
                        .append(" | ").append(cell(r.actorName() != null ? r.actorName() : r.source().name()))
                        .append(" | ").append(cell(r.note())).append(" |\n");
            }
        }
        return sb.toString();
    }

    /** One paying account's payouts or dividends in one item over the period. */
    private static final class PayoutSum {
        final int account;
        final LedgerType type;
        final short item;
        long total;
        final Set<String> runs = new HashSet<>();
        final Set<Integer> recipients = new HashSet<>();

        PayoutSum(int account, LedgerType type, short item) {
            this.account = account;
            this.type = type;
            this.item = item;
        }
    }

    private static long sum(Map<Integer, Long> m) {
        long t = 0;
        for (long v : m.values()) t += v;
        return t;
    }

    private static String time(long ms) {
        return TIME.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()));
    }

    private static String cell(@Nullable String s) {
        return s == null ? "" : s.replace("|", "\\|").replace('\n', ' ').replace('\r', ' ');
    }
}
