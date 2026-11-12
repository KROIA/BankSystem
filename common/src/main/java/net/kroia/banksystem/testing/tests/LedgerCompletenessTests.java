package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.ISyncServerBank;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.api.currency.ExternalAccountRef;
import net.kroia.banksystem.api.ledger.LedgerActor;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.bank.AsyncBank;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import net.kroia.banksystem.banking.bankmanager.ServerBankManager;
import net.kroia.banksystem.banking.binding.BankAccountBindings;
import net.kroia.banksystem.banking.ledger.TransactionLedger;
import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.data.table.TransactionLogManager;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.multi_server.DepositItemsInBankRequest;
import net.kroia.banksystem.networking.multi_server.WithdrawItemsFromBankRequest;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.testing.LiveStateGuard;
import net.kroia.banksystem.testing.StubCurrencyProvider;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * BS-254 — every balance change writes a ledger row. For each path that used to skip the ledger (raw calls of other
 * mods, raw slave requests, slot create/remove, external bind/unbind) the change of the slot's total balance
 * (free + locked) must equal the signed sum of the rows written for it. The guard tests check that callers that log
 * themselves get no extra RAW_CHANGE row.
 * <p>
 * <b>Server type:</b> MASTER_ONLY (BANK_MANAGER category). Accounts, users, bindings and ledger rows are removed in
 * teardown.
 */
public class LedgerCompletenessTests extends TestSuite {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000b254");
    private static final String PLAYER_NAME = "LedgerCompletePlayer";
    private static final String STUB_KEY_A = "bs254_stub_a";
    private static final String STUB_KEY_B = "bs254_stub_b";
    /** Row types whose amount is signed (the only ones these paths may write). */
    private static final Set<LedgerType> SIGNED = EnumSet.of(LedgerType.RAW_CHANGE, LedgerType.TRANSFER,
            LedgerType.EXTERNAL_BIND, LedgerType.EXTERNAL_UNBIND);

    private ServerBankManager manager;
    private TransactionLogManager ledger;
    private LiveStateGuard stateGuard;
    private final List<Integer> accounts = new ArrayList<>();
    private ItemID money, diamond, emerald, slot;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.BANK_MANAGER;
    }

    @Override
    public void registerTests() {
        addTest("raw_bank_calls_write_raw_change_rows", this::rawBankCalls);
        addTest("raw_slave_requests_write_raw_change_rows", this::rawSlaveRequests);
        addTest("slot_create_and_remove_write_rows", this::slotCreateRemove);
        addTest("external_bind_and_unbind_write_rows", this::externalBindUnbind);
        addTest("logging_callers_get_no_raw_change_row", this::noDoubleLog);
    }

    @Override
    public void setup() {
        IBankManager bm = BankSystemMod.getAPI().getServerBankManager();
        if (bm == null || !(bm.getSync() instanceof ServerBankManager sbm)) return;
        manager = sbm;
        ledger = BankSystemModBackend.getTransactionLogManager();
        stateGuard = LiveStateGuard.capture(manager);
        StubCurrencyProvider.reset();
        if (!manager.userExists(PLAYER)) manager.addUser(new User(PLAYER, PLAYER_NAME));
        money = MoneyItem.getItemID();
        diamond = allow(Items.DIAMOND);
        emerald = allow(Items.EMERALD);
        slot = allow(Items.COPPER_INGOT);
    }

    @Override
    public void teardown() {
        try {
            if (manager == null) return;
            BankAccountBindings bindings = BankAccountBindings.get();
            for (int nr : accounts) {
                if (bindings != null) bindings.removeAllForAccount(nr);
                manager.deleteBankAccount(nr);
            }
            StubCurrencyProvider.teardown();
            manager.removeUser(PLAYER);
            deleteLedgerRows();
        } finally {
            accounts.clear();
            LiveStateGuard.restore(stateGuard);
            stateGuard = null;
        }
    }

    // ------------------------------------------------------------------ path 1: other mods' raw calls

    private TestResult rawBankCalls() {
        if (manager == null) return fail("no master ServerBankManager");
        ServerBankAccount a = newAccount("BS254Raw"), b = newAccount("BS254RawOther");
        if (a == null || b == null) return fail("account setup");
        ISyncServerBank bank = a.getOrCreateBank(money), other = b.getOrCreateBank(money);
        if (bank == null || other == null) return fail("no money bank");
        int na = a.getAccountNumber(), nb = b.getAccountNumber();
        String e;
        if ((e = balanced("deposit", na, money, () -> bank.deposit(1_000L))) != null) return fail(e);
        if ((e = balanced("withdraw", na, money, () -> bank.withdraw(300L))) != null) return fail(e);
        if ((e = balanced("setBalance", na, money, () -> bank.setBalance(5_000L))) != null) return fail(e);
        if ((e = noRows("lock", na, () -> bank.lockAmount(600L))) != null) return fail(e);
        if ((e = balanced("withdrawLocked", na, money, () -> bank.withdrawLocked(100L))) != null) return fail(e);
        if ((e = balanced("withdrawLockedPrefered", na, money, () -> bank.withdrawLockedPrefered(700L))) != null) return fail(e);
        if ((e = noRows("unlockAll", na, bank::unlockAll)) != null) return fail(e);
        if ((e = noRows("lock again", na, () -> bank.lockAmount(400L))) != null) return fail(e);
        long lastB = lastRowId(nb);
        if ((e = balanced("transferFromLocked", na, money, () -> bank.transferFromLocked(150L, other))) != null) return fail(e);
        if ((e = balanced("transferFromLockedPrefered", na, money, () -> bank.transferFromLockedPrefered(300L, other))) != null) return fail(e);
        if ((e = balanced("transfer", na, money, () -> bank.transfer(200L, other))) != null) return fail(e);
        long inB = sumSince(nb, money, lastB);
        if (inB != 650L) return fail("receiving account rows sum " + inB + ", expected 650");
        if (other.getTotalBalance() != 650L) return fail("receiving balance " + other.getTotalBalance());
        return pass("deposit/withdraw/setBalance/withdrawLocked*/transferFromLocked* balance; lock/unlock write nothing");
    }

    // ------------------------------------------------------------------ path 2: raw slave requests

    private TestResult rawSlaveRequests() {
        if (manager == null) return fail("no master ServerBankManager");
        ServerBankAccount a = newAccount("BS254Slave");
        if (a == null || a.getOrCreateBank(money) == null) return fail("account setup");
        int nr = a.getAccountNumber();
        AsyncBank.Request handler = AsyncBank.Request.instance;
        String e;
        if ((e = balanced("raw DepositAsync", nr, money, () -> handler.handleOnMasterServer(
                AsyncBank.InputData.of(AsyncBank.FunctionType.DepositAsync, nr, money, 40L), "", null).join())) != null)
            return fail(e);
        if ((e = balanced("SetBalanceAsync", nr, money, () -> handler.handleOnMasterServer(
                AsyncBank.InputData.of(AsyncBank.FunctionType.SetBalanceAsync, nr, money, 900L), "", null).join())) != null)
            return fail(e);
        // A code on setBalance is ignored: the change is still one RAW_CHANGE row, not an UNKNOWN_LEGACY row.
        AsyncBank.InputData taggedSet = TransactionLedger.tagged(LedgerType.ADMIN_SET, PLAYER, null,
                () -> AsyncBank.InputData.of(AsyncBank.FunctionType.SetBalanceAsync, nr, money, 1_200L));
        long last = lastRowId(nr);
        if ((e = balanced("tagged SetBalanceAsync", nr, money,
                () -> handler.handleOnMasterServer(taggedSet, "", null).join())) != null) return fail(e);
        List<TransactionLogRecord> rows = rowsSince(nr, last);
        if (rows.size() != 1 || rows.get(0).type() != LedgerType.RAW_CHANGE || rows.get(0).amount() != 300L
                || rows.get(0).source() != LedgerActor.Source.PLAYER) return fail("tagged setBalance rows " + rows);
        if ((e = noRows("LockAmountAsync", nr, () -> handler.handleOnMasterServer(
                AsyncBank.InputData.of(AsyncBank.FunctionType.LockAmountAsync, nr, money, 200L), "", null).join())) != null)
            return fail(e);
        if ((e = noRows("UnlockAmountAsync", nr, () -> handler.handleOnMasterServer(
                AsyncBank.InputData.of(AsyncBank.FunctionType.UnlockAmountAsync, nr, money, 100L), "", null).join())) != null)
            return fail(e);
        if ((e = balanced("SetBalanceAsync with locked", nr, money, () -> handler.handleOnMasterServer(
                AsyncBank.InputData.of(AsyncBank.FunctionType.SetBalanceAsync, nr, money, 10L), "", null).join())) != null)
            return fail(e);
        TransactionLogRecord raw = rowsSince(nr, 0).get(0);
        if (raw.source() != LedgerActor.Source.UNKNOWN || raw.balanceAfter() == null
                || raw.balanceAfter() != a.getBank(money).getTotalBalance()) return fail("raw row actor / balance_after " + raw);
        return pass("raw deposit and setBalance requests balance (actor UNKNOWN, balance_after set); lock/unlock write nothing");
    }

    // ------------------------------------------------------------------ path 3: slot create / remove

    private TestResult slotCreateRemove() {
        if (manager == null) return fail("no master ServerBankManager");
        ServerBankAccount a = newAccount("BS254Slots");
        if (a == null) return fail("account setup");
        int nr = a.getAccountNumber();
        String e;
        if ((e = balanced("createBank", nr, diamond, () -> a.createBank(diamond, 250L))) != null) return fail(e);
        if (a.getBank(diamond) == null || a.getBank(diamond).getTotalBalance() != 250L) return fail("slot not created with 250");
        a.getBank(diamond).lockAmount(50L);
        if ((e = balanced("removeBank with locked", nr, diamond, () -> a.removeBank(diamond))) != null) return fail(e);
        if ((e = balanced("createBank again", nr, diamond, () -> a.createBank(diamond, 70L))) != null) return fail(e);
        if ((e = balanced("createBank second item", nr, emerald, () -> a.createBank(emerald, 30L))) != null) return fail(e);
        long last = lastRowId(nr);
        a.removeAllBanks();
        if (sumSince(nr, diamond, last) != -70L || sumSince(nr, emerald, last) != -30L)
            return fail("removeAllBanks rows " + rowsSince(nr, last));
        return pass("start balance and removal (free + locked) each write a RAW_CHANGE row");
    }

    // ------------------------------------------------------------------ path 4: external bind / unbind

    private TestResult externalBindUnbind() {
        if (manager == null) return fail("no master ServerBankManager");
        ServerBankAccount a = newAccount("BS254Bind");
        if (a == null) return fail("account setup");
        int nr = a.getAccountNumber();
        a.createBank(slot, 0L);
        ISyncServerBank bank = a.getBank(slot);
        if (bank == null) return fail("no slot");
        String e;
        if ((e = balanced("fund", nr, slot, () -> bank.setBalance(200L))) != null) return fail(e);

        StubCurrencyProvider.create(STUB_KEY_A, true, 500L);
        ExternalAccountRef refA = new ExternalAccountRef(StubCurrencyProvider.PROVIDER_ID, STUB_KEY_A, "BS254-A", true);
        BankStatus[] status = new BankStatus[1];
        long last = lastRowId(nr);
        if ((e = balanced("bind", nr, slot, () -> status[0] = manager.bindExternalAccount(nr, slot, refA))) != null) return fail(e);
        if (status[0] != BankStatus.SUCCESS) return fail("bind returned " + status[0]);
        if (sumSince(nr, slot, last, LedgerType.EXTERNAL_BIND) != 500L) return fail("bind rows " + rowsSince(nr, last));
        if ((e = balanced("bound deposit", nr, slot, () -> bank.deposit(50L))) != null) return fail(e);
        if ((e = noRows("bound lock", nr, () -> bank.lockAmount(30L))) != null) return fail(e);
        last = lastRowId(nr);
        if ((e = balanced("unbind, funds left outside", nr, slot,
                () -> status[0] = manager.unbindExternalAccount(nr, slot, false))) != null) return fail(e);
        if (status[0] != BankStatus.SUCCESS) return fail("unbind returned " + status[0]);
        if (sumSince(nr, slot, last, LedgerType.EXTERNAL_UNBIND) >= 0) return fail("unbind rows " + rowsSince(nr, last));

        StubCurrencyProvider.create(STUB_KEY_B, true, 300L);
        ExternalAccountRef refB = new ExternalAccountRef(StubCurrencyProvider.PROVIDER_ID, STUB_KEY_B, "BS254-B", true);
        if ((e = balanced("bind B", nr, slot, () -> status[0] = manager.bindExternalAccount(nr, slot, refB))) != null) return fail(e);
        if (status[0] != BankStatus.SUCCESS) return fail("bind B returned " + status[0]);
        if ((e = balanced("unbind B, funds kept", nr, slot,
                () -> status[0] = manager.unbindExternalAccount(nr, slot, true))) != null) return fail(e);
        if (status[0] != BankStatus.SUCCESS) return fail("unbind B returned " + status[0]);
        return pass("bind / unbind write EXTERNAL_BIND / EXTERNAL_UNBIND rows for the value that joins or leaves");
    }

    // ------------------------------------------------------------------ guards against double rows

    private TestResult noDoubleLog() {
        if (manager == null) return fail("no master ServerBankManager");
        ServerBankAccount a = newAccount("BS254Guard");
        if (a == null) return fail("account setup");
        a.addUser(manager.getUserByUUID(PLAYER), BankPermission.getAllPermissions());
        ISyncServerBank bank = a.getOrCreateBank(money);
        if (bank == null) return fail("no money bank");
        int nr = a.getAccountNumber();

        long before = bank.getTotalBalance();
        String e = noRows("callerLogs", nr, () -> BankSystemMod.getAPI().getLedger().callerLogs(() -> bank.deposit(25L)));
        if (e != null) return fail(e);
        if (bank.getTotalBalance() != before + 25L) return fail("callerLogs deposit not applied");

        long last = lastRowId(nr);
        AsyncBank.InputData tagged = TransactionLedger.tagged(LedgerType.UPLOAD_DEPOSIT, PLAYER, null,
                () -> AsyncBank.InputData.of(AsyncBank.FunctionType.DepositAsync, nr, money, 7L));
        AsyncBank.Request.instance.handleOnMasterServer(tagged, "", null).join();
        if ((e = onlyRow("tagged deposit", nr, last, LedgerType.UPLOAD_DEPOSIT)) != null) return fail(e);

        last = lastRowId(nr);
        Map<ItemID, Long> items = new HashMap<>(Map.of(diamond, 4L));
        Map<ItemID, Long> notDeposited = new DepositItemsInBankRequest().handleOnMasterServer(
                new DepositItemsInBankRequest.InputData(nr, PLAYER, items, LedgerType.DEPOSIT.code()), "", null).join().items();
        if (!notDeposited.isEmpty()) return fail("deposit refused " + notDeposited);
        if ((e = onlyRow("item deposit", nr, last, LedgerType.DEPOSIT)) != null) return fail(e);

        last = lastRowId(nr);
        Map<ItemID, Long> withdrawn = new WithdrawItemsFromBankRequest().handleOnMasterServer(
                new WithdrawItemsFromBankRequest.InputData(nr, PLAYER, items, LedgerType.DOWNLOAD_WITHDRAW.code()), "", null)
                .join().items();
        if (withdrawn.getOrDefault(diamond, 0L) != 4L) return fail("withdraw " + withdrawn);
        if ((e = onlyRow("item withdraw", nr, last, LedgerType.DOWNLOAD_WITHDRAW)) != null) return fail(e);
        return pass("callerLogs, tagged and item-request paths write their own row only");
    }

    // ------------------------------------------------------------------ helpers

    /** @return null when the slot's total moved by exactly the signed sum of the rows {@code op} wrote, else why not */
    private String balanced(String step, int nr, ItemID item, Runnable op) {
        long before = total(nr, item), last = lastRowId(nr);
        op.run();
        long moved = total(nr, item) - before, sum = 0;
        for (TransactionLogRecord r : rowsSince(nr, last)) {
            if (r.itemId() != item.getShort()) continue;
            if (!SIGNED.contains(r.type())) return step + ": unexpected row " + r;
            sum += r.amount();
        }
        return moved == sum ? null : step + ": total moved " + moved + ", rows sum " + sum + " " + rowsSince(nr, last);
    }

    private String noRows(String step, int nr, Runnable op) {
        long last = lastRowId(nr);
        op.run();
        List<TransactionLogRecord> rows = rowsSince(nr, last);
        return rows.isEmpty() ? null : step + " wrote " + rows;
    }

    private String onlyRow(String step, int nr, long last, LedgerType type) {
        List<TransactionLogRecord> rows = rowsSince(nr, last);
        return rows.size() == 1 && rows.get(0).type() == type ? null : step + ": expected one " + type + " row, got " + rows;
    }

    private long total(int nr, ItemID item) {
        IServerBankAccount account = manager.getBankAccount(nr);
        ISyncServerBank bank = account == null ? null : account.getBank(item);
        return bank == null ? 0L : bank.getTotalBalance();
    }

    private long sumSince(int nr, ItemID item, long last) {
        return sumSince(nr, item, last, null);
    }

    private long sumSince(int nr, ItemID item, long last, LedgerType type) {
        long sum = 0;
        for (TransactionLogRecord r : rowsSince(nr, last))
            if (r.itemId() == item.getShort() && (type == null || r.type() == type)) sum += r.amount();
        return sum;
    }

    private long lastRowId(int nr) {
        List<TransactionLogRecord> rows = rowsSince(nr, 0);
        return rows.isEmpty() ? 0L : rows.get(0).id();
    }

    /** Rows of {@code nr} with an id above {@code last}, newest first; the DB worker orders the read after queued writes. */
    private List<TransactionLogRecord> rowsSince(int nr, long last) {
        try {
            return ledger.getByAccount(nr, 0).get(10, TimeUnit.SECONDS).stream().filter(r -> r.id() > last).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private ServerBankAccount newAccount(String name) {
        IServerBankAccount existing = manager.getBankAccountByName(name);
        if (existing != null) manager.deleteBankAccount(existing.getAccountNumber());
        if (!(manager.createBankAccount(name) instanceof ServerBankAccount account)) return null;
        accounts.add(account.getAccountNumber());
        return account;
    }

    private ItemID allow(Item item) {
        ItemID id = ItemIDManager.registerItemStackServerSide_direct(item.getDefaultInstance());
        if (!manager.isItemIDAllowed(id)) manager.allowItemID(id);
        return id;
    }

    private void deleteLedgerRows() {
        DatabaseManager db = BankSystemModBackend.getDatabaseManager();
        if (db == null) return;
        List<Integer> nrs = new ArrayList<>(accounts);
        try {
            db.getDatabaseThread().submit(() -> {
                try (var ps = db.getConnection().prepareStatement("DELETE FROM TransactionLog WHERE account_number = ?")) {
                    for (int a : nrs) { ps.setInt(1, a); ps.executeUpdate(); }
                    db.commitTransaction();
                } catch (java.sql.SQLException ignored) {
                    // cleanup is best-effort
                }
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // cleanup is best-effort
        }
    }
}
