package net.kroia.banksystem.testing.tests;

import dev.architectury.platform.Platform;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bank.IAsyncBank;
import net.kroia.banksystem.api.bankaccount.IAsyncBankAccount;
import net.kroia.banksystem.api.bankmanager.IAsyncBankManager;
import net.kroia.banksystem.api.ledger.LedgerActor;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.banking.company.Company;
import net.kroia.banksystem.banking.company.CompanyManager;
import net.kroia.banksystem.banking.company.DividendEvent;
import net.kroia.banksystem.banking.company.PayoutExecutor;
import net.kroia.banksystem.banking.company.PayoutSchedule;
import net.kroia.banksystem.banking.converter.ConverterCacheManager;
import net.kroia.banksystem.banking.ledger.SuspicionChecks;
import net.kroia.banksystem.banking.ledger.TransactionLedger;
import net.kroia.banksystem.client.cache.ShareVisualCache;
import net.kroia.banksystem.data.filter.EqualityFilter;
import net.kroia.banksystem.data.table.record.BalanceHistoryRecord;
import net.kroia.banksystem.data.table.record.PayoutHistoryRecord;
import net.kroia.banksystem.data.table.record.SuspicionFlagRecord;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.minecraft.entity.custom.BankDownloadBlockEntity;
import net.kroia.banksystem.minecraft.entity.custom.BankSystemDisplayBlockEntity;
import net.kroia.banksystem.minecraft.entity.custom.BankTerminalBlockEntity;
import net.kroia.banksystem.minecraft.entity.custom.BankUploadBlockEntity;
import net.kroia.banksystem.minecraft.entity.custom.ShareStamperBlockEntity;
import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem;
import net.kroia.banksystem.networking.entity.UpdateBankUploadBlockEntityPacket;
import net.kroia.banksystem.networking.general.BalanceHistoryRequest;
import net.kroia.banksystem.screen.custom.ATMScreen;
import net.kroia.banksystem.screen.custom.AuditScreen;
import net.kroia.banksystem.screen.custom.BalanceHistoryScreen;
import net.kroia.banksystem.screen.custom.BankAccountManagementScreen;
import net.kroia.banksystem.screen.custom.BankDownloadScreen;
import net.kroia.banksystem.screen.custom.BankSystemSettingScreen;
import net.kroia.banksystem.screen.custom.BankTerminalScreen;
import net.kroia.banksystem.screen.custom.BankUploadScreen;
import net.kroia.banksystem.screen.custom.CompanyManagementScreen;
import net.kroia.banksystem.screen.custom.DisplayConfigScreen;
import net.kroia.banksystem.screen.custom.ModSettingsScreen;
import net.kroia.banksystem.screen.custom.PayDividendScreen;
import net.kroia.banksystem.screen.custom.PayoutEditScreen;
import net.kroia.banksystem.screen.custom.ShareStamperScreen;
import net.kroia.banksystem.screen.custom.ShareVisualEditorScreen;
import net.kroia.banksystem.screen.custom.atm.MoneyElement;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.BankCraftingRecipes;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.banksystem.util.MoneyFormat;
import net.kroia.modutilities.gui.client.ClientCompat;
import net.kroia.modutilities.gui.elements.CheckBox;
import net.kroia.modutilities.gui.elements.DropDownMenu;
import net.kroia.modutilities.gui.elements.ItemView;
import net.kroia.modutilities.gui.elements.TextBox;
import net.kroia.testkit.TestCategory;
import net.kroia.testkit.TestRegistry;
import net.kroia.testkit.TestResult;
import net.kroia.testkit.TestSuite;
import net.kroia.testkit.autotest.AutoTest;
import net.kroia.testkit.gui.Gui;
import net.kroia.testkit.gui.GuiNode;
import net.kroia.testkit.gui.Layout;
import net.kroia.testkit.gui.Screenshots;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.awt.Rectangle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * BS-288: the plain BankSystem screens of the documentation site, taken as scripted screenshots
 * ({@link Screenshots#take}) on the game client of an sp cell, plus the inventory hover picture and the Bank Terminal
 * GIF of BS-291. File names and the state each screen shows: Docs/planning/images.md, section 2 "BankSystem (24)"
 * and section 1.
 * <p>
 * The first test builds an invented bank world on the integrated server: the players Tavrik (the client), Quenby
 * and Brindle, Tavrik's personal account "Savings", the shared account "Farm Co-op", the companies "Acme Mining"
 * (two workers, a daily wage, shares) and "Brindle Timber", a week of balance history, ledger rows, payouts and two
 * suspicion flags, written straight into the databases. The block screens use blocks at a work spot next to the
 * player. The other tests each open one screen and take its picture; a failed shot does not stop the others.
 * <p>
 * On demand: the shots take minutes and resize the window, so the suite runs only when named with
 * {@code --only banksystem/bs_docs_shots}. Start the client with {@code --client-name Tavrik}: the player name shows
 * on several screens. Needs the integrated server as BankSystem master (sp cell). The in-world pictures are taken by
 * {@link DocsWorldShotsSuite} on the same world.
 */
public class DocsScreenshotSuite extends TestSuite {

    static final String TOPIC = "banksystem/bs_docs_shots";
    static final long WAIT_MS = 15_000;
    /** Ticks to wait after opening a screen, so its requests are answered and charts are filled. */
    static final int DATA_TICKS = 60;
    static final long HOUR = 3_600_000L;
    /** User decision 2026-11-24: Full HD at GUI scale 2 for every picture. */
    static final Screenshots.Scene SCENE = new Screenshots.Scene(1920, 1080, 2, 6_000, true);

    /** Invented names only (user rule): never a real player name, never "Dev". */
    static final String SELF = "Tavrik", QUENBY = "Quenby", BRINDLE = "Brindle";
    static final String SAVINGS = "Savings", FARM = "Farm Co-op";
    static final String COMPANY = "Acme Mining", COMPANY_2 = "Brindle Timber";
    static final int ALL = BankPermission.DEPOSIT.getValue() | BankPermission.WITHDRAW.getValue() | BankPermission.MANAGE.getValue();

    /** Work spot of the block screens: the player stands at (40.5, -60, 40.5) facing east, the blocks two blocks ahead. */
    static final String SPOT = "tp @s 40.5 -60 40.5 -90 0";
    static final BlockPos UPLOAD = new BlockPos(42, -60, 39), DOWNLOAD = new BlockPos(42, -60, 40),
            TERMINAL = new BlockPos(42, -60, 41), STAMPER = new BlockPos(42, -60, 42);

    /** Items with a bank in the docs world. */
    private static final List<Item> ITEMS = List.of(Items.IRON_INGOT, Items.DIAMOND, Items.GOLD_INGOT, Items.EMERALD,
            Items.COAL, Items.WHEAT, Items.STICK, Items.CARROT, Items.POTATO, Items.OAK_LOG);

    /** The world built by {@link #test_fixture()}, shared with the world suite in the same client; null until built. */
    static @Nullable Fixture fixture;
    /** Why the fixture could not be built (the shots skip with it), or null. */
    static @Nullable String fixtureProblem = "fixture not built yet";

    record Fixture(UUID self, UUID quenby, UUID brindle, int savings, int farm, int quenbyAccount, int brindleAccount,
                   int companyId, int companyAccount, int company2Id, int company2Account, ItemID money, Map<Item, ItemID> items,
                   ItemID share, ItemID share2) {
        ItemID item(Item item) {
            return items.get(item);
        }
    }

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.DOCS_SHOTS;
    }

    @Override
    public Side side() {
        return Side.CLIENT;
    }

    @Override
    public boolean onDemand() {
        return true;
    }

    @Override
    public void registerTests() {
        addTest("fixture", this::test_fixture);
        addTest("account_management", () -> shot("banksystem/account-management.png", this::accountManagement));
        addTest("account_permissions", () -> shot("banksystem/account-permissions.png", SCENE, this::accountPermissions,
                DocsScreenshotSuite::visibleControls));
        addTest("atm_withdraw", () -> shot("banksystem/atm-withdraw.png", this::atmWithdraw));
        addTest("atm_convert", () -> {
            TestResult r = shot("banksystem/atm-convert.png", this::atmConvert);
            kit(); // closing the ATM drops the converter cache as notes, which the player picks up
            return r;
        });
        addTest("bank_terminal", () -> shot("banksystem/bank-terminal.png", this::bankTerminal));
        addTest("bank_terminal_crafting", () -> shot("banksystem/bank-terminal-crafting.png", this::bankTerminalCrafting));
        addTest("balance_history", () -> shot("banksystem/balance-history.png",
                () -> balanceHistory(() -> new BalanceHistoryScreen(null, fixture.savings()))));
        addTest("upload_block", () -> shot("banksystem/upload-block.png", this::uploadBlock));
        addTest("download_block", () -> shot("banksystem/download-block.png", this::downloadBlock));
        addTest("company_overview", () -> shot("banksystem/company-overview.png", () -> companyTab(null)));
        addTest("company_workers", () -> shot("banksystem/company-workers.png", () -> companyTab("Workers")));
        addTest("company_statistics", () -> shot("banksystem/company-statistics.png", () -> companyTab("Statistics")));
        addTest("company_market_tab", this::test_companyMarketTab);
        addTest("pay_dividend", () -> shot("banksystem/pay-dividend.png", this::payDividend));
        addTest("payout_edit", () -> shot("banksystem/payout-edit.png", this::payoutEdit));
        addTest("display_config", () -> shot("banksystem/display-config.png", () -> {
            open(() -> new DisplayConfigScreen(BlockPos.ZERO, BankSystemDisplayBlockEntity.DisplayType.BALANCE_HISTORY,
                    fixture.savings()), DisplayConfigScreen.class);
            return null;
        }));
        addTest("account_admin_mode", () -> shot("banksystem/account-admin-mode.png", () -> {
            Gui.onClient(() -> BankAccountManagementScreen.openScreen(fixture.savings(), true));
            Gui.waitForScreen(BankAccountManagementScreen.class, 100);
            Gui.waitTicks(DATA_TICKS);
            return null;
        }));
        addTest("bank_settings", () -> shot("banksystem/bank-settings.png", this::bankSettings));
        addTest("audit_screen", () -> shot("banksystem/audit-screen.png", this::auditScreen));
        addTest("balance_history_flags", () -> shot("banksystem/balance-history-flags.png",
                () -> balanceHistory(() -> new BalanceHistoryScreen(null, fixture.savings(), true))));
        addTest("server_history", () -> shot("banksystem/server-history.png", this::serverHistory));
        addTest("mod_settings", () -> shot("banksystem/mod-settings.png", this::modSettings));
        addTest("share_stamper", () -> shot("banksystem/share-stamper.png", this::shareStamper));
        addTest("share_visuals", () -> shot("banksystem/share-visuals.png", this::shareVisuals));
        addTest("share_items", this::test_shareItems);
        addTest("bank_terminal_deposit", this::test_terminalGif);
    }

    // ---------------------------------------------------------------- fixture

    /** Builds the invented bank world (see the class Javadoc). Every shot skips if this fails. */
    TestResult test_fixture() {
        if (fixture != null) return pass("built by an earlier docs suite in this client");
        String include = System.getProperty(TestRegistry.INCLUDE_PROPERTY);
        if (include == null || include.isBlank() || TestRegistry.isExcluded(TestRegistry.topicKey(this), include, null))
            return skip(fixtureProblem = "on demand: --only " + TOPIC);
        if (AutoTest.isMasterDown()) return skip(fixtureProblem = "master down");
        MinecraftServer server = server();
        if (server == null) return skip(fixtureProblem = "needs the integrated server (sp cell)");
        if (BankSystemModBackend.getInstances_forTesting().isSlaveServer) return skip(fixtureProblem = "not the BankSystem master");
        String name = Gui.onClient(() -> Minecraft.getInstance().player.getGameProfile().getName());
        if (!SELF.equals(name))
            return fail(fixtureProblem = "start the client with --client-name " + SELF + " (the name shows on the screens), not " + name);
        List<String> notes = new ArrayList<>();
        try {
            // final window size first, so every screen is laid out once at the size it is shot at
            Gui.onClient(() -> Minecraft.getInstance().getWindow().setWindowed(SCENE.windowWidth(), SCENE.windowHeight()));
            Gui.setGuiScale(2);
            Gui.waitTicks(10);
            for (String command : List.of("gamerule sendCommandFeedback false", "gamerule doMobSpawning false",
                    "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "time set " + SCENE.dayTime(),
                    "weather clear", SPOT)) {
                Gui.sendCommand(command);
                Gui.waitTicks(2);
            }

            UUID self = Gui.onClient(() -> Minecraft.getInstance().player.getUUID());
            UUID quenby = offlineUuid(QUENBY), brindle = offlineUuid(BRINDLE);
            IAsyncBankManager bank = BankSystemMod.getAPI().getServerBankManager().getAsync();
            if (!Boolean.TRUE.equals(await(bank.setBanksystemAdminModeAsync(self, true), WAIT_MS)))
                notes.add("admin flag call returned false (already set?)");
            bank.addUserAsync(quenby, QUENBY);
            bank.addUserAsync(brindle, BRINDLE);
            User selfUser = await(bank.getUserByUUIDAsync(self), WAIT_MS);
            User quenbyUser = await(bank.getUserByUUIDAsync(quenby), WAIT_MS);
            User brindleUser = await(bank.getUserByUUIDAsync(brindle), WAIT_MS);
            if (selfUser == null || quenbyUser == null || brindleUser == null)
                return fail(fixtureProblem = "users missing: " + selfUser + " " + quenbyUser + " " + brindleUser);

            // item ids, allowed for banking
            ItemID money = MoneyItem.getItemID();
            Map<Item, ItemID> items = new LinkedHashMap<>();
            for (Item item : ITEMS) {
                ItemID id = await(ItemIDManager.registerItemStackServerSide(new ItemStack(item)), WAIT_MS);
                await(bank.allowItemIDAsync(id), WAIT_MS);
                items.put(item, id);
            }

            // accounts
            int savings = await(bank.createPersonalBankAccountGetAccountNrAsync(self), WAIT_MS);
            int quenbyAccount = await(bank.createPersonalBankAccountGetAccountNrAsync(quenby), WAIT_MS);
            int brindleAccount = await(bank.createPersonalBankAccountGetAccountNrAsync(brindle), WAIT_MS);
            if (savings <= 0 || quenbyAccount <= 0 || brindleAccount <= 0)
                return fail(fixtureProblem = "personal accounts missing: " + savings + " " + quenbyAccount + " " + brindleAccount);
            IAsyncBankAccount savingsRef = account(bank, savings);
            savingsRef.setAccountNameAsync(SAVINGS);
            IAsyncBankAccount farmRef = await(bank.getBankAccountByNameAsync(FARM), WAIT_MS);
            if (farmRef == null) farmRef = account(bank, await(bank.createBankAccountGetAccountNrAsync(FARM), WAIT_MS));
            int farm = farmRef.getAccountNumberAsync();
            farmRef.addUserAsync(selfUser, ALL);
            farmRef.addUserAsync(quenbyUser, BankPermission.DEPOSIT.getValue());
            // companies (founding is free)
            int[] acme = company(COMPANY, self);
            int[] timber = company(COMPANY_2, self);
            int companyId = acme[0], companyAccount = acme[1], company2Id = timber[0];
            IAsyncBankAccount companyRef = account(bank, companyAccount);
            companyRef.addUserAsync(quenbyUser, BankPermission.DEPOSIT.getValue() | BankPermission.WITHDRAW.getValue());
            companyRef.addUserAsync(brindleUser, BankPermission.DEPOSIT.getValue());
            onServer(() -> {
                CompanyManager cm = CompanyManager.get();
                stampUpTo(cm, companyId, 1_000 + 12); // 12 wait in the stamper's output
                stampUpTo(cm, company2Id, 400);
                cm.updateDescription(companyId, "Iron and diamond mining in the northern hills. Wages are paid every Minecraft day.");
                cm.updateDescription(company2Id, "Oak and spruce logs, cut to order.");
                return null;
            });
            ItemID share = await(ItemIDManager.registerItemStackServerSide(
                    Gui.onClient(() -> StampedShareItem.ofCompany(BankSystemItems.STAMPED_SHARE.get(), companyId))), WAIT_MS);
            ItemID share2 = await(ItemIDManager.registerItemStackServerSide(
                    Gui.onClient(() -> StampedShareItem.ofCompany(BankSystemItems.STAMPED_SHARE.get(), company2Id))), WAIT_MS);
            await(bank.allowItemIDAsync(share), WAIT_MS);
            await(bank.allowItemIDAsync(share2), WAIT_MS);
            visuals(companyId, "mountain", 0xFF5B6770, "pickaxe", 0xFFE8B84A, 0xFFF3E9D2, COMPANY,
                    "Shares of Acme Mining. Dividends from the ore sales.", self, notes);
            visuals(company2Id, "tree", 0xFF3F7A3A, "axe", 0xFF8A5A2B, 0xFFE9F0DC, COMPANY_2,
                    "Shares of Brindle Timber.", self, notes);

            fixture = new Fixture(self, quenby, brindle, savings, farm, quenbyAccount, brindleAccount, companyId,
                    companyAccount, company2Id, timber[1], money, items, share, share2);

            // final balances (whole items; money in dollars)
            String error = setBalances(savingsRef, money, 12_450, Items.IRON_INGOT, 64, Items.DIAMOND, 24, Items.GOLD_INGOT, 24,
                    Items.EMERALD, 16, Items.COAL, 128, Items.WHEAT, 96, Items.STICK, 64);
            if (error == null) error = setBalance(savingsRef, share, 500);
            if (error == null) error = setBalances(farmRef, money, 3_200, Items.WHEAT, 640, Items.CARROT, 256, Items.POTATO, 192);
            if (error == null) error = setBalances(account(bank, quenbyAccount), money, 2_200);
            if (error == null) error = setBalance(account(bank, quenbyAccount), share, 300);
            if (error == null) error = setBalances(account(bank, brindleAccount), money, 5_140, Items.OAK_LOG, 320);
            if (error == null) error = setBalance(account(bank, brindleAccount), share, 200);
            if (error == null) error = setBalance(account(bank, brindleAccount), share2, 400);
            if (error == null) error = setBalances(companyRef, money, 48_500, Items.IRON_INGOT, 512, Items.DIAMOND, 96);
            if (error == null) error = setBalances(account(bank, timber[1]), money, 9_800);
            if (error != null) return fail(fixtureProblem = error);

            // invented history: a week of balances, ledger rows, payouts and two flags
            error = writeHistory(fixture, quenbyAccountName(bank, quenbyAccount));
            if (error != null) return fail(fixtureProblem = error);

            // the work spot: the blocks the block screens are opened from
            for (String command : List.of("fill 37 -61 36 46 -61 46 minecraft:smooth_stone", "fill 37 -60 36 46 -56 46 minecraft:air",
                    "setblock 42 -60 39 banksystem:bank_upload_block[facing=west]",
                    "setblock 42 -60 40 banksystem:bank_download_block[facing=west]",
                    "setblock 42 -60 41 banksystem:bank_terminal_block[facing=west]",
                    "setblock 42 -60 42 banksystem:share_stamper[facing=west]", SPOT)) {
                Gui.sendCommand(command);
                Gui.waitTicks(2);
            }
            kit();
            Gui.onClient(() -> jeiOverlay(false)); // no other mod's overlay in the pictures
            Gui.waitTicks(20);
            error = setUpBlocks(fixture);
            if (error != null) return fail(fixtureProblem = error);
            Gui.waitTicks(40); // let the flag alerts and toasts fade

            fixtureProblem = null;
            return pass("Savings " + savings + ", Farm Co-op " + farm + ", " + COMPANY + " " + companyId + " (account "
                    + companyAccount + "), " + COMPANY_2 + " " + company2Id + ", player " + name
                    + (notes.isEmpty() ? "" : "; notes: " + notes));
        } catch (RuntimeException e) {
            fixture = null;
            return fail(fixtureProblem = "fixture: " + e);
        }
    }

    static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    IAsyncBankAccount account(IAsyncBankManager bank, int number) {
        IAsyncBankAccount account = await(bank.getBankAccountAsync(number), WAIT_MS);
        if (account == null) throw new IllegalStateException("no account " + number);
        return account;
    }

    private String quenbyAccountName(IAsyncBankManager bank, int number) {
        String name = onServer(() -> {
            var sync = BankSystemMod.getAPI().getServerBankManager().getSync();
            var account = sync == null ? null : sync.getBankAccount(number);
            return account == null ? null : account.getAccountName();
        });
        return name == null || name.isBlank() ? QUENBY : name;
    }

    /** The company's id and bank account, founded by the player if it does not exist yet. */
    private int[] company(String name, UUID self) {
        AsyncCompanyManager.CompanyInfoOutput info = await(AsyncCompanyManager.getCompanyInfoAsync(name), WAIT_MS);
        if (info == null || !info.present()) {
            AsyncCompanyManager.CreateOutput out = await(AsyncCompanyManager.createCompanyAsync(name, 10_000L, self, SELF), WAIT_MS);
            if (out == null || out.resultCode() != AsyncCompanyManager.CODE_OK)
                throw new IllegalStateException("company " + name + " not created: " + (out == null ? "no answer" : "code " + out.resultCode()));
            info = await(AsyncCompanyManager.getCompanyInfoAsync(name), WAIT_MS);
        }
        return new int[]{info.companyId(), info.bankAccountNr()};
    }

    /** Server thread: stamps shares until {@code issued} are out (one supply broadcast per share). */
    private static void stampUpTo(CompanyManager cm, int companyId, long issued) {
        Company company = cm.getById(companyId);
        if (company == null) throw new IllegalStateException("no company " + companyId);
        for (long n = company.getTotalSharesIssued(); n < issued; n++) cm.stampShare(companyId);
    }

    private void visuals(int companyId, String bg, int bgTint, String fg, int fgTint, int base, String name, String description,
                         UUID self, List<String> notes) {
        AsyncCompanyManager.UpdateShareVisualsOutput out = await(AsyncCompanyManager.updateShareVisualsAsync(companyId, bg, bgTint,
                fg, fgTint, base, name, description, self), WAIT_MS);
        if (out == null || out.resultCode() != AsyncCompanyManager.CODE_OK)
            notes.add("share visuals of " + name + ": " + (out == null ? "no answer" : "code " + out.resultCode()));
    }

    /** Sets pairs of (item or money id, whole amount); the first pair is the money. Returns an error or null. */
    private @Nullable String setBalances(IAsyncBankAccount account, ItemID money, long dollars, Object... itemAmounts) {
        String error = setBalance(account, money, dollars);
        for (int i = 0; error == null && i < itemAmounts.length; i += 2)
            error = setBalance(account, fixture.item((Item) itemAmounts[i]), ((Number) itemAmounts[i + 1]).longValue());
        return error;
    }

    /** Sets one bank's balance to {@code whole} items without a ledger row (the fixture writes its own history). */
    @Nullable String setBalance(IAsyncBankAccount account, ItemID item, long whole) {
        IAsyncBank b = await(account.getOrCreateBankAsync(item), WAIT_MS);
        if (b == null) return "no bank for " + item + " in account " + account.getAccountNumberAsync();
        long raw = whole * scale();
        Boolean ok = TransactionLedger.withoutRawLog(() -> await(b.setBalanceAsync(raw), WAIT_MS));
        return Boolean.TRUE.equals(ok) ? null : "balance of " + item + " in account " + account.getAccountNumberAsync() + " not set";
    }

    static long scale() {
        return net.kroia.banksystem.BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
    }

    // ---------------------------------------------------------------- invented history

    /** One balance change of the invented history: hours before now, account, item, whole amount after it. */
    private record Point(double hoursAgo, int account, short item, long whole) {}

    /**
     * Writes a week of history ending at the live balances: balance points (and the wealth series, cash only here),
     * the matching ledger rows of Savings (last 24 h) and of Acme Mining (7 days, for its statistics), seven daily
     * wages to Quenby with their payout rows, and two suspicion flags on Savings.
     */
    private @Nullable String writeHistory(Fixture f, String quenbyAccountName) {
        var history = BankSystemModBackend.getBalanceHistoryManager();
        var ledger = BankSystemModBackend.getTransactionLogManager();
        var instances = BankSystemModBackend.getInstances_forTesting();
        if (history == null || ledger == null || instances.SUSPICION_FLAG_MANAGER == null || instances.PAYOUT_HISTORY_MANAGER == null)
            return "history managers missing";
        long now = System.currentTimeMillis(), scale = scale();
        short money = f.money().getShort();
        LedgerActor tavrik = LedgerActor.player(f.self(), SELF), quenby = LedgerActor.player(f.quenby(), QUENBY),
                brindle = LedgerActor.player(f.brindle(), BRINDLE), system = LedgerActor.system();
        List<Point> points = new ArrayList<>();
        List<TransactionLogRecord> rows = new ArrayList<>();

        // Savings: money with the two flagged jumps (a 5'000 withdrawal three days ago, a wealth jump 36 h ago)
        addSeries(points, f.savings(), money, 168, 8_200, 140, 9_400, 116, 8_650, 98, 10_100, 72, 5_100, 36, 11_900,
                24, 11_860, 20, 11_060, 19, 10_810, 17, 12_010, 9, 12_260, 5, 12_600, 3, 12_450);
        addSeries(points, f.savings(), id(f, Items.IRON_INGOT), 168, 128, 120, 96, 60, 35, 23, 99, 15, 67, 12, 64);
        addSeries(points, f.savings(), id(f, Items.DIAMOND), 168, 12, 96, 16, 7, 24);
        addSeries(points, f.savings(), id(f, Items.GOLD_INGOT), 168, 40, 50, 32, 1, 24);
        addSeries(points, f.savings(), id(f, Items.EMERALD), 168, 16);
        addSeries(points, f.savings(), id(f, Items.COAL), 22, 128);
        addSeries(points, f.savings(), id(f, Items.WHEAT), 2, 96);
        addSeries(points, f.savings(), id(f, Items.STICK), 168, 66, 12, 64);
        addSeries(points, f.savings(), f.share().getShort(), 150, 500);
        rows.add(row(f.savings(), LedgerType.DEPOSIT, id(f, Items.IRON_INGOT), 64, tavrik, null, null, now, 23, null));
        rows.add(row(f.savings(), LedgerType.UPLOAD_DEPOSIT, id(f, Items.COAL), 128, tavrik, null, null, now, 22, null));
        rows.add(row(f.savings(), LedgerType.TRANSFER, money, -800, tavrik, f.farm(), null, now, 20, 11_060L));
        rows.add(row(f.savings(), LedgerType.WITHDRAW, money, 250, tavrik, null, null, now, 19, null));
        rows.add(row(f.savings(), LedgerType.TRANSFER, money, 1_200, quenby, f.quenbyAccount(), null, now, 17, 12_010L));
        rows.add(row(f.savings(), LedgerType.DOWNLOAD_WITHDRAW, id(f, Items.IRON_INGOT), 32, tavrik, null, null, now, 15, null));
        rows.add(row(f.savings(), LedgerType.CRAFT_CONSUME, id(f, Items.IRON_INGOT), 3, tavrik, null, null, now, 12, null));
        rows.add(row(f.savings(), LedgerType.CRAFT_CONSUME, id(f, Items.STICK), 2, tavrik, null, null, now, 12, null));
        rows.add(row(f.savings(), LedgerType.DIVIDEND, money, 250, system, f.companyAccount(), f.companyId(), now, 9, 12_260L));
        rows.add(row(f.savings(), LedgerType.DEPOSIT, id(f, Items.DIAMOND), 8, tavrik, null, null, now, 7, null));
        rows.add(row(f.savings(), LedgerType.CONVERTER_DEPOSIT, money, 340, tavrik, null, null, now, 5, null));
        rows.add(row(f.savings(), LedgerType.TRANSFER, money, -150, tavrik, f.brindleAccount(), null, now, 3, 12_450L));
        rows.add(row(f.savings(), LedgerType.UPLOAD_DEPOSIT, id(f, Items.WHEAT), 96, tavrik, null, null, now, 2, null));
        rows.add(row(f.savings(), LedgerType.WITHDRAW, id(f, Items.GOLD_INGOT), 8, tavrik, null, null, now, 1, null));
        rows.add(row(f.savings(), LedgerType.DEPOSIT, money, 120, quenby, null, null, now, 0.5, null));
        rows.add(row(f.savings(), LedgerType.WITHDRAW, money, 120, tavrik, null, null, now, 0.25, null));

        // Farm Co-op, Quenby, Brindle
        addSeries(points, f.farm(), money, 168, 1_500, 130, 2_100, 90, 2_350, 50, 2_400, 20, 3_200);
        addSeries(points, f.farm(), id(f, Items.WHEAT), 168, 320, 120, 480, 72, 560, 24, 640);
        addSeries(points, f.farm(), id(f, Items.CARROT), 140, 256);
        addSeries(points, f.farm(), id(f, Items.POTATO), 100, 192);
        addSeries(points, f.quenbyAccount(), money, 168, 2_450, 154, 2_600, 130, 2_750, 106, 2_900, 82, 3_050,
                58, 3_200, 34, 3_350, 17, 2_150, 10, 2_200);
        addSeries(points, f.quenbyAccount(), f.share().getShort(), 150, 300);
        addSeries(points, f.brindleAccount(), money, 168, 4_000, 100, 4_600, 50, 4_990, 3, 5_140);
        addSeries(points, f.brindleAccount(), id(f, Items.OAK_LOG), 168, 200, 80, 320);
        addSeries(points, f.brindleAccount(), f.share().getShort(), 150, 200);
        addSeries(points, f.brindleAccount(), f.share2().getShort(), 140, 400);
        addSeries(points, f.company2Account(), money, 168, 6_500, 120, 7_200, 72, 8_100, 30, 9_100, 6, 9_800);

        // Acme Mining: daily ore sales, a wage to Quenby every day, two equipment withdrawals, one dividend
        long[] sales = {3_200, 4_100, 2_800, 5_200, 3_900, 4_600, 3_500}; // six days ago .. today
        List<double[]> events = new ArrayList<>(); // hoursAgo, kind (0 sale, 1 wage, 2 withdraw, 3 dividend), amount
        for (int d = 6; d >= 0; d--) {
            events.add(new double[]{d * 24 + 14, 1, -150});
            events.add(new double[]{d * 24 + 18, 0, sales[6 - d]});
            if (d == 5 || d == 2) events.add(new double[]{d * 24 + 20, 2, -600});
        }
        events.add(new double[]{100, 3, -300});
        events.add(new double[]{9, 3, -500});
        events.sort((a, b) -> Double.compare(b[0], a[0]));
        long balance = 48_500;
        for (double[] e : events) balance -= (long) e[2];
        addSeries(points, f.companyAccount(), money, 168, balance);
        addSeries(points, f.companyAccount(), id(f, Items.IRON_INGOT), 168, 640, 90, 512);
        addSeries(points, f.companyAccount(), id(f, Items.DIAMOND), 168, 80, 40, 96);
        List<PayoutHistoryRecord> payouts = new ArrayList<>();
        long scheduleId = onServer(() -> wageSchedule(f, quenbyAccountName));
        int sale = 0;
        for (double[] e : events) {
            balance += (long) e[2];
            double h = e[0];
            points.add(new Point(h, f.companyAccount(), money, balance));
            switch ((int) e[1]) {
                case 0 -> rows.add(row(f.companyAccount(), LedgerType.DEPOSIT, money, (long) e[2], sale++ % 2 == 0 ? quenby : brindle,
                        null, f.companyId(), now, h, null));
                case 1 -> {
                    rows.add(row(f.companyAccount(), LedgerType.PAYOUT, money, (long) e[2], system, f.quenbyAccount(), f.companyId(), now, h, balance));
                    payouts.add(new PayoutHistoryRecord(PayoutHistoryRecord.UNSAVED_ID, f.companyId(), scheduleId, f.companyAccount(),
                            f.quenby(), 150 * scale, now - (long) (h * HOUR), PayoutHistoryRecord.Status.OK, QUENBY,
                            quenbyAccountName, PayoutSchedule.MONEY_CURRENCY, PayoutHistoryRecord.Type.NORMAL));
                }
                case 2 -> rows.add(row(f.companyAccount(), LedgerType.WITHDRAW, money, -(long) e[2], tavrik, null, f.companyId(), now, h, null));
                default -> rows.add(row(f.companyAccount(), LedgerType.DIVIDEND, money, (long) e[2], tavrik, null, f.companyId(), now, h, balance));
            }
        }

        List<BalanceHistoryRecord> records = new ArrayList<>();
        for (Point p : points) {
            long time = now - (long) (p.hoursAgo() * HOUR);
            records.add(new BalanceHistoryRecord(p.account(), p.item(), p.whole() * scale, 0, time));
            if (p.item() == money)
                records.add(new BalanceHistoryRecord(p.account(), BalanceHistoryRecord.WEALTH_ITEM_ID, p.whole() * scale, 0, time));
        }
        // the per-minute snapshots of the fixture's own balance changes (starting balance, founding money) would draw
        // spikes at the right edge: the invented history replaces them (later snapshots repeat the live balances)
        for (int account : List.of(f.savings(), f.farm(), f.quenbyAccount(), f.brindleAccount(), f.companyAccount(), f.company2Account()))
            await(history.removeHistory(Optional.empty(), Optional.of(new EqualityFilter(account)), Optional.empty()), 60_000);
        await(history.save(records), 60_000);
        await(ledger.save(rows), 60_000);
        await(instances.PAYOUT_HISTORY_MANAGER.save(payouts), 60_000);
        // the dividends in Pay Dividend's history (1'000 shares in banks, 3 holders)
        if (instances.DIVIDEND_HISTORY_STORE != null)
            for (double[] e : events)
                if (e[1] == 3) instances.DIVIDEND_HISTORY_STORE.insert(new DividendEvent(f.companyId(), null,
                        now - (long) (e[0] * HOUR), money, (long) -e[2] * scale / 1_000, (long) -e[2] * scale, 3, "MANUAL"));
        var flags = instances.SUSPICION_FLAG_MANAGER;
        await(flags.save(new SuspicionFlagRecord(SuspicionFlagRecord.UNSAVED_ID, now - 72 * HOUR, f.savings(),
                SuspicionChecks.LARGE_NOTE_WITHDRAWAL, 1, 5_000 * scale, null,
                SELF + " withdrew " + MoneyFormat.format(5_000 * scale) + " from account " + f.savings() + " in 2 withdrawal row(s)",
                false)), WAIT_MS);
        await(flags.save(new SuspicionFlagRecord(SuspicionFlagRecord.UNSAVED_ID, now - 36 * HOUR, f.savings(),
                SuspicionChecks.WEALTH_JUMP, 1, 6_800 * scale, null,
                "wealth of account " + f.savings() + " grew from " + MoneyFormat.format(5_100 * scale) + " to "
                        + MoneyFormat.format(11_900 * scale) + " in the last hour; cash change " + MoneyFormat.format(6_800 * scale),
                false)), WAIT_MS);
        return null;
    }

    /** Server thread: the daily wage of 150 $ from Acme Mining to Quenby (created once). */
    private static long wageSchedule(Fixture f, String quenbyAccountName) {
        CompanyManager cm = CompanyManager.get();
        Company company = cm.getById(f.companyId());
        for (PayoutSchedule s : company.getPayoutSchedules())
            if (f.quenby().equals(s.getTargetUUID())) return s.getScheduleId();
        CompanyManager.ScheduleCreateOutcome out = cm.createSchedule(f.companyId(), f.quenby(), 150 * scale(), 24_000L,
                PayoutExecutor.getLastObservedTick(), f.self(), f.quenbyAccount(), QUENBY, quenbyAccountName,
                PayoutSchedule.Mode.FIXED_PAYOUT, PayoutSchedule.MONEY_CURRENCY);
        if (out.schedule == null) throw new IllegalStateException("wage not created: " + out.result);
        return out.schedule.getScheduleId();
    }

    private static short id(Fixture f, Item item) {
        return f.item(item).getShort();
    }

    /** Adds (hoursAgo, whole amount) pairs of one account's item. */
    private static void addSeries(List<Point> points, int account, short item, double... pairs) {
        for (int i = 0; i < pairs.length; i += 2) points.add(new Point(pairs[i], account, item, (long) pairs[i + 1]));
    }

    private static TransactionLogRecord row(int account, LedgerType type, short item, long whole, LedgerActor actor,
                                            @Nullable Integer other, @Nullable Integer companyId, long now, double hoursAgo,
                                            @Nullable Long balanceAfterWhole) {
        long scale = scale();
        return TransactionLogRecord.ofAt(account, type, item, whole * scale, actor, other, companyId,
                now - (long) (hoursAgo * HOUR), null, null, balanceAfterWhole == null ? null : balanceAfterWhole * scale);
    }

    // ---------------------------------------------------------------- work spot blocks

    /** Connects the upload block, gives the download block two orders, binds the stamper; returns an error or null. */
    private @Nullable String setUpBlocks(Fixture f) {
        return onServer(() -> {
            var level = server().overworld();
            ServerPlayer player = server().getPlayerList().getPlayer(f.self());
            if (player == null) return "no server player";
            if (!(level.getBlockEntity(UPLOAD) instanceof BankUploadBlockEntity upload)) return "no upload block at " + UPLOAD;
            upload.handlePacket(player, new UpdateBankUploadBlockEntityPacket(UPLOAD, true, false, f.savings()));
            if (!(level.getBlockEntity(DOWNLOAD) instanceof BankDownloadBlockEntity download)) return "no download block at " + DOWNLOAD;
            download.applySettings(f.self(), f.savings(), List.of(
                    new BankDownloadBlockEntity.WithdrawOrder(f.item(Items.IRON_INGOT), 64, BankDownloadBlockEntity.WithdrawCondition.NONE, 0),
                    new BankDownloadBlockEntity.WithdrawOrder(f.item(Items.COAL), 32, BankDownloadBlockEntity.WithdrawCondition.HAS_MORE_THEN, 100)));
            if (!(level.getBlockEntity(TERMINAL) instanceof BankTerminalBlockEntity terminal)) return "no terminal at " + TERMINAL;
            var inventory = terminal.getInventory(f.self());
            inventory.setItem(0, new ItemStack(Items.IRON_INGOT, 64));
            inventory.setItem(1, new ItemStack(Items.OAK_PLANKS, 20));
            inventory.setItem(2, new ItemStack(Items.COBBLESTONE, 48));
            if (!(level.getBlockEntity(STAMPER) instanceof ShareStamperBlockEntity stamper)) return "no stamper at " + STAMPER;
            stamper.bind(f.companyId(), f.self());
            stamper.setMode(ShareStamperBlockEntity.Mode.STAMP);
            stamper.setItem(ShareStamperBlockEntity.SLOT_INPUT, new ItemStack(BankSystemItems.BLANK_SHARE.get(), 48));
            stamper.setItem(ShareStamperBlockEntity.SLOT_OUTPUT,
                    StampedShareItem.ofCompany(BankSystemItems.STAMPED_SHARE.get(), f.companyId()).copyWithCount(12));
            return null;
        });
    }

    // ---------------------------------------------------------------- shots

    TestResult shot(String file, Supplier<String> prepare) {
        return shot(file, SCENE, prepare, null);
    }

    /**
     * Runs {@code prepare} (opens the screen and brings it into the listed state; returns null when ready or a reason
     * to fail), takes the picture, cut to {@code crop} (window pixels, asked after {@code prepare}) when not null, and
     * closes every screen.
     */
    TestResult shot(String file, Screenshots.Scene scene, Supplier<String> prepare, @Nullable Supplier<Rectangle> crop) {
        if (fixture == null) return skip(String.valueOf(fixtureProblem));
        try {
            String problem = prepare.get();
            if (problem != null) return fail(problem);
            // in the world (no screen) the mouse turns the camera: it is left alone there
            boolean screen = Gui.onClient(() -> Minecraft.getInstance().screen != null);
            if (screen) parkMouse();
            List<Layout.Problem> layout = screen ? Gui.layoutProblems() : List.of();
            Rectangle area = crop == null ? null : crop.get();
            Path path = Screenshots.take(file, scene, area);
            if (!Files.isRegularFile(path)) return fail("no screenshot at " + path);
            // layout problems are reported, not failed: the picture is checked by eye (overlapping decorations are normal)
            return pass(path + (area == null ? "" : " cropped to " + area) + "; layout problems " + layout.size()
                    + (layout.isEmpty() ? "" : ": " + layout.stream().limit(6).map(Layout.Problem::message).toList()));
        } catch (RuntimeException e) {
            return fail(file + ": " + e);
        } finally {
            closeAll();
        }
    }

    /** Closes the open screen and the parents it returns to. */
    static void closeAll() {
        for (int i = 0; i < 6 && Gui.onClient(() -> Minecraft.getInstance().screen != null); i++) Gui.close();
        Gui.waitTicks(5);
    }

    /** Opens {@code screen} on the client thread and waits for it and its data. */
    static <T extends Screen> T open(Supplier<T> screen, Class<T> type) {
        Gui.onClient(() -> ClientCompat.setScreen(screen.get()));
        T s = Gui.waitForScreen(type, 100);
        Gui.waitTicks(DATA_TICKS);
        return s;
    }

    /** Right-clicks a block of the work spot (the player stands at {@link #SPOT}) and waits for its screen. */
    static <T extends Screen> void useBlock(BlockPos pos, Class<T> type) {
        Gui.sendCommand(SPOT);
        Gui.waitTicks(5);
        Gui.onClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(pos), Direction.WEST, pos, false));
        });
        Gui.waitForScreen(type, 100);
        Gui.waitTicks(DATA_TICKS);
    }

    private @Nullable String accountManagement() {
        Gui.onClient(() -> BankAccountManagementScreen.openScreen(fixture.farm(), false));
        Gui.waitForScreen(BankAccountManagementScreen.class, 100);
        Gui.waitTicks(DATA_TICKS);
        return null;
    }

    /** Edit Permissions of Quenby on Farm Co-op (Deposit only). */
    private @Nullable String accountPermissions() {
        accountManagement();
        GuiNode row = Gui.find(n -> n.visible() && "BankUserWidget".equals(n.type())
                && findIn(n, c -> QUENBY.equals(c.text())) != null, "Quenby's user row");
        GuiNode edit = findIn(row, c -> c.visible() && "Edit Permissions".equals(c.text()));
        if (edit == null) return "no Edit Permissions button in Quenby's row";
        Gui.click(edit);
        Gui.waitTicks(20);
        return null;
    }

    /** The visible check boxes and buttons with a margin, in window pixels (the permission screen is mostly empty). */
    static Rectangle visibleControls() {
        List<GuiNode> nodes = Gui.findAll(n -> n.visible() && (n.handle() instanceof CheckBox
                || n.handle() instanceof net.kroia.modutilities.gui.elements.Button));
        int scale = Gui.onClient(() -> (int) Minecraft.getInstance().getWindow().getGuiScale()), margin = 16;
        Rectangle r = null;
        for (GuiNode n : nodes) {
            Rectangle b = new Rectangle(n.x(), n.y(), n.width(), n.height());
            r = r == null ? b : r.union(b);
        }
        if (r == null) return new Rectangle(0, 0, SCENE.windowWidth(), SCENE.windowHeight());
        return new Rectangle((r.x - margin) * scale, (r.y - margin) * scale, (r.width + 2 * margin) * scale, (r.height + 2 * margin) * scale);
    }

    private @Nullable String atm() {
        Gui.onClient(ATMScreen::openScreen);
        Gui.waitForScreen(ATMScreen.class, 100);
        Gui.waitTicks(DATA_TICKS);
        return null;
    }

    /** Withdraw tab: 2 x 100 $, 50 $, 20 $ and 5 $ picked (275 $). */
    private @Nullable String atmWithdraw() {
        atm();
        Map<Item, Integer> pick = new LinkedHashMap<>();
        pick.put(BankSystemItems.MONEY100.get(), 2);
        pick.put(BankSystemItems.MONEY50.get(), 1);
        pick.put(BankSystemItems.MONEY20.get(), 1);
        pick.put(BankSystemItems.MONEY5.get(), 1);
        for (Map.Entry<Item, Integer> p : pick.entrySet()) {
            GuiNode element = Gui.find(n -> n.handle() instanceof MoneyElement m && m.getItemStack().is(p.getKey()), "ATM row " + p.getKey());
            if (!element.visible()) scrollTo(element);
            element = Gui.find(n -> n.visible() && n.handle() instanceof MoneyElement m && m.getItemStack().is(p.getKey()), "ATM row " + p.getKey());
            GuiNode plus = findIn(element, c -> c.visible() && "+1".equals(c.text()));
            if (plus == null) return "no +1 button in the ATM row of " + p.getKey();
            for (int i = 0; i < p.getValue(); i++) {
                Gui.click(plus);
                Gui.waitTicks(3);
            }
        }
        Gui.waitTicks(10);
        return null;
    }

    /** Convert tab with 1'250.00 $ in the player's converter cache. */
    private @Nullable String atmConvert() {
        onServer(() -> {
            ConverterCacheManager.get().clear(fixture.self());
            return ConverterCacheManager.get().deposit(fixture.self(), 1_250L * scale());
        });
        atm();
        Gui.click("Convert");
        Gui.waitTicks(40);
        return null;
    }

    private @Nullable String bankTerminal() {
        useBlock(TERMINAL, BankTerminalScreen.class);
        return null;
    }

    /** Use Bank Items and Auto-deposit output ticked, an iron pickaxe laid out in the grid from the bank. */
    private @Nullable String bankTerminalCrafting() {
        useBlock(TERMINAL, BankTerminalScreen.class);
        for (String text : List.of("Use Bank Items", "Auto-deposit output")) {
            List<GuiNode> boxes = Gui.findAll(n -> n.visible() && n.handle() instanceof CheckBox box && text.equals(box.getText()));
            if (boxes.isEmpty()) return "no check box " + text;
            boolean checked = Gui.onClient(() -> ((CheckBox) boxes.get(0).handle()).isChecked());
            if (!checked) {
                GuiNode box = boxes.get(0); // only its box (the right end) takes the click
                Gui.click(box.x() + box.width() - box.height() / 2.0, box.centerY(), GLFW.GLFW_MOUSE_BUTTON_LEFT);
                Gui.waitTicks(20);
            }
        }
        Gui.onClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (!(mc.screen instanceof BankTerminalScreen terminal)) return;
            var recipe = BankCraftingRecipes.byId(mc.level, ResourceLocation.withDefaultNamespace("iron_pickaxe"));
            if (recipe != null) terminal.getMenu().requestGhostRecipe(recipe);
        });
        Gui.waitTicks(DATA_TICKS);
        return null;
    }

    /** A balance history screen at the 7 day view. */
    private @Nullable String balanceHistory(Supplier<BalanceHistoryScreen> screen) {
        open(screen, BalanceHistoryScreen.class);
        click(n -> "7d".equals(n.text()), "7d", true);
        Gui.waitTicks(DATA_TICKS);
        return null;
    }

    private @Nullable String uploadBlock() {
        useBlock(UPLOAD, BankUploadScreen.class);
        return null;
    }

    private @Nullable String downloadBlock() {
        useBlock(DOWNLOAD, BankDownloadScreen.class);
        return null;
    }

    /** Company Management of Acme Mining, on {@code tab} (null: Overview). */
    private @Nullable String companyTab(@Nullable String tab) {
        open(() -> new CompanyManagementScreen(fixture.companyId(), COMPANY), CompanyManagementScreen.class);
        if (tab != null) {
            click(n -> tab.equals(n.text()), tab + " tab", true);
            Gui.waitTicks(DATA_TICKS);
        }
        return null;
    }

    /** The Market tab needs StockMarket: skipped in a BankSystem-only cell. */
    private TestResult test_companyMarketTab() {
        if (!Platform.isModLoaded("stockmarket")) return skip("needs StockMarket loaded (BankSystem-only cell)");
        return shot("banksystem/company-market-tab.png", () -> {
            // the tab's own calls: Create Market (25 $ a share), then trading on if the new market starts paused
            var created = await(AsyncCompanyManager.openShareMarketAsync(fixture.companyId(), 25f, fixture.self()), WAIT_MS);
            int status = created == null ? -1 : created.status();
            if (status != AsyncCompanyManager.SM_STATUS_SUCCESS && status != AsyncCompanyManager.SM_STATUS_ALREADY_EXISTS)
                return "no market for " + COMPANY + " (status " + status + ")";
            Integer open = await(AsyncCompanyManager.isMarketOpenAsync(fixture.companyId()), WAIT_MS);
            if (open == null || open != AsyncCompanyManager.MARKET_OPEN_YES) {
                var opened = await(AsyncCompanyManager.setMarketOpenAsync(fixture.companyId(), true, fixture.self()), WAIT_MS);
                if (opened == null || opened.status() != AsyncCompanyManager.SM_STATUS_SUCCESS)
                    return "market of " + COMPANY + " stays closed (status " + (opened == null ? -1 : opened.status()) + ")";
            }
            String problem = companyTab("Market");
            Gui.waitTicks(DATA_TICKS); // the price chart fills in
            return problem;
        });
    }

    private @Nullable String payDividend() {
        CompanyManagementScreen parent = open(() -> new CompanyManagementScreen(fixture.companyId(), COMPANY), CompanyManagementScreen.class);
        open(() -> new PayDividendScreen(parent, fixture.companyId(), fixture.self()), PayDividendScreen.class);
        List<GuiNode> boxes = Gui.findAll(n -> n.visible() && n.handle() instanceof TextBox box && "0.01".equals(box.getText()));
        if (boxes.isEmpty()) return "no amount box with 0.01";
        Gui.onClient(() -> ((TextBox) boxes.get(0).handle()).setText("0.50"));
        Gui.waitTicks(10);
        return null;
    }

    private @Nullable String payoutEdit() {
        AsyncCompanyManager.ListSchedulesOutput list = await(AsyncCompanyManager.listSchedulesAsync(fixture.companyId()), WAIT_MS);
        if (list == null || list.schedules().isEmpty()) return "no payout schedule";
        PayoutSchedule schedule = list.schedules().get(0).toSchedule();
        CompanyManagementScreen parent = open(() -> new CompanyManagementScreen(fixture.companyId(), COMPANY), CompanyManagementScreen.class);
        open(() -> new PayoutEditScreen(parent, fixture.companyId(), schedule, fixture.self(), true, () -> {}), PayoutEditScreen.class);
        return null;
    }

    /** Bank Settings with iron ingot selected in the item list. */
    private @Nullable String bankSettings() {
        Gui.onClient(BankSystemSettingScreen::openScreen);
        Gui.waitForScreen(BankSystemSettingScreen.class, 100);
        Gui.waitTicks(DATA_TICKS);
        click(n -> shows(n, Items.IRON_INGOT), "iron ingot in the item list", true);
        Gui.waitTicks(20);
        return null;
    }

    /** The audit list of Savings, Last 24h. */
    private @Nullable String auditScreen() {
        open(() -> new AuditScreen(null, fixture.savings()), AuditScreen.class);
        // the label of a drop-down menu does not take clicks, its arrow button does: open the menu directly
        GuiNode menu = Gui.find(n -> n.handle() instanceof DropDownMenu d && "All time".equals(d.getLabelText()), "range menu");
        Gui.onClient(() -> ((DropDownMenu) menu.handle()).expand());
        Gui.waitTicks(5);
        click(n -> "Last 24h".equals(n.text()), "Last 24h", true);
        Gui.waitTicks(DATA_TICKS);
        return null;
    }

    private @Nullable String serverHistory() {
        BankSystemSettingScreen parent = settingsScreen();
        return balanceHistory(() -> new BalanceHistoryScreen(parent, BalanceHistoryRequest.Query.SERVER_WIDE_ACCOUNT, true));
    }

    private @Nullable String modSettings() {
        BankSystemSettingScreen parent = settingsScreen();
        if (!Gui.onClient(ModSettingsScreen::canOpen)) return "Mod Settings need the master and op level 2";
        open(() -> new ModSettingsScreen(parent), ModSettingsScreen.class);
        scrollTo(Gui.find(n -> "Suspicious activity".equals(n.text()), "Suspicious activity group"));
        return null;
    }

    private static BankSystemSettingScreen settingsScreen() {
        Gui.onClient(BankSystemSettingScreen::openScreen);
        BankSystemSettingScreen s = Gui.waitForScreen(BankSystemSettingScreen.class, 100);
        Gui.waitTicks(20);
        return s;
    }

    private @Nullable String shareStamper() {
        useBlock(STAMPER, ShareStamperScreen.class);
        return null;
    }

    private @Nullable String shareVisuals() {
        if (!lookUpVisuals(fixture.companyId())) return "no share visuals of " + COMPANY + " on the client";
        Gui.onClient(() -> ShareVisualEditorScreen.openScreen(null, fixture.companyId(), fixture.self(), true));
        Gui.waitForScreen(ShareVisualEditorScreen.class, 100);
        Gui.waitTicks(DATA_TICKS);
        return null;
    }

    /** Asks the master for a company's share visuals; true once the client has them. */
    static boolean lookUpVisuals(int companyId) {
        Gui.onClient(() -> ShareVisualCache.tryLookup(companyId));
        return Gui.waitUntil(() -> ShareVisualCache.has(companyId), 100);
    }

    /**
     * The inventory with shares of both companies, the mouse on an Acme Mining share so its tooltip shows; cut to the
     * inventory and the tooltip. Survival mode: in creative the inventory key opens the creative screen.
     */
    private TestResult test_shareItems() {
        if (fixture == null) return skip(String.valueOf(fixtureProblem));
        try {
            lookUpVisuals(fixture.companyId());
            lookUpVisuals(fixture.company2Id());
            for (String command : List.of("gamemode survival", "kill @e[type=minecraft:item]", "clear @s",
                    "item replace entity @s hotbar.1 with minecraft:torch 32", "item replace entity @s hotbar.2 with minecraft:bread 12",
                    "item replace entity @s hotbar.3 with minecraft:iron_pickaxe", "item replace entity @s inventory.22 with minecraft:cobblestone 64")) {
                Gui.sendCommand(command);
                Gui.waitTicks(2);
            }
            onServer(() -> {
                ServerPlayer player = server().getPlayerList().getPlayer(fixture.self());
                // the tooltip of slot 10 opens to the right and down: slot 9 and the row below stay in sight
                player.getInventory().setItem(9, StampedShareItem.ofCompany(BankSystemItems.STAMPED_SHARE.get(), fixture.company2Id()).copyWithCount(5));
                player.getInventory().setItem(10, StampedShareItem.ofCompany(BankSystemItems.STAMPED_SHARE.get(), fixture.companyId()).copyWithCount(12));
                player.getInventory().setItem(18, new ItemStack(BankSystemItems.BLANK_SHARE.get(), 16));
                return null;
            });
            Gui.waitTicks(10);
            Gui.onClient(() -> ClientCompat.setScreen(new InventoryScreen(Minecraft.getInstance().player)));
            Gui.waitForScreen(InventoryScreen.class, 100);
            Gui.waitTicks(10);
            int[] origin = Gui.onClient(() -> {
                Screen s = Minecraft.getInstance().screen;
                return new int[]{(s.width - 176) / 2, (s.height - 166) / 2};
            });
            // main inventory slot 10 (second slot of the top row): x 26, y 84 in the 176x166 window
            Gui.hover(origin[0] + 26 + 8, origin[1] + 84 + 8);
            Gui.waitTicks(20); // the tooltip's supply line comes from the share visual cache
            Gui.hover(origin[0] + 26 + 8, origin[1] + 84 + 8);
            int scale = 2, margin = 8;
            Rectangle area = new Rectangle((origin[0] - margin) * scale, (origin[1] - margin) * scale,
                    (176 + 2 * margin) * scale, (166 + 2 * margin) * scale);
            Path path = Screenshots.take("banksystem/share-items.png", SCENE, area);
            if (!Files.isRegularFile(path)) return fail("no screenshot at " + path);
            return pass(path + " cropped to " + area);
        } catch (RuntimeException e) {
            return fail("share-items: " + e);
        } finally {
            Gui.hover(-100, -100);
            closeAll();
            Gui.sendCommand("gamemode creative");
            kit(); // the shares leave the inventory again (the GIF shows it)
        }
    }

    /**
     * The Bank Terminal of Savings with 64 iron ingots in its slots: Send items to bank, then +10 on iron and Receive
     * items from bank (an 800 px GIF of about 8 s).
     */
    private TestResult test_terminalGif() {
        if (fixture == null) return skip(String.valueOf(fixtureProblem));
        try {
            String problem = onServer(() -> {
                if (!(server().overworld().getBlockEntity(TERMINAL) instanceof BankTerminalBlockEntity terminal)) return "no terminal";
                var inventory = terminal.getInventory(fixture.self());
                for (int i = 0; i < inventory.getContainerSize(); i++) inventory.setItem(i, ItemStack.EMPTY);
                inventory.setItem(0, new ItemStack(Items.IRON_INGOT, 64));
                return null;
            });
            if (problem != null) return fail(problem);
            useBlock(TERMINAL, BankTerminalScreen.class);
            parkMouse();
            Path gif = Screenshots.record("banksystem/bank-terminal-deposit.gif", SCENE, () -> {
                Gui.waitTicks(12);
                Gui.click("Send items to bank");
                Gui.waitTicks(45);
                Gui.click(bankRowButton(Items.IRON_INGOT, "+10"));
                Gui.waitTicks(15);
                Gui.click("Receive items from bank");
                Gui.waitTicks(45);
            });
            if (!Files.isRegularFile(gif)) return fail("no GIF at " + gif);
            return pass(gif + " (" + gif.toFile().length() / 1024 + " KB)");
        } catch (RuntimeException e) {
            return fail("bank-terminal-deposit: " + e);
        } finally {
            closeAll();
        }
    }

    // ---------------------------------------------------------------- helpers

    /** The player's inventory in every container shot: a few everyday items, nothing from the bank. */
    static void kit() {
        for (String command : List.of("kill @e[type=minecraft:item]", "clear @s",
                "item replace entity @s hotbar.1 with minecraft:torch 32",
                "item replace entity @s hotbar.2 with minecraft:bread 12",
                "item replace entity @s hotbar.3 with minecraft:iron_pickaxe",
                "item replace entity @s inventory.0 with minecraft:cobblestone 64",
                "item replace entity @s inventory.4 with minecraft:oak_planks 20")) {
            Gui.sendCommand(command);
            Gui.waitTicks(2);
        }
    }

    /**
     * Shows or hides JEI's item list and bookmarks beside container screens. JEI's API has no switch for them: this
     * flips its internal toggles (the Ctrl+O key, kept in memory only) by reflection. Client thread.
     */
    static void jeiOverlay(boolean on) {
        try {
            Object state = Class.forName("mezz.jei.common.Internal").getMethod("getClientToggleState").invoke(null);
            Class<?> type = state.getClass();
            if ((boolean) type.getMethod("isOverlayEnabled").invoke(state) != on)
                type.getMethod("toggleOverlayEnabled").invoke(state);
            if ((boolean) type.getMethod("isBookmarkOverlayEnabled").invoke(state) != on)
                type.getMethod("toggleBookmarkEnabled").invoke(state);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // ponytail: JEI internals; without JEI there is no list, after a JEI rename the list stays in the picture
        }
    }

    static @Nullable MinecraftServer server() {
        return Gui.onClient(() -> Minecraft.getInstance().getSingleplayerServer());
    }

    /** Runs {@code task} on the server thread and waits for it. */
    <T> T onServer(Supplier<T> task) {
        return await(server().submit(task), WAIT_MS);
    }

    /** The button {@code text} in the Bank Terminal row of {@code item}. */
    private static GuiNode bankRowButton(Item item, String text) {
        GuiNode row = Gui.find(n -> n.visible() && "BankElement".equals(n.type()) && stackOf(n.handle()) != null
                && stackOf(n.handle()).is(item), "bank row of " + item);
        GuiNode button = findIn(row, c -> c.visible() && text.equals(c.text()));
        if (button == null) throw new IllegalStateException("no " + text + " in the bank row of " + item);
        return button;
    }

    /** The item stack of a Bank Terminal row (a private inner class of the screen). */
    private static @Nullable ItemStack stackOf(@Nullable Object row) {
        if (row == null) return null;
        try {
            var field = row.getClass().getDeclaredField("stack");
            field.setAccessible(true);
            return field.get(row) instanceof ItemStack s ? s : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Whether {@code n} is an ItemView showing {@code item} (empty item views have no stack). */
    static boolean shows(GuiNode n, Item item) {
        return n.handle() instanceof ItemView v && v.getItemStack() != null && v.getItemStack().is(item);
    }

    /**
     * Moves Minecraft's cursor position off the window, so no tooltip of a hovered element is in the shot (screens
     * render hover from the real cursor, which the GUI driver never moves; the OS cursor stays where it is).
     */
    static void parkMouse() {
        Gui.onClient(() -> {
            try {
                for (String name : new String[]{"xpos", "ypos"}) {
                    var field = MouseHandler.class.getDeclaredField(name);
                    field.setAccessible(true);
                    field.setDouble(Minecraft.getInstance().mouseHandler, -1000);
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                // ponytail: Mojang field names (NeoForge at runtime); on Fabric a hovered tooltip may stay in the shot
            }
        });
    }

    /** Clicks the first visible match; throws if there is none and {@code required}. */
    static void click(Predicate<GuiNode> filter, String what, boolean required) {
        List<GuiNode> found = Gui.findAll(n -> n.visible() && filter.test(n));
        if (!found.isEmpty()) Gui.click(found.get(0));
        else if (required) throw new IllegalStateException("no visible " + what);
    }

    static @Nullable GuiNode findIn(GuiNode root, Predicate<GuiNode> filter) {
        List<GuiNode> found = new ArrayList<>();
        root.forEach(n -> {
            if (found.isEmpty() && filter.test(n)) found.add(n);
        });
        return found.isEmpty() ? null : found.get(0);
    }

    /** Scrolls the list view that holds {@code node} so the node's row is at the top. */
    static void scrollTo(GuiNode node) {
        Gui.onClient(() -> {
            var row = (net.kroia.modutilities.gui.elements.base.GuiElement) node.handle();
            var parent = row.getParent();
            while (parent != null && !(parent instanceof net.kroia.modutilities.gui.elements.base.ListView)) {
                if (!(parent.getParent() instanceof net.kroia.modutilities.gui.elements.base.ListView)) row = parent;
                parent = parent.getParent();
            }
            if (parent instanceof net.kroia.modutilities.gui.elements.base.ListView list)
                list.setScrollOffset(row.getTop() + list.getScrollOffset());
        });
        Gui.waitTicks(5);
    }
}
