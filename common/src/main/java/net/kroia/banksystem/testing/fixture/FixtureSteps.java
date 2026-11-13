package net.kroia.banksystem.testing.fixture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.IAsyncBank;
import net.kroia.banksystem.api.bankaccount.IAsyncBankAccount;
import net.kroia.banksystem.api.bankmanager.IAsyncBankManager;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.banking.company.ShareIntegrity;
import net.kroia.banksystem.banking.ledger.AdminAudit;
import net.kroia.banksystem.banking.ledger.SuspicionChecks;
import net.kroia.banksystem.banking.ledger.TransactionLedger;
import net.kroia.banksystem.banking.converter.ConverterCacheManager;
import net.kroia.banksystem.data.AuditExporter;
import net.kroia.banksystem.data.DiagnosticsDatabase;
import net.kroia.banksystem.data.TransferJournal;
import net.kroia.banksystem.minecraft.block.BankSystemBlocks;
import net.kroia.banksystem.minecraft.entity.custom.BankDownloadBlockEntity;
import net.kroia.banksystem.minecraft.entity.custom.BankTerminalBlockEntity;
import net.kroia.banksystem.minecraft.entity.custom.BankUploadBlockEntity;
import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem;
import net.kroia.banksystem.minecraft.menu.custom.BankTerminalContainerMenu;
import net.kroia.banksystem.networking.entity.ConverterCommitToBankPacket;
import net.kroia.banksystem.networking.multi_server.TransferStatusRequest;
import net.kroia.banksystem.testing.fixture.FixtureKey.Case;
import net.kroia.banksystem.testing.tests.TransferJournalTests;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.modutilities.ModUtilitiesMod;
import net.kroia.modutilities.NbtCompat;
import net.kroia.modutilities.networking.client_server.arrs.AsynchronousRequestResponseSystem;
import net.kroia.modutilities.networking.multi_server.IServerIdentity;
import net.kroia.modutilities.networking.multi_server.MultiServerManager;
import net.kroia.modutilities.testing.SimulatedMasterHang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * BS-269 — test only: the BankSystem story steps of the generated audit fixture (step A, C9–C16, D and E's bank part), run on the
 * trusted slave that drives the scenario. Every step blocks (on the server thread it keeps running queued server
 * tasks while it waits, like {@code TestSuite.await}), appends its cases to {@link FixtureKey#current()} and returns
 * the ids it made. Master work goes through {@link FixtureStepRequest}. Every deposit / withdraw waits for its final
 * journal step (CONFIRMED / DELIVERED / TIMED_OUT), so none ends up "after snapshot" except the one {@link #finish} plants. Steps throw
 * {@link IllegalStateException} when something they plant does not happen.
 * <p>
 * Terminal deposits use a detached {@link BankTerminalBlockEntity} ({@code depositReserved}, the terminal's own path);
 * withdrawals lock, then {@code withdrawLockedPreferedAsync} tagged WITHDRAW like the terminal (no items are handed out).
 */
public final class FixtureSteps {

    public static final List<String> TESTERS = List.of("TesterA", "TesterB", "TesterC", "TesterD", "TesterE");
    /** Extra BankSystem testers (TesterF..V belong to StockMarket, TesterZ is the client player). */
    public static final String ADMIN_TARGET = "TesterW", CLEAN_BURST = "TesterX", CLEAN_SHARES = "TesterY";
    public static final String SHARED_ACCOUNT = "FixtureShared", COMPANY = "FixtureCo", CLEAN_COMPANY = "FixtureCleanCo",
            STAMP_COMPANY = "FixtureStampCo";
    /** In {@link Step#note()} of C11 when the 3 WEALTH_JUMP cases were left out. */
    public static final String WEALTH_SKIPPED = "wealth cases skipped: no price provider (StockMarket not loaded)";
    /** Each Tester's role in the AT story (E). */
    private static final Map<String, String> ROLES = Map.of("TesterA", "exploiter", "TesterB", "pays in A's notes",
            "TesterC", "shares an account with A", "TesterD", "trades in overlapping sessions", "TesterE", "clean trader",
            ADMIN_TARGET, "target of the admin actions", CLEAN_BURST, "clean large note burst",
            CLEAN_SHARES, "founds the clean share company");

    private static final long SCALE = BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
    private static final long WAIT_MS = 30_000, MASTER_MS = 120_000, SETTLE_MS = 120_000;
    private static final Set<String> FINAL = Set.of(TransferJournal.CONFIRMED, TransferJournal.DELIVERED, TransferJournal.RETURNED,
            TransferJournal.TIMED_OUT, TransferJournal.FAILED);
    /** Where {@link #notes} placed its chest (slave overworld); read by {@link #banknotesAtCopy}. */
    private static volatile @Nullable BlockPos notesChest;

    private FixtureSteps() {}

    /** A test player: {@code uuid} = {@link FixtureKey#testerUuid}, {@code account} = personal account number. */
    public record Tester(String name, UUID uuid, int account) {}

    /** Step A result. {@code transfers} = transfer ids of the background deposits / withdrawals. */
    public record Background(List<Tester> testers, int sharedAccount, int companyId, int companyAccount, List<String> transfers) {
        public Tester tester(String name) {
            for (Tester t : testers) if (t.name().equals(name)) return t;
            throw new IllegalArgumentException("no tester " + name);
        }
    }

    /** C9–C16 result: the case ids added to the key, and the ledger row / flag / transfer ids the step made. */
    public record Step(List<String> cases, List<Long> ledger, List<Long> flags, List<String> transfers, String note) {}

    // ---- steps -------------------------------------------------------------------------------------------------

    /** The BankSystem user {@code name} (uuid {@link FixtureKey#testerUuid}) with a personal account; creates what is missing. */
    public static Tester ensureTester(MinecraftServer slave, String name) {
        return ensureUser(slave, name, FixtureKey.testerUuid(name));
    }

    private static Tester ensureUser(MinecraftServer slave, String name, UUID uuid) {
        IAsyncBankManager m = manager();
        if (!Boolean.TRUE.equals(await(slave, m.userExistsAsync(uuid)))) {
            m.addUserAsync(uuid, name);
            for (int i = 0; !Boolean.TRUE.equals(await(slave, m.userExistsAsync(uuid))); i++) {
                if (i >= 50) throw new IllegalStateException("user " + name + " not created on the master");
                pump(slave, 100);
            }
        }
        Integer nr = await(slave, m.createPersonalBankAccountGetAccountNrAsync(uuid));
        if (nr == null || nr == ServerBankAccount.INVALID_ACCOUNT_NUMBER)
            throw new IllegalStateException("no personal account for " + name + ": " + nr);
        return new Tester(name, uuid, nr);
    }

    /**
     * A — clean background: TesterA..E with personal accounts (key players), shared account {@link #SHARED_ACCOUNT}
     * (TesterA + TesterC, key link proven/account), company {@link #COMPANY} founded by TesterE, small terminal
     * deposits and withdrawals of diamonds and iron, then 2 balance snapshots. No cases.
     */
    public static Background background(MinecraftServer slave) {
        FixtureKey key = FixtureKey.current();
        List<Tester> testers = new ArrayList<>();
        for (String name : TESTERS) {
            testers.add(ensureTester(slave, name));
            key.addPlayer(name, ROLES.get(name));
        }
        Tester a = testers.get(0), c = testers.get(2), e = testers.get(4);
        IAsyncBankManager m = manager();
        ItemID diamond = allow(slave, Items.DIAMOND), iron = allow(slave, Items.IRON_INGOT);

        Integer shared = await(slave, m.getBankAccountNrByNameAsync(SHARED_ACCOUNT));
        if (shared == null || shared == ServerBankAccount.INVALID_ACCOUNT_NUMBER)
            shared = await(slave, m.createBankAccountGetAccountNrAsync(SHARED_ACCOUNT));
        if (shared == null || shared == ServerBankAccount.INVALID_ACCOUNT_NUMBER)
            throw new IllegalStateException("shared account not created: " + shared);
        IAsyncBankAccount sharedAccount = await(slave, m.getBankAccountAsync(shared));
        for (Tester t : List.of(a, c)) {
            sharedAccount.addUserAsync(new User(t.uuid(), t.name()), BankPermission.getAllPermissions());
            for (int i = 0; !Boolean.TRUE.equals(await(slave, sharedAccount.hasUserAsync(t.uuid()))); i++) {
                if (i >= 50) throw new IllegalStateException(t.name() + " not added to the shared account");
                pump(slave, 100);
            }
        }
        key.addLink(a.uuid().toString(), c.uuid().toString(), "proven", "account");

        AsyncCompanyManager.CompanyInfoOutput info = ensureCompany(slave, COMPANY, e);

        List<String> transfers = new ArrayList<>();
        for (Tester t : testers) {
            transfers.add(depositOk(slave, t.uuid(), t.account(), new ItemStack(Items.DIAMOND, 8)));
            transfers.add(depositOk(slave, t.uuid(), t.account(), new ItemStack(Items.IRON_INGOT, 16)));
            transfers.add(withdraw(slave, t.uuid(), t.account(), diamond, 3 * SCALE));
        }
        transfers.add(depositOk(slave, a.uuid(), shared, new ItemStack(Items.IRON_INGOT, 6)));
        transfers.add(withdraw(slave, c.uuid(), shared, iron, 2 * SCALE));
        snapshot(slave);
        pump(slave, 1_100);
        snapshot(slave);
        return new Background(List.copyOf(testers), shared, info.companyId(), info.bankAccountNr(), transfers);
    }

    /**
     * C9 — rule {@code balance}; each change sits between its own snapshot pair (the case window spans them) and is never
     * undone. Flag: /money add $2,000 to TesterB, then that ledger row is deleted (DB edit): a balance change with no
     * ledger row. Clean: /money set TesterC to $1,500 (the ADMIN_SET row's "old->new" note explains it exactly).
     */
    public static Step balanceGap(MinecraftServer slave) {
        FixtureKey key = FixtureKey.current();
        Tester b = ensureTester(slave, "TesterB"), c = ensureTester(slave, "TesterC");
        List<String> cases = new ArrayList<>();
        String money = MoneyItem.getItemID().getName();

        long from = now();
        snapshot(slave);
        pump(slave, 1_100);
        long before = ledgerMax(slave);
        money(slave, "add", b.name(), 2_000);
        JsonObject add = row(ledgerAfter(slave, before), b.account(), LedgerType.ADMIN_ADD);
        long addId = add.get("id").getAsLong(), gap = add.get("amount").getAsLong();
        master(slave, "ledgerEdit", obj("id", addId, "set", obj("ref", "bs269-c9")));
        if (master(slave, "ledgerDelete", obj("ref", "bs269-c9", "id", addId)).get("deleted").getAsInt() != 1)
            throw new IllegalStateException("C9: ledger row " + addId + " not deleted");
        snapshot(slave);
        long gapTo = now();

        long cleanFrom = now();
        snapshot(slave);
        pump(slave, 1_100);
        before = ledgerMax(slave);
        money(slave, "set", c.name(), 1_500);
        JsonObject set = row(ledgerAfter(slave, before), c.account(), LedgerType.ADMIN_SET);
        long setId = set.get("id").getAsLong();
        snapshot(slave);
        long to = now();

        key.addCase(kase(cases, "C9-flag", "flag", "balance").subject(String.valueOf(b.account())).item(money)
                .window(from, gapTo).amount(gap).tolerance(0).planted("DB edit")
                .note("money add on TesterB, then its ADMIN_ADD ledger row " + addId + " deleted: the balance rose with no ledger row"));
        key.addCase(kase(cases, "C9-clean", "clean", "balance").subject(String.valueOf(c.account())).item(money)
                .window(cleanFrom, to).amount(set.get("amount").getAsLong()).tolerance(0).ledger(setId).planted("command")
                .note("/money set on TesterC (handler code as admin TesterAdmin): the ADMIN_SET row's \"old->new\" note explains the change exactly"));
        return new Step(cases, List.of(addId, setId), List.of(), List.of(), "C9 deleted row " + addId + " (" + gap + " raw)");
    }

    /**
     * C10 — admin actions on {@link #ADMIN_TARGET}'s own account (no other tester is touched): money add / set / remove,
     * ADMIN_UNLOCK, freeze on, freeze off. First probes {@code /money add TesterW 1} from the console (offline name);
     * /money needs a player, so add / set / remove run the /money handler code as admin TesterAdmin ({@code money} op).
     * Freeze is the real console command. Rule {@code admin:<LedgerType>}; freeze on and off both write ACCOUNT_FREEZE.
     */
    public static Step adminCommands(MinecraftServer slave) {
        long from = now();
        FixtureKey key = FixtureKey.current();
        Tester w = ensureTester(slave, ADMIN_TARGET);
        key.addPlayer(w.name(), ROLES.get(w.name()));
        List<String> cases = new ArrayList<>();
        List<Long> ledger = new ArrayList<>();
        String money = MoneyItem.getItemID().getName();

        long before = ledgerMax(slave);
        JsonArray lines = master(slave, "command", obj("cmd", "money add " + w.name() + " 1")).getAsJsonArray("lines");
        // an applied probe merges with C10-ADMIN_ADD (same rule + subject): that case then carries both rows
        JsonObject probeRow = find(ledgerAfter(slave, before), w.account(), LedgerType.ADMIN_ADD);
        String probe = "console '/money add " + w.name() + " 1' (offline name): " + (probeRow != null ? "applied" : "refused")
                + " " + lines + "; add/set/remove use the money op (/money handler code as admin " + FixtureStepRequest.ADMIN + ")";

        Object[][] plan = {
                {"C10-ADMIN_ADD-flag", LedgerType.ADMIN_ADD, obj("action", "add", "user", w.name(), "amount", 100)},
                {"C10-ADMIN_SET-flag", LedgerType.ADMIN_SET, obj("action", "set", "user", w.name(), "amount", 250)},
                {"C10-ADMIN_REMOVE-flag", LedgerType.ADMIN_REMOVE, obj("action", "remove", "user", w.name(), "amount", 50)},
                {"C10-ADMIN_UNLOCK-flag", LedgerType.ADMIN_UNLOCK, obj("account", w.account(), "item", MoneyItem.getItemID().getShort(), "lock", 20 * SCALE)},
                {"C10-ACCOUNT_FREEZE-on-flag", LedgerType.ACCOUNT_FREEZE, obj("cmd", "banksystem freeze " + w.account() + " on")},
                {"C10-ACCOUNT_FREEZE-off-flag", LedgerType.ACCOUNT_FREEZE, obj("cmd", "banksystem freeze " + w.account() + " off")}};
        for (Object[] p : plan) {
            String id = (String) p[0];
            LedgerType type = (LedgerType) p[1];
            long caseFrom = now();
            before = ledgerMax(slave);
            String op = switch (type) {
                case ADMIN_ADD, ADMIN_SET, ADMIN_REMOVE -> "money";
                case ADMIN_UNLOCK -> "unlock";
                default -> "command";
            };
            JsonObject r = master(slave, op, (JsonObject) p[2]);
            if (r.has("ok") && !r.get("ok").getAsBoolean()) throw new IllegalStateException(id + " refused");
            JsonObject row = row(ledgerAfter(slave, before), w.account(), type);
            List<Long> rows = new ArrayList<>(List.of(row.get("id").getAsLong()));
            long amount = row.get("amount").getAsLong();
            if (type == LedgerType.ADMIN_ADD && probeRow != null) {
                rows.add(0, probeRow.get("id").getAsLong());
                amount += probeRow.get("amount").getAsLong();
                caseFrom = from;
            }
            ledger.addAll(rows);
            key.addCase(kase(cases, id, "flag", "admin:" + type.name()).subject(String.valueOf(w.account()))
                    .item(type == LedgerType.ACCOUNT_FREEZE ? null : money).window(caseFrom, now())
                    .amount(amount).tolerance(0).ledger(rows.toArray()).planted(type == LedgerType.ADMIN_UNLOCK ? "test hook" : "command")
                    .note(switch (type) {
                        case ADMIN_UNLOCK -> "admin reset locked balance (UpdateBankAccountRequest as TesterAdmin) after a planted lock of 20.00";
                        case ACCOUNT_FREEZE -> "console /banksystem freeze";
                        default -> "/money handler code as admin TesterAdmin" + (rows.size() > 1 ? " plus the console probe row" : "");
                    } + "; an admin event (AT adminEvents), not a finding"));
        }
        return new Step(cases, ledger, List.of(), List.of(), probe + "; window " + from + ".." + now());
    }

    /**
     * C11 — every SuspicionFlag type the run can trigger, each with a clean look-alike on a DIFFERENT subject (same rule
     * + subject within 15 min merge into one finding). Funding by /money add (C $11M, D $2M). Notes: C withdraws $10M in
     * one burst of $8M + $2M (LARGE_NOTE_WITHDRAWAL), B pays in the $2M (BANKNOTE_LAUNDERING; key link C → B
     * likely/notes, the deposit equals exactly one C withdrawal), C pays the $8M back (own notes: clean laundering).
     * Transfers: D → E $1M into a new account (TRANSFER_TO_NEW_ACCOUNT), D → C $999,999.99 (clean), E → company $500k.
     * Wealth: snapshot + sweep: B and C jump (WEALTH_JUMP), D moved $2M through but ends flat (clean); skipped without a
     * price provider. Clean burst: after the sweep TesterX gets $9.5M, withdraws it in one burst (below $10M) and pays it
     * back. Parked: a terminal deposit with RESTART_AFTER_APPLY (TRANSFER_PARKED + a parked transfer), a normal one
     * (clean). Binding: D's stone slot bound to TesterA's stub account (EXTERNAL_BINDING_SUSPENDED), E's to E's own
     * (clean). Shares: {@link #CLEAN_COMPANY} (founded by TesterY) stamps 3 and TesterY banks them (clean SHARE_CREATED /
     * SHARE_EXCESS); {@link #COMPANY} gets 5 reported as made by /give (SHARE_CREATED), banked unstamped by E (SHARE_EXCESS).
     * Not covered: MONEY_SUPPLY_GROWTH (needs a balance snapshot at least 1 h old).
     */
    public static Step suspicionFlags(MinecraftServer slave) {
        long from = now();
        FixtureKey key = FixtureKey.current();
        Tester b = ensureTester(slave, "TesterB"), c = ensureTester(slave, "TesterC"),
                d = ensureTester(slave, "TesterD"), e = ensureTester(slave, "TesterE"),
                x = ensureTester(slave, CLEAN_BURST), y = ensureTester(slave, CLEAN_SHARES);
        key.addPlayer(x.name(), ROLES.get(x.name())).addPlayer(y.name(), ROLES.get(y.name()));
        List<String> cases = new ArrayList<>(), transfers = new ArrayList<>();
        List<Long> ledger = new ArrayList<>(), flags = new ArrayList<>();
        long flag0 = flagMax(slave);
        ItemID money = MoneyItem.getItemID();
        Item note = BankSystemItems.MONEY500000.get();
        long noteRaw = ((MoneyItem) note).worth();
        AsyncCompanyManager.CompanyInfoOutput co = company(slave, COMPANY);

        money(slave, "add", c.name(), 11_000_000);
        money(slave, "add", d.name(), 2_000_000);

        // 2 + 1: banknotes; one burst, its $2M part is exactly what B pays in (AT matches a notes link on that)
        String w1 = withdraw(slave, c.uuid(), c.account(), money, 16 * noteRaw);
        String w1b = withdraw(slave, c.uuid(), c.account(), money, 4 * noteRaw);
        long laundFrom = now();
        String payB = depositOk(slave, b.uuid(), b.account(), new ItemStack(note, 4));
        long cleanLaundFrom = now();
        String payC = depositOk(slave, c.uuid(), c.account(), new ItemStack(note, 16));
        long cleanLaundTo = now();
        transfers.addAll(List.of(w1, w1b, payB, payC));
        key.addLink(c.uuid().toString(), b.uuid().toString(), "likely", "notes");

        // 4: transfers into new accounts
        long newFrom = now();
        long toE = transferMoney(slave, d, e.account(), 1_000_000 * SCALE);
        long cleanNewFrom = now();
        long toC = transferMoney(slave, d, c.account(), 1_000_000 * SCALE - 1);
        long cleanNewTo = now();
        transferMoney(slave, e, co.bankAccountNr(), 500_000 * SCALE);

        JsonObject large = awaitFlag(slave, flag0, SuspicionChecks.LARGE_NOTE_WITHDRAWAL, c.account(), 90_000);

        // 3: wealth over the last hour (from the background snapshots)
        snapshot(slave);
        // no price provider (StockMarket not loaded) = no WEALTH rows = no wealth cases
        boolean wealth = master(slave, "sweep", obj()).get("wealth").getAsBoolean();
        JsonObject wealthB = wealth ? awaitFlag(slave, flag0, SuspicionChecks.WEALTH_JUMP, b.account(), WAIT_MS) : null;
        JsonObject wealthC = wealth ? awaitFlag(slave, flag0, SuspicionChecks.WEALTH_JUMP, c.account(), WAIT_MS) : null;
        long wealthTo = now();
        JsonObject laund = awaitFlag(slave, flag0, SuspicionChecks.BANKNOTE_LAUNDERING, b.account(), WAIT_MS);
        JsonObject newAcc = awaitFlag(slave, flag0, SuspicionChecks.TRANSFER_TO_NEW_ACCOUNT, e.account(), WAIT_MS);

        // 2 clean: a burst below the threshold on another subject, after the sweep so X's $9.5M is not swept
        money(slave, "add", x.name(), 9_500_000);
        long burst2From = now();
        String w2 = withdraw(slave, x.uuid(), x.account(), money, 19 * noteRaw);
        long burst2Close = now() + SuspicionChecks.refundGraceMs;
        String payX = depositOk(slave, x.uuid(), x.account(), new ItemStack(note, 19));
        transfers.addAll(List.of(w2, payX));

        // 6: parked
        long parkCleanFrom = now();
        String okDeposit = depositOk(slave, d.uuid(), d.account(), new ItemStack(Items.IRON_INGOT, 2));
        long parkCleanTo = now();
        master(slave, "armFault", obj("fault", "RESTART_AFTER_APPLY"));
        long parkFrom = now();
        Sent parked = deposit(slave, b.uuid(), b.account(), new ItemStack(Items.IRON_INGOT, 3));
        if (!parked.future().isCompletedExceptionally()) {
            master(slave, "armFault", obj("fault", "NONE"));
            throw new IllegalStateException("C11: the faulted deposit ended " + parked.future().join() + ", not unknown");
        }
        JsonObject park = awaitFlag(slave, flag0, SuspicionChecks.TRANSFER_PARKED, b.account(), WAIT_MS);
        long parkTo = now();
        transfers.addAll(List.of(okDeposit, parked.transferId()));

        // 7: external binding
        long bindFrom = now();
        JsonObject foreign = master(slave, "bindExternal", obj("account", d.account(), "key", "fixture_foreign_" + d.account(),
                "owner", FixtureKey.testerUuid("TesterA").toString()));
        if (!foreign.get("suspended").getAsBoolean()) throw new IllegalStateException("C11: foreign binding not suspended: " + foreign);
        JsonObject bind = awaitFlag(slave, flag0, SuspicionChecks.EXTERNAL_BINDING_SUSPENDED, d.account(), WAIT_MS);
        long bindCleanFrom = now();
        JsonObject own = master(slave, "bindExternal", obj("account", e.account(), "key", "fixture_own_" + e.account(),
                "owner", e.uuid().toString()));
        if (!"SUCCESS".equals(own.get("status").getAsString()) || own.get("suspended").getAsBoolean())
            throw new IllegalStateException("C11: own binding not bound or suspended: " + own);
        long bindCleanTo = now();

        // 9 + 8: shares; clean on the clean company, flags on FixtureCo
        AsyncCompanyManager.CompanyInfoOutput cleanCo = ensureCompany(slave, CLEAN_COMPANY, y);
        ItemStack cleanShare = StampedShareItem.ofCompany(BankSystemItems.STAMPED_SHARE.get(), cleanCo.companyId());
        long stampFrom = now();
        for (int i = 0; i < 3; i++) {
            Integer code = await(slave, AsyncCompanyManager.stamperApplyAsync(cleanCo.companyId(), false, y.uuid(),
                    TransferJournal.newTransferId(), now(), true));
            if (code == null || code != AsyncCompanyManager.CODE_OK) throw new IllegalStateException("C11: stamp refused: " + code);
        }
        long stampTo = now();
        String stamped = depositOk(slave, y.uuid(), y.account(), cleanShare.copyWithCount(3));
        long bankedTo = now();
        ItemStack share = StampedShareItem.ofCompany(BankSystemItems.STAMPED_SHARE.get(), co.companyId());
        long createdFrom = now();
        ShareIntegrity.reportCreated(e.name(), co.companyId(), 5, "give command");
        JsonObject created = awaitFlag(slave, flag0, SuspicionChecks.SHARE_CREATED, co.bankAccountNr(), WAIT_MS);
        long excessFrom = now();
        String unstamped = depositOk(slave, e.uuid(), e.account(), share.copyWithCount(5));
        JsonObject excess = awaitFlag(slave, flag0, SuspicionChecks.SHARE_EXCESS, co.bankAccountNr(), WAIT_MS);
        transfers.addAll(List.of(stamped, unstamped));

        // clean look-alikes that must not have flagged
        pumpUntil(slave, () -> now() > burst2Close + 5_000, SuspicionChecks.refundGraceMs + 10_000);
        long to = now();
        for (int[] none : new int[][]{{SuspicionChecks.LARGE_NOTE_WITHDRAWAL, x.account()}, {SuspicionChecks.BANKNOTE_LAUNDERING, c.account()},
                {SuspicionChecks.BANKNOTE_LAUNDERING, x.account()}, {SuspicionChecks.TRANSFER_TO_NEW_ACCOUNT, c.account()},
                {SuspicionChecks.WEALTH_JUMP, d.account()}, {SuspicionChecks.TRANSFER_PARKED, d.account()},
                {SuspicionChecks.EXTERNAL_BINDING_SUSPENDED, e.account()}, {SuspicionChecks.SHARE_CREATED, cleanCo.bankAccountNr()},
                {SuspicionChecks.SHARE_EXCESS, cleanCo.bankAccountNr()}}) {
            if (none[0] == SuspicionChecks.WEALTH_JUMP && !wealth) continue;
            JsonObject f = findFlag(slave, flag0, none[0], none[1]);
            if (f != null) throw new IllegalStateException("C11: clean look-alike flagged: " + f);
        }

        flagCase(key, cases, flags, "C11-2-flag", large, from, "test hook",
                "TesterC withdrew $10M from the money bank in one burst of $8M + $2M (terminal-style, no items handed out)");
        key.addCase(kase(cases, "C11-2-clean", "clean", rule(SuspicionChecks.LARGE_NOTE_WITHDRAWAL))
                .subject(String.valueOf(x.account())).window(burst2From, burst2Close + 5_000).amount(19 * noteRaw).tolerance(0)
                .ledger(refIds(slave, w2).toArray()).planted("test hook").note("TesterX withdrew $9.5M in one burst, below the $10M threshold, and paid it back"));
        flagCase(key, cases, flags, "C11-1-flag", laund, laundFrom, "test hook",
                "TesterB paid in 4 x $500k notes, exactly the $2M TesterC withdrew in one go, with no own note withdrawals");
        key.addCase(kase(cases, "C11-1-clean", "clean", rule(SuspicionChecks.BANKNOTE_LAUNDERING))
                .subject(String.valueOf(c.account())).window(cleanLaundFrom, cleanLaundTo).amount(16 * noteRaw).tolerance(0)
                .ledger(refIds(slave, payC).toArray()).planted("test hook").note("TesterC paid in 16 of the own $500k notes ($8M)"));
        flagCase(key, cases, flags, "C11-4-flag", newAcc, newFrom, "test hook", "TesterD transferred $1M into TesterE's new account");
        key.addCase(kase(cases, "C11-4-clean", "clean", rule(SuspicionChecks.TRANSFER_TO_NEW_ACCOUNT))
                .subject(String.valueOf(c.account())).window(cleanNewFrom, cleanNewTo).amount(1_000_000 * SCALE - 1).tolerance(0)
                .ledger(toC).planted("test hook").note("TesterD transferred $999,999.99 into TesterC's new account, below $1M"));
        if (wealth) {
            flagCase(key, cases, flags, "C11-3-b-flag", wealthB, from, "command",
                    "TesterB's wealth jumped by the $2M paid in notes (snapshot + sweep)");
            flagCase(key, cases, flags, "C11-3-c-flag", wealthC, from, "command",
                    "TesterC's wealth jumped after /money add $11M (snapshot + sweep)");
            key.addCase(kase(cases, "C11-3-clean", "clean", rule(SuspicionChecks.WEALTH_JUMP))
                    .subject(String.valueOf(d.account())).window(from, wealthTo).planted("command")
                    .note("TesterD got $2M by /money add and transferred it all on: ends flat"));
        }
        flagCase(key, cases, flags, "C11-6-flag", park, parkFrom, "test hook",
                "terminal deposit with RESTART_AFTER_APPLY: simulated restart; reply lost, request timed out, status unknown → TIMED_OUT, parked");
        key.addCase(kase(cases, "C11-6-clean", "clean", rule(SuspicionChecks.TRANSFER_PARKED))
                .subject(String.valueOf(d.account())).window(parkCleanFrom, parkCleanTo).planted("test hook")
                .ledger(refIds(slave, okDeposit).toArray()).note("the same terminal deposit without the fault"));
        String ironName = await(slave, ItemIDManager.registerItemStackServerSide(new ItemStack(Items.IRON_INGOT))).getName();
        key.addCase(kase(cases, "C11-transfer-parked-flag", "flag", "transfer").subject(parked.transferId()).item(ironName)
                .window(parkFrom, parkTo).amount(0).tolerance(0)
                .transfer(parked.transferId(), TransferJournal.TO_MASTER, TransferJournal.ASYNC_BANK, "parked",
                        Map.of(ironName, 3 * SCALE), Map.of(ironName, 3 * SCALE))
                .ledger(refIds(slave, parked.transferId()).toArray()).flags(park.get("id").getAsLong())
                .transfers(parked.transferId()).planted("test hook")
                .note("simulated restart; reply lost, request timed out, status unknown → TIMED_OUT (master row written)"));
        key.addCase(kase(cases, "C11-transfer-ok-clean", "clean", "transfer").subject(okDeposit).item(ironName)
                .window(parkCleanFrom, parkCleanTo).amount(0).tolerance(0)
                .transfer(okDeposit, TransferJournal.TO_MASTER, TransferJournal.ASYNC_BANK, "ok",
                        Map.of(ironName, 2 * SCALE), Map.of(ironName, 2 * SCALE))
                .ledger(refIds(slave, okDeposit).toArray()).transfers(okDeposit).planted("test hook").note("CONFIRMED terminal deposit"));
        flagCase(key, cases, flags, "C11-7-flag", bind, bindFrom, "test hook",
                "TesterD's stone slot bound to a stub currency account of TesterA, then the start-up binding check");
        key.addCase(kase(cases, "C11-7-clean", "clean", rule(SuspicionChecks.EXTERNAL_BINDING_SUSPENDED))
                .subject(String.valueOf(e.account())).window(bindCleanFrom, bindCleanTo).planted("test hook")
                .note("TesterE's stone slot bound to TesterE's own stub currency account"));
        flagCase(key, cases, flags, "C11-9-flag", created, createdFrom, "test hook",
                "5 " + COMPANY + " shares reported as made by /give (ShareIntegrity.reportCreated, as the /give hook does)");
        key.addCase(kase(cases, "C11-9-clean", "clean", rule(SuspicionChecks.SHARE_CREATED))
                .subject(String.valueOf(cleanCo.bankAccountNr())).window(stampFrom, stampTo).planted("test hook")
                .note("3 " + CLEAN_COMPANY + " shares stamped (stamper apply)"));
        flagCase(key, cases, flags, "C11-8-flag", excess, excessFrom, "test hook",
                "TesterE banked the 5 unstamped " + COMPANY + " shares: 5 banked, 0 stamped");
        key.addCase(kase(cases, "C11-8-clean", "clean", rule(SuspicionChecks.SHARE_EXCESS))
                .subject(String.valueOf(cleanCo.bankAccountNr())).window(stampTo, bankedTo).planted("test hook")
                .ledger(refIds(slave, stamped).toArray()).note("TesterY banked the 3 stamped " + CLEAN_COMPANY + " shares: 3 banked, 3 stamped"));
        for (String t : List.of(w1, w1b, payB)) ledger.addAll(refIds(slave, t));
        ledger.add(toE);
        return new Step(cases, ledger, flags, transfers,
                (wealth ? "" : WEALTH_SKIPPED + "; ")
                        + "not covered: MONEY_SUPPLY_GROWTH (needs a balance snapshot at least 1 h old); window " + from + ".." + to);
    }

    /**
     * C13 — rule {@code banknotes} (one global finding, subject null: notes found in the world − notes the logs say are
     * outside the bank). Runs after C11: TesterX pays from the $9.5M of its clean burst. TesterX withdraws 8 times
     * (terminal-style, all below $10M in one burst, every pay-in below the $1M laundering floor, every gain below the
     * $1M wealth floor): $350k → TesterY pays in exactly $350k (key link X → Y likely/notes); $120k + $130k + $140k →
     * TesterW pays in the $390k run as one deposit (link X → W); $400k left in a chest placed on the slave; $200k /
     * $100k into the client player's inventory / ender chest (into the chest when no client is online); $500k destroyed
     * (planted missing). Planted surplus: 10 x $100k put into the chest by code, no record. A /give of one $100k note to
     * the client is logged in NoteFlow, so it is explained (balanced). Net difference +$500k → C13-flag.
     * {@link #banknotesAtCopy} fills key {@code banknotes} at the copy moment.
     */
    public static Step notes(MinecraftServer slave) {
        long from = now();
        FixtureKey key = FixtureKey.current();
        Tester x = ensureTester(slave, CLEAN_BURST), y = ensureTester(slave, CLEAN_SHARES), w = ensureTester(slave, ADMIN_TARGET);
        ServerPlayer client = client(slave);
        String clientName = client == null ? null : client.getGameProfile().getName();
        if (client != null && client.getUUID().equals(FixtureKey.testerUuid(clientName)))
            key.addPlayer(clientName, "client player: holds TesterX's notes (C13), deleted with payout (C16)");
        ItemID money = MoneyItem.getItemID();
        Item k10 = BankSystemItems.MONEY10000.get(), k100 = BankSystemItems.MONEY100000.get();
        long r10 = ((MoneyItem) k10).worth(), r100 = ((MoneyItem) k100).worth();
        List<String> transfers = new ArrayList<>();

        String single = withdraw(slave, x.uuid(), x.account(), money, 35 * r10);
        List<String> run = List.of(withdraw(slave, x.uuid(), x.account(), money, 12 * r10),
                withdraw(slave, x.uuid(), x.account(), money, 13 * r10), withdraw(slave, x.uuid(), x.account(), money, 14 * r10));
        String toChest = withdraw(slave, x.uuid(), x.account(), money, 4 * r100);
        String toInventory = withdraw(slave, x.uuid(), x.account(), money, 2 * r100);
        String toEnder = withdraw(slave, x.uuid(), x.account(), money, r100);
        String destroyed = withdraw(slave, x.uuid(), x.account(), money, 5 * r100);
        String payY = depositOk(slave, y.uuid(), y.account(), new ItemStack(k10, 35));
        String payW = depositOk(slave, w.uuid(), w.account(), new ItemStack(k10, 39));
        key.addLink(x.uuid().toString(), y.uuid().toString(), "likely", "notes");
        key.addLink(x.uuid().toString(), w.uuid().toString(), "likely", "notes");
        transfers.add(single);
        transfers.addAll(run);
        transfers.addAll(List.of(toChest, toInventory, toEnder, destroyed, payY, payW));

        BlockPos pos = onSlave(slave, () -> {
            ServerLevel level = slave.overworld();
            BlockPos p = level.getSharedSpawnPos().offset(2, -3, 2);
            level.setBlock(p, Blocks.CHEST.defaultBlockState(), 3);
            if (!(level.getBlockEntity(p) instanceof ChestBlockEntity chest)) throw new IllegalStateException("C13: no chest at " + p);
            chest.clearContent();
            chest.setItem(0, new ItemStack(k100, 4));
            chest.setItem(1, new ItemStack(k100, 10)); // planted surplus: no ledger row, no NoteFlow row
            if (client == null) chest.setItem(2, new ItemStack(k100, 3));
            else {
                if (!client.getInventory().add(new ItemStack(k100, 2)))
                    throw new IllegalStateException("C13: client inventory full");
                if (!client.getEnderChestInventory().addItem(new ItemStack(k100, 1)).isEmpty())
                    throw new IllegalStateException("C13: client ender chest full");
                slave.getCommands().performPrefixedCommand(slave.createCommandSourceStack(),
                        "give " + clientName + " " + BuiltInRegistries.ITEM.getKey(k100) + " 1");
            }
            chest.setChanged();
            return p;
        });
        notesChest = pos;

        List<String> cases = new ArrayList<>();
        String place = "container@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        key.addCase(kase(cases, "C13-flag", "flag", "banknotes").window(from, now()).amount(5 * r100).tolerance(0)
                .planted("test hook").note("surplus: 10 x $100k put into the chest at " + place + " by code (no record); missing: TesterX's"
                        + " $500k withdrawal destroyed; net +$500k. Explained, not counted: TesterX's notes in the chest"
                        + (client == null ? "" : ", the client's inventory and ender chest, the /give note (NoteFlow)")
                        + "; the $350k and $390k pay-ins"));
        List<Long> ledger = new ArrayList<>();
        for (String t : transfers) ledger.addAll(refIds(slave, t));
        return new Step(cases, ledger, List.of(), transfers, client == null ? "no client player on the slave: notes in the chest only"
                : "client " + clientName + ": $200k + /give $100k in the inventory, $100k in the ender chest; chest " + place);
    }

    /**
     * Key {@code banknotes} at the copy moment: the money in the C13 chest (player null) and in the client player's
     * inventory and ender chest. Server thread. {@link #finish} calls it; don't call it as well.
     */
    public static void banknotesAtCopy(MinecraftServer slave) {
        FixtureKey key = FixtureKey.current();
        BlockPos pos = notesChest;
        if (pos != null && slave.overworld().getBlockEntity(pos) instanceof Container chest)
            key.addBanknote(null, "container@" + pos.getX() + "," + pos.getY() + "," + pos.getZ(), moneyIn(chest));
        ServerPlayer client = client(slave);
        if (client != null) {
            key.addBanknote(client.getGameProfile().getName(), "inventory", moneyIn(client.getInventory()));
            key.addBanknote(client.getGameProfile().getName(), "enderchest", moneyIn(client.getEnderChestInventory()));
        }
    }

    /**
     * C14 — the share stamper on the slave (BS-262), on a new subject ({@link #STAMP_COMPANY}, founded by TesterY; C11
     * flagged FixtureCo and kept FixtureCleanCo clean): 4 stamps and 1 redeem through the slave stamper request
     * (SHARE_STAMP / SHARE_REDEEM, issued 3), then TesterY banks 5 shares: more than issued → SHARE_EXCESS of 2. Adds
     * to C11: the redeem path and an excess measured against a non-zero issued count.
     */
    public static Step shares(MinecraftServer slave) {
        long from = now();
        Tester y = ensureTester(slave, CLEAN_SHARES);
        long flag0 = flagMax(slave);
        AsyncCompanyManager.CompanyInfoOutput co = ensureCompany(slave, STAMP_COMPANY, y);
        List<String> stamps = new ArrayList<>();
        for (int i = 0; i < 4; i++) stamps.add(stamp(slave, co.companyId(), false, y.uuid()));
        stamps.add(stamp(slave, co.companyId(), true, y.uuid()));
        long excessFrom = now();
        String banked = depositOk(slave, y.uuid(), y.account(),
                StampedShareItem.ofCompany(BankSystemItems.STAMPED_SHARE.get(), co.companyId()).copyWithCount(5));
        JsonObject excess = awaitFlag(slave, flag0, SuspicionChecks.SHARE_EXCESS, co.bankAccountNr(), WAIT_MS);
        if (excess.get("amount").getAsLong() != 2 * SCALE) throw new IllegalStateException("C14: excess " + excess);
        List<Long> ledger = new ArrayList<>();
        for (String t : stamps) {
            List<Long> ids = refIds(slave, t);
            if (ids.isEmpty()) throw new IllegalStateException("C14: no ledger row for stamper transfer " + t);
            ledger.addAll(ids);
        }
        ledger.addAll(refIds(slave, banked));
        List<String> cases = new ArrayList<>();
        List<Long> flags = new ArrayList<>();
        flagCase(FixtureKey.current(), cases, flags, "C14-8-flag", excess, excessFrom, "test hook",
                "4 " + STAMP_COMPANY + " shares stamped and 1 redeemed through the slave stamper (issued 3); TesterY banked 5: excess 2");
        return new Step(cases, ledger, flags, List.of(banked), "stamper transfers " + stamps + "; window " + from + ".." + now());
    }

    /**
     * C15 — clean transfers of TesterY on the slave, each waited for its final journal step: converter cache → bank
     * ($1,234, CONVERTER_DEPOSIT), upload block (5 diamonds, UPLOAD_DEPOSIT), download block (3 diamonds,
     * DOWNLOAD_WITHDRAW). Detached block entities (no level), like the block tests. Class ok → expect clean. Not covered:
     * a converter withdraw (the converter pays notes out of its own cache on the server the player is on: no bank call,
     * no transfer, no ledger row; CONVERTER_WITHDRAW is never written).
     */
    public static Step converterAndBlocks(MinecraftServer slave) {
        FixtureKey key = FixtureKey.current();
        Tester y = ensureTester(slave, CLEAN_SHARES);
        ItemID diamondId = allow(slave, Items.DIAMOND);
        String money = MoneyItem.getItemID().getName(), diamond = diamondId.getName();
        List<String> cases = new ArrayList<>();
        List<Long> ledger = new ArrayList<>();

        long cents = 123_400, convFrom = now(), before = ledgerMax(slave);
        ConverterCacheManager.get().clear(y.uuid());
        ConverterCacheManager.get().deposit(y.uuid(), cents);
        CompletableFuture<Map<ItemID, Long>> conv = new ConverterCommitToBankPacket(y.account()).commitFromSlave(y.uuid(), cents);
        if (conv == null) throw new IllegalStateException("C15: converter cache not reserved");
        settle(slave, conv);
        if (conv.isCompletedExceptionally() || !conv.join().isEmpty()) throw new IllegalStateException("C15: converter commit " + conv);
        String convId = awaitRow(slave, before, y.account(), LedgerType.CONVERTER_DEPOSIT).get("ref").getAsString();
        awaitFinal(slave, convId);
        long convTo = now();

        long upFrom = now();
        before = ledgerMax(slave);
        BankUploadBlockEntity up = new BankUploadBlockEntity(BlockPos.ZERO, BankSystemBlocks.BANK_UPLOAD_BLOCK.get().defaultBlockState());
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("SendingEnabled", true);
        tag.putInt("BankAccountNumber", y.account());
        NbtCompat.putUUID(tag, "PlayerOwner", y.uuid());
        up.loadAdditional(tag, slave.registryAccess()); // mc<=1.21.5
        //up.loadAdditional(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, slave.registryAccess(), tag)); // mc>=1.21.6
        up.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
        up.update();
        String upId = awaitRow(slave, before, y.account(), LedgerType.UPLOAD_DEPOSIT).get("ref").getAsString();
        awaitFinal(slave, upId);
        long upTo = now();

        long downFrom = now();
        before = ledgerMax(slave);
        download(slave, y, diamondId, 3);
        String downId = awaitRow(slave, before, y.account(), LedgerType.DOWNLOAD_WITHDRAW).get("ref").getAsString();
        awaitFinal(slave, downId);
        long downTo = now();

        Object[][] plan = {
                {"C15-converter-clean", convId, TransferJournal.TO_MASTER, TransferJournal.CONVERTER_DEPOSIT, money, cents, convFrom, convTo,
                        "converter cache → bank, $1,234 (cache filled directly, no notes went in)"},
                {"C15-upload-clean", upId, TransferJournal.TO_MASTER, TransferJournal.ASYNC_BANK, diamond, 5 * SCALE, upFrom, upTo,
                        "upload block, 5 diamonds"},
                {"C15-download-clean", downId, TransferJournal.FROM_MASTER, TransferJournal.ASYNC_BANK, diamond, 3 * SCALE, downFrom, downTo,
                        "download block, 3 diamonds"}};
        for (Object[] p : plan) {
            String tid = (String) p[1], item = (String) p[4];
            long raw = (Long) p[5];
            List<Long> rows = refIds(slave, tid);
            ledger.addAll(rows);
            key.addCase(kase(cases, (String) p[0], "clean", "transfer").subject(tid).item(item).window((Long) p[6], (Long) p[7])
                    .amount(0).tolerance(0).transfer(tid, (String) p[2], (String) p[3], "ok", Map.of(item, raw), Map.of(item, raw))
                    .ledger(rows.toArray()).transfers(tid).planted("test hook").note(p[8] + " by TesterY on the slave"));
        }
        return new Step(cases, ledger, List.of(), List.of(convId, upId, downId), "converter " + convId + ", upload " + upId + ", download " + downId);
    }

    /**
     * C16 — deleted users (admin TesterAdmin, {@code deleteUser} op), each payout leg reaching its end:
     * <ul>
     *   <li>the client player (online on the slave, 4 diamonds): the master's own drop fails (offline there), the slave
     *       DROP delivers → PAYOUT rows with its transfer id, class ok (C16-payout-clean). The client then gets a new,
     *       empty account for later steps. Skipped without a client player.</li>
     *   <li>TesterW (offline everywhere): the slave DROP ends FAILED (player offline) → ACCOUNT_DELETE_LOSS "player
     *       offline" with no ref; the DROP moved nothing, class ok (C16-failed-clean).</li>
     *   <li>TesterX (money only) with THROW_BEFORE_APPLY armed on the slave: the DROP marks itself applying, then throws
     *       before it delivers (no reply, no journal row); the master's status ask gets UNKNOWN → ACCOUNT_DELETE_LOSS with
     *       the transfer id + TRANSFER_PARKED (C16-6-flag, C16-transfer-parked-flag).</li>
     * </ul>
     */
    public static Step deletedUser(MinecraftServer slave) {
        FixtureKey key = FixtureKey.current();
        Tester w = ensureTester(slave, ADMIN_TARGET), x = ensureTester(slave, CLEAN_BURST);
        ServerPlayer client = client(slave);
        String money = MoneyItem.getItemID().getName();
        List<String> cases = new ArrayList<>(), transfers = new ArrayList<>();
        List<Long> ledger = new ArrayList<>(), flags = new ArrayList<>();
        long flag0 = flagMax(slave);
        String note = "";

        if (client != null) {
            String name = client.getGameProfile().getName();
            Tester z = ensureUser(slave, name, client.getUUID());
            allow(slave, Items.DIAMOND);
            long aFrom = now();
            transfers.add(depositOk(slave, z.uuid(), z.account(), new ItemStack(Items.DIAMOND, 4)));
            JsonObject r = master(slave, "deleteUser", obj("uuid", z.uuid().toString()));
            JsonObject delivered = r.getAsJsonObject("delivered");
            if (delivered.size() != 1 || !r.getAsJsonObject("undelivered").isEmpty())
                throw new IllegalStateException("C16: client payout not delivered by one slave DROP: " + r);
            String tid = delivered.keySet().iterator().next();
            Map<String, Long> items = new HashMap<>();
            delivered.getAsJsonObject(tid).entrySet().forEach(e -> items.put(e.getKey(), e.getValue().getAsLong()));
            awaitFinal(slave, tid);
            List<Long> rows = refIds(slave, tid);
            ledger.addAll(rows);
            transfers.add(tid);
            key.addCase(kase(cases, "C16-payout-clean", "clean", "transfer").subject(tid).window(aFrom, now()).amount(0).tolerance(0)
                    .transfer(tid, TransferJournal.FROM_MASTER, TransferJournal.DROP, "ok", items, items).ledger(rows.toArray())
                    .transfers(tid).planted("command").note("user " + name + " (client, online on the slave) deleted: the master's own drop"
                            + " failed (offline there), the slave DROP delivered the payout"));
            ensureUser(slave, name, client.getUUID());
        } else note = "no client player on the slave: C16-payout-clean skipped; ";

        long bFrom = now(), before = ledgerMax(slave);
        JsonObject rb = master(slave, "deleteUser", obj("uuid", w.uuid().toString()));
        if (!rb.get("delivered").getAsJsonObject().isEmpty() || rb.get("reason").isJsonNull() || !rb.get("unknownTransferId").isJsonNull())
            throw new IllegalStateException("C16: TesterW's payout should fail (offline): " + rb);
        List<Map<String, Object>> failed = journal(slave, "SELECT transfer_id FROM TransferJournal WHERE path = ? AND step = ? AND player_uuid = ? ORDER BY id DESC",
                TransferJournal.DROP, TransferJournal.FAILED, w.uuid().toString());
        if (failed.isEmpty()) throw new IllegalStateException("C16: no FAILED DROP journal row for TesterW");
        String bId = (String) failed.get(0).get("transfer_id");
        for (JsonElement e : ledgerAfter(slave, before)) {
            JsonObject row = e.getAsJsonObject();
            if (row.get("account_number").getAsInt() == w.account()) ledger.add(row.get("id").getAsLong());
        }
        transfers.add(bId);
        key.addCase(kase(cases, "C16-failed-clean", "clean", "transfer").subject(bId).window(bFrom, now()).amount(0).tolerance(0)
                .transfer(bId, TransferJournal.FROM_MASTER, TransferJournal.DROP, "ok", Map.of(), Map.of()).transfers(bId).planted("command")
                .note("user TesterW deleted while offline everywhere: the slave DROP ended FAILED and moved nothing;"
                        + " the balance is ACCOUNT_DELETE_LOSS \"player offline\" (no ref)"));

        long cFrom = now();
        JsonObject rc;
        TransferStatusRequest.armFault_forTesting(TransferStatusRequest.Fault.THROW_BEFORE_APPLY);
        try {
            rc = master(slave, "deleteUser", obj("uuid", x.uuid().toString()));
        } finally {
            TransferStatusRequest.armFault_forTesting(null);
        }
        if (rc.get("unknownTransferId").isJsonNull()) throw new IllegalStateException("C16: TesterX's DROP outcome not unknown: " + rc);
        String cId = rc.get("unknownTransferId").getAsString();
        JsonObject park = awaitFlag(slave, flag0, SuspicionChecks.TRANSFER_PARKED, x.account(), WAIT_MS);
        List<Long> loss = refIds(slave, cId);
        if (loss.isEmpty()) throw new IllegalStateException("C16: no ACCOUNT_DELETE_LOSS row with ref " + cId);
        long lost = park.get("amount").getAsLong();
        ledger.addAll(loss);
        transfers.add(cId);
        flagCase(key, cases, flags, "C16-6-flag", park, cFrom, "test hook",
                "user TesterX deleted; THROW_BEFORE_APPLY on the slave: the DROP outcome stayed unknown, logged as lost");
        key.addCase(kase(cases, "C16-transfer-parked-flag", "flag", "transfer").subject(cId).item(money).window(cFrom, now())
                .amount(lost).tolerance(0).transfer(cId, TransferJournal.FROM_MASTER, TransferJournal.DROP, "parked", Map.of(money, lost), Map.of())
                .ledger(loss.toArray()).flags(park.get("id").getAsLong()).transfers(cId).planted("test hook")
                .note("THROW_BEFORE_APPLY: the slave DROP threw before it delivered (no reply, no journal row), status UNKNOWN →"
                        + " ACCOUNT_DELETE_LOSS with this transfer id + TRANSFER_PARKED; with no journal step AT needs it >= 10 min before the copy"));
        return new Step(cases, ledger, flags, transfers, note + "unknown DROP " + cId + ", failed DROP " + bId);
    }

    /**
     * D — transfers master ↔ slave, rule {@code transfer}, subject = transfer id; all on TesterY's diamonds (no money, so
     * the C13 banknote count stays exact; no clean case has TesterY's account as subject). C11 / C15 / C16 already key an
     * ok terminal deposit, converter commit, upload, download and DROP. This step adds:
     * <ul>
     *   <li>ok: a terminal withdraw; a terminal deposit into the frozen account, refused → RETURNED.</li>
     *   <li>known-gap: a raw deposit (no ledger type: the master's RAW_CHANGE row has no id); a craft ingredient
     *       withdraw (CRAFT_CONSUME) the master refuses (frozen), its own lock freed by the craft-failure unlock while
     *       the #6 lock stays (6b, clean look-alike); a DEPOSIT with no block context the master refuses (frozen).</li>
     *   <li>parked: BS-267 #1 download block withdraw with RESTART_AFTER_APPLY (master debits, reply lost → TIMED_OUT,
     *       the block keeps the items); #6 terminal withdraw with THROW_BEFORE_APPLY (TIMED_OUT, lock kept), 6a the
     *       admin "reset locked balance" on the same account + item; #2 craft output deposit with THROW_BEFORE_APPLY
     *       (TIMED_OUT, items gone).</li>
     *   <li>planted (DB edit): lost = a CONFIRMED deposit whose master row is deleted; twice = a raw deposit's
     *       RAW_CHANGE row relabelled DEPOSIT with an earlier deposit's id; created = the same with a fresh id.</li>
     * </ul>
     * Left out: BS-267 #3 (see {@link Step#note()}).
     */
    public static Step transfers(MinecraftServer slave) {
        long from = now();
        FixtureKey key = FixtureKey.current();
        Tester y = ensureTester(slave, CLEAN_SHARES);
        ItemID diamondId = allow(slave, Items.DIAMOND);
        String diamond = diamondId.getName(), acct = String.valueOf(y.account());
        IAsyncBank bank = bank(slave, y.account(), diamondId);
        List<String> cases = new ArrayList<>(), transfers = new ArrayList<>();
        List<Long> ledger = new ArrayList<>(), flags = new ArrayList<>();
        String toM = TransferJournal.TO_MASTER, fromM = TransferJournal.FROM_MASTER, async = TransferJournal.ASYNC_BANK;

        // created (first: with no journal step AT needs it >= 10 min before the copy)
        long cFrom = now(), before = ledgerMax(slave);
        String donorC = rawDeposit(slave, y.account(), bank, 4 * SCALE);
        long rowC = awaitRow(slave, before, y.account(), LedgerType.RAW_CHANGE).get("id").getAsLong();
        String created = TransferJournal.newTransferId();
        relabel(slave, rowC, created);
        transferCase(key, cases, "D-created-flag", created, null, null, "created", diamond, 0, 4 * SCALE, cFrom, List.of(rowC), null,
                "DB edit", "RAW_CHANGE row " + rowC + " of raw deposit " + donorC + " relabelled DEPOSIT with a fresh id no journal has;"
                        + " no journal step, so AT classes it only >= 10 min before the copy (else after-snapshot)");

        // lost
        long lFrom = now();
        String lost = depositOk(slave, y.uuid(), y.account(), new ItemStack(Items.DIAMOND, 10));
        awaitFinal(slave, lost);
        List<Long> lostRows = refIds(slave, lost);
        if (master(slave, "ledgerDelete", obj("ref", lost)).get("deleted").getAsInt() < 1)
            throw new IllegalStateException("D: master row of " + lost + " not deleted");
        transferCase(key, cases, "D-lost-flag", lost, toM, async, "lost", diamond, 10 * SCALE, 0, lFrom, List.of(), null, "DB edit",
                "CONFIRMED terminal deposit of 10 diamonds, its master DEPOSIT row " + lostRows + " deleted (simulates BS-267 #5);"
                        + " also an unkeyed balance gap on TesterY's diamonds");

        // twice
        long tFrom = now();
        String twice = depositOk(slave, y.uuid(), y.account(), new ItemStack(Items.DIAMOND, 6));
        awaitFinal(slave, twice);
        List<Long> twiceRows = new ArrayList<>(refIds(slave, twice));
        before = ledgerMax(slave);
        String donorT = rawDeposit(slave, y.account(), bank, 6 * SCALE);
        long rowT = awaitRow(slave, before, y.account(), LedgerType.RAW_CHANGE).get("id").getAsLong();
        relabel(slave, rowT, twice);
        twiceRows.add(rowT);
        transferCase(key, cases, "D-twice-flag", twice, toM, async, "twice", diamond, 6 * SCALE, 12 * SCALE, tFrom, twiceRows, null,
                "DB edit", "CONFIRMED terminal deposit of 6 diamonds; RAW_CHANGE row " + rowT + " of raw deposit " + donorT
                        + " relabelled DEPOSIT with this id: a second master credit");

        // raw call (known gap)
        long rFrom = now();
        String raw = rawDeposit(slave, y.account(), bank, 2 * SCALE);
        transferCase(key, cases, "D-raw-clean", raw, toM, async, "known-gap", diamond, 2 * SCALE, 0, rFrom, List.of(), null, "test hook",
                "raw bank call (depositAsync, no ledger type): the master's RAW_CHANGE row has no transfer id");

        // ok withdraw
        long wFrom = now();
        String okW = withdraw(slave, y.uuid(), y.account(), diamondId, 2 * SCALE);
        awaitFinal(slave, okW);
        List<Long> okRows = refIds(slave, okW);
        transferCase(key, cases, "D-withdraw-clean", okW, fromM, async, "ok", diamond, 2 * SCALE, 2 * SCALE, wFrom, okRows, null,
                "test hook", "terminal-style withdraw of 2 diamonds, DELIVERED");

        // BS-267 #6: never applied, lock kept
        long sixFrom = now(), flag0 = flagMax(slave);
        if (await(slave, bank.lockAmountAsync(5 * SCALE)) != BankStatus.SUCCESS) throw new IllegalStateException("D #6: lock refused");
        Sent six = faulted(slave, "THROW_BEFORE_APPLY", () -> send(slave, LedgerType.WITHDRAW, y.uuid(), "terminal", bank, 5 * SCALE, false));
        AdminAudit.parkTransfer(y.account(), diamondId, 5 * SCALE, six.transferId(), y.uuid(), "terminal withdraw", six.future().exceptionNow());
        expectStep(slave, six.transferId(), TransferJournal.TIMED_OUT);
        JsonObject park6 = awaitFlag(slave, flag0, SuspicionChecks.TRANSFER_PARKED, y.account(), WAIT_MS);
        transferCase(key, cases, "D-6-transfer-parked-flag", six.transferId(), fromM, async, "parked", diamond, 0, 0, sixFrom, List.of(),
                park6.get("id").getAsLong(), "test hook", "BS-267 #6: terminal withdraw of 5 diamonds, THROW_BEFORE_APPLY on the master:"
                        + " never applied, status UNKNOWN → TIMED_OUT; the items are out (terminal-style: none handed out here), the 5 stay locked");

        // 6b + refused deposits: account frozen (console /banksystem freeze)
        long lockedBefore = await(slave, bank.getLockedBalanceAsync());
        if (lockedBefore < 5 * SCALE) throw new IllegalStateException("D #6: lock not kept: " + lockedBefore);
        if (await(slave, bank.lockAmountAsync(2 * SCALE)) != BankStatus.SUCCESS) throw new IllegalStateException("D 6b: craft lock refused");
        master(slave, "command", obj("cmd", "banksystem freeze " + y.account() + " on"));
        try {
            long craftFrom = now();
            Sent craft = send(slave, LedgerType.CRAFT_CONSUME, y.uuid(), "terminal", bank, 2 * SCALE, false);
            expectStatus(craft, BankStatus.FAILED_ACCOUNT_FROZEN, "D 6b craft withdraw");
            Long freed = await(slave, BankTerminalContainerMenu.releaseOwnCraftLock(bank, 2 * SCALE));
            for (int i = 0; await(slave, bank.getLockedBalanceAsync()) != lockedBefore; i++) {
                if (i >= 50) throw new IllegalStateException("D 6b: locked not back to " + lockedBefore + " (freed " + freed + ")");
                pump(slave, 100);
            }
            transferCase(key, cases, "D-6b-craft-clean", craft.transferId(), fromM, async, "known-gap", diamond, 0, 0, craftFrom, List.of(), null,
                    "test hook", "un-debited craft (CRAFT_CONSUME refused by the master, account frozen); the craft-failure unlock freed only"
                            + " its own 2 diamonds (no ledger row), the #6 lock of 5 stays. Only REQUESTED: known-gap >= 10 min before the copy"
                            + " (else ok); clean either way");

            long retFrom = now();
            Sent ret = deposit(slave, y.uuid(), y.account(), new ItemStack(Items.DIAMOND, 4));
            expectStatus(ret, BankStatus.FAILED_ACCOUNT_FROZEN, "D RETURNED deposit");
            expectStep(slave, ret.transferId(), TransferJournal.RETURNED);
            transferCase(key, cases, "D-returned-clean", ret.transferId(), toM, async, "ok", diamond, 0, 0, retFrom, List.of(), null,
                    "test hook", "terminal deposit of 4 diamonds refused by the master (account frozen), items back in the terminal: RETURNED");

            long outFrom = now();
            Sent out = send(slave, LedgerType.DEPOSIT, y.uuid(), null, bank, 3 * SCALE, true);
            expectStatus(out, BankStatus.FAILED_ACCOUNT_FROZEN, "D outside deposit");
            transferCase(key, cases, "D-outside-clean", out.transferId(), toM, async, "known-gap", diamond, 3 * SCALE, 0, outFrom, List.of(),
                    null, "test hook", "DEPOSIT of 3 diamonds with no block context, refused by the master (account frozen); only"
                            + " REQUESTED: known-gap >= 10 min before the copy (else after-snapshot); clean either way");
            transfers.addAll(List.of(craft.transferId(), ret.transferId(), out.transferId()));
        } finally {
            master(slave, "command", obj("cmd", "banksystem freeze " + y.account() + " off"));
        }

        // 6a: admin reset locked balance on the same account + item
        long unlockFrom = now();
        before = ledgerMax(slave);
        master(slave, "unlock", obj("account", y.account(), "item", diamondId.getShort(), "lock", 0));
        JsonObject unlock = row(ledgerAfter(slave, before), y.account(), LedgerType.ADMIN_UNLOCK);
        long unlockId = unlock.get("id").getAsLong();
        key.addCase(kase(cases, "D-6a-ADMIN_UNLOCK-flag", "flag", "admin:ADMIN_UNLOCK").subject(acct).item(diamond)
                .window(unlockFrom, now()).amount(unlock.get("amount").getAsLong()).tolerance(0).ledger(unlockId).planted("test hook")
                .note("BS-267 6a: admin reset locked balance (UpdateBankAccountRequest as TesterAdmin) of the lock #6 left; an admin event, not a finding"));

        // BS-267 #1: master debits, then a simulated restart loses the reply
        long oneFrom = now();
        flag0 = flagMax(slave);
        before = ledgerMax(slave);
        master(slave, "armFault", obj("fault", "RESTART_AFTER_APPLY"));
        download(slave, y, diamondId, 3);
        String one = awaitRow(slave, before, y.account(), LedgerType.DOWNLOAD_WITHDRAW).get("ref").getAsString();
        if (!TransferJournal.TIMED_OUT.equals(awaitFinal(slave, one))) {
            master(slave, "armFault", obj("fault", "NONE"));
            throw new IllegalStateException("D #1: download " + one + " did not end TIMED_OUT");
        }
        JsonObject park1 = awaitFlag(slave, flag0, SuspicionChecks.TRANSFER_PARKED, y.account(), WAIT_MS);
        List<Long> oneRows = refIds(slave, one);
        transferCase(key, cases, "D-1-transfer-parked-flag", one, fromM, async, "parked", diamond, 3 * SCALE, 0, oneFrom, oneRows,
                park1.get("id").getAsLong(), "test hook", "BS-267 #1: download block withdraw of 3 diamonds, RESTART_AFTER_APPLY: the"
                        + " master debited (DOWNLOAD_WITHDRAW), simulated restart, reply lost → TIMED_OUT; the block keeps the items"
                        + " (received = the journal's view: no DELIVERED step)");

        // BS-267 #2: craft output deposit, outcome unknown, items gone
        long twoFrom = now();
        flag0 = flagMax(slave);
        Sent two = faulted(slave, "THROW_BEFORE_APPLY", () -> send(slave, LedgerType.CRAFT_OUTPUT, y.uuid(), "terminal", bank, 2 * SCALE, true));
        AdminAudit.parkTransfer(y.account(), diamondId, 2 * SCALE, two.transferId(), y.uuid(), "craft output deposit", two.future().exceptionNow());
        expectStep(slave, two.transferId(), TransferJournal.TIMED_OUT);
        JsonObject park2 = awaitFlag(slave, flag0, SuspicionChecks.TRANSFER_PARKED, y.account(), WAIT_MS);
        transferCase(key, cases, "D-2-transfer-parked-flag", two.transferId(), toM, async, "parked", diamond, 2 * SCALE, 0, twoFrom, List.of(),
                park2.get("id").getAsLong(), "test hook", "BS-267 #2: craft output deposit of 2 diamonds, THROW_BEFORE_APPLY on the master:"
                        + " not credited, status UNKNOWN → TIMED_OUT; the output stays gone");

        transfers.addAll(List.of(created, lost, twice, raw, okW, six.transferId(), one, two.transferId(), donorC, donorT));
        ledger.addAll(List.of(rowC, rowT, unlockId));
        ledger.addAll(twiceRows.subList(0, twiceRows.size() - 1));
        ledger.addAll(okRows);
        ledger.addAll(oneRows);
        flags.addAll(List.of(park6.get("id").getAsLong(), park1.get("id").getAsLong(), park2.get("id").getAsLong()));
        return new Step(cases, ledger, flags, transfers, "left out: BS-267 #3 (drop refund after the account was deleted: needs the"
                + " master's WithdrawMoneyPacket DROP refund path, no fixture op reaches it); no journal step, AT classes them only"
                + " >= 10 min before the copy: D-created-flag (else after-snapshot = no finding), D-6b-craft-clean, D-outside-clean;"
                + " window " + from + ".." + now());
    }

    /**
     * E, bank part (StockMarket keys the market part): /money add $3M funds TesterA (unkeyed; with StockMarket A also has
     * its market profit). A withdraws $70,000 as 7 x $10k notes, TesterB pays exactly those in (key link A → B
     * likely/notes; $70k matches no other withdrawal or run), A transfers $250,000 to TesterC's account (link A – C
     * proven/transfer, below the new-account flag). Snapshot + sweep: A's WEALTH_JUMP (bank-flag:3, only with a price
     * provider). Then an admin /money remove of $500,000 on A (admin:ADMIN_REMOVE).
     */
    public static Step exploitBank(MinecraftServer slave) {
        long from = now();
        FixtureKey key = FixtureKey.current();
        Tester a = ensureTester(slave, "TesterA"), b = ensureTester(slave, "TesterB"), c = ensureTester(slave, "TesterC");
        List<String> cases = new ArrayList<>();
        List<Long> ledger = new ArrayList<>(), flags = new ArrayList<>();
        ItemID money = MoneyItem.getItemID();
        Item k10 = BankSystemItems.MONEY10000.get();

        money(slave, "add", a.name(), 3_000_000);
        String out = withdraw(slave, a.uuid(), a.account(), money, 7 * ((MoneyItem) k10).worth());
        String in = depositOk(slave, b.uuid(), b.account(), new ItemStack(k10, 7));
        link(key, a.uuid().toString(), b.uuid().toString(), "likely", "notes");
        long toC = transferMoney(slave, a, c.account(), 250_000 * SCALE);
        link(key, a.uuid().toString(), c.uuid().toString(), "proven", "transfer");
        ledger.addAll(refIds(slave, out));
        ledger.addAll(refIds(slave, in));
        ledger.add(toC);

        snapshot(slave);
        boolean wealth = master(slave, "sweep", obj()).get("wealth").getAsBoolean();
        // after 0: the 10-min timer sweep (or StockMarket's profit) may have flagged A before this step
        JsonObject jump = wealth ? awaitFlag(slave, 0, SuspicionChecks.WEALTH_JUMP, a.account(), WAIT_MS) : null;

        long removeFrom = now(), before = ledgerMax(slave);
        money(slave, "remove", a.name(), 500_000);
        JsonObject remove = row(ledgerAfter(slave, before), a.account(), LedgerType.ADMIN_REMOVE);
        long removeId = remove.get("id").getAsLong();
        ledger.add(removeId);
        key.addCase(kase(cases, "E-ADMIN_REMOVE-flag", "flag", "admin:ADMIN_REMOVE").subject(String.valueOf(a.account()))
                .item(money.getName()).window(removeFrom, now()).amount(remove.get("amount").getAsLong()).tolerance(0).ledger(removeId)
                .planted("command").note("admin /money remove $500,000 on TesterA (handler code as admin TesterAdmin) before the copy;"
                        + " an admin event, not a finding"));
        if (jump != null)
            flagCase(key, cases, flags, "E-3-flag", jump, Math.min(from, jump.get("ts").getAsLong()), "command",
                    "TesterA's wealth jumped: /money add $3M (plus the market profit with StockMarket); snapshot + sweep");
        return new Step(cases, ledger, flags, List.of(out, in), (wealth ? "" : WEALTH_SKIPPED + "; ")
                + "notes $70,000 A → B (" + out + " → " + in + "), $250,000 A → C; window " + from + ".." + now());
    }

    /**
     * C12 — bank saves suppressed (after a {@code save-all flush}), then 3 terminal deposits of iron by TesterD: their
     * ledger rows are behind bank_last_save_ms. Leaves the suppression ON (the export must see it); turn it off with
     * {@code suppressSave {"on":false}}.
     */
    public static Step lastSave(MinecraftServer slave) {
        long from = now();
        Tester d = ensureTester(slave, "TesterD");
        List<String> cases = new ArrayList<>(), transfers = new ArrayList<>();
        List<Long> ledger = new ArrayList<>();
        master(slave, "suppressSave", obj("on", true));
        for (int n = 2; n <= 4; n++) transfers.add(depositOk(slave, d.uuid(), d.account(), new ItemStack(Items.IRON_INGOT, n)));
        master(slave, "barrier", obj());
        for (String t : transfers) ledger.addAll(refIds(slave, t));
        FixtureKey.current().addCase(kase(cases, "C12-flag", "flag", "last-save").window(from, now())
                .ledger(ledger.toArray()).transfers(transfers.toArray()).planted("test hook")
                .note("simulated stop without final save (bank save suppressed)"));
        return new Step(cases, ledger, List.of(), transfers, "bank save suppression left ON");
    }

    /** Case id of the D after-snapshot transfer {@link #finish} keys. */
    public static final String AFTER_SNAPSHOT = "D-after-snapshot-clean";

    /**
     * The copy moment, the last step (after {@link #lastSave}): master export {@code fixture} + key balances (master,
     * as exported) → master quiet → {@code beforeSlaveExport} (StockMarket: its quiet part and its last cases; null =
     * none) → D after-snapshot: a terminal deposit of 1 diamond by TesterY sent while {@link SimulatedMasterHang} drops
     * it, so the slave journal has only REQUESTED at the export (a later timeout ends NOT_APPLIED, which writes no
     * step) → slave {@code saveEverything} (world + player inventories; bank saves stay suppressed from lastSave) →
     * {@link #banknotesAtCopy} → slave export {@code fixture} → key servers (paths relative to the fixture root:
     * {@code master/…}, {@code slave/…}) → {@code <slave run dir>/fixture/answer-key.json}. Server thread.
     *
     * @return the written key file
     */
    public static Path finish(MinecraftServer slave, @Nullable Runnable beforeSlaveExport) {
        FixtureKey key = FixtureKey.current();
        JsonObject mx = JsonParser.parseString(await(slave, FixtureStepRequest.call("export", obj("name", "fixture").toString()),
                FixtureStepRequest.INSTANCE.getTimeoutMs())).getAsJsonObject();
        for (JsonElement e : master(slave, "balances", obj()).getAsJsonArray("balances")) {
            JsonObject b = e.getAsJsonObject();
            key.addBalance(b.get("account").getAsInt(), b.get("item").getAsString(), b.get("raw").getAsLong());
        }
        master(slave, "quiet", obj());
        if (beforeSlaveExport != null) beforeSlaveExport.run();

        String tid = afterSnapshot(slave, key);
        AuditExporter.Result sx = await(slave, onSlave(slave, () -> {
            slave.saveEverything(true, true, true);
            banknotesAtCopy(slave);
            return AuditExporter.export(slave, "fixture");
        }), (AuditExporter.DB_TIMEOUT_MINUTES + 1) * 60_000L);
        for (Map<String, Object> r : journal(slave, "SELECT step FROM TransferJournal WHERE transfer_id = ?", tid))
            if (FINAL.contains(String.valueOf(r.get("step"))))
                throw new IllegalStateException("after-snapshot transfer " + tid + " reached " + r.get("step") + " before the slave export");

        Path runDir = slave.getServerDirectory().toAbsolutePath().normalize();
        IServerIdentity id = MultiServerManager.getIdentity();
        key.addServer("master", mx.get("uid").isJsonNull() ? null : mx.get("uid").getAsString(), "master/" + mx.get("world").getAsString(),
                "master/" + mx.get("folder").getAsString());
        key.addServer("slave", id == null ? null : id.serverUid().toString(), "slave/" + rel(runDir, slave.getWorldPath(LevelResource.ROOT)),
                "slave/" + rel(runDir, sx.folder()));
        try {
            return key.write(runDir);
        } catch (IOException e) {
            throw new IllegalStateException("answer key not written: " + e, e);
        }
    }

    /** D after-snapshot: see {@link #finish}. @return its transfer id */
    private static String afterSnapshot(MinecraftServer slave, FixtureKey key) {
        if (!ModUtilitiesMod.ENABLE_DEV_FEATURES) throw new IllegalStateException("after-snapshot needs SimulatedMasterHang (MU dev features)");
        long from = now();
        Tester y = ensureTester(slave, CLEAN_SHARES);
        ItemID diamond = allow(slave, Items.DIAMOND);
        IAsyncBank bank = bank(slave, y.account(), diamond);
        TransferJournal.Ref journal = new TransferJournal.Ref(TransferJournal.newTransferId(), "terminal");
        CompletableFuture<BankStatus> f;
        SimulatedMasterHang.set(true);
        try {
            f = TransactionLedger.tagged(LedgerType.DEPOSIT, y.uuid(), null, journal, () -> bank.depositAsync(SCALE));
        } finally {
            SimulatedMasterHang.set(false);
        }
        String tid = journal.transferId();
        List<Map<String, Object>> steps = journal(slave, "SELECT step FROM TransferJournal WHERE transfer_id = ?", tid);
        if (f.isDone() || steps.size() != 1 || !TransferJournal.REQUESTED.equals(steps.get(0).get("step")))
            throw new IllegalStateException("after-snapshot deposit " + tid + " not in flight: " + (f.isDone() ? f : steps));
        transferCase(key, new ArrayList<>(), AFTER_SNAPSHOT, tid, TransferJournal.TO_MASTER, TransferJournal.ASYNC_BANK, "after-snapshot",
                diamond.getName(), SCALE, 0, from, List.of(), null, "test hook", "terminal deposit of 1 diamond by TesterY sent right"
                        + " before the slave export while the master link drops it (simulated master hang): only REQUESTED, no master row");
        return tid;
    }

    private static String rel(Path runDir, Path p) {
        return runDir.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    // ---- bank ops (slave) --------------------------------------------------------------------------------------

    private record Sent(String transferId, CompletableFuture<BankStatus> future) {}

    /** A terminal deposit of {@code stack} by {@code player} into {@code account}, waited for its final step. */
    private static Sent deposit(MinecraftServer s, UUID player, int account, ItemStack stack) {
        ItemID item = await(s, ItemIDManager.registerItemStackServerSide(stack));
        if (item == null || !item.isValid()) throw new IllegalStateException("no ItemID for " + stack);
        boolean isMoney = stack.getItem() instanceof MoneyItem;
        IAsyncBank bank = bank(s, account, isMoney ? MoneyItem.getItemID() : item);
        long unit = isMoney ? ((MoneyItem) stack.getItem()).worth() : SCALE;
        ItemID bankItem = bank.getItemIDAsync();
        BankTerminalBlockEntity terminal = new BankTerminalBlockEntity(BlockPos.ZERO, BankSystemBlocks.BANK_TERMINAL_BLOCK.get().defaultBlockState());
        BankTerminalBlockEntity.TerminalInventory inventory = new BankTerminalBlockEntity.TerminalInventory(terminal, 9);
        inventory.setStackInSlot(0, stack.copy());
        TransferJournal.Ref journal = TransferJournal.ref("terminal");
        List<CompletableFuture<BankStatus>> sent = new ArrayList<>();
        terminal.depositReserved(inventory, player, item, stack.getCount(), journal, n -> {
                    CompletableFuture<BankStatus> f = TransactionLedger.tagged(LedgerType.DEPOSIT, player,
                            isMoney ? SuspicionChecks.BANKNOTE_NOTE + item.getName() + " x" + n : null, journal, () -> bank.depositAsync(n * unit));
                    sent.add(f);
                    return f;
                }, n -> {},
                (ex, n) -> AdminAudit.parkTransfer(account, bankItem, n * unit, journal.transferId(), player, "terminal deposit", ex));
        if (sent.size() != 1) throw new IllegalStateException("terminal deposit sent " + sent.size() + "x");
        settle(s, sent.get(0));
        return new Sent(journal.transferId(), sent.get(0));
    }

    private static String depositOk(MinecraftServer s, UUID player, int account, ItemStack stack) {
        Sent d = deposit(s, player, account, stack);
        BankStatus status = d.future().isCompletedExceptionally() ? null : d.future().join();
        if (status != BankStatus.SUCCESS) throw new IllegalStateException("deposit of " + stack + " into " + account + ": " + status);
        return d.transferId();
    }

    /** Terminal-style withdraw: lock, then withdrawLockedPrefered tagged WITHDRAW. @return the transfer id */
    private static String withdraw(MinecraftServer s, UUID player, int account, ItemID item, long raw) {
        IAsyncBank bank = bank(s, account, item);
        BankStatus lock = await(s, bank.lockAmountAsync(raw));
        if (lock != BankStatus.SUCCESS) throw new IllegalStateException("lock of " + raw + " " + item.getName() + " on " + account + ": " + lock);
        TransferJournal.Ref journal = TransferJournal.ref("terminal");
        CompletableFuture<BankStatus> f = TransactionLedger.tagged(LedgerType.WITHDRAW, player, null, journal,
                () -> bank.withdrawLockedPreferedAsync(raw));
        settle(s, f);
        BankStatus status = f.isCompletedExceptionally() ? null : f.join();
        if (status != BankStatus.SUCCESS) throw new IllegalStateException("withdraw of " + raw + " " + item.getName() + " from " + account + ": " + status);
        return journal.transferId();
    }

    /**
     * One {@code type}-tagged deposit / withdraw (withdrawLockedPrefered) of {@code raw} on {@code bank}, settled; journal
     * context {@code context} (null = not from a bank block).
     */
    private static Sent send(MinecraftServer s, LedgerType type, UUID player, @Nullable String context, IAsyncBank bank, long raw,
                             boolean deposit) {
        TransferJournal.Ref journal = new TransferJournal.Ref(TransferJournal.newTransferId(), context);
        CompletableFuture<BankStatus> f = TransactionLedger.tagged(type, player, null, journal,
                () -> deposit ? bank.depositAsync(raw) : bank.withdrawLockedPreferedAsync(raw));
        settle(s, f);
        return new Sent(journal.transferId(), f);
    }

    /** Arms {@code fault} on the master, runs {@code op}; its outcome must stay unknown (else disarms and throws). */
    private static Sent faulted(MinecraftServer s, String fault, Supplier<Sent> op) {
        master(s, "armFault", obj("fault", fault));
        Sent r = op.get();
        if (!r.future().isCompletedExceptionally()) {
            master(s, "armFault", obj("fault", "NONE"));
            throw new IllegalStateException(fault + ": transfer " + r.transferId() + " ended " + r.future().join() + ", not unknown");
        }
        return r;
    }

    private static void expectStatus(Sent r, BankStatus want, String what) {
        BankStatus got = r.future().isCompletedExceptionally() ? null : r.future().join();
        if (got != want) throw new IllegalStateException(what + ": " + got + ", expected " + want);
    }

    /**
     * A raw slave deposit (untagged: no ledger type), as another mod's bank call: the master writes a RAW_CHANGE row
     * without the id. @return its transfer id (slave journal), after its final step
     */
    private static String rawDeposit(MinecraftServer s, int account, IAsyncBank bank, long raw) {
        long since = now();
        CompletableFuture<BankStatus> f = bank.depositAsync(raw);
        settle(s, f);
        if (f.isCompletedExceptionally() || f.join() != BankStatus.SUCCESS)
            throw new IllegalStateException("raw deposit of " + raw + " into " + account + ": " + f);
        List<Map<String, Object>> rows = journal(s, "SELECT transfer_id FROM TransferJournal WHERE step = ? AND account_number = ?"
                + " AND ledger_type IS NULL AND ts >= ? ORDER BY id DESC", TransferJournal.REQUESTED, account, since);
        if (rows.isEmpty()) throw new IllegalStateException("no journal row for the raw deposit into " + account);
        String tid = (String) rows.get(0).get("transfer_id");
        awaitFinal(s, tid);
        return tid;
    }

    /** DB edit: master ledger row {@code id} becomes a DEPOSIT with ref {@code transferId} (note cleared). */
    private static void relabel(MinecraftServer s, long id, String transferId) {
        if (master(s, "ledgerEdit", obj("id", id, "set", obj("type", "DEPOSIT", "ref", transferId, "note", JsonNull.INSTANCE)))
                .get("updated").getAsInt() != 1) throw new IllegalStateException("ledger row " + id + " not relabelled");
    }

    /** A detached download block of {@code t} that withdraws {@code count} of {@code item} now (like the block tests). */
    private static void download(MinecraftServer s, Tester t, ItemID item, int count) {
        BankDownloadBlockEntity down = new BankDownloadBlockEntity(BlockPos.ZERO, BankSystemBlocks.BANK_DOWNLOAD_BLOCK.get().defaultBlockState());
        CompoundTag dt = new CompoundTag(); // BankDownloadBlockEntity.TAGS: A account, P powered, U owner
        dt.putInt("A", t.account());
        dt.putBoolean("P", true);
        NbtCompat.putUUID(dt, "U", t.uuid());
        down.loadAdditional(dt, s.registryAccess()); // mc<=1.21.5
        //down.loadAdditional(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, s.registryAccess(), dt)); // mc>=1.21.6
        down.getWithdrawOrders().add(new BankDownloadBlockEntity.WithdrawOrder(item, count, BankDownloadBlockEntity.WithdrawCondition.NONE, 0));
        down.update();
    }

    /** Money transfer by {@code from}. @return the id of the incoming TRANSFER row on {@code toAccount} */
    private static long transferMoney(MinecraftServer s, Tester from, int toAccount, long raw) {
        IAsyncBank bank = bank(s, from.account(), MoneyItem.getItemID());
        long before = ledgerMax(s);
        BankStatus status = await(s, TransactionLedger.tagged(LedgerType.TRANSFER, from.uuid(), null, () -> bank.transferAsync(raw, toAccount)));
        if (status != BankStatus.SUCCESS) throw new IllegalStateException("transfer of " + raw + " to " + toAccount + ": " + status);
        return row(ledgerAfter(s, before), toAccount, LedgerType.TRANSFER).get("id").getAsLong();
    }

    /**
     * Waits for the outcome, asking the master for the status once a second like the tick does ({@link #pumpUntil} runs
     * the request timeouts). SETTLE_MS covers a lost reply: 30 s request timeout + the status answer.
     */
    private static void settle(MinecraftServer s, CompletableFuture<?> f) {
        if (!pumpUntil(s, f::isDone, SETTLE_MS)) throw new IllegalStateException("transfer outcome still pending after " + SETTLE_MS + " ms");
    }

    private static IAsyncBank bank(MinecraftServer s, int account, ItemID item) {
        IAsyncBankAccount acc = await(s, manager().getBankAccountAsync(account));
        IAsyncBank bank = acc == null ? null : await(s, acc.getOrCreateBankAsync(item));
        if (bank == null) throw new IllegalStateException("no " + item.getName() + " bank on account " + account);
        return bank;
    }

    private static ItemID allow(MinecraftServer s, Item item) {
        ItemID id = await(s, ItemIDManager.registerItemStackServerSide(new ItemStack(item)));
        if (id == null || !id.isValid()) throw new IllegalStateException("no ItemID for " + item);
        if (!Boolean.TRUE.equals(await(s, manager().isItemIDAllowedAsync(id)))) await(s, manager().allowItemIDAsync(id));
        return id;
    }

    private static AsyncCompanyManager.CompanyInfoOutput company(MinecraftServer s, String name) {
        AsyncCompanyManager.CompanyInfoOutput info = await(s, AsyncCompanyManager.getCompanyInfoAsync(name));
        if (info == null || !info.present()) throw new IllegalStateException("company " + name + " missing (run background first)");
        return info;
    }

    /** Company {@code name} founded by {@code founder} (max supply 1,000); creates it when missing. */
    private static AsyncCompanyManager.CompanyInfoOutput ensureCompany(MinecraftServer s, String name, Tester founder) {
        AsyncCompanyManager.CompanyInfoOutput info = await(s, AsyncCompanyManager.getCompanyInfoAsync(name));
        if (info != null && info.present()) return info;
        AsyncCompanyManager.CreateOutput out = await(s, AsyncCompanyManager.createCompanyAsync(name, 1_000L, founder.uuid(), founder.name()));
        if (out == null || out.resultCode() != AsyncCompanyManager.CODE_OK)
            throw new IllegalStateException("company " + name + " not created: " + (out == null ? "no answer" : "code " + out.resultCode()));
        return company(s, name);
    }

    /** One stamp (or redeem) through the slave stamper request. @return its transfer id (the master rows' ref) */
    private static String stamp(MinecraftServer s, int companyId, boolean redeem, UUID actor) {
        String tid = TransferJournal.newTransferId();
        Integer code = await(s, AsyncCompanyManager.stamperApplyAsync(companyId, redeem, actor, tid, now(), true));
        if (code == null || code != AsyncCompanyManager.CODE_OK)
            throw new IllegalStateException((redeem ? "redeem" : "stamp") + " refused: " + code);
        return tid;
    }

    /** The client player on the slave (TesterZ in the fixture run), or null when none is online. */
    public static @Nullable ServerPlayer client(MinecraftServer slave) {
        List<ServerPlayer> players = slave.getPlayerList().getPlayers();
        return players.isEmpty() ? null : players.get(0);
    }

    /** Raw money in {@code c}: every banknote stack, count x worth. */
    private static long moneyIn(Container c) {
        long raw = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack st = c.getItem(i);
            if (st.getItem() instanceof MoneyItem m) raw += st.getCount() * m.worth();
        }
        return raw;
    }

    /** Runs {@code body} on the slave's server thread (world and inventory edits). */
    private static <T> T onSlave(MinecraftServer s, Supplier<T> body) {
        return s.isSameThread() ? body.get() : await(s, CompletableFuture.supplyAsync(body, s));
    }

    private static IAsyncBankManager manager() {
        return BankSystemModBackend.getInstances_forTesting().SERVER_BANK_MANAGER.getAsync();
    }

    // ---- master ops --------------------------------------------------------------------------------------------

    private static JsonObject master(MinecraftServer s, String step, JsonObject args) {
        return JsonParser.parseString(await(s, FixtureStepRequest.call(step, args.toString()), MASTER_MS)).getAsJsonObject();
    }

    private static void snapshot(MinecraftServer s) {
        master(s, "snapshot", obj());
    }

    private static void money(MinecraftServer s, String action, String user, long dollars) {
        if (!master(s, "money", obj("action", action, "user", user, "amount", dollars)).get("ok").getAsBoolean())
            throw new IllegalStateException("money " + action + " " + user + " " + dollars + " refused");
    }

    private static long ledgerMax(MinecraftServer s) {
        return master(s, "ledgerAfter", obj("id", -1)).get("max").getAsLong();
    }

    private static JsonArray ledgerAfter(MinecraftServer s, long id) {
        return master(s, "ledgerAfter", obj("id", id)).getAsJsonArray("rows");
    }

    /** Ledger row ids with ref = {@code transferId} (the master's row of a slave transfer). */
    private static List<Long> refIds(MinecraftServer s, String transferId) {
        List<Long> ids = new ArrayList<>();
        for (JsonElement e : master(s, "ledgerRows", obj("ref", transferId)).getAsJsonArray("rows"))
            ids.add(e.getAsJsonObject().get("id").getAsLong());
        return ids;
    }

    /** The newest {@code kind} row on {@code account} after ledger id {@code after}, polled for {@link #WAIT_MS}. */
    private static JsonObject awaitRow(MinecraftServer s, long after, int account, LedgerType kind) {
        long end = now() + WAIT_MS;
        while (true) {
            JsonObject r = find(ledgerAfter(s, after), account, kind);
            if (r != null) return r;
            if (now() > end) throw new IllegalStateException("no " + kind + " ledger row on account " + account + " within " + WAIT_MS + " ms");
            pump(s, 500);
        }
    }

    // ---- slave journal -----------------------------------------------------------------------------------------

    /** Rows of the slave's diagnostics DB (TransferJournal, NoteFlow) after a flush. */
    private static List<Map<String, Object>> journal(MinecraftServer s, String sql, Object... params) {
        DiagnosticsDatabase db = DiagnosticsDatabase.live();
        if (db == null || db.isDisabled()) throw new IllegalStateException("no live diagnostics DB on the slave");
        await(s, db.flush());
        try {
            return TransferJournalTests.query(db.getFile(), sql, params);
        } catch (SQLException e) {
            throw new IllegalStateException("journal query: " + e.getMessage(), e);
        }
    }

    private static void expectStep(MinecraftServer s, String transferId, String want) {
        String got = awaitFinal(s, transferId);
        if (!want.equals(got)) throw new IllegalStateException("transfer " + transferId + " ended " + got + ", expected " + want);
    }

    /** Waits until the slave journal has a final step for {@code transferId}. @return that step */
    private static String awaitFinal(MinecraftServer s, String transferId) {
        long end = now() + SETTLE_MS;
        while (true) {
            for (Map<String, Object> r : journal(s, "SELECT step FROM TransferJournal WHERE transfer_id = ?", transferId))
                if (FINAL.contains(String.valueOf(r.get("step")))) return String.valueOf(r.get("step"));
            if (now() > end) throw new IllegalStateException("transfer " + transferId + " has no final journal step after " + SETTLE_MS + " ms");
            pump(s, 500);
        }
    }

    private static @Nullable JsonObject find(JsonArray rows, int account, LedgerType kind) {
        JsonObject hit = null;
        for (JsonElement e : rows) {
            JsonObject r = e.getAsJsonObject();
            if (r.get("account_number").getAsInt() == account && kind.name().equals(r.get("kind").getAsString())) hit = r;
        }
        return hit;
    }

    private static JsonObject row(JsonArray rows, int account, LedgerType kind) {
        JsonObject r = find(rows, account, kind);
        if (r == null) throw new IllegalStateException("no " + kind + " ledger row on account " + account);
        return r;
    }

    private static long flagMax(MinecraftServer s) {
        return master(s, "flags", obj("after", -1)).get("max").getAsLong();
    }

    private static @Nullable JsonObject findFlag(MinecraftServer s, long after, int type, int account) {
        for (JsonElement e : master(s, "flags", obj("after", after)).getAsJsonArray("flags")) {
            JsonObject f = e.getAsJsonObject();
            if (f.get("flag_type").getAsInt() == type && f.get("account_number").getAsInt() == account) return f;
        }
        return null;
    }

    private static JsonObject awaitFlag(MinecraftServer s, long after, int type, int account, long timeoutMs) {
        long end = now() + timeoutMs;
        while (true) {
            JsonObject f = findFlag(s, after, type, account);
            if (f != null) return f;
            if (now() > end)
                throw new IllegalStateException("no " + SuspicionChecks.typeName(type) + " flag on account " + account + " within " + timeoutMs + " ms");
            pump(s, 1_000);
        }
    }

    // ---- key ---------------------------------------------------------------------------------------------------

    /** AT rule id: the numeric flag_type, e.g. {@code bank-flag:6} for TRANSFER_PARKED. */
    private static String rule(int flagType) {
        return "bank-flag:" + flagType;
    }

    private static Case kase(List<String> ids, String id, String expect, String rule) {
        ids.add(id);
        return Case.of(id, expect, rule);
    }

    /**
     * A bank-flag case from a SuspicionFlag row: subject = its account, amount = its amount, records.flags = its id (no
     * ledger: key records must be a subset of the finding's), window up to now.
     */
    private static void flagCase(FixtureKey key, List<String> ids, List<Long> flagIds, String id, JsonObject flag, long from,
                                 String planted, String note) {
        long fid = flag.get("id").getAsLong();
        flagIds.add(fid);
        key.addCase(kase(ids, id, "flag", rule(flag.get("flag_type").getAsInt())).subject(String.valueOf(flag.get("account_number").getAsInt()))
                .window(from, Math.max(now(), flag.get("ts").getAsLong())).amount(flag.get("amount").getAsLong()).tolerance(0)
                .flags(fid).planted(planted).note(note));
    }

    /**
     * A {@code transfer} case: expect flag for lost / twice / created / parked, clean otherwise; amount |sent − received|;
     * zero amounts are left out of the sent / received maps. Window from {@code from} to now.
     */
    private static void transferCase(FixtureKey key, List<String> ids, String id, String tid, @Nullable String direction,
                                     @Nullable String path, String cls, String item, long sent, long received, long from,
                                     List<Long> rows, @Nullable Long flag, String planted, String note) {
        Case c = kase(ids, id, Set.of("lost", "twice", "created", "parked").contains(cls) ? "flag" : "clean", "transfer").subject(tid)
                .item(item).window(from, now()).amount(Math.abs(sent - received)).tolerance(0)
                .transfer(tid, direction, path, cls, sent == 0 ? Map.of() : Map.of(item, sent), received == 0 ? Map.of() : Map.of(item, received))
                .ledger(rows.toArray()).transfers(tid).planted(planted).note(note);
        if (flag != null) c.flags(flag);
        key.addCase(c);
    }

    /** Adds the link unless the key has it already, either way round (another step or StockMarket may have). */
    private static void link(FixtureKey key, String a, String b, String strength, String way) {
        synchronized (key) {
            for (JsonElement e : key.links) {
                JsonObject l = e.getAsJsonObject();
                String la = l.get("a").getAsString(), lb = l.get("b").getAsString();
                if (way.equals(l.get("way").getAsString()) && strength.equals(l.get("strength").getAsString())
                        && (la.equals(a) && lb.equals(b) || !way.equals("notes") && la.equals(b) && lb.equals(a))) return;
            }
            key.addLink(a, b, strength, way);
        }
    }

    // ---- waiting -----------------------------------------------------------------------------------------------

    private static <T> T await(MinecraftServer s, CompletableFuture<T> f) {
        return await(s, f, WAIT_MS);
    }

    /** Like {@code TestSuite.await}: on the server thread it runs queued server tasks while waiting. */
    private static <T> T await(MinecraftServer s, CompletableFuture<T> f, long timeoutMs) {
        if (!pumpUntil(s, f::isDone, timeoutMs)) throw new IllegalStateException("timed out after " + timeoutMs + " ms");
        return f.join();
    }

    /**
     * On the server thread no tick runs while we block, so this runs ModUtilities' request-timeout sweep itself: 20 ARRS
     * ticks per second (it sweeps every 20 ticks). Without it a request with no reply never fails. It also ticks the
     * player connections once a second so keep-alives go out; otherwise the client times out after 30 s. Tests run from a
     * server.execute task (AutoTest), never inside the connection tick, so this does not re-enter it. It also runs the
     * transfer-status retry like the tick does. The sweep clock is static: loops of short pumps must still sweep.
     */
    private static long lastSweep;

    public static boolean pumpUntil(MinecraftServer s, BooleanSupplier done, long timeoutMs) {
        long end = System.nanoTime() + timeoutMs * 1_000_000L;
        if (s.isSameThread()) s.managedBlock(() -> {
            long t = System.nanoTime();
            if (t - lastSweep >= 1_000_000_000L) {
                lastSweep = t;
                for (int i = 0; i < 20; i++) AsynchronousRequestResponseSystem.tick(false);
                TransferStatusRequest.retryTick(System.currentTimeMillis());
                if (s.getConnection() != null) s.getConnection().tick();
            }
            return done.getAsBoolean() || t > end;
        });
        else while (!done.getAsBoolean() && System.nanoTime() < end) LockSupport.parkNanos(10_000_000L);
        return done.getAsBoolean();
    }

    private static void pump(MinecraftServer s, long ms) {
        pumpUntil(s, () -> false, ms);
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    /** {@code obj("k", v, …)}: numbers, strings, booleans and nested JsonElements. */
    private static JsonObject obj(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            String k = (String) kv[i];
            Object v = kv[i + 1];
            if (v instanceof JsonElement j) o.add(k, j);
            else if (v instanceof Number n) o.addProperty(k, n);
            else if (v instanceof Boolean b) o.addProperty(k, b);
            else o.addProperty(k, String.valueOf(v));
        }
        return o;
    }
}
