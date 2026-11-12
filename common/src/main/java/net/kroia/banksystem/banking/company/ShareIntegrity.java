package net.kroia.banksystem.banking.company;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.api.bank.IServerBank;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.banking.ledger.AdminAudit;
import net.kroia.banksystem.banking.ledger.SuspicionChecks;
import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem;
import net.kroia.banksystem.util.ItemID;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BS-235 — share integrity, master only. A company's banked shares (free + locked over all its share
 * ItemIDs) must not exceed {@link Company#getTotalSharesIssued()} (stamped). More banked than stamped
 * raises one {@link SuspicionChecks#SHARE_EXCESS} flag per company and pauses dividends until an admin
 * runs {@code /banksystem shares acknowledge}; it is raised again only when the excess grows past the
 * raised / acknowledged level. Nothing is ever removed or refused.
 * <p>
 * Checked after a ledger row that can raise a share balance, once a day, and on acknowledge. Rows that
 * destroy banked shares (account delete loss, bank / account closure, disallowed item) lower stamped.
 */
public final class ShareIntegrity {

    private static final long SCALE = BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    /** Rows that move shares from a player into a bank (amount > 0 = deposit). */
    private static final String INTO_BANK = codes(LedgerType.DEPOSIT, LedgerType.UPLOAD_DEPOSIT,
            LedgerType.CONVERTER_DEPOSIT, LedgerType.SHARE_TRADE, LedgerType.ADMIN_ADD, LedgerType.ADMIN_SET,
            LedgerType.CRAFT_OUTPUT, LedgerType.STARTING_BALANCE, LedgerType.WITHDRAW_REFUND);
    /** Rows where a player put shares into a bank (SHARE_TRADE is signed: negative = withdrawal). */
    private static final String HINT_IN = codes(LedgerType.DEPOSIT, LedgerType.UPLOAD_DEPOSIT,
            LedgerType.CONVERTER_DEPOSIT, LedgerType.SHARE_TRADE, LedgerType.WITHDRAW_REFUND);
    /** Rows that hand shares out of a bank to a player. */
    private static final String OUT_OF_BANK = codes(LedgerType.WITHDRAW, LedgerType.DOWNLOAD_WITHDRAW,
            LedgerType.CONVERTER_WITHDRAW, LedgerType.ACCOUNT_DELETE_PAYOUT);
    /** BS-234 "give to me" rows by an admin are ADMIN_REMOVE with this note. */
    private static final String GIVEN_NOTE = "shares given to editor";

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    private static final Set<Integer> PENDING = ConcurrentHashMap.newKeySet();

    private ShareIntegrity() {}

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
        PENDING.clear();
    }

    private static boolean master() {
        return BACKEND_INSTANCES != null && !BACKEND_INSTANCES.isSlaveServer;
    }

    /** Ledger hook ({@code TransactionLedger.afterWrite}); any thread, never throws. */
    public static void onLedgerRow(TransactionLogRecord row) {
        try {
            if (!master() || row.itemId() == ItemID.INVALID_ID.getShort()) return;
            Integer cid = StampedShareItem.getCompanyIdForItemID(new ItemID(row.itemId()));
            if (cid == null) return;
            switch (row.type()) {
                // ADMIN_REMOVE is not here: the BS-234 destroy choice lowers stamped itself
                case ACCOUNT_DELETE_LOSS, BANK_CLOSED, ADMIN_DISALLOW_ITEM -> {
                    long count = Math.abs(row.amount()) / SCALE;
                    if (count > 0) onServer(() -> {
                        CompanyManager cm = CompanyManager.get();
                        if (cm != null) cm.destroyShares(cid, count);
                    });
                }
                default -> {
                    if (row.amount() > 0 && PENDING.add(cid)) onServer(() -> {
                        PENDING.remove(cid);
                        check(cid);
                    });
                }
            }
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    private static void onServer(Runnable r) {
        net.minecraft.server.MinecraftServer server = dev.architectury.utils.GameInstance.getServer();
        if (server == null) r.run();
        else server.execute(r);
    }

    /** Daily sweep (server thread). */
    public static void checkAll() {
        try {
            CompanyManager cm = CompanyManager.get();
            if (!master() || cm == null) return;
            for (Company c : new ArrayList<>(cm.getAll())) check(c.getCompanyId());
        } catch (RuntimeException e) {
            warn("daily share check failed: " + e);
        }
    }

    /** Checks one company (server thread). */
    public static void check(int companyId) {
        CompanyManager cm = CompanyManager.get();
        ISyncServerBankManager bm = bankManager();
        Company c = cm == null ? null : cm.getById(companyId);
        if (c == null || bm == null) return;
        long banked = bankedRaw(bm, companyId) / SCALE;
        long since = c.getShareConsistentTs();
        if (apply(cm, c, banked, System.currentTimeMillis())) raise(c, banked, since);
    }

    /** @return free + locked raw balance of every share ItemID of the company over all accounts. */
    public static long bankedRaw(ISyncServerBankManager bm, int companyId) {
        long sum = 0;
        for (ItemID id : CompanyStatsQuery.shareItemIds(companyId)) {
            for (int nr : bm.listAccountsHolding(id)) {
                IServerBankAccount acct = bm.getBankAccount(nr);
                IServerBank bank = acct == null ? null : acct.getBank(id);
                if (bank != null) sum += bank.getTotalBalance();
            }
        }
        return sum;
    }

    /**
     * Updates the company's integrity state for {@code bankedShares} (whole shares).
     * @return true when a new SHARE_EXCESS alert must be raised
     */
    public static boolean apply(CompanyManager cm, Company c, long bankedShares, long nowMs) {
        long excess = bankedShares - c.getTotalSharesIssued();
        if (excess <= 0) { // consistent again: closes an open alert, resumes dividends
            cm.setShareState(c.getCompanyId(), nowMs, 0, false);
            return false;
        }
        if (excess <= c.getShareExcessLevel()) return false;
        cm.setShareState(c.getCompanyId(), c.getShareConsistentTs(), excess, true);
        return true;
    }

    /** Admin acknowledged: remembers the current excess as accepted and resumes dividends. @return the excess */
    public static long acknowledge(CompanyManager cm, Company c, long bankedShares, long nowMs) {
        long excess = Math.max(0L, bankedShares - c.getTotalSharesIssued());
        cm.setShareState(c.getCompanyId(), excess == 0 ? nowMs : c.getShareConsistentTs(), excess, false);
        return excess;
    }

    /** Command entry (server thread, master). @return the acknowledged excess, or -1 if the company is unknown */
    public static long acknowledge(int companyId) {
        CompanyManager cm = CompanyManager.get();
        ISyncServerBankManager bm = bankManager();
        Company c = cm == null ? null : cm.getById(companyId);
        if (c == null || bm == null) return -1L;
        long excess = acknowledge(cm, c, bankedRaw(bm, companyId) / SCALE, System.currentTimeMillis());
        if (BACKEND_INSTANCES.SUSPICION_FLAG_MANAGER != null)
            BACKEND_INSTANCES.SUSPICION_FLAG_MANAGER.acknowledgeAll(c.getBankAccountNr(), SuspicionChecks.SHARE_EXCESS)
                    .exceptionally(t -> null);
        return excess;
    }

    private static void raise(Company c, long banked, long since) {
        long stamped = c.getTotalSharesIssued();
        long excess = banked - stamped;
        int account = c.getBankAccountNr();
        int cid = c.getCompanyId();
        String head = "Company " + c.getName() + " (#" + cid + "): stamped " + stamped + ", banked " + banked
                + ", excess " + excess + ". Dividends paused until /banksystem shares acknowledge " + cid;
        short[] items = shortIds(cid);
        DatabaseManager db = BankSystemModBackend.getDatabaseManager();
        if (db == null) {
            AdminAudit.raise(account, SuspicionChecks.SHARE_EXCESS, 3, excess * SCALE, null, head);
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                String d = details(db.getConnection(), cid, items, since);
                db.commitTransaction();
                return d;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, db.getDatabaseThread()).whenComplete((d, t) -> {
            if (t != null) warn("share excess details failed: " + t);
            AdminAudit.raise(account, SuspicionChecks.SHARE_EXCESS, 3, excess * SCALE, null,
                    d == null || d.isEmpty() ? head : head + "\n" + d);
        });
    }

    private static short[] shortIds(int companyId) {
        List<ItemID> ids = CompanyStatsQuery.shareItemIds(companyId);
        short[] out = new short[ids.size()];
        for (int i = 0; i < out.length; i++) out[i] = ids.get(i).getShort();
        return out;
    }

    /**
     * Alert body: share deposits since {@code sinceMs} (the last time banked did not exceed stamped) and a
     * per-player hint of shares received from banks / stampers minus shares deposited. DB thread.
     */
    public static String details(Connection conn, int companyId, short[] shareItems, long sinceMs) throws SQLException {
        if (shareItems.length == 0) return "";
        StringBuilder in = new StringBuilder();
        for (short s : shareItems) in.append(in.isEmpty() ? "" : ",").append(s);
        StringBuilder sb = new StringBuilder("Share deposits since " + time(sinceMs) + ":");
        int n = 0;
        try (PreparedStatement ps = conn.prepareStatement("SELECT ts, COALESCE(actor_name, actor_uuid, 'system'), amount, "
                + "type_code, account_number FROM TransactionLog WHERE item_id IN (" + in + ") AND type_code IN ("
                + INTO_BANK + ") AND amount > 0 AND ts >= ? ORDER BY ts DESC, id DESC LIMIT 8")) {
            ps.setLong(1, Math.max(0L, sinceMs));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    n++;
                    sb.append("\n  ").append(time(rs.getLong(1))).append(' ').append(rs.getString(2)).append(" +")
                            .append(rs.getLong(3) / SCALE).append(' ').append(LedgerType.fromCode(rs.getInt(4)))
                            .append(" -> account ").append(rs.getInt(5));
                }
            }
        }
        if (n == 0) sb.append(" none");
        // + received from banks / stampers, - deposited; summed in raw units (a stamp row is one share)
        String net = "SUM(CASE WHEN type_code = " + LedgerType.SHARE_STAMP.code() + " THEN amount * " + SCALE
                + " WHEN type_code = " + LedgerType.SHARE_REDEEM.code() + " THEN -amount * " + SCALE
                + " WHEN type_code IN (" + HINT_IN + ") THEN -amount"
                + " WHEN type_code IN (" + OUT_OF_BANK + ") OR (type_code = " + LedgerType.ADMIN_REMOVE.code()
                + " AND note = ?) THEN ABS(amount) ELSE 0 END)";
        List<String> plus = new ArrayList<>(), minus = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(actor_name, actor_uuid), " + net
                + " AS n FROM TransactionLog WHERE actor_uuid IS NOT NULL AND ts >= ? AND (item_id IN (" + in
                + ") OR (item_id = 0 AND company_id = ? AND type_code IN (" + LedgerType.SHARE_STAMP.code() + ","
                + LedgerType.SHARE_REDEEM.code() + "))) GROUP BY actor_uuid HAVING n <> 0 ORDER BY n DESC")) {
            ps.setString(1, GIVEN_NOTE);
            ps.setLong(2, Math.max(0L, sinceMs));
            ps.setInt(3, companyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long shares = rs.getLong(2) / SCALE;
                    if (shares > 0 && plus.size() < 5) plus.add(rs.getString(1) + " +" + shares);
                    else if (shares < 0) minus.add(rs.getString(1) + " " + shares);
                }
            }
        }
        if (!plus.isEmpty() || !minus.isEmpty()) {
            sb.append("\nHint only, not proof (received from banks/stampers + / deposited -):");
            if (!plus.isEmpty()) sb.append("\n  ").append(String.join(", ", plus));
            int from = Math.max(0, minus.size() - 5); // most negative last in DESC order
            if (!minus.isEmpty()) sb.append("\n  ").append(String.join(", ", minus.subList(from, minus.size())));
        }
        return sb.toString();
    }

    /**
     * Shares that appeared outside a stamper (/give, creative inventory): one SHARE_CREATED flag on the
     * company account on the master, a log line on a slave. Never throws.
     */
    public static void reportCreated(String who, int companyId, long count, String how) {
        try {
            if (BACKEND_INSTANCES == null || count <= 0) return;
            CompanyManager cm = CompanyManager.get();
            Company c = cm == null ? null : cm.getById(companyId);
            String text = who + " created " + count + " share(s) of company "
                    + (c != null ? c.getName() + " " : "") + "#" + companyId + " via " + how + " (not stamped)";
            if (master() && c != null)
                AdminAudit.raise(c.getBankAccountNr(), SuspicionChecks.SHARE_CREATED, 2, count * SCALE, null, text);
            else warn(text);
        } catch (RuntimeException ignored) {
            // diagnostics never affect gameplay
        }
    }

    private static ISyncServerBankManager bankManager() {
        return BACKEND_INSTANCES == null || BACKEND_INSTANCES.SERVER_BANK_MANAGER == null ? null
                : BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
    }

    private static String time(long ms) {
        return ms <= 0 ? "the start" : TIME.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()));
    }

    private static String codes(LedgerType... types) {
        StringBuilder sb = new StringBuilder();
        for (LedgerType t : types) sb.append(sb.isEmpty() ? "" : ",").append(t.code());
        return sb.toString();
    }

    private static void warn(String msg) {
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) BACKEND_INSTANCES.LOGGER.warn("[ShareIntegrity] " + msg);
    }
}
