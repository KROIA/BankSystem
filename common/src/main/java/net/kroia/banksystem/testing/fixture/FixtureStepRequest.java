package net.kroia.banksystem.testing.fixture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.currency.ExternalAccountRef;
import net.kroia.banksystem.api.bank.IServerBank;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.banking.bankmanager.ServerBankManager;
import net.kroia.banksystem.banking.binding.BankAccountBindings;
import net.kroia.banksystem.banking.binding.BindingRow;
import net.kroia.banksystem.banking.ledger.SuspicionChecks;
import net.kroia.banksystem.banking.ledger.TransactionLedger;
import net.kroia.banksystem.data.AuditExporter;
import net.kroia.banksystem.data.DatabaseManager;
import net.kroia.banksystem.data.DiagnosticsDatabase;
import net.kroia.banksystem.data.table.TransactionLogManager;
import net.kroia.banksystem.minecraft.command.ServerBankSystemCommandHandler;
import net.kroia.banksystem.networking.general.UpdateBankAccountRequest;
import net.kroia.banksystem.networking.multi_server.TransferStatusRequest;
import net.kroia.banksystem.testing.DevBootstrap;
import net.kroia.banksystem.testing.StubCurrencyProvider;
import net.kroia.banksystem.util.BankSystemDataHandler;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.kroia.modutilities.networking.multi_server.IServerIdentity;
import net.kroia.modutilities.networking.multi_server.MultiServerManager;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * BS-269 — test only: one master step of the generated audit fixture, asked by the trusted slave that drives the
 * scenario (the master runs its suites first and is passive afterwards). Lives in {@code testing/} (not compiled in
 * release builds) and is registered by {@link DevBootstrap}. Answered only for a trusted slave without a player sender.
 * <p>
 * Call from the slave with {@link #call(String, String)}: {@code step} is one of the op names below, {@code args} a JSON
 * object as a string ({@code ""} or {@code "{}"} when the op takes none). The future completes with the op's result,
 * a JSON object as a string, or fails with an {@link IllegalStateException} carrying the master's error message.
 * Every op answers only after the master's server thread, the BS db-worker (TransactionLog, balance history, flags)
 * and the diagnostics writer (bank_last_save_ms, journals) drained everything queued before the answer, the same as
 * {@code barrier}. All database ops run on the db-worker.
 * <p>
 * Ops (args → result):
 * <ul>
 *   <li>{@code command}: {@code {"cmd":"money add TesterA 1000"}} (no leading slash needed) → {@code {"lines":["…",…]}},
 *       the chat lines the command sent to the console source until the following barrier. Runs as the server console
 *       (permission level 4).</li>
 *   <li>{@code snapshot}: {@code {}} → {@code {"ok":true}}. One balance snapshot of every account now (the rows are in
 *       the DB when it answers).</li>
 *   <li>{@code armFault}: {@code {"fault":"THROW_BEFORE_APPLY"|"RESTART_AFTER_APPLY"|"NONE"}} → {@code {"ok":true}}.
 *       One-shot, consumed by the next transfer with a transfer id the master applies. THROW_BEFORE_APPLY: the apply
 *       throws, nothing moves, no reply; status UNKNOWN → the slave parks the transfer (TIMED_OUT). RESTART_AFTER_APPLY:
 *       the apply happens (ledger row written), then the status cache is wiped as by a restart and the reply is lost;
 *       status UNKNOWN → TIMED_OUT + TRANSFER_PARKED. NONE disarms. Key label: "test hook".</li>
 *   <li>{@code suppressSave}: {@code {"on":true}} → {@code {"ok":true}}. On: runs {@code save-all flush} first, then
 *       skips every later bank-data save (also the shutdown save) and keeps {@code bank_last_save_ms} where it is.
 *       {@code {"on":false}} saves normally again (no save is triggered).</li>
 *   <li>{@code ledgerRows}: {@code {"ref":"<ref>"}} → {@code {"rows":[{"id":12,"account_number":3,"type_code":2,
 *       "kind":"DEPOSIT","item_id":5,"amount":1000,"ref":"…","note":null,"ts":1763000000000,…},…]}}, every TransactionLog
 *       column by its SQL name (numbers as JSON numbers, SQL NULL as null), ordered by id.</li>
 *   <li>{@code ledgerDelete}: {@code {"ref":"<ref>"}} or {@code {"ref":"<ref>","id":12}} → {@code {"deleted":n}}.
 *       Deletes every row with that ref, or only row {@code id} (and only if it has that ref). Key label: "DB edit".</li>
 *   <li>{@code ledgerEdit}: {@code {"id":12,"set":{"amount":"500","note":"x"}}} → {@code {"updated":n}} (0 = no such
 *       row). Editable fields only: {@code amount} (raw long), {@code account} (account number), {@code item} (item id
 *       short), {@code ref} (string or null), {@code type} (LedgerType name or code; sets type_code and kind),
 *       {@code note} (string or null). Values as JSON strings or numbers. Key label: "DB edit".</li>
 *   <li>{@code barrier}: {@code {}} → {@code {"ok":true}}, after the server thread, the db-worker and the diagnostics
 *       writer drained.</li>
 *   <li>{@code export}: {@code {"name":"fixture"}} → {@code {"folder":"world/audit_exports/20261113-120000-fixture",
 *       "world":"world","uid":"<server uid>","files":n,"warnings":["…"]}}. The BS-255 audit export on the master;
 *       {@code folder} and {@code world} are relative to the master's server run dir, with forward slashes (the zip is
 *       {@code folder + ".zip"}).</li>
 *   <li>{@code balances}: {@code {}} → {@code {"balances":[{"account":3,"item":"minecraft:diamond","itemId":5,
 *       "raw":"1000","locked":"0"},…]}}. Every bank of every account; {@code raw} = free balance, {@code locked} = locked
 *       balance, both raw integer strings.</li>
 *   <li>{@code quiet}: {@code {}} → {@code {"ok":true}}. Balance snapshot interval → 0: no further timer snapshots
 *       ({@code snapshot} still works).</li>
 *   <li>{@code money}: {@code {"action":"add"|"set"|"remove","user":"TesterA","amount":12.5}} → {@code {"ok":true}}.
 *       The /money handler code as the fixture admin {@link #ADMIN} (a BankSystem admin user, created on first use):
 *       /money itself needs a player, the console is refused. Writes ADMIN_ADD / ADMIN_SET / ADMIN_REMOVE.</li>
 *   <li>{@code unlock}: {@code {"account":3,"item":5,"lock":"2000"}} → {@code {"ok":true}}. Locks {@code lock} raw of
 *       bank {@code item} (item id short) on the master, then the admin "reset locked balance" (UpdateBankAccountRequest)
 *       as {@link #ADMIN}: one ADMIN_UNLOCK row.</li>
 *   <li>{@code sweep}: {@code {}} → {@code {"raised":n,"wealth":true}}. The wealth-jump sweep over every account now;
 *       {@code wealth} false = no price provider or price currency set (StockMarket not loaded): snapshots write no
 *       WEALTH rows, so no WEALTH_JUMP can fire.</li>
 *   <li>{@code bindExternal}: {@code {"account":3,"key":"k","owner":"<uuid>"}} → {@code {"status":"SUCCESS",
 *       "suspended":true,"item":"minecraft:stone"}}. Creates stub currency account {@code key} (balance 0) owned by
 *       {@code owner}, binds the account's stone slot to it, then runs the start-up foreign-binding check on that row
 *       (suspended + EXTERNAL_BINDING_SUSPENDED when no manager of the account may bind it). Leaves the stub registered.</li>
 *   <li>{@code ledgerAfter}: {@code {"id":12}} → {@code {"max":40,"rows":[…]}}: TransactionLog rows with id &gt; 12 (as
 *       {@code ledgerRows}) and the highest id; {@code "id":-1} = only {@code max}.</li>
 *   <li>{@code flags}: {@code {"after":4}} → {@code {"max":9,"flags":[{"id":5,"ts":…,"account_number":3,"flag_type":2,
 *       "typeName":"LARGE_NOTE_WITHDRAWAL","severity":1,"amount":…,"ref":…,"details":…},…]}}: SuspicionFlag rows with
 *       id &gt; after; {@code "after":-1} = only {@code max}.</li>
 *   <li>{@code deleteUser}: {@code {"uuid":"<uuid>"}} → {@code {"undelivered":{"<item>":"<raw>",…},"reason":"player offline"|null,
 *       "delivered":{"<transfer id>":{"<item>":"<raw>",…},…},"unknownTransferId":"<id>"|null}}. Deletes the BankSystem user
 *       with payout ({@code removeUserWithPayout}) as admin {@link #ADMIN}: drop on the master first, then a DROP on each
 *       slave; answers once the ledger rows (ACCOUNT_DELETE_PAYOUT / _LOSS) are written. {@code delivered} = what each
 *       slave DROP handed out, {@code unknownTransferId} = the DROP whose outcome stayed unknown (its items are in
 *       {@code undelivered}, logged as loss + TRANSFER_PARKED).</li>
 * </ul>
 * Errors (unknown step, bad args, refused caller, SQL error) answer {@code {"error":"…"}}; {@link #call} turns that into
 * a failed future.
 */
public class FixtureStepRequest extends BankSystemGenericRequest<FixtureStepRequest.Input, String> {

    public static final FixtureStepRequest INSTANCE = new FixtureStepRequest();
    /** The fixture's BankSystem admin user (uuid {@link FixtureKey#testerUuid}) for the {@code money} and {@code unlock} ops. */
    public static final String ADMIN = "TesterAdmin";
    /** Results (ledger rows, balances) can outgrow the default 32767-char string codec. */
    private static final StreamCodec<io.netty.buffer.ByteBuf, String> OUT_CODEC = ByteBufCodecs.stringUtf8(262_144);

    public record Input(String step, String args) {
        static final StreamCodec<RegistryFriendlyByteBuf, Input> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Input::step,
                ByteBufCodecs.STRING_UTF8, Input::args,
                Input::new);
    }

    /** Slave side: runs {@code step} on the master. Fails with the master's error message on {@code {"error":…}}. */
    public static CompletableFuture<String> call(String step, String args) {
        return INSTANCE.sendRequestToMaster(new Input(step, args == null ? "" : args)).thenApply(out -> {
            JsonElement e = JsonParser.parseString(out);
            if (e.isJsonObject() && e.getAsJsonObject().has("error"))
                throw new IllegalStateException(step + ": " + e.getAsJsonObject().get("error").getAsString());
            return out;
        });
    }

    @Override
    public String getRequestTypeID() {
        return FixtureStepRequest.class.getName();
    }

    /** The export waits up to {@link AuditExporter#DB_TIMEOUT_MINUTES} for its database copies. */
    @Override
    public long getTimeoutMs() {
        return Math.max(SLOW_READ_TIMEOUT_MS, (AuditExporter.DB_TIMEOUT_MINUTES + 2) * 60_000L);
    }

    @Override
    public CompletableFuture<String> handleOnMasterServer(Input in, String slaveID, @Nullable UUID playerSender) {
        MinecraftServer server = dev.architectury.utils.GameInstance.getServer();
        if (playerSender != null || slaveID == null || slaveID.isEmpty() || isFromUntrustedSlave(slaveID) || server == null)
            return CompletableFuture.completedFuture(errorJson("refused: trusted slave only, no player sender"));
        CompletableFuture<JsonObject> op;
        try {
            JsonObject a = in.args().isBlank() ? new JsonObject() : JsonParser.parseString(in.args()).getAsJsonObject();
            op = run(server, in.step(), a);
        } catch (RuntimeException e) {
            op = CompletableFuture.failedFuture(e);
        }
        return op.thenCompose(r -> barrier(server).thenApply(v -> r.toString()))
                .exceptionally(t -> {
                    Throwable c = TransferStatusRequest.cause(t);
                    warn("fixture step " + in.step() + " failed: " + c);
                    return errorJson(c.getMessage() == null ? c.toString() : c.getMessage());
                });
    }

    private static CompletableFuture<JsonObject> run(MinecraftServer server, String step, JsonObject a) {
        return switch (step) {
            case "command" -> command(server, a.get("cmd").getAsString());
            case "snapshot" -> onServer(server, () -> {
                BankSystemModBackend.takeBalanceSnapshot_forTesting();
                return ok();
            });
            case "armFault" -> {
                String f = a.get("fault").getAsString();
                TransferStatusRequest.armFault_forTesting("NONE".equals(f) ? null : TransferStatusRequest.Fault.valueOf(f));
                yield CompletableFuture.completedFuture(ok());
            }
            case "suppressSave" -> a.get("on").getAsBoolean()
                    ? command(server, "save-all flush").thenCompose(r -> barrier(server)).thenApply(v -> {
                        BankSystemDataHandler.suppressBankSave_forTesting(true);
                        return ok();
                    })
                    : onServer(server, () -> {
                        BankSystemDataHandler.suppressBankSave_forTesting(false);
                        return ok();
                    });
            case "ledgerRows" -> onDb(c -> {
                JsonObject r = new JsonObject();
                r.add("rows", query(c, "SELECT " + TransactionLogManager.SELECT_COLS + " FROM TransactionLog WHERE ref = ? ORDER BY id",
                        a.get("ref").getAsString()));
                return r;
            });
            case "ledgerDelete" -> onDb(c -> {
                boolean one = a.has("id");
                try (PreparedStatement s = c.prepareStatement("DELETE FROM TransactionLog WHERE ref = ?" + (one ? " AND id = ?" : ""))) {
                    s.setString(1, a.get("ref").getAsString());
                    if (one) s.setLong(2, a.get("id").getAsLong());
                    return count("deleted", s.executeUpdate());
                }
            });
            case "ledgerEdit" -> ledgerEdit(a.get("id").getAsLong(), a.getAsJsonObject("set"));
            case "barrier" -> CompletableFuture.completedFuture(ok());
            case "export" -> onServer(server, () -> AuditExporter.export(server, a.has("name") ? a.get("name").getAsString() : null))
                    .thenCompose(f -> f).thenApply(res -> {
                        Path runDir = server.getServerDirectory().toAbsolutePath().normalize();
                        JsonObject r = new JsonObject();
                        r.addProperty("folder", runDir.relativize(res.folder().toAbsolutePath().normalize()).toString().replace('\\', '/'));
                        r.addProperty("world", runDir.relativize(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize())
                                .toString().replace('\\', '/'));
                        IServerIdentity id = MultiServerManager.getIdentity();
                        r.addProperty("uid", id == null ? null : id.serverUid().toString());
                        r.addProperty("files", res.files());
                        JsonArray w = new JsonArray();
                        res.warnings().forEach(w::add);
                        r.add("warnings", w);
                        return r;
                    });
            case "balances" -> onServer(server, () -> {
                JsonArray out = new JsonArray();
                if (BankSystemModBackend.getInstances_forTesting().SERVER_BANK_MANAGER.getSync() instanceof ServerBankManager bm)
                    for (int nr : bm.getAllAccountNumbers()) {
                        var account = bm.getBankAccount(nr);
                        if (account == null) continue;
                        for (Map.Entry<ItemID, IServerBank> e : account.getAllBanks().entrySet()) {
                            if (e.getValue() == null) continue;
                            JsonObject b = new JsonObject();
                            b.addProperty("account", nr);
                            b.addProperty("item", e.getKey().getName());
                            b.addProperty("itemId", e.getKey().getShort());
                            b.addProperty("raw", Long.toString(e.getValue().getBalance()));
                            b.addProperty("locked", Long.toString(e.getValue().getLockedBalance()));
                            out.add(b);
                        }
                    }
                JsonObject r = new JsonObject();
                r.add("balances", out);
                return r;
            });
            case "quiet" -> onServer(server, () -> {
                BankSystemModBackend.getInstances_forTesting().SERVER_SETTINGS.UTILITIES.BALANCE_SNAPSHOT_INTERVAL_MINUTES.set(0L);
                return ok();
            });
            case "money" -> onServer(server, () -> {
                UUID admin = fixtureAdmin();
                String user = a.get("user").getAsString();
                float amount = a.get("amount").getAsFloat();
                ServerBankSystemCommandHandler h = new ServerBankSystemCommandHandler();
                boolean done = switch (a.get("action").getAsString()) {
                    case "add" -> h.money_add_user(admin, user, amount);
                    case "set" -> h.money_set_user(admin, user, amount);
                    case "remove" -> h.money_remove_user(admin, user, amount);
                    default -> throw new IllegalArgumentException("money action: add | set | remove");
                };
                JsonObject r = new JsonObject();
                r.addProperty("ok", done);
                return r;
            });
            case "unlock" -> onServer(server, () -> {
                int nr = a.get("account").getAsInt();
                ItemID item = new ItemID(a.get("item").getAsShort());
                var account = bankManager().getBankAccount(nr);
                var bank = account == null ? null : account.getBank(item);
                if (bank == null) throw new IllegalArgumentException("no bank " + item.getShort() + " on account " + nr);
                long lock = a.get("lock").getAsLong();
                if (lock > 0 && bank.lockAmount(lock) != BankStatus.SUCCESS)
                    throw new IllegalStateException("could not lock " + lock + " on account " + nr);
                return new UpdateBankAccountRequest().handleOnMasterServer(new UpdateBankAccountRequest.InputData(nr, "", null,
                        List.of(new UpdateBankAccountRequest.InputData.BankData(item, 0L, false, true, false, false)), null),
                        "", fixtureAdmin());
            }).thenCompose(f -> f).thenApply(d -> {
                if (d == null) throw new IllegalStateException("unlock refused");
                return ok();
            });
            case "sweep" -> onServer(server, () -> bankManager().getAllAccountNumbers())
                    .thenCompose(nrs -> SuspicionChecks.sweepWealth(nrs, System.currentTimeMillis()))
                    .thenApply(n -> {
                        JsonObject r = count("raised", n);
                        // snapshots write WEALTH rows only with a price provider + currency (StockMarket sets both)
                        r.addProperty("wealth", BankSystemMod.getAPI().getItemPriceProvider() != null
                                && BankSystemMod.getAPI().getPriceCurrencyItem() != 0);
                        return r;
                    });
            case "bindExternal" -> onServer(server, () -> bindExternal(a.get("account").getAsInt(), a.get("key").getAsString(),
                    UUID.fromString(a.get("owner").getAsString())));
            case "ledgerAfter" -> onDb(c -> after(c, "TransactionLog", TransactionLogManager.SELECT_COLS, a.get("id").getAsLong()));
            case "flags" -> onDb(c -> {
                JsonObject r = after(c, "SuspicionFlag", "id, ts, account_number, flag_type, severity, amount, ref, details",
                        a.get("after").getAsLong());
                for (JsonElement e : r.getAsJsonArray("rows")) {
                    JsonObject f = e.getAsJsonObject();
                    f.addProperty("typeName", SuspicionChecks.typeName(f.get("flag_type").getAsInt()));
                }
                r.add("flags", r.remove("rows"));
                return r;
            });
            case "deleteUser" -> onServer(server, () -> {
                UUID uuid = UUID.fromString(a.get("uuid").getAsString());
                CompletableFuture<ServerBankManager.PayoutResult> f = TransactionLedger.withActor(TransactionLedger.admin(fixtureAdmin()),
                        () -> bankManager().removeUserWithPayout(uuid));
                if (f == null) throw new IllegalArgumentException("no user " + uuid);
                return f;
            }).thenCompose(f -> f).thenApply(p -> {
                JsonObject r = new JsonObject();
                r.add("undelivered", items(p.undelivered()));
                r.addProperty("reason", p.reason());
                JsonObject d = new JsonObject();
                p.delivered().forEach((tid, m) -> d.add(tid, items(m)));
                r.add("delivered", d);
                r.addProperty("unknownTransferId", p.unknownTransferId());
                return r;
            });
            default -> throw new IllegalArgumentException("unknown fixture step '" + step + "'");
        };
    }

    // ---- ops ---------------------------------------------------------------------------------------------------

    private static CompletableFuture<JsonObject> command(MinecraftServer server, String cmd) {
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        CommandSource sink = new CommandSource() {
            @Override public void sendSystemMessage(Component c) { lines.add(c.getString()); }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        // ponytail: async command replies are caught only until the barrier + one more server tick; wait longer if a
        // command ever answers later than that.
        return onServer(server, () -> {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSource(sink), cmd);
            return null;
        }).thenCompose(v -> barrier(server)).thenCompose(v -> onServer(server, () -> {
            JsonArray out = new JsonArray();
            synchronized (lines) { lines.forEach(out::add); }
            JsonObject r = new JsonObject();
            r.add("lines", out);
            return r;
        }));
    }

    /** The fixture admin user, created (and made a BankSystem admin) on first use. Server thread. */
    private static UUID fixtureAdmin() {
        ServerBankManager bm = bankManager();
        UUID id = FixtureKey.testerUuid(ADMIN);
        if (bm.getUserByUUID(id) == null) bm.addUser(id, ADMIN);
        if (!bm.isBanksystemAdmin(id)) bm.setBanksystemAdminMode(id, true);
        return id;
    }

    private static JsonObject bindExternal(int nr, String key, UUID owner) {
        ServerBankManager bm = bankManager();
        var provider = BankSystemMod.getAPI().getCurrencyProvider(StubCurrencyProvider.PROVIDER_ID);
        if (provider == null || !provider.isAvailable()) StubCurrencyProvider.reset();
        StubCurrencyProvider.create(key, false, 0L).setOwner(owner);
        ItemID slot = ItemIDManager.registerItemStackServerSide_direct(Items.STONE.getDefaultInstance());
        bm.allowItemID(slot);
        var account = bm.getBankAccount(nr);
        if (account == null) throw new IllegalArgumentException("no account " + nr);
        if (account.getBank(slot) == null) account.createBank(slot, 0);
        BankStatus status = bm.bindExternalAccount(nr, slot, new ExternalAccountRef(StubCurrencyProvider.PROVIDER_ID, key, key, false));
        BankAccountBindings bindings = BankAccountBindings.get();
        BindingRow row = bindings == null ? null : bindings.getBinding(nr, slot);
        JsonObject r = new JsonObject();
        r.addProperty("status", status.name());
        r.addProperty("suspended", row != null && bm.suspendIfForeign(row));
        r.addProperty("item", slot.getName());
        return r;
    }

    /** {@code {"max":M,"rows":[rows with id > after]}}; {@code after < 0} = no rows. */
    private static JsonObject after(Connection c, String table, String cols, long after) throws SQLException {
        JsonObject r = new JsonObject();
        r.addProperty("max", query(c, "SELECT COALESCE(MAX(id), 0) AS m FROM " + table).get(0).getAsJsonObject().get("m").getAsLong());
        r.add("rows", after < 0 ? new JsonArray() : query(c, "SELECT " + cols + " FROM " + table + " WHERE id > ? ORDER BY id", after));
        return r;
    }

    /** Every column by its SQL name (numbers as JSON numbers, SQL NULL as null). */
    private static JsonArray query(Connection c, String sql, Object... params) throws SQLException {
        JsonArray rows = new JsonArray();
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) s.setObject(i + 1, params[i]);
            try (ResultSet rs = s.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    JsonObject row = new JsonObject();
                    for (int i = 1; i <= md.getColumnCount(); i++) row.add(md.getColumnLabel(i), json(rs.getObject(i)));
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private static ServerBankManager bankManager() {
        if (BankSystemModBackend.getInstances_forTesting().SERVER_BANK_MANAGER.getSync() instanceof ServerBankManager bm) return bm;
        throw new IllegalStateException("no ServerBankManager");
    }

    /** Editable TransactionLog columns by key field name. {@code type} is special: type_code + kind. */
    private static final Map<String, String> EDITABLE = Map.of(
            "amount", "amount", "account", "account_number", "item", "item_id", "ref", "ref", "note", "note");

    private static CompletableFuture<JsonObject> ledgerEdit(long id, JsonObject set) {
        List<String> cols = new ArrayList<>();
        List<Object> vals = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : set.entrySet()) {
            JsonElement v = e.getValue();
            if (e.getKey().equals("type")) {
                LedgerType t = v.getAsJsonPrimitive().isNumber() || v.getAsString().matches("-?\\d+")
                        ? LedgerType.fromCode(v.getAsInt()) : LedgerType.valueOf(v.getAsString());
                cols.add("type_code");
                vals.add(t.code());
                cols.add("kind");
                vals.add(t.name());
                continue;
            }
            String col = EDITABLE.get(e.getKey());
            if (col == null) throw new IllegalArgumentException("field '" + e.getKey() + "' is not editable (amount, account, item, ref, type, note)");
            cols.add(col);
            vals.add(v.isJsonNull() ? null : switch (e.getKey()) {
                case "amount" -> (Object) v.getAsLong();
                case "account" -> v.getAsInt();
                case "item" -> v.getAsShort();
                default -> v.getAsString();
            });
        }
        if (cols.isEmpty()) throw new IllegalArgumentException("ledgerEdit: nothing to set");
        return onDb(c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE TransactionLog SET "
                    + String.join(" = ?, ", cols) + " = ? WHERE id = ?")) {
                for (int i = 0; i < vals.size(); i++) s.setObject(i + 1, vals.get(i));
                s.setLong(vals.size() + 1, id);
                return count("updated", s.executeUpdate());
            }
        });
    }

    /**
     * Completes after the server thread ran everything queued before it, then after the db-worker and the diagnostics
     * writer committed everything queued before that (ledger rows, balance history, flags, bank_last_save_ms).
     */
    public static CompletableFuture<Void> barrier(MinecraftServer server) {
        return CompletableFuture.runAsync(() -> {}, server).thenCompose(v -> {
            DatabaseManager db = BankSystemModBackend.getDatabaseManager();
            DiagnosticsDatabase diag = BankSystemModBackend.getInstances_forTesting().DIAGNOSTICS_DB;
            return CompletableFuture.allOf(
                    db == null ? CompletableFuture.completedFuture(null) : CompletableFuture.runAsync(() -> {}, db.getDatabaseThread()),
                    diag == null ? CompletableFuture.completedFuture(null) : diag.flush());
        });
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private interface SqlOp { JsonObject run(Connection c) throws SQLException; }

    /** Runs on the BS db-worker and commits; a failure rolls back. */
    private static CompletableFuture<JsonObject> onDb(SqlOp op) {
        DatabaseManager db = BankSystemModBackend.getDatabaseManager();
        if (db == null) return CompletableFuture.failedFuture(new IllegalStateException("no database"));
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject r = op.run(db.getConnection());
                db.commitTransaction();
                return r;
            } catch (SQLException e) {
                try { db.getConnection().rollback(); } catch (SQLException ignored) { }
                throw new IllegalStateException("SQL: " + e.getMessage(), e);
            }
        }, db.getDatabaseThread());
    }

    private static <T> CompletableFuture<T> onServer(MinecraftServer server, Supplier<T> body) {
        return CompletableFuture.supplyAsync(body, server);
    }

    private static JsonElement json(@Nullable Object o) {
        if (o == null) return JsonNull.INSTANCE;
        if (o instanceof Number n) return new JsonPrimitive(n);
        return new JsonPrimitive(o.toString());
    }

    /** {@code {"<namespace:id>":"<raw>",…}} */
    private static JsonObject items(Map<ItemID, Long> m) {
        JsonObject o = new JsonObject();
        m.forEach((id, raw) -> o.addProperty(id.getName(), Long.toString(raw)));
        return o;
    }

    private static JsonObject ok() {
        JsonObject r = new JsonObject();
        r.addProperty("ok", true);
        return r;
    }

    private static JsonObject count(String key, int n) {
        JsonObject r = new JsonObject();
        r.addProperty(key, n);
        return r;
    }

    private static String errorJson(String message) {
        JsonObject r = new JsonObject();
        r.addProperty("error", message);
        return r.toString();
    }

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, Input input) { Input.CODEC.encode(buf, input); }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, String output) { OUT_CODEC.encode(buf, output); }

    @Override
    public Input decodeInput(RegistryFriendlyByteBuf buf) { return Input.CODEC.decode(buf); }

    @Override
    public String decodeOutput(RegistryFriendlyByteBuf buf) { return OUT_CODEC.decode(buf); }
}
