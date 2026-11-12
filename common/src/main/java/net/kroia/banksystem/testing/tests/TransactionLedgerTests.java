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
import net.kroia.banksystem.banking.bankmanager.ServerBankManager;
import net.kroia.banksystem.banking.company.Company;
import net.kroia.banksystem.banking.company.CompanyManager;
import net.kroia.banksystem.banking.ledger.AdminAudit;
import net.kroia.banksystem.banking.ledger.TransactionLedger;
import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.data.DriverShim;
import net.kroia.banksystem.data.TransferJournal;
import net.kroia.banksystem.data.table.TransactionLogManager;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.data.table.record.SuspicionFlagRecord;
import net.kroia.banksystem.banking.ledger.SuspicionChecks;
import net.kroia.banksystem.minecraft.command.ServerBankSystemCommandHandler;
import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem;
import net.kroia.banksystem.networking.entity.WithdrawMoneyPacket;
import net.kroia.banksystem.networking.general.UpdateBankAccountRequest;
import net.kroia.banksystem.networking.multi_server.DepositItemsInBankRequest;
import net.kroia.banksystem.networking.multi_server.DropItemsInPlayerInventoryRequest;
import net.kroia.banksystem.networking.multi_server.TransferStatusRequest;
import net.kroia.banksystem.networking.multi_server.WithdrawItemsFromBankRequest;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.testing.LiveStateGuard;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.UtilitiesPlatform;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.kroia.modutilities.networking.client_server.arrs.RequestOutcomeUnknownException;
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
import java.util.LinkedHashMap;
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
        addTest("user_deletion_offline_writes_loss_rows", () -> threw(this::userDeletionOfflineLoss)); // Task #127
        addTest("user_deletion_splits_payout_and_loss", this::userDeletionSplit); // Task #127
        addTest("user_deletion_keeps_company_account", this::userDeletionKeepsCompanyAccount); // Task #127
        addTest("share_balance_admin_edit", this::shareBalanceAdminEdit); // BS-234
        addTest("user_deletion_payout_rows_per_transfer", this::userDeletionPayoutLegs); // Task #136
        addTest("user_deletion_unknown_outcome_loss_ref", () -> threw(this::userDeletionUnknownOutcome)); // Task #136
        addTest("user_deletion_unknown_outcome_item_flag", () -> threw(this::userDeletionUnknownOutcomeItem)); // BS-242
        addTest("user_deletion_raw_to_items", this::payoutRawToItems); // Task #136
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
        // Task #126 (B6)
        addTest("transfer_id_applies_once", () -> threw(this::transferIdAppliesOnce));
        addTest("drop_unknown_outcome_parks", () -> threw(this::dropUnknownParks));
        addTest("drop_not_sent_refunds_with_switch_on", this::dropNotSentRefundsWithSwitchOn);
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
                {LedgerType.ADMIN_DISALLOW_ITEM, 27}, {LedgerType.ACCOUNT_DELETE_LOSS, 28},
                {LedgerType.RAW_CHANGE, 29}, {LedgerType.EXTERNAL_BIND, 30}, {LedgerType.EXTERNAL_UNBIND, 31},
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

    /**
     * Task #127: the balance of a user who is online nowhere is destroyed: ACCOUNT_DELETE_LOSS "player offline", no
     * PAYOUT row, actor kept although the rows are written after the (async on a master with slaves) delivery chain.
     */
    private TestResult userDeletionOfflineLoss() {
        if (!ready()) return fail("live setup missing");
        if (!(manager instanceof ServerBankManager sbm)) return fail("live manager is not a ServerBankManager");
        UUID doomed = UUID.fromString("00000000-0000-0000-0000-00000000f10d");
        addUser(doomed, "LedgerTestDoomed");
        IServerBank bank = manager.getOrCreatePersonalBank(doomed, MoneyItem.getItemID());
        if (bank == null) return fail("no personal bank");
        int nr = manager.getPersonalBankAccount(doomed).getAccountNumber();
        touchedAccounts.add(nr);
        bank.deposit(1_234L);
        long total = bank.getTotalBalance();
        var payout = TransactionLedger.withActor(LedgerActor.admin(ADMIN, ADMIN_NAME), () -> sbm.removeUserWithPayout(doomed));
        if (payout == null) return fail("user not removed");
        // one DROP per connected slave, each up to an ARRS timeout (30 s) + status check
        ServerBankManager.PayoutResult result = await(payout, 65_000);
        List<TransactionLogRecord> rows = rows(nr);
        if (find(rows, LedgerType.ACCOUNT_DELETE_PAYOUT) != null) return fail("PAYOUT row for an offline user: " + rows);
        TransactionLogRecord r = find(rows, LedgerType.ACCOUNT_DELETE_LOSS, MoneyItem.getItemID());
        if (r == null) return fail("no ACCOUNT_DELETE_LOSS row; result " + result);
        if (r.amount() != total || !ADMIN.equals(r.actorUuid()) || r.ref() != null) return fail("row " + r);
        if (r.note() == null || !r.note().endsWith("player offline")) return fail("note " + r.note());
        return pass("offline user: ACCOUNT_DELETE_LOSS " + total + " (player offline), admin actor kept");
    }

    /** Task #127: per item the undelivered amount is taken from the account rows in order; the rest is PAYOUT. */
    private TestResult userDeletionSplit() {
        if (!ready()) return fail("live setup missing");
        if (!(manager instanceof ServerBankManager sbm)) return fail("live manager is not a ServerBankManager");
        ItemID money = MoneyItem.getItemID();
        Map<Integer, Map<ItemID, Long>> balances = new LinkedHashMap<>();
        balances.put(accountNr, Map.of(money, 100L));
        balances.put(otherAccountNr, Map.of(money, 50L));
        sbm.logDeletePayout(balances, new ServerBankManager.PayoutResult(Map.of(money, 120L), "test"), OTHER, LedgerActor.system());
        List<TransactionLogRecord> first = rows(accountNr), second = rows(otherAccountNr);
        TransactionLogRecord loss1 = find(first, LedgerType.ACCOUNT_DELETE_LOSS, money);
        TransactionLogRecord pay2 = find(second, LedgerType.ACCOUNT_DELETE_PAYOUT, money);
        TransactionLogRecord loss2 = find(second, LedgerType.ACCOUNT_DELETE_LOSS, money);
        if (find(first, LedgerType.ACCOUNT_DELETE_PAYOUT, money) != null) return fail("first account fully lost, got a PAYOUT row");
        if (loss1 == null || loss1.amount() != 100L) return fail("first account LOSS " + loss1);
        if (pay2 == null || pay2.amount() != 30L) return fail("second account PAYOUT " + pay2);
        if (loss2 == null || loss2.amount() != 20L) return fail("second account LOSS " + loss2);
        if (loss2.note() == null || !loss2.note().endsWith("not delivered: test")) return fail("note " + loss2.note());
        if (loss1.ref() != null || pay2.ref() != null || loss2.ref() != null) return fail("ref without slave legs: " + loss1 + " / " + pay2);
        return pass("120 of 150 undelivered: LOSS 100 + PAYOUT 30 / LOSS 20");
    }

    /**
     * Task #136: LOSS first in account order; then each account's payout drains the master's own drop (ref null) and the
     * slave legs in chain order (ref = transfer_id). Legs T1 60 / T2 40, undelivered 20, balances 100 / 50: local part 30.
     */
    private TestResult userDeletionPayoutLegs() {
        if (!ready()) return fail("live setup missing");
        if (!(manager instanceof ServerBankManager sbm)) return fail("live manager is not a ServerBankManager");
        ItemID money = MoneyItem.getItemID();
        UUID deleted = UUID.randomUUID();
        String t1 = TransferJournal.newTransferId(), t2 = TransferJournal.newTransferId();
        Map<Integer, Map<ItemID, Long>> balances = new LinkedHashMap<>();
        balances.put(accountNr, Map.of(money, 100L));
        balances.put(otherAccountNr, Map.of(money, 50L));
        Map<String, Map<ItemID, Long>> legs = new LinkedHashMap<>();
        legs.put(t1, Map.of(money, 60L));
        legs.put(t2, Map.of(money, 40L));
        sbm.logDeletePayout(balances, new ServerBankManager.PayoutResult(Map.of(money, 20L), "test", legs, null), deleted, LedgerActor.system());
        Map<String, Long> first = deleteRowSums(accountNr, deleted), second = deleteRowSums(otherAccountNr, deleted);
        Map<String, Long> want1 = Map.of("ACCOUNT_DELETE_LOSS null", 20L, "ACCOUNT_DELETE_PAYOUT null", 30L, "ACCOUNT_DELETE_PAYOUT " + t1, 50L);
        Map<String, Long> want2 = Map.of("ACCOUNT_DELETE_PAYOUT " + t1, 10L, "ACCOUNT_DELETE_PAYOUT " + t2, 40L);
        if (!want1.equals(first)) return fail("first account " + first + ", expected " + want1);
        if (!want2.equals(second)) return fail("second account " + second + ", expected " + want2);
        return pass("LOSS 20, PAYOUT 30 (master) + 50 (T1) / PAYOUT 10 (T1) + 40 (T2)");
    }

    /** Task #136: an unknown DROP outcome: the LOSS rows carry that transfer id and a TRANSFER_PARKED flag is raised. */
    private TestResult userDeletionUnknownOutcome() throws Exception {
        if (!ready()) return fail("live setup missing");
        if (!(manager instanceof ServerBankManager sbm)) return fail("live manager is not a ServerBankManager");
        var flags = BankSystemModBackend.getInstances_forTesting().SUSPICION_FLAG_MANAGER;
        if (flags == null) return fail("no flag manager");
        ItemID money = MoneyItem.getItemID();
        UUID deleted = UUID.randomUUID();
        String tid = TransferJournal.newTransferId();
        // otherAccountNr: drop_unknown_outcome_parks reads the TRANSFER_PARKED flags of accountNr
        sbm.logDeletePayout(Map.of(otherAccountNr, Map.of(money, 100L)),
                new ServerBankManager.PayoutResult(Map.of(money, 30L), "outcome unknown, transfer " + tid, Map.of(), tid),
                deleted, LedgerActor.system());
        Map<String, Long> want = Map.of("ACCOUNT_DELETE_LOSS " + tid, 30L, "ACCOUNT_DELETE_PAYOUT null", 70L);
        Map<String, Long> got = deleteRowSums(otherAccountNr, deleted);
        if (!want.equals(got)) return fail("rows " + got + ", expected " + want);
        for (SuspicionFlagRecord f : flags.getRecent(otherAccountNr, 0, 0).get(10, TimeUnit.SECONDS))
            if (f.flagType() == SuspicionChecks.TRANSFER_PARKED && tid.equals(f.ref()))
                return f.amount() == 30L ? pass("LOSS 30 ref = transfer id, PAYOUT 70 ref null, flag 6 raised") : fail("flag " + f);
        return fail("no TRANSFER_PARKED flag for " + tid);
    }

    /** BS-242: a parked non-money item: flag amount 0, the alert names the scaled item amount, no money sign. */
    private TestResult userDeletionUnknownOutcomeItem() throws Exception {
        if (!ready()) return fail("live setup missing");
        if (!(manager instanceof ServerBankManager sbm)) return fail("live manager is not a ServerBankManager");
        var flags = BankSystemModBackend.getInstances_forTesting().SUSPICION_FLAG_MANAGER;
        if (flags == null) return fail("no flag manager");
        ItemID diamond = allow(Items.DIAMOND.getDefaultInstance());
        String tid = TransferJournal.newTransferId();
        sbm.logDeletePayout(Map.of(otherAccountNr, Map.of(diamond, 250L)),
                new ServerBankManager.PayoutResult(Map.of(diamond, 250L), "outcome unknown, transfer " + tid, Map.of(), tid),
                UUID.randomUUID(), LedgerActor.system());
        for (SuspicionFlagRecord f : flags.getRecent(otherAccountNr, 0, 0).get(10, TimeUnit.SECONDS)) {
            if (f.flagType() != SuspicionChecks.TRANSFER_PARKED || !tid.equals(f.ref())) continue;
            if (f.amount() != 0) return fail("flag amount " + f.amount() + ", expected 0");
            String chat = AdminAudit.flagChat(otherAccountNr, f.flagType(), f.severity(), f.amount(), f.details());
            String want = "2.50 " + Items.DIAMOND.getDefaultInstance().getHoverName().getString();
            if (!chat.contains(want) || chat.contains(net.kroia.banksystem.api.MoneyFormat.formatMoney(250L)) || chat.contains(" 250"))
                return fail("expected '" + want + "' without money or raw count:\n" + chat);
            return pass("flag amount 0, alert shows " + want);
        }
        return fail("no TRANSFER_PARKED flag for " + tid);
    }

    /** Task #136: raw balances become item counts: money cents split into notes, items raw / ratio, the rest below one item returned. */
    private TestResult payoutRawToItems() {
        if (!ready()) return fail("live setup missing");
        ItemID money = MoneyItem.getItemID();
        ItemID diamond = allow(Items.DIAMOND.getDefaultInstance());
        Map<ItemID, Long> counts = new HashMap<>();
        Map<ItemID, ServerBankManager.PayoutUnit> units = new HashMap<>();
        long moneyRest = ServerBankManager.toItems(money, 1_234L, 100L, counts, units); // 12.34
        long diamondRest = ServerBankManager.toItems(diamond, 550L, 100L, counts, units); // 5.5 diamonds
        if (moneyRest != 0L || diamondRest != 50L) return fail("rest " + moneyRest + " / " + diamondRest);
        // money comes out as notes (10 + 1 + 0.20 + 0.10 + 0.01 for 12.34); the raw round trip below checks their worth
        if (!Long.valueOf(5L).equals(counts.get(diamond)) || counts.size() < 3) return fail("counts " + counts);
        Map<ItemID, Long> back = new HashMap<>();
        counts.forEach((id, n) -> back.merge(units.get(id).bank(), n * units.get(id).raw(), Long::sum));
        if (!Map.of(money, 1_234L, diamond, 500L).equals(back)) return fail("back to raw " + back + " from " + counts);
        Map<ItemID, Long> bound = new HashMap<>();
        long boundRest = ServerBankManager.toItems(diamond, 200L, 81L, bound, new HashMap<>()); // a bound slot's own ratio
        if (boundRest != 38L || !Long.valueOf(2L).equals(bound.get(diamond))) return fail("ratio 81: " + bound + " rest " + boundRest);
        return pass("12.34 -> " + counts.size() + " stacks of notes worth 1234, 550 -> 5 diamonds + rest 50, ratio 81: 200 -> 2 + rest 38");
    }

    /** Task #136: amount per "TYPE ref" over one deletion's ACCOUNT_DELETE_* rows (the note names the deleted user). */
    private Map<String, Long> deleteRowSums(int account, UUID deletedUser) {
        Map<String, Long> sums = new HashMap<>();
        for (TransactionLogRecord r : rows(account))
            if (r.note() != null && r.note().startsWith("user deleted: " + deletedUser))
                sums.merge(r.type() + " " + r.ref(), r.amount(), Long::sum);
        return sums;
    }

    /** Task #127: a company account left without users is not deleted, so it is not paid out and keeps its balance. */
    private TestResult userDeletionKeepsCompanyAccount() {
        if (!ready()) return fail("live setup missing");
        CompanyManager cm = CompanyManager.get();
        if (cm == null) return fail("no CompanyManager");
        UUID member = UUID.fromString("00000000-0000-0000-0000-00000000f10e");
        addUser(member, "LedgerTestMember");
        int nr = newAccount("LedgerTestCompany", member);
        Company company = nr == ServerBankAccount.INVALID_ACCOUNT_NUMBER ? null
                : cm.createCompany("LedgerTest" + nr, nr, null, 100L).company; // null caller: no founder user, no caps
        try {
            if (company == null) return fail("company account not created");
            IServerBank bank = manager.getBankAccount(nr).getOrCreateBank(MoneyItem.getItemID());
            if (bank == null) return fail("no money bank");
            bank.deposit(500L);
            long before = bank.getTotalBalance();
            manager.removeUser(member);
            if (manager.getBankAccount(nr) == null) return fail("company account was deleted");
            if (bank.getTotalBalance() != before) return fail("balance " + bank.getTotalBalance() + ", expected " + before);
            List<TransactionLogRecord> rows = rows(nr);
            if (find(rows, LedgerType.ACCOUNT_DELETE_PAYOUT) != null || find(rows, LedgerType.ACCOUNT_DELETE_LOSS) != null)
                return fail("company account was paid out: " + rows);
            return pass("company account kept with its balance, no ACCOUNT_DELETE_* rows");
        } finally {
            if (company != null) cm.deleteCompany(company.getCompanyId());
            if (nr != ServerBankAccount.INVALID_ACCOUNT_NUMBER) manager.deleteBankAccount(nr);
            if (manager.userExists(member)) manager.removeUser(member);
        }
    }

    /** BS-234: a share balance can't be raised; lowering it returns shares to the company or destroys them. */
    private TestResult shareBalanceAdminEdit() {
        if (!ready()) return fail("live setup missing");
        CompanyManager cm = CompanyManager.get();
        if (cm == null) return fail("no CompanyManager");
        int companyNr = newAccount("LedgerTestShareCo", PLAYER);
        Company company = companyNr == ServerBankAccount.INVALID_ACCOUNT_NUMBER ? null
                : cm.createCompany("LedgerTestShares" + companyNr, companyNr, null, 100L).company;
        ItemID share = null;
        try {
            if (company == null) return fail("company not created");
            int id = company.getCompanyId();
            share = allow(StampedShareItem.ofCompany(BankSystemItems.STAMPED_SHARE.get(), id));
            for (int i = 0; i < 10; i++) cm.stampShare(id);
            IServerBank holder = manager.getBankAccount(accountNr).getOrCreateBank(share);
            IServerBank own = manager.getBankAccount(companyNr).getOrCreateBank(share);
            if (holder == null || own == null) return fail("no share bank");
            holder.deposit(500L); // 5 shares
            own.deposit(500L);

            editShare(accountNr, share, 900L, UpdateBankAccountRequest.SHARE_RETURN_TO_COMPANY);
            if (holder.getTotalBalance() != 500L) return fail("share increase applied: " + holder.getTotalBalance());
            editShare(accountNr, share, 0L, UpdateBankAccountRequest.SHARE_NONE);
            if (holder.getTotalBalance() != 500L) return fail("decrease without a choice applied");

            editShare(accountNr, share, 300L, UpdateBankAccountRequest.SHARE_RETURN_TO_COMPANY);
            if (holder.getTotalBalance() != 300L || own.getTotalBalance() != 700L)
                return fail("return to company: holder " + holder.getTotalBalance() + ", company " + own.getTotalBalance());
            TransactionLogRecord out = find(rows(accountNr), LedgerType.TRANSFER, share);
            TransactionLogRecord in = find(rows(companyNr), LedgerType.TRANSFER, share);
            if (out == null || in == null || out.amount() != -200L || in.amount() != 200L
                    || !Integer.valueOf(id).equals(out.companyId()) || !ADMIN.equals(out.actorUuid()))
                return fail("return rows " + out + " / " + in);

            editShare(companyNr, share, 0L, UpdateBankAccountRequest.SHARE_RETURN_TO_COMPANY);
            if (own.getTotalBalance() != 700L) return fail("company account returned shares to itself");
            editShare(companyNr, share, 400L, UpdateBankAccountRequest.SHARE_DESTROY);
            if (own.getTotalBalance() != 400L || company.getTotalSharesIssued() != 7L)
                return fail("destroy: balance " + own.getTotalBalance() + ", issued " + company.getTotalSharesIssued());
            TransactionLogRecord destroyed = find(rows(companyNr), LedgerType.ADMIN_REMOVE, share);
            if (destroyed == null || destroyed.amount() != 300L || !Integer.valueOf(id).equals(destroyed.companyId()))
                return fail("destroy row " + destroyed);
            return pass("increase refused, return moves shares with TRANSFER rows, destroy lowers totalSharesIssued");
        } finally {
            if (share != null) manager.getBankAccount(accountNr).removeBank(share);
            if (company != null) cm.deleteCompany(company.getCompanyId());
            if (companyNr != ServerBankAccount.INVALID_ACCOUNT_NUMBER) manager.deleteBankAccount(companyNr);
        }
    }

    private void editShare(int account, ItemID share, long balance, int choice) {
        List<UpdateBankAccountRequest.InputData.BankData> data = new ArrayList<>();
        data.add(new UpdateBankAccountRequest.InputData.BankData(share, balance, true, false, false, false, choice));
        new UpdateBankAccountRequest().handleOnMasterServer(
                new UpdateBankAccountRequest.InputData(account, "", null, data, null), "", ADMIN).join();
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

        // Raw call (NO_LOG): BS-254, ServerBank writes one RAW_CHANGE row with source UNKNOWN.
        int before = rows(accountNr).size();
        handler.handleOnMasterServer(net.kroia.banksystem.banking.bank.AsyncBank.InputData.of(
                net.kroia.banksystem.banking.bank.AsyncBank.FunctionType.DepositAsync, accountNr, moneyId, 5L), "", null).join();
        List<TransactionLogRecord> afterRaw = rows(accountNr);
        TransactionLogRecord raw = afterRaw.isEmpty() ? null : afterRaw.get(0);
        if (afterRaw.size() != before + 1 || raw.type() != LedgerType.RAW_CHANGE || raw.amount() != 5L
                || raw.source() != LedgerActor.Source.UNKNOWN) return fail("raw deposit row " + raw);

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
        return pass("tagged call logged, raw call as RAW_CHANGE, disallowed code logged as 0");
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

    // ------------------------------------------------------------------ Task #126 (B6) transfer status

    private static final String FAKE_SLAVE = "b126-test-slave";

    @FunctionalInterface
    private interface Body { TestResult run() throws Exception; }

    private TestResult threw(Body body) {
        try {
            return body.run();
        } catch (Exception e) {
            return fail("threw " + e);
        }
    }

    /** A repeated transfer id from a slave applies once and gets the first reply; the status request reads the store. */
    private TestResult transferIdAppliesOnce() throws Exception {
        if (!ready()) return fail("live setup missing");
        ItemID moneyId = MoneyItem.getItemID();
        ItemID diamond = allow(Items.DIAMOND.getDefaultInstance());
        IServerBank money = manager.getBankAccount(accountNr).getOrCreateBank(moneyId);
        if (money == null) return fail("no money bank");
        TransferStatusRequest status = new TransferStatusRequest();
        boolean wasTrusted = manager.isSlaveServerTrusted(FAKE_SLAVE);
        manager.trustSlaveServer(FAKE_SLAVE);
        try {
            TransferJournal.Ref ref = TransferJournal.ref("b126_test");
            AsyncBank.InputData in = TransactionLedger.tagged(LedgerType.UPLOAD_DEPOSIT, PLAYER, null, ref,
                    () -> AsyncBank.InputData.of(AsyncBank.FunctionType.DepositAsync, accountNr, moneyId, 11L));
            long before = money.getBalance();
            AsyncBank.OutputData first = AsyncBank.Request.instance.handleOnMasterServer(in, FAKE_SLAVE, null).join();
            AsyncBank.OutputData again = AsyncBank.Request.instance.handleOnMasterServer(in, FAKE_SLAVE, null).join();
            if (first != again) return fail("repeat must get the stored reply");
            if (money.getBalance() != before + 11L) return fail("money +" + (money.getBalance() - before) + ", expected +11 once");
            if (ledger.getByRef(ref.transferId()).get(10, TimeUnit.SECONDS).size() != 1) return fail("repeat wrote a ledger row");

            String tid = TransferJournal.newTransferId();
            Map<ItemID, Long> items = new HashMap<>(Map.of(diamond, 3L));
            DepositItemsInBankRequest dep = new DepositItemsInBankRequest();
            var depIn = new DepositItemsInBankRequest.InputData(accountNr, PLAYER, items, LedgerType.CONVERTER_DEPOSIT.code(), tid);
            if (!dep.handleOnMasterServer(depIn, FAKE_SLAVE, null).join().items().isEmpty()) return fail("deposit refused");
            long diamonds = manager.getBankAccount(accountNr).getBank(diamond).getBalance();
            if (!dep.handleOnMasterServer(depIn, FAKE_SLAVE, null).join().items().isEmpty()) return fail("repeat reply differs");
            if (manager.getBankAccount(accountNr).getBank(diamond).getBalance() != diamonds) return fail("repeat deposited twice");
            if (ledger.getByRef(tid).get(10, TimeUnit.SECONDS).size() != 1) return fail("repeat wrote an item row");

            if (status.handleOnMasterServer(new TransferStatusRequest.InputData(0, tid), FAKE_SLAVE, null).join().state() != TransferStatusRequest.APPLIED)
                return fail("status of an applied transfer");
            if (status.handleOnMasterServer(new TransferStatusRequest.InputData(0, tid), "b126-other-slave", null).join().state() != TransferStatusRequest.NOT_APPLIED)
                return fail("another slave must not see the transfer");
            if (status.handleOnMasterServer(new TransferStatusRequest.InputData(0, tid), FAKE_SLAVE, PLAYER).join().state() != TransferStatusRequest.UNKNOWN)
                return fail("a player must get UNKNOWN");

            manager.untrustSlaveServer(FAKE_SLAVE);
            String refused = TransferJournal.newTransferId();
            Map<ItemID, Long> back = dep.handleOnMasterServer(new DepositItemsInBankRequest.InputData(accountNr, PLAYER, items,
                    LedgerType.CONVERTER_DEPOSIT.code(), refused), FAKE_SLAVE, null).join().items();
            if (!items.equals(back)) return fail("untrusted deposit returned " + back);
            if (TransferStatusRequest.RESULTS.get(FAKE_SLAVE, refused) != null) return fail("refused transfer stored");
            if (status.handleOnMasterServer(new TransferStatusRequest.InputData(0, refused), FAKE_SLAVE, null).join().state() != TransferStatusRequest.NOT_APPLIED)
                return fail("status of a refused transfer");
            return pass("deposit + item deposit apply once per (slave, transfer_id); status APPLIED / NOT_APPLIED / UNKNOWN");
        } finally {
            if (wasTrusted) manager.trustSlaveServer(FAKE_SLAVE);
            else manager.untrustSlaveServer(FAKE_SLAVE);
        }
    }

    /** A DROP whose outcome stays unknown is parked: no refund row, a TRANSFER_PARKED flag with the transfer id. */
    private TestResult dropUnknownParks() throws Exception {
        if (!ready()) return fail("live setup missing");
        var flags = BankSystemModBackend.getInstances_forTesting().SUSPICION_FLAG_MANAGER;
        if (flags == null) return fail("no flag manager");
        ItemID note = ItemID.of(BankSystemItems.MONEY5.get().getDefaultInstance());
        long worth = ((MoneyItem) BankSystemItems.MONEY5.get()).worth();
        String tid = TransferJournal.newTransferId();
        new WithdrawMoneyPacket(new HashMap<>(), accountNr).onDropResult(FAKE_SLAVE, PLAYER, accountNr, Map.of(note, 2L), tid, null,
                new java.util.concurrent.CompletionException(new RequestOutcomeUnknownException("test")));
        // by ref: the suite-wide account already has a refund row from withdraw_money_writes_withdraw_and_refund
        if (find(ledger.getByRef(tid).get(10, TimeUnit.SECONDS), LedgerType.WITHDRAW_REFUND) != null) return fail("unknown outcome was refunded");
        for (SuspicionFlagRecord f : flags.getRecent(accountNr, 0, 0).get(10, TimeUnit.SECONDS))
            if (f.flagType() == SuspicionChecks.TRANSFER_PARKED)
                return tid.equals(f.ref()) && f.amount() == 2 * worth ? pass("parked: flag 6, ref = transfer id, amount " + f.amount())
                        : fail("flag " + f);
        return fail("no TRANSFER_PARKED flag");
    }

    /**
     * The DROP to a trusted but unconnected slave is refused synchronously (ARRS failures are on), so the
     * refund runs inside the call. The slave must be trusted: untrusted slaves may not withdraw at all.
     */
    private TestResult dropNotSentRefundsWithSwitchOn() {
        if (!ready()) return fail("live setup missing");
        IServerBank money = manager.getBankAccount(accountNr).getOrCreateBank(MoneyItem.getItemID());
        if (money == null) return fail("no money bank");
        money.deposit(10_000L);
        long before = money.getBalance();
        HashMap<ItemID, Long> request = new HashMap<>();
        request.put(ItemID.of(BankSystemItems.MONEY5.get().getDefaultInstance()), 2L);
        String slave = "b126-no-such-slave";
        boolean wasTrusted = manager.isSlaveServerTrusted(slave);
        long lastId = rows(accountNr).stream().mapToLong(TransactionLogRecord::id).max().orElse(0L);
        manager.trustSlaveServer(slave);
        try {
            new WithdrawMoneyPacket(request, accountNr).handle(PLAYER, slave);
        } finally {
            if (!wasTrusted) manager.untrustSlaveServer(slave);
        }
        // only this call's rows: older WITHDRAW / WITHDRAW_REFUND rows of the suite account must not satisfy it
        List<TransactionLogRecord> rows = rows(accountNr).stream().filter(r -> r.id() > lastId).toList();
        TransactionLogRecord w = find(rows, LedgerType.WITHDRAW), refund = find(rows, LedgerType.WITHDRAW_REFUND);
        if (w == null || refund == null) return fail("expected WITHDRAW + WITHDRAW_REFUND, got " + rows);
        if (w.ref() == null || !w.ref().equals(refund.ref())) return fail("refs " + w.ref() + " / " + refund.ref());
        return assertEquals("balance restored", before, money.getBalance());
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

    private static TransactionLogRecord find(List<TransactionLogRecord> rows, LedgerType type, ItemID item) {
        for (TransactionLogRecord r : rows) if (r.type() == type && r.itemId() == item.getShort()) return r;
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
                     var pf = db.getConnection().prepareStatement("DELETE FROM SuspicionFlag WHERE account_number = ?"); // Task #126 park test
                     var pr = db.getConnection().prepareStatement("DELETE FROM TransactionLog WHERE ref = ?")) {
                    for (int a : accounts) { ps.setInt(1, a); ps.executeUpdate(); pf.setInt(1, a); pf.executeUpdate(); }
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
