package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bank.IServerBank;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.api.event.TransactionLoggedEvent;
import net.kroia.banksystem.api.ledger.LedgerActor;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.api.ledger.TradeLogEntry;
import net.kroia.banksystem.api.ledger.TradeRowKind;
import net.kroia.banksystem.api.ledger.TradeSide;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.bank.AsyncBank;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import net.kroia.banksystem.banking.ledger.TransactionLedger;
import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.data.DriverShim;
import net.kroia.banksystem.data.TransferJournal;
import net.kroia.banksystem.data.table.TransactionLogManager;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.minecraft.command.ServerBankSystemCommandHandler;
import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.entity.WithdrawMoneyPacket;
import net.kroia.banksystem.networking.general.UpdateBankAccountRequest;
import net.kroia.banksystem.networking.multi_server.DepositItemsInBankRequest;
import net.kroia.banksystem.networking.multi_server.DropItemsInPlayerInventoryRequest;
import net.kroia.banksystem.networking.multi_server.WithdrawItemsFromBankRequest;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.testing.LiveStateGuard;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.UtilitiesPlatform;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
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
 * trade rows). Task #117 (v2.2.0) adds the account-freeze tests ({@code freeze_*}).
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
        // Task #117 — account freeze
        addTest("freeze_flag_persists_save_load", this::freezePersists);
        addTest("freeze_api_row_and_event_once", this::freezeApiRowAndEvent);
        addTest("freeze_command_admin_only", this::freezeCommand);
        addTest("freeze_blocks_player_paths", this::freezeBlocksPlayerPaths);
        addTest("freeze_allows_admin_and_raw_calls", this::freezeAllowsAdminAndRaw);
        // Task #119 — API event
        addTest("transaction_logged_event_once_per_row", this::transactionLoggedEvent);
        // Task #125 — transfer_id on the wire
        addTest("async_bank_ref_is_transfer_id", this::asyncBankRefIsTransferId);
        addTest("deposit_items_ref_is_transfer_id", this::depositItemsRefIsTransferId);
        addTest("transfer_id_codec_compat", this::transferIdCodecCompat);
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

    // ------------------------------------------------------------------ Task #125 transfer_id

    /**
     * The slave's journal id goes on the wire (AsyncBank.journal() writes the decoded id, so this is the slave
     * TransferJournal transfer_id) and the master writes it as the row's ref.
     */
    private TestResult asyncBankRefIsTransferId() {
        try {
            return asyncBankRef();
        } catch (Exception e) {
            return fail("threw " + e);
        }
    }

    private TestResult asyncBankRef() throws Exception {
        if (!ready()) return fail("live setup missing");
        ItemID moneyId = MoneyItem.getItemID();
        TransferJournal.Ref ref = TransferJournal.ref("ref_test");
        AsyncBank.InputData in = TransactionLedger.tagged(LedgerType.UPLOAD_DEPOSIT, PLAYER, null, ref,
                () -> AsyncBank.InputData.of(AsyncBank.FunctionType.DepositAsync, accountNr, moneyId, 7L));
        AsyncBank.BankIdentifyAndDataPacket<?> p = in.decodeParams();
        if (!ref.transferId().equals(p.transferId())) return fail("wire id " + p.transferId() + " != journal id " + ref.transferId());
        AsyncBank.Request.instance.handleOnMasterServer(in, "", null).join();
        List<TransactionLogRecord> byRef = ledger.getByRef(ref.transferId()).get(10, TimeUnit.SECONDS);
        if (byRef.size() != 1 || byRef.get(0).type() != LedgerType.UPLOAD_DEPOSIT || byRef.get(0).amount() != 7L)
            return fail("rows by ref " + byRef);

        AsyncBank.BankIdentifyAndDataPacket<?> untagged = AsyncBank.InputData.of(
                AsyncBank.FunctionType.WithdrawAsync, accountNr, moneyId, 1L).decodeParams();
        String id = untagged.transferId();
        if (id == null || !UUID.fromString(id).toString().equals(id)) return fail("untagged withdraw id " + id);
        AsyncBank.BankIdentifyAndDataPacket<?> read = AsyncBank.InputData.of(
                AsyncBank.FunctionType.GetBalanceAsync, accountNr, moneyId).decodeParams();
        if (read.transferId() != null) return fail("read carries id " + read.transferId());
        return pass("ledger ref = journal id; untagged deposit/withdraw gets a fresh UUID, reads none");
    }

    private TestResult depositItemsRefIsTransferId() {
        if (!ready()) return fail("live setup missing");
        ItemID diamond = allow(Items.DIAMOND.getDefaultInstance());
        String tid = TransferJournal.newTransferId();
        Map<ItemID, Long> items = new HashMap<>();
        items.put(diamond, 3L);
        new DepositItemsInBankRequest().handleOnMasterServer(new DepositItemsInBankRequest.InputData(
                accountNr, PLAYER, items, LedgerType.CONVERTER_DEPOSIT.code(), tid), "", null).join();
        List<TransactionLogRecord> byRef;
        try {
            byRef = ledger.getByRef(tid).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fail("getByRef threw: " + e);
        }
        if (byRef.size() != 1 || byRef.get(0).type() != LedgerType.CONVERTER_DEPOSIT || byRef.get(0).amount() != 3L)
            return fail("rows by ref " + byRef);
        return pass("deposit row ref = transfer_id");
    }

    /** transfer_id is the last, optional field: legacy payloads decode to null, legacy decoders ignore it. */
    private TestResult transferIdCodecCompat() {
        String tid = TransferJournal.newTransferId();
        ItemID money = MoneyItem.getItemID();
        Map<ItemID, Long> items = Map.of(money, 5L);

        // DepositItemsInBankRequest: the pre-#125 codec, copied
        StreamCodec<RegistryFriendlyByteBuf, DepositItemsInBankRequest.InputData> depositLegacy = StreamCodec.composite(
                ByteBufCodecs.INT, p -> p.bankAccount(),
                ExtraCodecUtils.nullable(UUIDUtil.STREAM_CODEC), p -> p.executor(),
                ExtraCodecUtils.mapStreamCodec(ItemID.STREAM_CODEC, ByteBufCodecs.VAR_LONG, HashMap<ItemID, Long>::new), p -> p.items(),
                ByteBufCodecs.VAR_INT, p -> p.ledgerTypeCode(),
                DepositItemsInBankRequest.InputData::new);
        var dOld = new DepositItemsInBankRequest.InputData(7, PLAYER, items, LedgerType.CONVERTER_DEPOSIT.code());
        var dNew = new DepositItemsInBankRequest.InputData(7, PLAYER, items, LedgerType.CONVERTER_DEPOSIT.code(), tid);
        TestResult r = compat("deposit", depositLegacy, DepositItemsInBankRequest.InputData.STREAM_CODEC, dOld, dNew);
        if (!r.passed()) return r;

        // DropItemsInPlayerInventoryRequest: the pre-#125 codec, copied
        StreamCodec<RegistryFriendlyByteBuf, DropItemsInPlayerInventoryRequest.InputData> dropLegacy = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, p -> p.playerReceiver(),
                ExtraCodecUtils.mapStreamCodec(ItemID.STREAM_CODEC, ByteBufCodecs.VAR_LONG, HashMap<ItemID, Long>::new), p -> p.items(),
                ByteBufCodecs.BOOL, p -> p.forceDrop(),
                DropItemsInPlayerInventoryRequest.InputData::new);
        r = compat("drop", dropLegacy, DropItemsInPlayerInventoryRequest.InputData.STREAM_CODEC,
                new DropItemsInPlayerInventoryRequest.InputData(PLAYER, items, true),
                new DropItemsInPlayerInventoryRequest.InputData(PLAYER, items, true, tid));
        if (!r.passed()) return r;

        // AsyncBank.BankIdentifyAndDataPacket: the pre-#125 encode/decode, copied
        StreamCodec<RegistryFriendlyByteBuf, AsyncBank.BankIdentifyAndDataPacket<Long>> bankLegacy = StreamCodec.of(
                (buf, p) -> {
                    buf.writeInt(p.accountNr());
                    ItemID.STREAM_CODEC.encode(buf, p.itemID());
                    ExtraCodecUtils.nullable(ByteBufCodecs.VAR_LONG).encode(buf, p.extra());
                    ByteBufCodecs.VAR_INT.encode(buf, p.ledgerCode());
                    ExtraCodecUtils.nullable(UUIDUtil.STREAM_CODEC).encode(buf, p.ledgerActor());
                    ExtraCodecUtils.nullable(ByteBufCodecs.STRING_UTF8).encode(buf, p.ledgerNote());
                },
                buf -> new AsyncBank.BankIdentifyAndDataPacket<>(buf.readInt(), ItemID.STREAM_CODEC.decode(buf),
                        ExtraCodecUtils.nullable(ByteBufCodecs.VAR_LONG).decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                        ExtraCodecUtils.nullable(UUIDUtil.STREAM_CODEC).decode(buf),
                        ExtraCodecUtils.nullable(ByteBufCodecs.STRING_UTF8).decode(buf), null));
        r = compat("async bank", bankLegacy, AsyncBank.BankIdentifyAndDataPacket.streamCodec(ByteBufCodecs.VAR_LONG.cast()),
                new AsyncBank.BankIdentifyAndDataPacket<>(7, money, 42L, LedgerType.DEPOSIT.code(), PLAYER, "n", null),
                new AsyncBank.BankIdentifyAndDataPacket<>(7, money, 42L, LedgerType.DEPOSIT.code(), PLAYER, "n", tid));
        if (!r.passed()) return r;

        // an id that is not a canonical UUID goes out as null instead of throwing
        String bad = through(TransferJournal.ID_CODEC, "not-a-uuid", TransferJournal.ID_CODEC);
        if (bad != null) return fail("non-UUID id decoded as " + bad);
        return pass("3 codecs: legacy->new = null id, new->legacy ok, new->new keeps the id; bad id -> null");
    }

    /** {@code old} has a null transfer_id, {@code neu} the same fields plus one. */
    private <T> TestResult compat(String what, StreamCodec<RegistryFriendlyByteBuf, T> legacy,
                                  StreamCodec<RegistryFriendlyByteBuf, T> current, T old, T neu) {
        try {
            T a = through(legacy, old, current);
            if (!old.equals(a)) return fail(what + " legacy->new: " + a);
            T b = through(current, neu, legacy);
            if (!old.equals(b)) return fail(what + " new->legacy: " + b);
            T c = through(current, neu, current);
            if (!neu.equals(c)) return fail(what + " new->new: " + c);
            return pass(what);
        } catch (RuntimeException e) {
            return fail(what + " threw " + e);
        }
    }

    /** Encodes with one codec and decodes the same bytes with another, like one ARRS payload. */
    private static <A, B> B through(StreamCodec<? super RegistryFriendlyByteBuf, A> enc, A value,
                                    StreamCodec<? super RegistryFriendlyByteBuf, B> dec) {
        RegistryFriendlyByteBuf buf = UtilitiesPlatform.createRegistryFriendlyByteBufServerSide();
        if (buf == null) throw new IllegalStateException("no server registry access");
        enc.encode(buf, value);
        return dec.decode(buf);
    }

    // ------------------------------------------------------------------ helpers

    // ------------------------------------------------------------------ Task #117 freeze

    private TestResult freezePersists() {
        if (!ready()) return fail("live setup missing");
        try {
            manager.setAccountFrozen(accountNr, true, LedgerActor.system());
            ServerBankAccount live = (ServerBankAccount) manager.getBankAccount(accountNr);
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            if (!live.save(tag)) return fail("save failed");
            ServerBankAccount loaded = ServerBankAccount.createFromTag(tag);
            if (loaded == null || !loaded.isFrozen()) return fail("frozen flag lost on save/load");
            tag.remove("frozen"); // pre-2.2.0 data
            ServerBankAccount old = ServerBankAccount.createFromTag(tag);
            if (old == null || old.isFrozen()) return fail("missing flag must load as not frozen");
            return pass("flag round-trips; missing = not frozen");
        } finally {
            manager.setAccountFrozen(accountNr, false, LedgerActor.system());
        }
    }

    private TestResult freezeApiRowAndEvent() {
        if (!ready()) return fail("live setup missing");
        List<net.kroia.banksystem.api.event.AccountFreezeChangedEvent> events = new ArrayList<>();
        java.util.function.Consumer<net.kroia.banksystem.api.event.AccountFreezeChangedEvent> listener = e -> {
            if (e.accountNumber() == accountNr) events.add(e);
        };
        var event = BankSystemMod.getAPI().getEvents().getAccountFreezeChangedEvent();
        event.addListener(listener);
        try {
            // earlier tests leave freeze rows on this account; only count rows written here
            long startId = rows(accountNr).stream().mapToLong(TransactionLogRecord::id).max().orElse(0L);
            LedgerActor admin = TransactionLedger.admin(ADMIN);
            if (!manager.setAccountFrozen(accountNr, true, admin)) return fail("set returned false");
            manager.setAccountFrozen(accountNr, true, admin); // no change
            if (!manager.isAccountFrozen(accountNr)) return fail("not frozen");
            if (events.size() != 1 || !events.get(0).frozen() || !ADMIN.equals(events.get(0).actor().uuid()))
                return fail("expected one freeze event, got " + events);
            manager.setAccountFrozen(accountNr, false, admin);
            if (manager.isAccountFrozen(accountNr) || events.size() != 2 || events.get(1).frozen())
                return fail("unfreeze: " + events);
            if (manager.setAccountFrozen(-12345, true, admin)) return fail("unknown account returned true");
            int frozenRows = 0, unfrozenRows = 0;
            for (TransactionLogRecord r : rows(accountNr)) {
                if (r.type() != LedgerType.ACCOUNT_FREEZE || r.id() <= startId) continue;
                if (r.amount() != 0 || r.itemId() != 0 || !ADMIN.equals(r.actorUuid())) return fail("row " + r);
                if ("frozen".equals(r.note())) frozenRows++;
                if ("unfrozen".equals(r.note())) unfrozenRows++;
            }
            if (frozenRows != 1 || unfrozenRows != 1) return fail("rows frozen=" + frozenRows + " unfrozen=" + unfrozenRows);
            return pass("one row + one event per real change");
        } finally {
            event.removeListener(listener);
            manager.setAccountFrozen(accountNr, false, LedgerActor.system());
        }
    }

    private TestResult freezeCommand() {
        if (!ready()) return fail("live setup missing");
        ServerBankSystemCommandHandler h = new ServerBankSystemCommandHandler();
        try {
            if (h.banksystem_freeze(PLAYER, accountNr, 1) || manager.isAccountFrozen(accountNr))
                return fail("non-admin froze the account");
            if (!h.banksystem_freeze(ADMIN, accountNr, 1) || !manager.isAccountFrozen(accountNr)) return fail("on");
            if (!h.banksystem_freeze(ADMIN, accountNr, 2) || manager.isAccountFrozen(accountNr)) return fail("toggle");
            if (!h.banksystem_freeze(ADMIN, accountNr, 2) || !manager.isAccountFrozen(accountNr)) return fail("toggle back");
            if (!h.banksystem_freeze(ADMIN, accountNr, 0) || manager.isAccountFrozen(accountNr)) return fail("off");
            TransactionLogRecord r = find(rows(accountNr), LedgerType.ACCOUNT_FREEZE);
            if (r == null || r.source() != LedgerActor.Source.ADMIN || !ADMIN.equals(r.actorUuid())) return fail("row " + r);
            return pass("on / toggle / off by admin; non-admin refused");
        } finally {
            manager.setAccountFrozen(accountNr, false, LedgerActor.system());
        }
    }

    private TestResult freezeBlocksPlayerPaths() {
        if (!ready()) return fail("live setup missing");
        ItemID moneyId = MoneyItem.getItemID();
        ItemID diamond = allow(Items.DIAMOND.getDefaultInstance());
        IServerBank money = manager.getBankAccount(accountNr).getOrCreateBank(moneyId);
        IServerBank gems = manager.getBankAccount(accountNr).getOrCreateBank(diamond);
        IServerBank otherMoney = manager.getBankAccount(otherAccountNr).getOrCreateBank(moneyId);
        if (money == null || gems == null || otherMoney == null) return fail("no banks");
        money.deposit(10_000L);
        gems.deposit(500L);
        otherMoney.deposit(10_000L);
        long m = money.getBalance(), g = gems.getBalance(), o = otherMoney.getBalance();
        manager.setAccountFrozen(accountNr, true, LedgerActor.system());
        try {
            Map<ItemID, Long> items = new HashMap<>();
            items.put(diamond, 5L);
            Map<ItemID, Long> back = new DepositItemsInBankRequest().handleOnMasterServer(
                    new DepositItemsInBankRequest.InputData(accountNr, PLAYER, items), "", null).join().items();
            if (!Long.valueOf(5L).equals(back.get(diamond))) return fail("deposit request not refused: " + back);
            Map<ItemID, Long> out = new WithdrawItemsFromBankRequest().handleOnMasterServer(
                    new WithdrawItemsFromBankRequest.InputData(accountNr, PLAYER, items), "", null).join().items();
            if (!out.isEmpty()) return fail("withdraw request not refused: " + out);
            // Rejected (disallowed) slave code counts as player movement too.
            back = new DepositItemsInBankRequest().handleOnMasterServer(new DepositItemsInBankRequest.InputData(
                    accountNr, PLAYER, items, LedgerType.ADMIN_SET.code()), "", null).join().items();
            if (back.isEmpty()) return fail("forged code bypassed the freeze");

            HashMap<ItemID, Long> notes = new HashMap<>();
            notes.put(ItemID.of(BankSystemItems.MONEY5.get().getDefaultInstance()), 2L);
            new WithdrawMoneyPacket(notes, accountNr).handle(PLAYER, null);

            net.kroia.banksystem.banking.bank.AsyncBank.Request handler = net.kroia.banksystem.banking.bank.AsyncBank.Request.instance;
            for (LedgerType t : new LedgerType[]{LedgerType.DEPOSIT, LedgerType.UPLOAD_DEPOSIT, LedgerType.CRAFT_OUTPUT}) {
                var in = TransactionLedger.tagged(t, PLAYER, null, () -> net.kroia.banksystem.banking.bank.AsyncBank.InputData.of(
                        net.kroia.banksystem.banking.bank.AsyncBank.FunctionType.DepositAsync, accountNr, moneyId, 7L));
                handler.handleOnMasterServer(in, "", null).join();
            }
            for (LedgerType t : new LedgerType[]{LedgerType.WITHDRAW, LedgerType.DOWNLOAD_WITHDRAW, LedgerType.CRAFT_CONSUME}) {
                var in = TransactionLedger.tagged(t, PLAYER, null, () -> net.kroia.banksystem.banking.bank.AsyncBank.InputData.of(
                        net.kroia.banksystem.banking.bank.AsyncBank.FunctionType.WithdrawLockedPreferedAsync, accountNr, moneyId, 7L));
                handler.handleOnMasterServer(in, "", null).join();
            }
            // Master-local tagged calls (terminal / upload / download / crafting).
            if (TransactionLedger.tagged(LedgerType.DEPOSIT, PLAYER, null, () -> money.depositAsync(3L)).join() != net.kroia.banksystem.api.bank.BankStatus.FAILED_ACCOUNT_FROZEN)
                return fail("tagged local deposit not refused");
            if (TransactionLedger.tagged(LedgerType.WITHDRAW, PLAYER, null, () -> gems.withdrawLockedPreferedAsync(3L)).join() != net.kroia.banksystem.api.bank.BankStatus.FAILED_ACCOUNT_FROZEN)
                return fail("tagged local withdraw not refused");
            // Transfers in both directions.
            if (TransactionLedger.tagged(LedgerType.TRANSFER, PLAYER, null, () -> money.transfer(100L, otherMoney)) != net.kroia.banksystem.api.bank.BankStatus.FAILED_ACCOUNT_FROZEN)
                return fail("transfer out of frozen not refused");
            if (TransactionLedger.tagged(LedgerType.TRANSFER, OTHER, null, () -> otherMoney.transfer(100L, money)) != net.kroia.banksystem.api.bank.BankStatus.FAILED_ACCOUNT_FROZEN)
                return fail("transfer into frozen not refused");
            if (!net.kroia.banksystem.banking.ledger.AccountFreeze.blocks(accountNr, LedgerType.CONVERTER_DEPOSIT, null))
                return fail("converter deposit not blocked");

            if (money.getBalance() != m || gems.getBalance() != g || otherMoney.getBalance() != o)
                return fail("balances changed: money " + m + "->" + money.getBalance() + ", gems " + g + "->" + gems.getBalance()
                        + ", other " + o + "->" + otherMoney.getBalance());
            return pass("item requests, money withdraw, async/local tagged calls and transfers refused; balances unchanged");
        } finally {
            manager.setAccountFrozen(accountNr, false, LedgerActor.system());
        }
    }

    private TestResult freezeAllowsAdminAndRaw() {
        if (!ready()) return fail("live setup missing");
        ItemID moneyId = MoneyItem.getItemID();
        IServerBank money = manager.getBankAccount(accountNr).getOrCreateBank(moneyId);
        if (money == null) return fail("no money bank");
        money.deposit(1_000L);
        manager.setAccountFrozen(accountNr, true, LedgerActor.system());
        try {
            net.kroia.banksystem.banking.bank.AsyncBank.Request handler = net.kroia.banksystem.banking.bank.AsyncBank.Request.instance;
            long b = money.getBalance();
            // Raw (NO_LOG) calls, e.g. StockMarket settlement: lock, unlock, withdraw.
            handler.handleOnMasterServer(net.kroia.banksystem.banking.bank.AsyncBank.InputData.of(
                    net.kroia.banksystem.banking.bank.AsyncBank.FunctionType.LockAmountAsync, accountNr, moneyId, 50L), "", null).join();
            handler.handleOnMasterServer(net.kroia.banksystem.banking.bank.AsyncBank.InputData.of(
                    net.kroia.banksystem.banking.bank.AsyncBank.FunctionType.UnlockAmountAsync, accountNr, moneyId, 50L), "", null).join();
            if (money.getLockedBalance() != 0 || money.getBalance() != b) return fail("raw lock/unlock");
            handler.handleOnMasterServer(net.kroia.banksystem.banking.bank.AsyncBank.InputData.of(
                    net.kroia.banksystem.banking.bank.AsyncBank.FunctionType.WithdrawAsync, accountNr, moneyId, 10L), "", null).join();
            if (money.getBalance() != b - 10L) return fail("raw withdraw refused");
            // Admin edit through the account screen request.
            List<UpdateBankAccountRequest.InputData.BankData> data = new ArrayList<>();
            data.add(new UpdateBankAccountRequest.InputData.BankData(moneyId, 777L, true, false, false, false));
            new UpdateBankAccountRequest().handleOnMasterServer(new UpdateBankAccountRequest.InputData(accountNr, "", null,
                    data, null), "", ADMIN).join();
            if (money.getBalance() != 777L) return fail("admin set balance refused: " + money.getBalance());
            // Owner (non-admin) may not close a bank of a frozen account; the admin may unfreeze via the screen request.
            List<UpdateBankAccountRequest.InputData.BankData> close = new ArrayList<>();
            close.add(new UpdateBankAccountRequest.InputData.BankData(moneyId, 0L, false, false, true, false));
            new UpdateBankAccountRequest().handleOnMasterServer(new UpdateBankAccountRequest.InputData(accountNr, "", null,
                    close, null), "", PLAYER).join();
            if (manager.getBankAccount(accountNr).getBank(moneyId) == null) return fail("owner closed a frozen bank");
            new UpdateBankAccountRequest().handleOnMasterServer(new UpdateBankAccountRequest.InputData(accountNr, "", null,
                    null, null, 0), "", PLAYER).join();
            if (!manager.isAccountFrozen(accountNr)) return fail("owner unfroze via the screen request");
            new UpdateBankAccountRequest().handleOnMasterServer(new UpdateBankAccountRequest.InputData(accountNr, "", null,
                    null, null, 0), "", ADMIN).join();
            if (manager.isAccountFrozen(accountNr)) return fail("admin unfreeze via the screen request");
            return pass("raw lock/unlock/withdraw and admin edits work on a frozen account");
        } finally {
            manager.setAccountFrozen(accountNr, false, LedgerActor.system());
        }
    }

    // ------------------------------------------------------------------ Task #119 API event

    /** TRANSACTION_LOGGED fires synchronously, once per row, for write and writeAll. */
    private TestResult transactionLoggedEvent() {
        if (!ready()) return fail("live setup missing");
        List<TransactionLoggedEvent> events = new ArrayList<>();
        java.util.function.Consumer<TransactionLoggedEvent> listener = e -> {
            if (e.accountNumber() == accountNr || e.accountNumber() == otherAccountNr)
                synchronized (events) { events.add(e); }
        };
        var event = BankSystemMod.getAPI().getEvents().getTransactionLoggedEvent();
        event.addListener(listener);
        try {
            short money = MoneyItem.getItemID().getShort();
            LedgerActor player = LedgerActor.player(PLAYER, PLAYER_NAME);
            TransactionLogRecord single = TransactionLogRecord.of(accountNr, LedgerType.DEPOSIT, money, 1_234L,
                    player, otherAccountNr, 7, null, "event-test", null);
            TransactionLedger.write(single);
            TransactionLogRecord out = TransactionLogRecord.of(accountNr, LedgerType.TRANSFER, money, -5L,
                    LedgerActor.console(), otherAccountNr, null, null, null, null);
            TransactionLogRecord in = TransactionLogRecord.of(otherAccountNr, LedgerType.TRANSFER, money, 5L,
                    LedgerActor.console(), accountNr, null, null, null, null);
            TransactionLedger.writeAll(List.of(out, in));
            List<TransactionLoggedEvent> expected = List.of(
                    new TransactionLoggedEvent(accountNr, LedgerType.DEPOSIT, money, 1_234L, player,
                            otherAccountNr, 7, single.time(), "event-test"),
                    new TransactionLoggedEvent(accountNr, LedgerType.TRANSFER, money, -5L, LedgerActor.console(),
                            otherAccountNr, null, out.time(), null),
                    new TransactionLoggedEvent(otherAccountNr, LedgerType.TRANSFER, money, 5L, LedgerActor.console(),
                            accountNr, null, in.time(), null));
            synchronized (events) {
                if (!events.equals(expected)) return fail("events " + events + " != " + expected);
            }
            return pass("one event per row (write + writeAll) with the row's fields");
        } finally {
            event.removeListener(listener);
        }
    }

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

    static Connection openMemoryDb() throws Exception {
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
    static final class MemoryDb extends DatabaseManager {
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
