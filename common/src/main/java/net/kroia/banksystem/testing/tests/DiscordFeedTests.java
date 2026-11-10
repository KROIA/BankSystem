package net.kroia.banksystem.testing.tests;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.architectury.platform.Platform;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IBankManager;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.api.event.SuspicionFlaggedEvent;
import net.kroia.banksystem.api.event.TransactionLoggedEvent;
import net.kroia.banksystem.api.ledger.LedgerActor;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.banking.company.Company;
import net.kroia.banksystem.banking.company.CompanyManager;
import net.kroia.banksystem.banking.ledger.TransactionLedger;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.minecraft.compat.BankSystemDiscord;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.testing.LiveStateGuard;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.setting.Setting;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Task #119 (v2.2.0) — Discord feed ({@link BankSystemDiscord}) through its {@code sink} seam: type
 * mapping and values, recipient-leg filter, dividend coalescing, the live switch, suspicion posts, the
 * registered config file, and nothing posted from a slave.
 * <p>
 * <b>Server type:</b> BOTH; master cases skip on a slave and the slave case skips on the master.
 * Synthetic accounts and company only; ledger / flag rows written here are deleted afterwards.
 */
public class DiscordFeedTests extends TestSuite {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000d119");
    private static final String MARKER = "discord-feed-test-marker";

    private ISyncServerBankManager manager;
    private LiveStateGuard stateGuard;
    private int companyAccount, holderAccount;
    private @Nullable Company company;
    private short money;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.MOD_SETTINGS;
    }

    @Override
    public void registerTests() {
        addTest("deposit_posts_one_embed", () -> master(this::deposit));
        addTest("transfer_posts_out_and_in", () -> master(this::transfer));
        addTest("payout_posts_recipient_leg_only", () -> master(this::payout));
        addTest("dividend_rows_coalesced", () -> master(this::dividend));
        addTest("switch_off_posts_nothing", () -> master(this::switchOff));
        addTest("suspicion_flag_posts", () -> master(this::suspicion));
        addTest("config_file_lists_all_types", () -> master(this::configFile));
        addTest("slave_posts_nothing", this::slave);
    }

    @Override
    public void setup() {
        if (isSlave()) return;
        IBankManager bm = BankSystemMod.getAPI().getServerBankManager();
        if (bm == null || bm.getSync() == null || CompanyManager.get() == null) return;
        manager = bm.getSync();
        stateGuard = LiveStateGuard.capture(manager); // createCompany registers and allows a share ItemID
        money = MoneyItem.getItemID().getShort();
        IServerBankAccount a = manager.createBankAccount("DiscordTestCompany");
        IServerBankAccount b = manager.createBankAccount("DiscordTestHolder");
        if (a != null) companyAccount = a.getAccountNumber();
        if (b != null) holderAccount = b.getAccountNumber();
        if (a != null) company = CompanyManager.get().createCompany("DiscordTest" + companyAccount, companyAccount, null, 100L).company;
    }

    @Override
    public void teardown() {
        try {
            if (manager == null) return;
            if (company != null && CompanyManager.get() != null) CompanyManager.get().deleteCompany(company.getCompanyId());
            List<Integer> accounts = new ArrayList<>();
            for (int nr : new int[]{companyAccount, holderAccount}) {
                if (nr == 0) continue;
                accounts.add(nr);
                manager.deleteBankAccount(nr);
            }
            AdminAuditTests.deleteRows(accounts);
        } finally {
            company = null;
            companyAccount = holderAccount = 0;
            LiveStateGuard.restore(stateGuard);
            stateGuard = null;
        }
    }

    private TestResult deposit() {
        ItemID item = MoneyItem.getItemID();
        var posts = capture(true, () -> TransactionLedger.write(TransactionLogRecord.of(holderAccount, LedgerType.DEPOSIT,
                money, 1234L, LedgerActor.player(PLAYER, "DiscordTestPlayer"), null, null, null, MARKER, null)));
        if (posts.size() != 1 || !"bank-deposit".equals(posts.get(0).getKey())) return fail("posts " + posts);
        Map<String, String> v = posts.get(0).getValue();
        if (!"DiscordTestPlayer".equals(v.get("player")) || !PLAYER.toString().equals(v.get("player_uuid"))
                || !v.get("account").startsWith("#" + holderAccount + " ") || !v.get("item").endsWith("(" + item.getName() + ")")
                || !"12.34".equals(v.get("amount")) || !"DEPOSIT".equals(v.get("kind")) || !v.get("time").matches("<t:\\d+:f>")
                || !v.get("other_account").isEmpty() || !v.get("holders").isEmpty())
            return fail("values " + v);
        return pass("1 bank-deposit with player, account, item, amount 12.34, kind and time");
    }

    private TestResult transfer() {
        var posts = capture(true, () -> TransactionLedger.writeAll(List.of(
                row(holderAccount, LedgerType.TRANSFER, -500L, companyAccount, null),
                row(companyAccount, LedgerType.TRANSFER, 500L, holderAccount, null))));
        List<String> types = posts.stream().map(Map.Entry::getKey).sorted().toList();
        if (!types.equals(List.of("bank-transfer-in", "bank-transfer-out"))) return fail("types " + types);
        return pass("transfer pair -> 1 bank-transfer-out + 1 bank-transfer-in");
    }

    private TestResult payout() {
        int cid = company.getCompanyId();
        var posts = capture(true, () -> TransactionLedger.writeAll(List.of(
                row(companyAccount, LedgerType.PAYOUT, 700L, holderAccount, cid),
                row(holderAccount, LedgerType.PAYOUT, 700L, companyAccount, cid))));
        if (posts.size() != 1 || !"bank-payout".equals(posts.get(0).getKey())) return fail("posts " + posts);
        Map<String, String> v = posts.get(0).getValue();
        if (!v.get("account").startsWith("#" + holderAccount + " ") || !company.getName().equals(v.get("company"))
                || !"7".equals(v.get("amount")))
            return fail("values " + v);
        return pass("payout pair -> 1 post on the recipient account");
    }

    private TestResult dividend() {
        int cid = company.getCompanyId();
        List<TransactionLogRecord> rows = new ArrayList<>();
        for (long amount : new long[]{100L, 200L, 300L}) {
            rows.add(row(companyAccount, LedgerType.DIVIDEND, amount, holderAccount, cid));
            rows.add(row(holderAccount, LedgerType.DIVIDEND, amount, companyAccount, cid));
        }
        var posts = capture(true, () -> TransactionLedger.writeAll(rows));
        if (posts.size() != 1 || !"bank-dividend".equals(posts.get(0).getKey())) return fail("posts " + posts);
        Map<String, String> v = posts.get(0).getValue();
        if (!"3".equals(v.get("holders")) || !"6".equals(v.get("amount"))) return fail("values " + v);
        return pass("3 recipient rows -> 1 bank-dividend, holders 3, amount = sum");
    }

    private TestResult switchOff() {
        var posts = capture(false, () -> TransactionLedger.write(row(holderAccount, LedgerType.DEPOSIT, 100L, null, null)));
        return posts.isEmpty() ? pass("DISCORD_ENABLED=false -> nothing posted") : fail("posts " + posts);
    }

    private TestResult suspicion() {
        var posts = capture(true, () -> BankSystemMod.getAPI().getEvents().getSuspicionFlaggedEvent().notifyListeners(
                new SuspicionFlaggedEvent(holderAccount, 9999, "FLAG_9999", 2, 123L, "ref-x", MARKER, System.currentTimeMillis())));
        if (posts.size() != 1 || !"bank-suspicion".equals(posts.get(0).getKey())) return fail("posts " + posts);
        Map<String, String> v = posts.get(0).getValue();
        if (!"FLAG_9999".equals(v.get("flag")) || !"2".equals(v.get("severity")) || !"1.23".equals(v.get("amount"))
                || !"ref-x".equals(v.get("ref")) || !v.get("account").startsWith("#" + holderAccount + " "))
            return fail("values " + v);
        return pass("flag event -> 1 bank-suspicion");
    }

    private TestResult configFile() {
        Path file = Platform.getConfigFolder().resolve("modutilities").resolve("discord").resolve(BankSystemMod.MOD_ID + ".json");
        try {
            JsonObject types = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("types");
            if (BankSystemDiscord.TYPES.size() != 10) return fail("TYPES " + BankSystemDiscord.TYPES);
            for (String t : BankSystemDiscord.TYPES)
                if (types == null || !types.has(t)) return fail("missing type " + t + " in " + file);
            return pass("banksystem.json lists the 10 types");
        } catch (Exception e) {
            return fail("cannot read " + file + ": " + e);
        }
    }

    private TestResult slave() {
        if (!isSlave()) return skip("slave only");
        var posts = capture(true, () -> {
            TransactionLedger.write(row(1, LedgerType.DEPOSIT, 100L, null, null));
            BankSystemDiscord.onRow(new TransactionLoggedEvent(1, LedgerType.DEPOSIT, (short) 0, 100L, LedgerActor.system(),
                    null, null, System.currentTimeMillis(), MARKER));
        });
        return posts.isEmpty() ? pass("slave: ledger write and direct onRow post nothing") : fail("posts " + posts);
    }

    // ------------------------------------------------------------------ helpers

    private TestResult master(Supplier<TestResult> body) {
        if (isSlave()) return skip("master only");
        if (manager == null || company == null || holderAccount == 0) return fail("live setup missing");
        return body.get();
    }

    private static boolean isSlave() {
        return BankSystemModBackend.getInstances_forTesting().isSlaveServer;
    }

    private TransactionLogRecord row(int account, LedgerType type, long amount, @Nullable Integer other, @Nullable Integer companyId) {
        return new TransactionLogRecord(TransactionLogRecord.UNSAVED_ID, account, null, type, money, amount, other, companyId,
                System.currentTimeMillis(), MARKER);
    }

    /**
     * Runs {@code body} and a flush on the server thread with the feed switched {@code on}; returns the posts
     * of this suite (note or details = MARKER). Restores the switch and the sink.
     */
    private List<Map.Entry<String, Map<String, String>>> capture(boolean on, Runnable body) {
        Setting<Boolean> setting = BankSystemModBackend.getInstances_forTesting().SERVER_SETTINGS.INTEGRATIONS.DISCORD_ENABLED;
        boolean before = setting.get();
        BiConsumer<String, Map<String, String>> previous = BankSystemDiscord.sink;
        List<Map.Entry<String, Map<String, String>>> posts = new ArrayList<>();
        BankSystemDiscord.sink = (type, v) -> {
            if (MARKER.equals(v.get("note")) || MARKER.equals(v.get("details")))
                synchronized (posts) { posts.add(Map.entry(type, v)); }
        };
        setting.set(on);
        try {
            onServer(body);
            onServer(BankSystemDiscord::flush);
        } finally {
            setting.set(before);
            BankSystemDiscord.sink = previous;
        }
        synchronized (posts) { return new ArrayList<>(posts); }
    }

    /** Ledger events hop to the server thread; running the body there makes the posts synchronous. */
    private void onServer(Runnable r) {
        MinecraftServer s = getServer();
        if (s == null) r.run();
        else await(s.submit(r), 5000);
    }
}
