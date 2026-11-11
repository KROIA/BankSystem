package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bank.IServerBank;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.bank.AsyncBank;
import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.multi_server.TransferStatusRequest;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.testing.LiveStateGuard;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Task #162: an account-to-account AsyncBank transfer from a slave carries a transfer_id; a repeat applies once and the
 * status request answers APPLIED. MASTER_ONLY: calls the master handler with a fake trusted slave id.
 */
public class AsyncTransferIdTests extends TestSuite {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000f162");
    private static final String FAKE_SLAVE = "t162-test-slave";

    private ISyncServerBankManager manager;
    private LiveStateGuard stateGuard;
    private final List<Integer> accounts = new ArrayList<>();

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.BANK_MANAGER;
    }

    @Override
    public void registerTests() {
        addTest("account_transfer_applies_once", this::appliesOnce);
    }

    @Override
    public void setup() {
        IBankManager bm = BankSystemMod.getAPI().getServerBankManager();
        if (bm == null || bm.getSync() == null) return;
        manager = bm.getSync();
        stateGuard = LiveStateGuard.capture(manager);
        if (!manager.userExists(PLAYER)) manager.addUser(new User(PLAYER, "T162Player"));
        for (String name : List.of("T162From", "T162To")) {
            IServerBankAccount a = manager.createBankAccount(name);
            if (a == null) continue;
            a.addUser(manager.getUserByUUID(PLAYER), BankPermission.getAllPermissions());
            accounts.add(a.getAccountNumber());
        }
    }

    @Override
    public void teardown() {
        try {
            if (manager == null) return;
            for (int nr : accounts) manager.deleteBankAccount(nr);
            IServerBankAccount personal = manager.getPersonalBankAccount(PLAYER);
            if (personal != null) accounts.add(personal.getAccountNumber());
            manager.removeUser(PLAYER);
            deleteLedgerRows(new ArrayList<>(accounts));
        } finally {
            accounts.clear();
            LiveStateGuard.restore(stateGuard);
            stateGuard = null;
        }
    }

    private TestResult appliesOnce() {
        if (manager == null || accounts.size() != 2) return fail("live setup missing");
        ItemID moneyId = MoneyItem.getItemID();
        IServerBank from = manager.getBankAccount(accounts.get(0)).getOrCreateBank(moneyId);
        IServerBank to = manager.getBankAccount(accounts.get(1)).getOrCreateBank(moneyId);
        if (from == null || to == null) return fail("no money banks");
        from.deposit(100L);
        boolean wasTrusted = manager.isSlaveServerTrusted(FAKE_SLAVE);
        manager.trustSlaveServer(FAKE_SLAVE);
        try {
            AsyncBank.InputData in = AsyncBank.InputData.of(AsyncBank.FunctionType.TransferAsync, accounts.get(0), moneyId,
                    new AsyncBank.ParamGroup_long_int(30L, accounts.get(1)));
            String tid = ((AsyncBank.BankIdentifyAndDataPacket<?>) in.decodeParams()).transferId();
            if (tid == null) return fail("a transfer must carry a transfer_id");
            AsyncBank.OutputData first = AsyncBank.Request.instance.handleOnMasterServer(in, FAKE_SLAVE, null).join();
            AsyncBank.OutputData again = AsyncBank.Request.instance.handleOnMasterServer(in, FAKE_SLAVE, null).join();
            if (first != again) return fail("repeat must get the stored reply");
            if (from.getBalance() != 70L || to.getBalance() != 30L)
                return fail("balances " + from.getBalance() + "/" + to.getBalance() + ", expected 70/30 (moved once)");
            byte state = new TransferStatusRequest().handleOnMasterServer(new TransferStatusRequest.InputData(0, tid), FAKE_SLAVE, null).join().state();
            return assertEquals("status of the applied transfer", TransferStatusRequest.APPLIED, state);
        } finally {
            if (!wasTrusted) manager.untrustSlaveServer(FAKE_SLAVE);
        }
    }

    private static void deleteLedgerRows(List<Integer> accountNrs) {
        DatabaseManager db = BankSystemModBackend.getDatabaseManager();
        if (db == null) return;
        try {
            db.getDatabaseThread().submit(() -> {
                try (var ps = db.getConnection().prepareStatement("DELETE FROM TransactionLog WHERE account_number = ?")) {
                    for (int a : accountNrs) { ps.setInt(1, a); ps.executeUpdate(); }
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
