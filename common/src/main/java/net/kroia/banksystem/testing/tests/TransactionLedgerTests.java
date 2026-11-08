package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bank.IServerBank;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.api.ledger.LedgerActor;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.api.ledger.TradeLogEntry;
import net.kroia.banksystem.api.ledger.TradeRowKind;
import net.kroia.banksystem.api.ledger.TradeSide;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import net.kroia.banksystem.banking.ledger.TransactionLedger;
import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.data.DriverShim;
import net.kroia.banksystem.data.table.TransactionLogManager;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.minecraft.command.ServerBankSystemCommandHandler;
import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.entity.WithdrawMoneyPacket;
import net.kroia.banksystem.networking.general.UpdateBankAccountRequest;
import net.kroia.banksystem.networking.multi_server.DepositItemsInBankRequest;
import net.kroia.banksystem.networking.multi_server.WithdrawItemsFromBankRequest;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.testing.LiveStateGuard;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.world.item.Items;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Task #109 (v2.1.0) — transaction ledger: code table, legacy migration and the main write
 * paths (withdraw + refund, item deposit/withdraw, admin edits, transfers, account deletion,
 * trade rows).
 * <p>
 * <b>Server type:</b> MASTER_ONLY (BANK_MANAGER category) — the live paths need the master
 * {@code ServerBankManager} and the live ledger DB. Every created user/account and every
 * ledger row written for them is removed in teardown / finally blocks.
 */
public class TransactionLedgerTests extends TestSuite {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000f109");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-00000000f10a");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-00000000f10b");
    private static final String PLAYER_NAME = "LedgerTestPlayer";
    private static final String ADMIN_NAME = "LedgerTestAdmin";
    private static final String OTHER_NAME = "LedgerTestOther";

    private ISyncServerBankManager manager;
    private TransactionLogManager ledger;
    private LiveStateGuard stateGuard;
    private int accountNr = ServerBankAccount.INVALID_ACCOUNT_NUMBER;
    private int otherAccountNr = ServerBankAccount.INVALID_ACCOUNT_NUMBER;
    private final List<Integer> touchedAccounts = new ArrayList<>();
    private final List<String> touchedRefs = new ArrayList<>();

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.BANK_MANAGER;
    }

    @Override
    public void registerTests() {
        addTest("code_table_is_stable", this::codeTableIsStable);
        addTest("from_code_falls_back_to_unknown", this::fromCodeFallsBack);
        addTest("legacy_rows_are_migrated_and_load", this::legacyMigration);
        addTest("withdraw_money_writes_withdraw_and_refund", this::withdrawMoneyAndRefund);
        addTest("item_deposit_and_withdraw_requests", this::itemDepositAndWithdraw);
        addTest("converter_deposit_type_is_forwarded", this::converterDepositType);
        addTest("admin_commands_record_admin", this::adminCommands);
        addTest("account_update_admin_and_owner_rows", this::accountUpdateRows);
        addTest("transfer_writes_signed_pair", this::transferPair);
        addTest("api_log_records_console_actor", this::consoleActor);
        addTest("log_trade_round_trip_and_ref_sum", this::logTradeRoundTrip);
        addTest("starting_balance_row", this::startingBalance);
        addTest("user_deletion_writes_payout_rows", this::userDeletionPayout);
        addTest("slave_code_allow_list", this::slaveCodeAllowList);
        addTest("async_bank_code_logged_on_master", this::asyncBankCodeLoggedOnMaster);
        addTest("item_request_rejects_disallowed_code", this::itemRequestRejectsDisallowedCode);
    }

    @Override
    public void setup() {
        IBankManager bm = BankSystemMod.getAPI().getServerBankManager();
        if (bm == null || bm.getSync() == null) return;
        manager = bm.getSync();
        ledger = BankSystemModBackend.getTransactionLogManager();
        stateGuard = LiveStateGuard.capture(manager);
        addUser(PLAYER, PLAYER_NAME);
        addUser(ADMIN, ADMIN_NAME);
        addUser(OTHER, OTHER_NAME);
        manager.setBanksystemAdminMode(ADMIN, true);
        accountNr = newAccount("LedgerTestAccount", PLAYER);
        otherAccountNr = newAccount("LedgerTestOther", OTHER);
    }

    @Override
    public void teardown() {
        try {
            if (manager == null) return;
            for (int nr : List.of(accountNr, otherAccountNr)) {
                if (nr != ServerBankAccount.INVALID_ACCOUNT_NUMBER) manager.deleteBankAccount(nr);
            }
            for (UUID u : List.of(PLAYER, ADMIN, OTHER)) {
                IServerBankAccount personal = manager.getPersonalBankAccount(u);
                if (personal != null) touchedAccounts.add(personal.getAccountNumber());
                manager.removeUser(u);
            }
            deleteLedgerRows();
        } finally {
            accountNr = otherAccountNr = ServerBankAccount.INVALID_ACCOUNT_NUMBER;
            touchedAccounts.clear();
            touchedRefs.clear();
            LiveStateGuard.restore(stateGuard);
            stateGuard = null;
        }
    }

    // ------------------------------------------------------------------ pure

    private TestResult codeTableIsStable() {
        Object[][] expected = {
                {LedgerType.UNKNOWN_LEGACY, 0}, {LedgerType.DEPOSIT, 1}, {LedgerType.WITHDRAW, 2},
                {LedgerType.TRANSFER, 3}, {LedgerType.TRADE_BUY, 4}, {LedgerType.TRADE_SELL, 5},
                {LedgerType.WITHDRAW_REFUND, 6}, {LedgerType.ADMIN_SET, 7}, {LedgerType.ADMIN_ADD, 8},
                {LedgerType.ADMIN_REMOVE, 9}, {LedgerType.ADMIN_UNLOCK, 10}, {LedgerType.ADMIN_ACCOUNT_EDIT, 11},
                {LedgerType.BANK_CLOSED, 12}, {LedgerType.CONVERTER_DEPOSIT, 13}, {LedgerType.CONVERTER_WITHDRAW, 14},
                {LedgerType.UPLOAD_DEPOSIT, 15}, {LedgerType.DOWNLOAD_WITHDRAW, 16}, {LedgerType.CRAFT_CONSUME, 17},
                {LedgerType.CRAFT_OUTPUT, 18}, {LedgerType.STARTING_BALANCE, 19}, {LedgerType.PAYOUT, 20},
                {LedgerType.DIVIDEND, 21}, {LedgerType.SHARE_STAMP, 22}, {LedgerType.SHARE_REDEEM, 23},
                {LedgerType.SHARE_TRADE, 24}, {LedgerType.ACCOUNT_DELETE_PAYOUT, 25}, {LedgerType.ACCOUNT_FREEZE, 26},
                {LedgerType.ADMIN_DISALLOW_ITEM, 27},
        };
        if (expected.length != LedgerType.values().length)
            return fail("LedgerType has " + LedgerType.values().length + " constants, table lists " + expected.length
                    + " — add the new code to this test (append-only)");
        for (Object[] row : expected) {
            LedgerType t = (LedgerType) row[0];
            if (t.code() != (int) row[1]) return fail(t + " has code " + t.code() + ", expected " + row[1]);
            if (LedgerType.fromCode((int) row[1]) != t) return fail("fromCode(" + row[1] + ") != " + t);
        }
        return pass("all " + expected.length + " codes match the published table");
    }

    private TestResult fromCodeFallsBack() {
        if (LedgerType.fromCode(-1) != LedgerType.UNKNOWN_LEGACY) return fail("fromCode(-1)");
        if (LedgerType.fromCode(9999) != LedgerType.UNKNOWN_LEGACY) return fail("fromCode(9999)");
        if (LedgerType.fromLegacyKind("TRANSFER_OUT") != LedgerType.TRANSFER) return fail("TRANSFER_OUT");
        if (LedgerType.fromLegacyKind("TRANSFER_IN") != LedgerType.TRANSFER) return fail("TRANSFER_IN");
        if (LedgerType.fromLegacyKind("BOGUS") != LedgerType.UNKNOWN_LEGACY) return fail("BOGUS");
        if (LedgerType.fromLegacyKind(null) != LedgerType.UNKNOWN_LEGACY) return fail("null");
        return pass("unknown codes and kinds map to UNKNOWN_LEGACY");
    }

    /** Old-schema in-memory DB, legacy rows, migration run twice, rows read back through the manager. */
    private TestResult legacyMigration() {
        Connection c = null;
        try {
            c = openMemoryDb();
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE TransactionLog (id INTEGER PRIMARY KEY AUTOINCREMENT, account_number INTEGER NOT NULL, "
                        + "actor_uuid TEXT, kind TEXT NOT NULL, item_id INTEGER NOT NULL, amount INTEGER NOT NULL, "
                        + "other_account INTEGER, company_id INTEGER, ts INTEGER NOT NULL, note TEXT)");
                String[] kinds = {"DEPOSIT", "TRANSFER_OUT", "TRANSFER_IN", "PAYOUT", "SHARE_TRADE", "SOMETHING_OLD"};
                for (int i = 0; i < kinds.length; i++)
                    st.execute("INSERT INTO TransactionLog (account_number, kind, item_id, amount, ts) VALUES (5, '"
                            + kinds[i] + "', 1, 50, " + (1000 + i) + ")");
            }
            DatabaseManager.migrateTransactionLog(c);
            DatabaseManager.migrateTransactionLog(c); // idempotent: must not negate twice
            c.commit();
            TransactionLogManager mgr = new TransactionLogManager(new MemoryDb(c));
            List<TransactionLogRecord> rows = mgr.getByAccount(5, 0).get(10, TimeUnit.SECONDS);
            TestResult r = assertEquals("all legacy rows load", 6, rows.size());
            if (!r.passed()) return r;
            // newest first: SOMETHING_OLD, SHARE_TRADE, PAYOUT, TRANSFER_IN, TRANSFER_OUT, DEPOSIT
            LedgerType[] types = {LedgerType.UNKNOWN_LEGACY, LedgerType.SHARE_TRADE, LedgerType.PAYOUT,
                    LedgerType.TRANSFER, LedgerType.TRANSFER, LedgerType.DEPOSIT};
            long[] amounts = {50, 50, 50, 50, -50, 50};
            for (int i = 0; i < 6; i++) {
                if (rows.get(i).type() != types[i]) return fail("row " + i + " type " + rows.get(i).type() + " != " + types[i]);
                if (rows.get(i).amount() != amounts[i]) return fail("row " + i + " amount " + rows.get(i).amount() + " != " + amounts[i]);
            }
            return pass("legacy kinds mapped, TRANSFER_OUT negated once, unknown kept as 0");
        } catch (Exception e) {
            return fail("migration threw: " + e);
        } finally {
            try { if (c != null) c.close(); } catch (SQLException ignored) {}
        }
    }

    // ------------------------------------------------------------------ live paths

    private TestResult withdrawMoneyAndRefund() {
        if (!ready()) return fail("live setup missing");
        IServerBank money = manager.getBankAccount(accountNr).getOrCreateBank(MoneyItem.getItemID());
        if (money == null) return fail("no money bank");
        money.deposit(10_000L);
        ItemID note = ItemID.of(BankSystemItems.MONEY5.get().getDefaultInstance());
        long worth = ((MoneyItem) BankSystemItems.MONEY5.get()).worth();
        HashMap<ItemID, Long> request = new HashMap<>();
        request.put(note, 2L);
        // PLAYER is offline: nothing can be handed out, so the whole value is refunded.
        new WithdrawMoneyPacket(request, accountNr).handle(PLAYER, null);
        List<TransactionLogRecord> rows = rows(accountNr);
        TransactionLogRecord w = find(rows, LedgerType.WITHDRAW);
        TransactionLogRecord refund = find(rows, LedgerType.WITHDRAW_REFUND);
        if (w == null || refund == null) return fail("expected WITHDRAW + WITHDRAW_REFUND rows, got " + rows);
        if (w.amount() != 2 * worth || refund.amount() != 2 * worth) return fail("amounts " + w.amount() + "/" + refund.amount());
        if (!PLAYER.equals(w.actorUuid()) || w.source() != LedgerActor.Source.PLAYER) return fail("withdraw actor " + w);
        if (!PLAYER.equals(refund.actorUuid())) return fail("refund actor " + refund);
        if (refund.balanceAfter() == null || refund.balanceAfter() != 10_000L) return fail("refund balance_after " + refund.balanceAfter());
        return pass("WITHDRAW + WITHDRAW_REFUND rows with player actor");
    }

    private TestResult itemDepositAndWithdraw() {
        if (!ready()) return fail("live setup missing");
        ItemID diamond = allow(Items.DIAMOND.getDefaultInstance());
        Map<ItemID, Long> items = new HashMap<>();
        items.put(diamond, 300L);
        new DepositItemsInBankRequest().handleOnMasterServer(
                new DepositItemsInBankRequest.InputData(accountNr, PLAYER, items), "", null).join();
        items.put(diamond, 100L);
        new WithdrawItemsFromBankRequest().handleOnMasterServer(
                new WithdrawItemsFromBankRequest.InputData(accountNr, PLAYER, items), "", null).join();
        List<TransactionLogRecord> rows = rows(accountNr);
        TransactionLogRecord d = find(rows, LedgerType.DEPOSIT);
        TransactionLogRecord w = find(rows, LedgerType.WITHDRAW);
        if (d == null || w == null) return fail("expected DEPOSIT + WITHDRAW, got " + rows);
        if (d.amount() != 300 || w.amount() != 100) return fail("amounts " + d.amount() + "/" + w.amount());
        if (d.itemId() != diamond.getShort()) return fail("item id " + d.itemId());
        if (!PLAYER.equals(d.actorUuid()) || !PLAYER_NAME.equals(d.actorName())) return fail("actor " + d);
        if (w.balanceAfter() == null || w.balanceAfter() != 200L) return fail("balance_after " + w.balanceAfter());
        return pass("item deposit/withdraw rows with actor + balance_after");
    }

    private TestResult converterDepositType() {
        if (!ready()) return fail("live setup missing");
        ItemID diamond = allow(Items.DIAMOND.getDefaultInstance());
        Map<ItemID, Long> items = new HashMap<>();
        items.put(diamond, 5L);
        new DepositItemsInBankRequest().handleOnMasterServer(new DepositItemsInBankRequest.InputData(
                accountNr, PLAYER, items, LedgerType.CONVERTER_DEPOSIT.code()), "", null).join();
        TransactionLogRecord row = find(rows(accountNr), LedgerType.CONVERTER_DEPOSIT);
        if (row == null) return fail("no CONVERTER_DEPOSIT row");
        return pass("deposit request carries the CONVERTER_DEPOSIT type");
    }

    private TestResult adminCommands() {
        if (!ready()) return fail("live setup missing");
        ServerBankSystemCommandHandler h = new ServerBankSystemCommandHandler();
        if (!h.money_add_user(ADMIN, PLAYER_NAME, 50f)) return fail("money add failed");
        if (!h.money_remove_user(ADMIN, PLAYER_NAME, 20f)) return fail("money remove failed");
        if (!h.money_set_user(ADMIN, PLAYER_NAME, 100f)) return fail("money set failed");
        IServerBankAccount personal = manager.getPersonalBankAccount(PLAYER);
        if (personal == null) return fail("no personal account");
        touchedAccounts.add(personal.getAccountNumber());
        List<TransactionLogRecord> rows = rows(personal.getAccountNumber());
        for (LedgerType t : new LedgerType[]{LedgerType.ADMIN_ADD, LedgerType.ADMIN_REMOVE, LedgerType.ADMIN_SET}) {
            TransactionLogRecord r = find(rows, t);
            if (r == null) return fail("missing " + t + " in " + rows);
            if (!ADMIN.equals(r.actorUuid()) || !ADMIN_NAME.equals(r.actorName()) || r.source() != LedgerActor.Source.ADMIN)
                return fail(t + " actor not the admin: " + r);
            if (r.accountNumber() != personal.getAccountNumber()) return fail(t + " wrong target account");
        }
        TransactionLogRecord set = find(rows, LedgerType.ADMIN_SET);
        if (set.note() == null || !set.note().contains("->")) return fail("ADMIN_SET note lacks old->new: " + set.note());
        return pass("money add/remove/set rows carry admin uuid+name and the target account");
    }

    private TestResult accountUpdateRows() {
        if (!ready()) return fail("live setup missing");
        ItemID diamond = allow(Items.DIAMOND.getDefaultInstance());
        IServerBankAccount account = manager.getBankAccount(accountNr);
        IServerBank bank = account.getOrCreateBank(diamond);
        long oldBalance = bank.getBalance() + 1_000L; // earlier tests may leave diamonds behind
        bank.deposit(1_000L);
        bank.lockAmount(100L);
        UpdateBankAccountRequest req = new UpdateBankAccountRequest();
        List<UpdateBankAccountRequest.InputData.BankData> data = new ArrayList<>();
        data.add(new UpdateBankAccountRequest.InputData.BankData(diamond, 500L, true, true, false, false));
        req.handleOnMasterServer(new UpdateBankAccountRequest.InputData(accountNr, "LedgerTestRenamed", null,
                data, null), "", ADMIN).join();
        // Owner (non-admin, MANAGE) closes the bank.
        List<UpdateBankAccountRequest.InputData.BankData> close = new ArrayList<>();
        close.add(new UpdateBankAccountRequest.InputData.BankData(diamond, 0L, false, false, true, false));
        req.handleOnMasterServer(new UpdateBankAccountRequest.InputData(accountNr, "", null, close, null), "", PLAYER).join();

        List<TransactionLogRecord> rows = rows(accountNr);
        TransactionLogRecord unlock = find(rows, LedgerType.ADMIN_UNLOCK);
        TransactionLogRecord set = find(rows, LedgerType.ADMIN_SET);
        TransactionLogRecord edit = find(rows, LedgerType.ADMIN_ACCOUNT_EDIT);
        TransactionLogRecord closed = find(rows, LedgerType.BANK_CLOSED);
        if (unlock == null || set == null || edit == null || closed == null) return fail("missing rows: " + rows);
        if (unlock.amount() != 100L) return fail("unlock amount " + unlock.amount());
        if (!(oldBalance + "->500").equals(set.note())) return fail("set note " + set.note());
        if (set.source() != LedgerActor.Source.ADMIN || !ADMIN.equals(set.actorUuid())) return fail("set actor " + set);
        if (edit.amount() != 0 || edit.note() == null || !edit.note().contains("LedgerTestRenamed")) return fail("edit " + edit);
        if (closed.amount() != 500L || closed.source() != LedgerActor.Source.PLAYER || !PLAYER.equals(closed.actorUuid()))
            return fail("closed " + closed);
        return pass("unlock / set / edit rows by admin, BANK_CLOSED by owner");
    }

    private TestResult transferPair() {
        if (!ready()) return fail("live setup missing");
        IServerBank from = manager.getBankAccount(accountNr).getOrCreateBank(MoneyItem.getItemID());
        IServerBank to = manager.getBankAccount(otherAccountNr).getOrCreateBank(MoneyItem.getItemID());
        if (from == null || to == null) return fail("no money banks");
        from.deposit(1_000L);
        TransactionLedger.withActor(LedgerActor.player(PLAYER, PLAYER_NAME), () -> from.transfer(400L, to));
        TransactionLogRecord out = find(rows(accountNr), LedgerType.TRANSFER);
        TransactionLogRecord in = find(rows(otherAccountNr), LedgerType.TRANSFER);
        if (out == null || in == null) return fail("missing transfer rows");
        if (out.amount() != -400L || in.amount() != 400L) return fail("amounts " + out.amount() + "/" + in.amount());
        if (out.amount() + in.amount() != 0) return fail("pair does not sum to 0");
        if (out.otherAccount() == null || out.otherAccount() != otherAccountNr) return fail("out other_account");
        if (in.otherAccount() == null || in.otherAccount() != accountNr) return fail("in other_account");
        if (!PLAYER.equals(out.actorUuid()) || !PLAYER.equals(in.actorUuid())) return fail("actor not propagated");
        return pass("TRANSFER -400/+400 with counterparty and actor");
    }

    private TestResult consoleActor() {
        if (!ready()) return fail("live setup missing");
        BankSystemMod.getAPI().getLedger().log(accountNr, LedgerType.ADMIN_ADD, MoneyItem.getItemID(), 1L,
                LedgerActor.console(), null, "console test");
        TransactionLogRecord r = find(rows(accountNr), LedgerType.ADMIN_ADD);
        if (r == null) return fail("no row");
        if (r.source() != LedgerActor.Source.CONSOLE || r.actorUuid() != null) return fail("actor " + r);
        return pass("console actor stored as CONSOLE without uuid");
    }

    private TestResult logTradeRoundTrip() {
        if (!ready()) return fail("live setup missing");
        String ref = "ledger-test-" + UUID.randomUUID();
        touchedRefs.add(ref);
        ItemID diamond = allow(Items.DIAMOND.getDefaultInstance());
        ItemID money = MoneyItem.getItemID();
        long t = System.currentTimeMillis();
        var api = BankSystemMod.getAPI().getLedger();
        api.logTrade(new TradeLogEntry(accountNr, PLAYER, TradeSide.BUY, diamond, 300L, money, 3_000L, 1_000L,
                100L, ref, TradeRowKind.PERIODIC, t, null));
        api.logTrade(new TradeLogEntry(accountNr, PLAYER, TradeSide.BUY, diamond, 200L, money, 2_200L, 1_100L,
                null, ref, TradeRowKind.CLOSE, t + 1, "filled"));
        List<TransactionLogRecord> rows;
        try {
            rows = ledger.getByRef(ref).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fail("getByRef threw: " + e);
        }
        TestResult r = assertEquals("rows per ref", 2, rows.size());
        if (!r.passed()) return r;
        TransactionLogRecord p = rows.get(0), c = rows.get(1);
        if (p.type() != LedgerType.TRADE_BUY || p.source() != LedgerActor.Source.STOCKMARKET) return fail("type/source " + p);
        if (!"PERIODIC".equals(p.tag()) || !"CLOSE".equals(c.tag())) return fail("tags " + p.tag() + "/" + c.tag());
        if (p.itemId() != diamond.getShort() || p.currencyItem() == null || p.currencyItem() != money.getShort())
            return fail("item/currency " + p);
        if (p.moneyAmount() == null || p.moneyAmount() != 3_000L || p.price() == null || p.price() != 1_000L)
            return fail("money side " + p);
        if (p.virtualQty() == null || p.virtualQty() != 100L || c.virtualQty() != null) return fail("virtual qty");
        if (p.balanceAfter() != null) return fail("trade rows must not carry balance_after");
        if (p.amount() + c.amount() != 500L || p.moneyAmount() + c.moneyAmount() != 5_200L)
            return fail("deltas do not sum to the order totals");
        if (!PLAYER.equals(p.actorUuid())) return fail("actor " + p.actorUuid());
        return pass("PERIODIC + CLOSE rows round-trip and sum per ref");
    }

    private TestResult startingBalance() {
        if (!ready()) return fail("live setup missing");
        var settings = BankSystemModBackend.getInstances_forTesting().SERVER_SETTINGS.PLAYER.STARTING_BALANCE;
        long previous = settings.get();
        UUID fresh = UUID.fromString("00000000-0000-0000-0000-00000000f10c");
        try {
            settings.set(700L);
            addUser(fresh, "LedgerTestFresh");
            IServerBankAccount personal = manager.createPersonalBankAccount(fresh);
            if (personal == null) return fail("personal account not created");
            touchedAccounts.add(personal.getAccountNumber());
            TransactionLogRecord r = find(rows(personal.getAccountNumber()), LedgerType.STARTING_BALANCE);
            if (r == null) return fail("no STARTING_BALANCE row");
            if (r.amount() != 700L || !fresh.equals(r.actorUuid())) return fail("row " + r);
            return pass("STARTING_BALANCE row for a new personal account");
        } finally {
            settings.set(previous);
            manager.removeUser(fresh);
        }
    }

    private TestResult userDeletionPayout() {
        if (!ready()) return fail("live setup missing");
        UUID doomed = UUID.fromString("00000000-0000-0000-0000-00000000f10d");
        addUser(doomed, "LedgerTestDoomed");
        IServerBank bank = manager.getOrCreatePersonalBank(doomed, MoneyItem.getItemID());
        if (bank == null) return fail("no personal bank");
        int nr = manager.getPersonalBankAccount(doomed).getAccountNumber();
        touchedAccounts.add(nr);
        bank.deposit(1_234L);
        TransactionLedger.withActor(LedgerActor.admin(ADMIN, ADMIN_NAME), () -> manager.removeUser(doomed));
        TransactionLogRecord r = find(rows(nr), LedgerType.ACCOUNT_DELETE_PAYOUT);
        if (r == null) return fail("no ACCOUNT_DELETE_PAYOUT row");
        if (r.amount() < 1_234L || !ADMIN.equals(r.actorUuid())) return fail("row " + r);
        return pass("user deletion pays out with ACCOUNT_DELETE_PAYOUT");
    }

    // ------------------------------------------------------------------ slave type codes

    private TestResult slaveCodeAllowList() {
        var deposit = TransactionLedger.SLAVE_DEPOSIT_CODES;
        TransactionLedger.Resolved ok = TransactionLedger.resolveSlaveCode(LedgerType.UPLOAD_DEPOSIT.code(),
                deposit, false, "n", "slave-a", "test");
        if (ok == null || ok.type() != LedgerType.UPLOAD_DEPOSIT || !"n".equals(ok.note())) return fail("valid code not kept: " + ok);
        TransactionLedger.Resolved bad = TransactionLedger.resolveSlaveCode(LedgerType.ADMIN_ADD.code(),
                deposit, false, null, "slave-a", "test");
        if (bad == null || bad.type() != LedgerType.UNKNOWN_LEGACY
                || !"rejected code 8 from slave slave-a".equals(bad.note())) return fail("disallowed code: " + bad);
        TransactionLedger.Resolved unknown = TransactionLedger.resolveSlaveCode(9999,
                TransactionLedger.SLAVE_WITHDRAW_CODES, true, "x", "slave-b", "test");
        if (unknown == null || unknown.type() != LedgerType.UNKNOWN_LEGACY
                || !"rejected code 9999 from slave slave-b; x".equals(unknown.note())) return fail("unknown code: " + unknown);
        if (TransactionLedger.resolveSlaveCode(TransactionLedger.NO_LOG, deposit, true, null, "s", "test") != null)
            return fail("NO_LOG must write nothing where allowed");
        TransactionLedger.Resolved noLog = TransactionLedger.resolveSlaveCode(TransactionLedger.NO_LOG, deposit, false, null, "s", "test");
        if (noLog == null || noLog.type() != LedgerType.UNKNOWN_LEGACY) return fail("NO_LOG not allowed must be rejected: " + noLog);
        if (deposit.contains(LedgerType.WITHDRAW) || TransactionLedger.SLAVE_WITHDRAW_CODES.contains(LedgerType.DEPOSIT))
            return fail("deposit/withdraw lists overlap");
        return pass("allowed code kept, disallowed/unknown become 0 with note, NO_LOG writes nothing");
    }

    /** The master writes the row from the code the (simulated) slave put on an AsyncBank call. */
    private TestResult asyncBankCodeLoggedOnMaster() {
        if (!ready()) return fail("live setup missing");
        ItemID moneyId = MoneyItem.getItemID();
        IServerBank money = manager.getBankAccount(accountNr).getOrCreateBank(moneyId);
        if (money == null) return fail("no money bank");
        money.deposit(1_000L);
        net.kroia.banksystem.banking.bank.AsyncBank.Request handler = net.kroia.banksystem.banking.bank.AsyncBank.Request.instance;

        // Tagged deposit: row type, actor and note come from the request.
        var tagged = TransactionLedger.tagged(LedgerType.UPLOAD_DEPOSIT, PLAYER, "upload test",
                () -> net.kroia.banksystem.banking.bank.AsyncBank.InputData.of(
                        net.kroia.banksystem.banking.bank.AsyncBank.FunctionType.DepositAsync, accountNr, moneyId, 77L));
        handler.handleOnMasterServer(tagged, "", null).join();
        TransactionLogRecord up = find(rows(accountNr), LedgerType.UPLOAD_DEPOSIT);
        if (up == null || up.amount() != 77L || !PLAYER.equals(up.actorUuid()) || !"upload test".equals(up.note()))
            return fail("tagged deposit row " + up);

        // Raw call (StockMarket settlement): NO_LOG, no row.
        int before = rows(accountNr).size();
        handler.handleOnMasterServer(net.kroia.banksystem.banking.bank.AsyncBank.InputData.of(
                net.kroia.banksystem.banking.bank.AsyncBank.FunctionType.DepositAsync, accountNr, moneyId, 5L), "", null).join();
        if (rows(accountNr).size() != before) return fail("raw deposit wrote a row");

        // Disallowed code on a withdraw: operation runs, row is UNKNOWN_LEGACY with the note.
        long balance = money.getBalance();
        var bad = TransactionLedger.tagged(LedgerType.ADMIN_ADD, PLAYER, null,
                () -> net.kroia.banksystem.banking.bank.AsyncBank.InputData.of(
                        net.kroia.banksystem.banking.bank.AsyncBank.FunctionType.WithdrawAsync, accountNr, moneyId, 10L));
        handler.handleOnMasterServer(bad, "", null).join();
        if (money.getBalance() != balance - 10L) return fail("rejected code must not drop the withdraw");
        TransactionLogRecord rejected = find(rows(accountNr), LedgerType.UNKNOWN_LEGACY);
        if (rejected == null || rejected.amount() != 10L || rejected.note() == null
                || !rejected.note().startsWith("rejected code 8")) return fail("rejected row " + rejected);
        return pass("tagged call logged, raw call silent, disallowed code logged as 0");
    }

    private TestResult itemRequestRejectsDisallowedCode() {
        if (!ready()) return fail("live setup missing");
        ItemID diamond = allow(Items.DIAMOND.getDefaultInstance());
        Map<ItemID, Long> items = new HashMap<>();
        items.put(diamond, 4L);
        Map<ItemID, Long> notDeposited = new DepositItemsInBankRequest().handleOnMasterServer(new DepositItemsInBankRequest.InputData(
                accountNr, PLAYER, items, LedgerType.ADMIN_SET.code()), "", null).join().items();
        if (!notDeposited.isEmpty()) return fail("deposit dropped: " + notDeposited);
        TransactionLogRecord r = find(rows(accountNr), LedgerType.UNKNOWN_LEGACY);
        if (r == null || r.amount() != 4L || r.note() == null || !r.note().startsWith("rejected code 7"))
            return fail("row " + r);
        Map<ItemID, Long> withdrawn = new WithdrawItemsFromBankRequest().handleOnMasterServer(
                new WithdrawItemsFromBankRequest.InputData(accountNr, PLAYER, items, LedgerType.DOWNLOAD_WITHDRAW.code()), "", null)
                .join().items();
        if (withdrawn.getOrDefault(diamond, 0L) != 4L) return fail("withdraw failed: " + withdrawn);
        if (find(rows(accountNr), LedgerType.DOWNLOAD_WITHDRAW) == null) return fail("allowed withdraw code not written");
        return pass("disallowed deposit code logged as 0, allowed withdraw code kept");
    }

    // ------------------------------------------------------------------ helpers

    private boolean ready() {
        return manager != null && ledger != null && accountNr != ServerBankAccount.INVALID_ACCOUNT_NUMBER
                && otherAccountNr != ServerBankAccount.INVALID_ACCOUNT_NUMBER;
    }

    private void addUser(UUID uuid, String name) {
        if (!manager.userExists(uuid)) manager.addUser(new User(uuid, name));
    }

    private int newAccount(String name, UUID owner) {
        IServerBankAccount account = manager.createBankAccount(name);
        if (account == null) return ServerBankAccount.INVALID_ACCOUNT_NUMBER;
        account.addUser(manager.getUserByUUID(owner), BankPermission.getAllPermissions());
        touchedAccounts.add(account.getAccountNumber());
        return account.getAccountNumber();
    }

    private ItemID allow(net.minecraft.world.item.ItemStack stack) {
        ItemID id = ItemID.of(stack);
        if (!manager.isItemIDAllowed(id)) manager.allowItemID(id);
        return id;
    }

    /** Reads an account's rows; the single-thread DB worker orders this after every queued write. */
    private List<TransactionLogRecord> rows(int account) {
        try {
            return ledger.getByAccount(account, 0).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static TransactionLogRecord find(List<TransactionLogRecord> rows, LedgerType type) {
        for (TransactionLogRecord r : rows) if (r.type() == type) return r;
        return null;
    }

    private void deleteLedgerRows() {
        DatabaseManager db = BankSystemModBackend.getDatabaseManager();
        if (db == null) return;
        List<Integer> accounts = new ArrayList<>(touchedAccounts);
        List<String> refs = new ArrayList<>(touchedRefs);
        try {
            db.getDatabaseThread().submit(() -> {
                try (var ps = db.getConnection().prepareStatement("DELETE FROM TransactionLog WHERE account_number = ?");
                     var pr = db.getConnection().prepareStatement("DELETE FROM TransactionLog WHERE ref = ?")) {
                    for (int a : accounts) { ps.setInt(1, a); ps.executeUpdate(); }
                    for (String r : refs) { pr.setString(1, r); pr.executeUpdate(); }
                    db.commitTransaction();
                } catch (SQLException ignored) {
                    // cleanup is best-effort
                }
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // cleanup is best-effort
        }
    }

    private static Connection openMemoryDb() throws Exception {
        Class<?> driverClass;
        try {
            driverClass = ClassLoader.getSystemClassLoader().loadClass("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            driverClass = TransactionLedgerTests.class.getClassLoader().loadClass("org.sqlite.JDBC");
        }
        try {
            DriverManager.registerDriver(new DriverShim((Driver) driverClass.getDeclaredConstructor().newInstance()));
        } catch (Exception ignored) {}
        Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
        c.setAutoCommit(false);
        return c;
    }

    /** DatabaseManager stand-in over a private connection (same pattern as TransactionLogManagerTests). */
    private static final class MemoryDb extends DatabaseManager {
        private final Connection conn;
        private final java.util.concurrent.ExecutorService exec = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ledger-test-db-worker");
            t.setDaemon(true);
            return t;
        });

        MemoryDb(Connection conn) { this.conn = conn; }

        @Override public Connection getConnection() { return conn; }
        @Override public java.util.concurrent.ExecutorService getDatabaseThread() { return exec; }
        @Override public boolean commitTransaction() {
            try { conn.commit(); return true; } catch (SQLException e) { return false; }
        }
    }
}
