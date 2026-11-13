package net.kroia.banksystem.testing.fixture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import net.kroia.banksystem.data.TransferJournal;
import net.kroia.banksystem.minecraft.block.BankSystemBlocks;
import net.kroia.banksystem.minecraft.entity.custom.BankTerminalBlockEntity;
import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem;
import net.kroia.banksystem.networking.multi_server.TransferStatusRequest;
import net.kroia.banksystem.testing.fixture.FixtureKey.Case;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.modutilities.networking.client_server.arrs.AsynchronousRequestResponseSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * BS-269 — test only: the BankSystem story steps of the generated audit fixture (step A and C9–C12), run on the
 * trusted slave that drives the scenario. Every step blocks (on the server thread it keeps running queued server
 * tasks while it waits, like {@code TestSuite.await}), appends its cases to {@link FixtureKey#current()} and returns
 * the ids it made. Master work goes through {@link FixtureStepRequest}. Every deposit / withdraw waits for its final
 * journal step (CONFIRMED / DELIVERED / TIMED_OUT), so none ends up "after snapshot". Steps throw
 * {@link IllegalStateException} when something they plant does not happen.
 * <p>
 * Terminal deposits use a detached {@link BankTerminalBlockEntity} ({@code depositReserved}, the terminal's own path);
 * withdrawals lock, then {@code withdrawLockedPreferedAsync} tagged WITHDRAW like the terminal (no items are handed out).
 */
public final class FixtureSteps {

    public static final List<String> TESTERS = List.of("TesterA", "TesterB", "TesterC", "TesterD", "TesterE");
    /** Extra BankSystem testers (TesterF..V belong to StockMarket, TesterZ is the client player). */
    public static final String ADMIN_TARGET = "TesterW", CLEAN_BURST = "TesterX", CLEAN_SHARES = "TesterY";
    public static final String SHARED_ACCOUNT = "FixtureShared", COMPANY = "FixtureCo", CLEAN_COMPANY = "FixtureCleanCo";
    /** In {@link Step#note()} of C11 when the 3 WEALTH_JUMP cases were left out. */
    public static final String WEALTH_SKIPPED = "wealth cases skipped: no price provider (StockMarket not loaded)";
    /** Each Tester's role in the AT story (E). */
    private static final Map<String, String> ROLES = Map.of("TesterA", "exploiter", "TesterB", "pays in A's notes",
            "TesterC", "shares an account with A", "TesterD", "trades in overlapping sessions", "TesterE", "clean trader",
            ADMIN_TARGET, "target of the admin actions", CLEAN_BURST, "clean large note burst",
            CLEAN_SHARES, "founds the clean share company");

    private static final long SCALE = BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
    private static final long WAIT_MS = 30_000, MASTER_MS = 120_000, SETTLE_MS = 120_000;

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

    /** C9–C12 result: the case ids added to the key, and the ledger row / flag / transfer ids the step made. */
    public record Step(List<String> cases, List<Long> ledger, List<Long> flags, List<String> transfers, String note) {}

    // ---- steps -------------------------------------------------------------------------------------------------

    /** The BankSystem user {@code name} (uuid {@link FixtureKey#testerUuid}) with a personal account; creates what is missing. */
    public static Tester ensureTester(MinecraftServer slave, String name) {
        UUID uuid = FixtureKey.testerUuid(name);
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
        long[] lastAsk = {0};
        if (!pumpUntil(s, () -> {
            long now = now();
            if (now - lastAsk[0] >= 1_000) {
                lastAsk[0] = now;
                TransferStatusRequest.retryTick(now);
            }
            return f.isDone();
        }, SETTLE_MS)) throw new IllegalStateException("transfer outcome still pending after " + SETTLE_MS + " ms");
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
     * ticks per second (it sweeps every 20 ticks). Without it a request with no reply never fails.
     */
    public static boolean pumpUntil(MinecraftServer s, BooleanSupplier done, long timeoutMs) {
        long end = System.nanoTime() + timeoutMs * 1_000_000L;
        long[] lastSweep = {System.nanoTime()};
        if (s.isSameThread()) s.managedBlock(() -> {
            long t = System.nanoTime();
            if (t - lastSweep[0] >= 1_000_000_000L) {
                lastSweep[0] = t;
                for (int i = 0; i < 20; i++) AsynchronousRequestResponseSystem.tick(false);
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
