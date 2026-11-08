package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.api.ledger.LedgerActor;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import net.kroia.banksystem.banking.ledger.AdminAudit;
import net.kroia.banksystem.banking.ledger.SuspicionChecks;
import net.kroia.banksystem.banking.ledger.TransactionLedger;
import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.data.table.SuspicionFlagManager;
import net.kroia.banksystem.data.table.record.BalanceHistoryRecord;
import net.kroia.banksystem.data.table.record.SuspicionFlagRecord;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.testing.LiveStateGuard;
import net.kroia.modutilities.setting.Setting;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Tasks #111 / #114 / #115 (v2.2.0) — suspicious-activity flags: banknote laundering (incl.
 * netting and refunds), large withdrawals, wealth jump with cooldown, transfers to new and
 * legacy accounts, and the per-flag toggles.
 * <p>
 * <b>Server type:</b> MASTER_ONLY (BANK_MANAGER category). Synthetic accounts and actor UUIDs
 * only (actors need no user entry). Ledger, balance-history and flag rows of the test accounts,
 * the suite's audit-file lines and every Audit setting are restored afterwards.
 */
public class SuspicionFlagTests extends TestSuite {

    private static final String MARKER = "flag-test-f111";
    private static final long D = 100L; // raw units per dollar

    private ISyncServerBankManager manager;
    private SuspicionFlagManager flags;
    private LiveStateGuard stateGuard;
    private final int[] acct = new int[3];
    private final Map<Setting<?>, Object> savedSettings = new HashMap<>();
    private final List<String> alerts = new ArrayList<>();
    private int nextActor = 0;
    private long savedGrace;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.BANK_MANAGER;
    }

    @Override
    public void registerTests() {
        addTest("laundering_fires_at_threshold_only", this::launderingThreshold);
        addTest("laundering_nets_own_notes_not_others", this::launderingNetting);
        addTest("laundering_subtracts_refunds", this::launderingRefund);
        addTest("large_withdrawal_net_of_refund", this::largeWithdrawal);
        addTest("large_withdrawal_split_across_notes", this::largeWithdrawalSplit);
        addTest("wealth_jump_threshold_and_cooldown", this::wealthJump);
        addTest("transfer_to_new_account", this::newAccount);
        addTest("legacy_account_age", this::legacyAccount);
        addTest("toggles_disable_each_flag", this::toggles);
    }

    @Override
    public void setup() {
        IBankManager bm = BankSystemMod.getAPI().getServerBankManager();
        if (bm == null || bm.getSync() == null) return;
        manager = bm.getSync();
        flags = BankSystemModBackend.getInstances_forTesting().SUSPICION_FLAG_MANAGER;
        stateGuard = LiveStateGuard.capture(manager);
        for (int i = 0; i < acct.length; i++) {
            IServerBankAccount a = manager.createBankAccount("FlagTestAccount" + i);
            acct[i] = a == null ? ServerBankAccount.INVALID_ACCOUNT_NUMBER : a.getAccountNumber();
        }
        BankSystemModSettings.Audit s = BankSystemModBackend.getInstances_forTesting().SERVER_SETTINGS.AUDIT;
        for (Setting<?> st : List.of(s.LAUNDERING_FLAG_ENABLED, s.LAUNDERING_MIN_AMOUNT, s.LAUNDERING_WINDOW_HOURS,
                s.LARGE_NOTE_WITHDRAWAL_FLAG_ENABLED, s.LARGE_NOTE_WITHDRAWAL_AMOUNT, s.WEALTH_JUMP_FLAG_ENABLED,
                s.WEALTH_SWEEP_MINUTES, s.WEALTH_JUMP_PERCENT, s.WEALTH_JUMP_AMOUNT, s.WEALTH_JUMP_COOLDOWN_HOURS,
                s.NEW_ACCOUNT_TRANSFER_FLAG_ENABLED, s.NEW_ACCOUNT_TRANSFER_AMOUNT, s.NEW_ACCOUNT_AGE_HOURS))
            savedSettings.put(st, st.get());
        s.LAUNDERING_FLAG_ENABLED.set(true);
        s.LAUNDERING_MIN_AMOUNT.set(1_000L);
        s.LAUNDERING_WINDOW_HOURS.set(24);
        s.LARGE_NOTE_WITHDRAWAL_FLAG_ENABLED.set(true);
        s.LARGE_NOTE_WITHDRAWAL_AMOUNT.set(5_000L);
        s.WEALTH_JUMP_FLAG_ENABLED.set(true);
        s.WEALTH_JUMP_PERCENT.set(100);
        s.WEALTH_JUMP_AMOUNT.set(1_000L);
        s.WEALTH_JUMP_COOLDOWN_HOURS.set(6);
        s.NEW_ACCOUNT_TRANSFER_FLAG_ENABLED.set(true);
        s.NEW_ACCOUNT_TRANSFER_AMOUNT.set(1_000L);
        s.NEW_ACCOUNT_AGE_HOURS.set(24);
        savedGrace = SuspicionChecks.refundGraceMs;
        SuspicionChecks.refundGraceMs = 200L;
        AdminAudit.alertCapture = line -> { synchronized (alerts) { alerts.add(line); } };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public void teardown() {
        try {
            if (manager == null) return;
            drain();
            List<Integer> accounts = new ArrayList<>();
            for (int nr : acct) {
                if (nr == ServerBankAccount.INVALID_ACCOUNT_NUMBER) continue;
                accounts.add(nr);
                manager.deleteBankAccount(nr);
            }
            deleteRows(accounts);
            removeMarkedAuditLines();
        } finally {
            for (Map.Entry<Setting<?>, Object> e : savedSettings.entrySet()) ((Setting) e.getKey()).set(e.getValue());
            savedSettings.clear();
            SuspicionChecks.refundGraceMs = savedGrace;
            SuspicionChecks.clearLegacyCache();
            AdminAudit.alertCapture = null;
            synchronized (alerts) { alerts.clear(); }
            for (int i = 0; i < acct.length; i++) acct[i] = ServerBankAccount.INVALID_ACCOUNT_NUMBER;
            LiveStateGuard.restore(stateGuard);
            stateGuard = null;
        }
    }

    private boolean ready() {
        if (manager == null || flags == null || BankSystemModBackend.getTransactionLogManager() == null) return false;
        for (int nr : acct) if (nr == ServerBankAccount.INVALID_ACCOUNT_NUMBER) return false;
        return true;
    }

    // ------------------------------------------------------------------ #111

    private TestResult launderingThreshold() {
        if (!ready()) return fail("live setup missing");
        UUID fire = actor(), below = actor();
        noteDeposit(acct[0], fire, 1_000 * D);
        noteDeposit(acct[0], below, 1_000 * D - 1);
        if (count(acct[0], SuspicionChecks.BANKNOTE_LAUNDERING, fire) != 1) return fail("deposit at threshold not flagged");
        if (count(acct[0], SuspicionChecks.BANKNOTE_LAUNDERING, below) != 0) return fail("deposit below threshold flagged");
        return pass("flags at $1000, not at $999.99");
    }

    private TestResult launderingNetting() {
        if (!ready()) return fail("live setup missing");
        UUID own = actor(), other = actor();
        write(acct[0], LedgerType.WITHDRAW, own, 2_000 * D, "banknote x");
        noteDeposit(acct[1], own, 2_000 * D);
        if (count(acct[1], SuspicionChecks.BANKNOTE_LAUNDERING, own) != 0) return fail("own notes flagged");
        noteDeposit(acct[1], other, 2_000 * D);
        if (count(acct[1], SuspicionChecks.BANKNOTE_LAUNDERING, other) != 1) return fail("someone else's notes not flagged");
        noteDeposit(acct[1], own, 2_000 * D); // notes already deposited once
        if (count(acct[1], SuspicionChecks.BANKNOTE_LAUNDERING, own) != 1) return fail("second deposit of the same notes not flagged");
        return pass("own withdrawal covers one deposit; others' notes and re-deposits flagged");
    }

    private TestResult launderingRefund() {
        if (!ready()) return fail("live setup missing");
        UUID refunded = actor(), covered = actor();
        write(acct[0], LedgerType.WITHDRAW, refunded, 2_000 * D, "banknote x");
        write(acct[0], LedgerType.WITHDRAW_REFUND, refunded, 1_500 * D, "banknotes not handed out");
        noteDeposit(acct[2], refunded, 1_000 * D);
        write(acct[0], LedgerType.WITHDRAW, covered, 2_000 * D, "banknote x");
        write(acct[0], LedgerType.WITHDRAW_REFUND, covered, 500 * D, "banknotes not handed out");
        noteDeposit(acct[2], covered, 1_000 * D);
        if (count(acct[2], SuspicionChecks.BANKNOTE_LAUNDERING, refunded) != 1) return fail("refund not subtracted");
        if (count(acct[2], SuspicionChecks.BANKNOTE_LAUNDERING, covered) != 0) return fail("covered deposit flagged");
        return pass("net = withdrawn - refunded");
    }

    private TestResult largeWithdrawal() {
        if (!ready()) return fail("live setup missing");
        UUID fire = actor(), below = actor(), refunded = actor();
        write(acct[1], LedgerType.WITHDRAW, fire, 5_000 * D, "banknote x");
        write(acct[1], LedgerType.WITHDRAW, below, 5_000 * D - 1, "banknote x");
        write(acct[1], LedgerType.WITHDRAW, refunded, 6_000 * D, "banknote x");
        write(acct[1], LedgerType.WITHDRAW_REFUND, refunded, 2_000 * D, "banknotes not handed out");
        sleep(SuspicionChecks.refundGraceMs + 500L);
        if (count(acct[1], SuspicionChecks.LARGE_NOTE_WITHDRAWAL, fire) != 1) return fail("withdrawal at threshold not flagged");
        if (count(acct[1], SuspicionChecks.LARGE_NOTE_WITHDRAWAL, below) != 0) return fail("withdrawal below threshold flagged");
        if (count(acct[1], SuspicionChecks.LARGE_NOTE_WITHDRAWAL, refunded) != 0) return fail("refund not subtracted");
        return pass("$5000 flagged; $4999.99 and $6000-$2000 refund not");
    }

    /** Three note types that only exceed the threshold together, one combined refund. */
    private TestResult largeWithdrawalSplit() {
        if (!ready()) return fail("live setup missing");
        UUID fire = actor(), refunded = actor();
        for (UUID a : List.of(fire, refunded)) {
            write(acct[2], LedgerType.WITHDRAW, a, 2_000 * D, "banknote note_1000");
            write(acct[2], LedgerType.WITHDRAW, a, 2_000 * D, "banknote note_100");
            write(acct[2], LedgerType.WITHDRAW, a, 2_000 * D, "banknote note_10");
        }
        write(acct[2], LedgerType.WITHDRAW_REFUND, fire, 500 * D, "banknotes not handed out");     // net 5500
        write(acct[2], LedgerType.WITHDRAW_REFUND, refunded, 1_500 * D, "banknotes not handed out"); // net 4500
        sleep(SuspicionChecks.refundGraceMs + 500L);
        if (count(acct[2], SuspicionChecks.LARGE_NOTE_WITHDRAWAL, fire) != 1) return fail("split withdrawal: expected exactly 1 flag");
        if (count(acct[2], SuspicionChecks.LARGE_NOTE_WITHDRAWAL, refunded) != 0) return fail("refund below threshold still flagged");
        return pass("3 x $2000 - $500 flagged once; - $1500 not flagged");
    }

    // ------------------------------------------------------------------ #114

    private TestResult wealthJump() {
        if (!ready()) return fail("live setup missing");
        long now = System.currentTimeMillis() - 1;
        short w = BalanceHistoryRecord.WEALTH_ITEM_ID, money = MoneyItem.getItemID().getShort();
        clearHistory(acct[0], acct[1], acct[2]);
        // acct0: 1000 -> 3000 (+200 %, +$2000) -> flag; cash +500
        history(acct[0], w, 1_000 * D, now - 2 * 3_600_000L);
        history(acct[0], w, 3_000 * D, now - 600_000L);
        history(acct[0], money, 100 * D, now - 2 * 3_600_000L);
        history(acct[0], money, 600 * D, now - 600_000L);
        // acct1: 1000 -> 1999.99 (gain below $1000); acct2: 10000 -> 19000 (+90 %)
        history(acct[1], w, 1_000 * D, now - 2 * 3_600_000L);
        history(acct[1], w, 2_000 * D - 1, now - 600_000L);
        history(acct[2], w, 10_000 * D, now - 2 * 3_600_000L);
        history(acct[2], w, 19_000 * D, now - 600_000L);
        try {
            int first = SuspicionChecks.sweepWealth(List.of(acct[0], acct[1], acct[2]), now).get(10, TimeUnit.SECONDS);
            drain();
            int second = SuspicionChecks.sweepWealth(List.of(acct[0], acct[1], acct[2]), now).get(10, TimeUnit.SECONDS);
            if (first != 1 || second != 0) return fail("sweeps raised " + first + " then " + second);
            List<SuspicionFlagRecord> rows = flagRows(acct[0], SuspicionChecks.WEALTH_JUMP);
            if (rows.size() != 1 || rows.get(0).details() == null || !rows.get(0).details().contains("cash change 500"))
                return fail("flag row " + rows);
            if (!flagRows(acct[1], SuspicionChecks.WEALTH_JUMP).isEmpty() || !flagRows(acct[2], SuspicionChecks.WEALTH_JUMP).isEmpty())
                return fail("below-threshold account flagged");
            return pass("+200 %/+$2000 flagged once (cooldown); below amount or percent not flagged");
        } catch (Exception e) {
            return fail("threw: " + e);
        } finally {
            clearHistory(acct[0], acct[1], acct[2]);
        }
    }

    // ------------------------------------------------------------------ #115

    private TestResult newAccount() {
        if (!ready()) return fail("live setup missing");
        ServerBankAccount young = account(acct[0]), old = account(acct[1]);
        young.setCreatedAt(System.currentTimeMillis() - 3_600_000L);
        old.setCreatedAt(System.currentTimeMillis() - 48 * 3_600_000L);
        synchronized (alerts) { alerts.clear(); }
        UUID a = actor();
        transfer(acct[0], a, 1_000 * D);
        transfer(acct[0], a, 1_000 * D - 1);
        transfer(acct[0], a, -5_000 * D);
        transfer(acct[1], a, 5_000 * D);
        if (count(acct[0], SuspicionChecks.TRANSFER_TO_NEW_ACCOUNT, null) != 1) return fail("young account: expected exactly 1 flag");
        if (count(acct[1], SuspicionChecks.TRANSFER_TO_NEW_ACCOUNT, null) != 0) return fail("old account flagged");
        synchronized (alerts) {
            if (alerts.stream().noneMatch(l -> l.startsWith("FLAG " + SuspicionChecks.TRANSFER_TO_NEW_ACCOUNT)))
                return fail("no alert: " + alerts);
        }
        return pass("incoming $1000 into a 1h-old account flagged; below, outgoing and 48h-old not");
    }

    private TestResult legacyAccount() {
        if (!ready()) return fail("live setup missing");
        long now = System.currentTimeMillis();
        for (int nr : acct) account(nr).setCreatedAt(0L);
        deleteRows(List.of(acct[0], acct[1], acct[2]));
        clearHistory(acct[0], acct[1], acct[2]);
        SuspicionChecks.clearLegacyCache();
        history(acct[0], MoneyItem.getItemID().getShort(), 1, now - 48 * 3_600_000L); // old history
        history(acct[2], MoneyItem.getItemID().getShort(), 1, now - 3_600_000L);      // first seen 1h ago
        // acct1: no history at all -> treated as old
        UUID a = actor();
        transfer(acct[0], a, 5_000 * D);
        transfer(acct[1], a, 5_000 * D);
        transfer(acct[2], a, 5_000 * D);
        int c0 = count(acct[0], SuspicionChecks.TRANSFER_TO_NEW_ACCOUNT, null);
        int c1 = count(acct[1], SuspicionChecks.TRANSFER_TO_NEW_ACCOUNT, null);
        int c2 = count(acct[2], SuspicionChecks.TRANSFER_TO_NEW_ACCOUNT, null);
        clearHistory(acct[0], acct[1], acct[2]);
        if (c0 != 0 || c1 != 0 || c2 != 1) return fail("flags old/none/young = " + c0 + "/" + c1 + "/" + c2);
        // createdAt round-trips through NBT; absent = legacy
        ServerBankAccount live = account(acct[0]);
        live.setCreatedAt(12345L);
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        live.save(tag);
        ServerBankAccount loaded = ServerBankAccount.createFromTag(tag);
        if (loaded == null || loaded.getCreatedAt() != 12345L) return fail("createdAt lost on save/load");
        tag.remove("createdAt");
        ServerBankAccount legacy = ServerBankAccount.createFromTag(tag);
        if (legacy == null || legacy.getCreatedAt() != 0L) return fail("missing createdAt must load as legacy");
        return pass("legacy age from earliest history; no history = old; createdAt persists");
    }

    // ------------------------------------------------------------------ toggles

    private TestResult toggles() {
        if (!ready()) return fail("live setup missing");
        BankSystemModSettings.Audit s = BankSystemModBackend.getInstances_forTesting().SERVER_SETTINGS.AUDIT;
        s.LAUNDERING_FLAG_ENABLED.set(false);
        s.LARGE_NOTE_WITHDRAWAL_FLAG_ENABLED.set(false);
        s.WEALTH_JUMP_FLAG_ENABLED.set(false);
        s.NEW_ACCOUNT_TRANSFER_FLAG_ENABLED.set(false);
        try {
            long now = System.currentTimeMillis() - 1;
            account(acct[2]).setCreatedAt(now);
            drain();
            clearFlags(acct[2]); // earlier tests' (passing) flags on this account must not count here
            UUID a = actor();
            noteDeposit(acct[2], a, 9_000 * D);
            write(acct[2], LedgerType.WITHDRAW, a, 9_000 * D, "banknote x");
            transfer(acct[2], a, 9_000 * D);
            clearHistory(acct[2]);
            history(acct[2], BalanceHistoryRecord.WEALTH_ITEM_ID, 1_000 * D, now - 2 * 3_600_000L);
            history(acct[2], BalanceHistoryRecord.WEALTH_ITEM_ID, 9_000 * D, now - 600_000L);
            int swept = SuspicionChecks.sweepWealth(List.of(acct[2]), now).get(10, TimeUnit.SECONDS);
            sleep(SuspicionChecks.refundGraceMs + 500L);
            List<SuspicionFlagRecord> rows = flagRows(acct[2], -1);
            clearHistory(acct[2]);
            if (swept != 0 || !rows.isEmpty()) return fail("flags with every toggle off: sweep " + swept + ", rows " + rows);
            return pass("all four flags off");
        } catch (Exception e) {
            return fail("threw: " + e);
        } finally {
            s.LAUNDERING_FLAG_ENABLED.set(true);
            s.LARGE_NOTE_WITHDRAWAL_FLAG_ENABLED.set(true);
            s.WEALTH_JUMP_FLAG_ENABLED.set(true);
            s.NEW_ACCOUNT_TRANSFER_FLAG_ENABLED.set(true);
        }
    }

    // ------------------------------------------------------------------ helpers

    private UUID actor() {
        return UUID.fromString(String.format("00000000-0000-0000-0000-0000000f11%02x", nextActor++ & 0xff));
    }

    private ServerBankAccount account(int nr) {
        return (ServerBankAccount) manager.getBankAccount(nr);
    }

    private static void write(int account, LedgerType type, UUID actor, long amount, String note) {
        TransactionLedger.write(TransactionLogRecord.of(account, type, MoneyItem.getItemID().getShort(), amount,
                LedgerActor.player(actor, "FlagTestActor"), null, null, null, note + " " + MARKER, null));
    }

    private static void noteDeposit(int account, UUID actor, long amount) {
        write(account, LedgerType.DEPOSIT, actor, amount, SuspicionChecks.BANKNOTE_NOTE + "x");
    }

    private void transfer(int account, UUID actor, long signedAmount) {
        int other = account == acct[2] ? acct[1] : acct[2];
        TransactionLedger.write(TransactionLogRecord.of(account, LedgerType.TRANSFER, MoneyItem.getItemID().getShort(),
                signedAmount, LedgerActor.player(actor, "FlagTestActor"), other, null, null, MARKER, null));
    }

    /** Flags of one type on an account, optionally only those naming {@code actor}. Waits for pending checks. */
    private int count(int account, int type, UUID actor) {
        int n = 0;
        for (SuspicionFlagRecord r : flagRows(account, type))
            if (actor == null || (r.details() != null && r.details().contains(actor.toString()))) n++;
        return n;
    }

    private List<SuspicionFlagRecord> flagRows(int account, int type) {
        drain();
        try {
            List<SuspicionFlagRecord> out = new ArrayList<>();
            for (SuspicionFlagRecord r : flags.getRecent(account, 0, 0).get(10, TimeUnit.SECONDS))
                if (type < 0 || r.flagType() == type) out.add(r);
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Lets chained DB-worker callbacks (query -> raise -> save) finish. */
    private static void drain() {
        DatabaseManager db = BankSystemModBackend.getDatabaseManager();
        if (db == null) return;
        for (int i = 0; i < 4; i++)
            try { db.getDatabaseThread().submit(() -> {}).get(10, TimeUnit.SECONDS); } catch (Exception ignored) {}
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void history(int account, short item, long balance, long time) {
        db(c -> {
            try (var ps = c.prepareStatement("INSERT INTO BalanceHistory (account_number, item_id, balance, locked_balance, time) VALUES (?, ?, ?, 0, ?)")) {
                ps.setInt(1, account);
                ps.setShort(2, item);
                ps.setLong(3, balance);
                ps.setLong(4, time);
                ps.executeUpdate();
            }
        });
    }

    private static void clearHistory(int... accounts) {
        db(c -> {
            try (var ps = c.prepareStatement("DELETE FROM BalanceHistory WHERE account_number = ?")) {
                for (int a : accounts) { ps.setInt(1, a); ps.executeUpdate(); }
            }
        });
    }

    private static void deleteRows(List<Integer> accounts) {
        db(c -> {
            try (var a = c.prepareStatement("DELETE FROM TransactionLog WHERE account_number = ?");
                 var b = c.prepareStatement("DELETE FROM SuspicionFlag WHERE account_number = ?");
                 var h = c.prepareStatement("DELETE FROM BalanceHistory WHERE account_number = ?")) {
                for (int nr : accounts) {
                    a.setInt(1, nr); a.executeUpdate();
                    b.setInt(1, nr); b.executeUpdate();
                    h.setInt(1, nr); h.executeUpdate();
                }
            }
        });
    }

    private static void clearFlags(int... accounts) {
        db(c -> {
            try (var ps = c.prepareStatement("DELETE FROM SuspicionFlag WHERE account_number = ?")) {
                for (int a : accounts) { ps.setInt(1, a); ps.executeUpdate(); }
            }
        });
    }

    private interface SqlWork { void run(java.sql.Connection c) throws SQLException; }

    private static void db(SqlWork work) {
        DatabaseManager db = BankSystemModBackend.getDatabaseManager();
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

    /** Strips this suite's flag lines from today's and yesterday's audit file. */
    private void removeMarkedAuditLines() {
        List<String> needles = new ArrayList<>();
        for (int nr : acct) needles.add("account " + nr + " ");
        for (LocalDate d : List.of(LocalDate.now(), LocalDate.now().minusDays(1))) {
            Path file = AdminAudit.auditFile(d);
            try {
                if (file == null || !Files.exists(file)) continue;
                List<String> keep = new ArrayList<>();
                for (String l : Files.readAllLines(file, StandardCharsets.UTF_8))
                    if (!(l.contains(" FLAG ") && needles.stream().anyMatch(l::contains))) keep.add(l);
                if (keep.isEmpty()) Files.delete(file);
                else Files.write(file, keep, StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
        }
    }
}
