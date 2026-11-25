package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.api.ledger.LedgerActor;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.banking.company.Company;
import net.kroia.banksystem.banking.company.CompanyManager;
import net.kroia.banksystem.banking.company.PayoutExecutor;
import net.kroia.banksystem.banking.ledger.TransactionLedger;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.testing.LiveStateGuard;
import net.kroia.banksystem.util.ItemID;
import net.kroia.testkit.TestCategory;
import net.kroia.testkit.TestResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BS-288 / SM-359 — the two example messages of the docs picture {@code shared/discord-feed.png}, posted to the
 * live webhook (env {@code MC_DISCORD_TEST_WEBHOOK_FILE}) through the real feed and the shipped en_us templates:
 * a player deposits 256 iron ingots at a Bank Terminal, a company pays a dividend to 3 holders.
 * Invented names, no note. On demand, master or single server:
 * {@code --only banksystem/bs_docs_shots/DocsDiscordPostsSuite}.
 */
public class DocsDiscordPostsSuite extends DiscordFeedTests {

    private static final UUID MIA = UUID.fromString("00000000-0000-0000-0000-0000000d0c5a");
    private static final String PLAYER = "MinerMia", COMPANY = "Oak & Iron Co.";

    private @Nullable LiveStateGuard stateGuard;
    private @Nullable ISyncServerBankManager manager;
    private final List<Integer> accounts = new ArrayList<>(); // company, MinerMia, 2 more holders
    private @Nullable Company company;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.DOCS_SHOTS;
    }

    @Override
    public boolean onDemand() {
        return true;
    }

    @Override
    public void registerTests() {
        addTest("post_feed_examples", this::post);
    }

    @Override
    public void setup() {
        if (BankSystemModBackend.getInstances_forTesting().isSlaveServer) return;
        IBankManager bm = BankSystemMod.getAPI().getServerBankManager();
        if (bm == null || bm.getSync() == null || CompanyManager.get() == null) return;
        manager = bm.getSync();
        stateGuard = LiveStateGuard.capture(manager); // createCompany and the iron ItemID change live state
        for (String name : List.of(COMPANY, PLAYER, "Quenby", "Brindle")) {
            IServerBankAccount a = manager.createBankAccount(name);
            if (a != null) accounts.add(a.getAccountNumber());
        }
        if (accounts.size() == 4)
            company = CompanyManager.get().createCompany(COMPANY, accounts.get(0), null, 1000L).company;
    }

    @Override
    public void teardown() {
        try {
            if (company != null && CompanyManager.get() != null) CompanyManager.get().deleteCompany(company.getCompanyId());
            if (manager != null) for (int nr : accounts) manager.deleteBankAccount(nr);
            AdminAuditTests.deleteRows(accounts);
        } finally {
            company = null;
            manager = null;
            accounts.clear();
            LiveStateGuard.restore(stateGuard);
            stateGuard = null;
        }
    }

    private TestResult post() {
        if (BankSystemModBackend.getInstances_forTesting().isSlaveServer) return skip("master only");
        if (company == null) return fail("setup missing (accounts " + accounts + ")");
        short iron = ItemID.getOrRegisterFromItemStackServerSide_direct(new ItemStack(Items.IRON_INGOT)).getShort();
        short money = MoneyItem.getItemID().getShort();
        int companyAccount = accounts.get(0), cid = company.getCompanyId();
        LedgerActor payer = PayoutExecutor.companyActor(company);
        String ref = UUID.randomUUID().toString();
        long[] paid = {40_000L, 22_500L, 12_500L}; // 400.00 + 225.00 + 125.00 = 750.00
        List<TransactionLogRecord> dividend = new ArrayList<>();
        for (int i = 0; i < paid.length; i++) { // both legs, like DividendPayer; the feed posts the holder legs
            dividend.add(TransactionLogRecord.of(companyAccount, LedgerType.DIVIDEND, money, -paid[i], payer,
                    accounts.get(i + 1), cid, ref, null, null));
            dividend.add(TransactionLogRecord.of(accounts.get(i + 1), LedgerType.DIVIDEND, money, paid[i], payer,
                    companyAccount, cid, ref, null, null));
        }
        return postLive(List.of("bank-deposit", "bank-dividend"),
                v -> PLAYER.equals(v.get("player")) || COMPANY.equals(v.get("company")),
                () -> {
                    TransactionLedger.write(TransactionLogRecord.of(accounts.get(1), LedgerType.DEPOSIT, iron, 25_600L,
                            LedgerActor.player(MIA, PLAYER), null, null, null, null, null));
                    TransactionLedger.writeAll(dividend);
                });
    }
}
