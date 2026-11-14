package net.kroia.banksystem.testing.tests;

import com.google.gson.JsonArray;
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
import net.kroia.banksystem.banking.ledger.SuspicionChecks;
import net.kroia.banksystem.banking.ledger.TransactionLedger;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.minecraft.compat.BankSystemDiscord;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.testing.LiveStateGuard;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.discord.DiscordWebhooks;
import net.kroia.modutilities.setting.Setting;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Task #119 (v2.2.0) — Discord feed ({@link BankSystemDiscord}) through its {@code sink} seam: type
 * mapping and values, recipient-leg filter, dividend coalescing, the live switch, suspicion posts, the
 * registered config file, the shipped default templates, and nothing posted from a slave.
 * <p>
 * <b>Server type:</b> BOTH; master cases skip on a slave and the slave case skips on the master.
 * Synthetic accounts and company only; ledger / flag rows written here are deleted afterwards.
 * <p>
 * {@code live_webhook_posts} runs only when env var {@code MC_DISCORD_TEST_WEBHOOK_FILE} is set (path to a text file
 * holding the webhook URL, so the URL never lands in the matrix launch JSON): it posts one embed per type to that
 * webhook through the real {@link DiscordWebhooks} pipeline, to check the look in a Discord channel.
 */
public class DiscordFeedTests extends TestSuite {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000d119");
    private static final String MARKER = "discord-feed-test-marker";
    private static final String LIVE = "BankSystem live webhook test";
    private static final UUID LIVE_PLAYER = UUID.fromString("00000000-0000-0000-0000-0000001d119e");

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
        addTest("amount_text_format", this::amountText);
        addTest("deposit_posts_one_embed", () -> master(this::deposit));
        addTest("transfer_posts_out_and_in", () -> master(this::transfer));
        addTest("payout_posts_recipient_leg_only", () -> master(this::payout));
        addTest("dividend_rows_coalesced", () -> master(this::dividend));
        addTest("switch_off_posts_nothing", () -> master(this::switchOff));
        addTest("suspicion_flag_posts", () -> master(this::suspicion));
        addTest("config_file_lists_all_types", () -> master(this::configFile));
        addTest("default_templates_hide_account", this::defaultTemplates);
        addTest("template_placeholders_are_posted_keys", () -> master(this::templateKeys)); // BS-202
        addTest("slave_posts_nothing", this::slave);
        addTest("live_webhook_posts", () -> master(this::live));
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
                || !"12.34".equals(v.get("amount")) || !"12.34".equals(v.get("amountText")) || !"DEPOSIT".equals(v.get("kind")) || !v.get("time").matches("<t:\\d+:f>")
                || !v.get("other_account").isEmpty() || !v.get("holders").isEmpty())
            return fail("values " + v);
        return pass("1 bank-deposit with player, account, item, amount 12.34, kind and time");
    }

    /** amountText: money keeps 2 decimals with ' grouping, other items drop ".00", 0 stays empty like amount. */
    private TestResult amountText() {
        String[][] cases = {
                {BankSystemDiscord.amountText(150_000L, true), "1'500.00"},
                {BankSystemDiscord.amountText(42_050L, true), "420.50"},
                {BankSystemDiscord.amountText(300L, false), "3"},
                {BankSystemDiscord.amountText(150_000L, false), "1'500"},
                {BankSystemDiscord.amountText(250L, false), "2.50"},
                {BankSystemDiscord.amountText(0L, true), ""}};
        for (String[] c : cases)
            if (!c[1].equals(c[0])) return fail("amountText '" + c[0] + "', expected '" + c[1] + "'");
        return pass("money 1'500.00 / 420.50, items 3 / 1'500 / 2.50, 0 empty");
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
                || !"7".equals(v.get("amount")) || !"7.00".equals(v.get("amountText")))
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
        if (!"3".equals(v.get("holders")) || !"6".equals(v.get("amount")) || !"6.00".equals(v.get("amountText")))
            return fail("values " + v);
        return pass("3 recipient rows -> 1 bank-dividend, holders 3, amount = sum");
    }

    private TestResult switchOff() {
        var posts = capture(false, () -> TransactionLedger.write(row(holderAccount, LedgerType.DEPOSIT, 100L, null, null)));
        return posts.isEmpty() ? pass("DISCORD_ENABLED=false -> nothing posted") : fail("posts " + posts);
    }

    private TestResult suspicion() {
        var posts = capture(true, () -> BankSystemMod.getAPI().getEvents().getSuspicionFlaggedEvent().notifyListeners(
                new SuspicionFlaggedEvent(holderAccount, 9999, "FLAG_9999", 2, 42_050L, "ref-x", MARKER, System.currentTimeMillis())));
        if (posts.size() != 1 || !"bank-suspicion".equals(posts.get(0).getKey())) return fail("posts " + posts);
        Map<String, String> v = posts.get(0).getValue();
        if (!"FLAG_9999".equals(v.get("flag")) || !"2".equals(v.get("severity")) || !"420.5".equals(v.get("amount"))
                || !"420.50".equals(v.get("amountText"))
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

    /**
     * The shipped bank-* templates of every language show the player name but no UUID, account or counterparty
     * (an admin adds them via {@code fields} in banksystem.json); bank-suspicion keeps its account field.
     */
    private TestResult defaultTemplates() {
        for (String lang : List.of("en_us", "de_de", "de_ch", "zh_cn")) {
            try (var in = DiscordFeedTests.class.getResourceAsStream("/assets/banksystem/discord/" + lang + ".json")) {
                if (in == null) return fail("missing template file " + lang + ".json");
                JsonObject root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                for (String type : BankSystemDiscord.TYPES) {
                    String values = root.getAsJsonObject(type).getAsJsonArray("fields").toString();
                    if (values.contains("{amount}") || !values.contains("{amountText}")) {
                        return fail(lang + " " + type + " should show {amountText}, not {amount}: " + values);
                    } else if (type.equals("bank-suspicion")) {
                        if (!values.contains("{account}")) return fail(lang + " bank-suspicion lacks {account}");
                    } else if (values.contains("{account}") || values.contains("{player_uuid}") || values.contains("{other_account}")) {
                        return fail(lang + " " + type + " shows account, UUID or counterparty: " + values);
                    } else if (!values.contains("{player}")) {
                        return fail(lang + " " + type + " lacks {player}");
                    }
                }
            } catch (Exception e) {
                return fail("cannot read " + lang + ".json: " + e);
            }
        }
        return pass("4 languages: {amountText}; bank-* show {player} without account/UUID/counterparty; bank-suspicion keeps {account}");
    }

    /**
     * BS-202: one real post per type through the sink; every {placeholder} of that type's en_us template must be a
     * key of the posted values, else the embed shows the raw placeholder.
     */
    private TestResult templateKeys() {
        int cid = company.getCompanyId();
        LedgerActor player = LedgerActor.player(PLAYER, "DiscordTestPlayer");
        List<TransactionLogRecord> rows = List.of(
                markerRow(holderAccount, LedgerType.DEPOSIT, 150_000L, player, null, null),
                markerRow(holderAccount, LedgerType.WITHDRAW, -42_050L, player, null, null),
                markerRow(holderAccount, LedgerType.TRANSFER, -75_000L, player, companyAccount, null),
                markerRow(companyAccount, LedgerType.TRANSFER, 75_000L, player, holderAccount, null),
                markerRow(holderAccount, LedgerType.PAYOUT, 120_000L, player, companyAccount, cid),
                markerRow(holderAccount, LedgerType.DIVIDEND, 3_375L, player, companyAccount, cid),
                markerRow(companyAccount, LedgerType.SHARE_STAMP, -10_000L, player, null, cid),
                markerRow(holderAccount, LedgerType.ADMIN_ADD, 50_000L, LedgerActor.admin(PLAYER, "DiscordTestPlayer"), null, null),
                markerRow(holderAccount, LedgerType.STARTING_BALANCE, 10_000L, LedgerActor.system(), null, null));
        int flag = SuspicionChecks.LARGE_NOTE_WITHDRAWAL;
        var posts = capture(true, () -> {
            TransactionLedger.writeAll(rows);
            BankSystemMod.getAPI().getEvents().getSuspicionFlaggedEvent().notifyListeners(new SuspicionFlaggedEvent(
                    holderAccount, flag, SuspicionChecks.typeName(flag), 1, 42_050L, null, MARKER, System.currentTimeMillis()));
        });
        Map<String, Map<String, String>> byType = new java.util.HashMap<>();
        for (var e : posts) byType.putIfAbsent(e.getKey(), e.getValue());
        JsonObject root;
        try (var in = DiscordFeedTests.class.getResourceAsStream("/assets/banksystem/discord/en_us.json")) {
            if (in == null) return fail("missing template file en_us.json");
            root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return fail("cannot read en_us.json: " + e);
        }
        java.util.regex.Pattern placeholder = java.util.regex.Pattern.compile("\\{([A-Za-z0-9_]+)}");
        for (String type : BankSystemDiscord.TYPES) {
            Map<String, String> v = byType.get(type);
            if (v == null) return fail("no post for " + type + "; posted " + byType.keySet());
            JsonObject template = root.getAsJsonObject(type);
            if (template == null) return fail("en_us.json has no template for " + type);
            java.util.regex.Matcher m = placeholder.matcher(template.toString());
            while (m.find())
                if (!v.containsKey(m.group(1)))
                    return fail(type + ": template uses {" + m.group(1) + "}, the sink posts only " + v.keySet());
        }
        return pass(BankSystemDiscord.TYPES.size() + " types: every en_us placeholder is a posted key");
    }

    private TransactionLogRecord markerRow(int account, LedgerType type, long amount, LedgerActor actor,
                                           @Nullable Integer other, @Nullable Integer companyId) {
        return TransactionLogRecord.of(account, type, money, amount, actor, other, companyId, null, MARKER, null);
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

    /**
     * Posts one embed per type to the webhook URL in the file named by env {@code MC_DISCORD_TEST_WEBHOOK_FILE} through the real
     * {@link DiscordWebhooks} config and sender. Swaps both config files for the test and restores them byte for byte.
     * The URL is a secret: it never goes into a result, log or exception text.
     */
    private TestResult live() {
        String file = System.getenv("MC_DISCORD_TEST_WEBHOOK_FILE");
        if (file == null || file.isBlank()) return skip("env MC_DISCORD_TEST_WEBHOOK_FILE not set");
        String url;
        try {
            url = Files.readString(Path.of(file.strip()), StandardCharsets.UTF_8).strip();
        } catch (IOException | RuntimeException e) { // no e text: keep file content out of the result
            return fail("env MC_DISCORD_TEST_WEBHOOK_FILE: cannot read " + file + " (" + e.getClass().getSimpleName() + ")");
        }
        if (url.isEmpty()) return fail("env MC_DISCORD_TEST_WEBHOOK_FILE: " + file + " is blank");
        Path dir = Platform.getConfigFolder().resolve("modutilities").resolve("discord");
        Path hooksFile = dir.resolve("webhooks.json"), routesFile = dir.resolve(BankSystemMod.MOD_ID + ".json");
        byte[] oldHooks, oldRoutes;
        try {
            oldHooks = Files.exists(hooksFile) ? Files.readAllBytes(hooksFile) : null;
            oldRoutes = Files.exists(routesFile) ? Files.readAllBytes(routesFile) : null;
        } catch (IOException e) {
            return fail("cannot back up the Discord config: " + e);
        }
        Setting<Boolean> setting = BankSystemModBackend.getInstances_forTesting().SERVER_SETTINGS.INTEGRATIONS.DISCORD_ENABLED;
        boolean before = setting.get();
        BiConsumer<String, Map<String, String>> previous = BankSystemDiscord.sink;
        TestResult result;
        boolean restored;
        try {
            result = livePosts(url, dir, hooksFile, routesFile, setting);
        } catch (IOException e) {
            result = fail("cannot write the Discord config: " + e);
        } finally {
            setting.set(before);
            BankSystemDiscord.sink = previous;
            restored = put(hooksFile, oldHooks) & put(routesFile, oldRoutes); // both, even if the first fails
            DiscordWebhooks.reload();
        }
        return restored ? result : fail("could not restore webhooks.json / banksystem.json in " + dir + "; fix by hand");
    }

    /** Writes {@code content} to {@code file}, or deletes it if null (it did not exist). @return false on an I/O error */
    private static boolean put(Path file, @Nullable byte[] content) {
        try {
            if (content == null) Files.deleteIfExists(file);
            else Files.write(file, content);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Body of {@link #live()}: writes the test config, posts 10 types, waits for the sender. Caller restores. */
    private TestResult livePosts(String url, Path dir, Path hooksFile, Path routesFile, Setting<Boolean> setting) throws IOException {
        JsonObject hooks = new JsonObject();
        hooks.addProperty("live-test", url.trim());
        JsonObject hooksRoot = new JsonObject();
        hooksRoot.add("webhooks", hooks);
        JsonObject types = new JsonObject();
        for (String t : BankSystemDiscord.TYPES) {
            JsonObject route = new JsonObject();
            JsonArray names = new JsonArray();
            names.add("live-test");
            route.addProperty("enabled", true);
            route.add("webhooks", names);
            route.addProperty("minAmount", 0);
            types.add(t, route);
        }
        JsonObject routesRoot = new JsonObject();
        routesRoot.add("types", types);
        Files.createDirectories(dir);
        Files.writeString(hooksFile, hooksRoot.toString(), StandardCharsets.UTF_8);
        Files.writeString(routesFile, routesRoot.toString(), StandardCharsets.UTF_8);
        DiscordWebhooks.reload();
        if (!DiscordWebhooks.isActive()) return fail("DiscordWebhooks inactive after reload (webhook URL rejected?)");

        // other suites write ledger rows concurrently: only rows of this test reach Discord
        Map<String, Integer> forwarded = new ConcurrentHashMap<>();
        BankSystemDiscord.sink = (type, v) -> {
            if (!LIVE.equals(v.get("note")) && !LIVE.equals(v.get("details"))) return;
            forwarded.merge(type, 1, Integer::sum);
            DiscordWebhooks.post(BankSystemMod.MOD_ID, type, v);
        };
        setting.set(true);
        int cid = company.getCompanyId();
        LedgerActor player = LedgerActor.player(LIVE_PLAYER, "LiveTestPlayer");
        List<TransactionLogRecord> rows = List.of(
                liveRow(holderAccount, LedgerType.DEPOSIT, 150_000L, player, null, null),
                liveRow(holderAccount, LedgerType.WITHDRAW, -42_050L, player, null, null),
                liveRow(holderAccount, LedgerType.TRANSFER, -75_000L, player, companyAccount, null),
                liveRow(companyAccount, LedgerType.TRANSFER, 75_000L, player, holderAccount, null),
                liveRow(holderAccount, LedgerType.PAYOUT, 120_000L, player, companyAccount, cid),
                liveRow(holderAccount, LedgerType.DIVIDEND, 3_375L, player, companyAccount, cid),
                liveRow(companyAccount, LedgerType.SHARE_STAMP, -10_000L, player, null, cid),
                liveRow(holderAccount, LedgerType.ADMIN_ADD, 50_000L, LedgerActor.admin(LIVE_PLAYER, "LiveTestPlayer"), null, null),
                liveRow(holderAccount, LedgerType.STARTING_BALANCE, 10_000L, LedgerActor.system(), null, null));
        int flag = SuspicionChecks.LARGE_NOTE_WITHDRAWAL;
        onServer(() -> {
            TransactionLedger.writeAll(rows);
            BankSystemMod.getAPI().getEvents().getSuspicionFlaggedEvent().notifyListeners(new SuspicionFlaggedEvent(
                    holderAccount, flag, SuspicionChecks.typeName(flag), 1, 42_050L, null, LIVE, System.currentTimeMillis()));
            BankSystemDiscord.flush(); // posts the coalesced dividend
        });
        for (String t : BankSystemDiscord.TYPES)
            if (forwarded.getOrDefault(t, 0) != 1) return fail("forwarded per type " + forwarded);
        // ponytail: no public drain hook on DiscordWebhooks; fixed wait for the sender (batching, rate limits).
        // Queued items keep their URL, so a slower send still goes out after the caller's restore.
        pump(10_000);
        return pass("posted 10 types to the live webhook; check the channel");
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

    private TransactionLogRecord liveRow(int account, LedgerType type, long amount, LedgerActor actor,
                                      @Nullable Integer other, @Nullable Integer companyId) {
        return TransactionLogRecord.of(account, type, money, amount, actor, other, companyId, null, LIVE, null);
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
