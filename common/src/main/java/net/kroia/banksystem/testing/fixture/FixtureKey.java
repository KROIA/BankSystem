package net.kroia.banksystem.testing.fixture;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * BS-269 — test only: the fixture's {@code answer-key.json}, format 1 (BS-269 tracker item, AT case list). BankSystem
 * and StockMarket append to the one {@link #current()} key on the driving slave; {@link #write(Path)} writes it once at
 * the copy moment. Amounts are raw integers written as strings (money in cents, items in raw units), times epoch ms,
 * record ids strings.
 */
public final class FixtureKey {

    public static final int FORMAT = 1;
    public static final String FILE = "answer-key.json";

    private static FixtureKey current = new FixtureKey("", "");

    // public and stable: StockMarket's driver reads these
    public final JsonObject scenario = new JsonObject();
    public final JsonArray servers = new JsonArray(), players = new JsonArray(), cases = new JsonArray(),
            balances = new JsonArray(), banknotes = new JsonArray(), links = new JsonArray(), takeBack = new JsonArray();

    public FixtureKey(String topic, String commit) {
        scenario.addProperty("topic", topic);
        scenario.addProperty("commit", commit);
    }

    /** Starts a fresh shared key (driver, at the scenario start). */
    public static synchronized FixtureKey start(String topic, String commit) {
        return current = new FixtureKey(topic, commit);
    }

    /** The shared key both mods append to. */
    public static synchronized FixtureKey current() {
        return current;
    }

    /** UUID of an offline Tester player, as an offline-mode server makes it. */
    public static UUID testerUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** @param worldFolder, exportFolder relative to the fixture set folder (e.g. {@code master/world}) */
    public synchronized FixtureKey addServer(String role, String uid, String worldFolder, String exportFolder) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("uid", uid);
        o.addProperty("worldFolder", worldFolder);
        o.addProperty("exportFolder", exportFolder);
        servers.add(o);
        return this;
    }

    /** uuid = {@link #testerUuid(String)}. */
    public synchronized FixtureKey addPlayer(String name, String role) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("uuid", testerUuid(name).toString());
        o.addProperty("role", role);
        players.add(o);
        return this;
    }

    public synchronized FixtureKey addCase(Case c) {
        cases.add(c.json.deepCopy());
        return this;
    }

    public synchronized FixtureKey addBalance(int account, String item, long raw) {
        JsonObject o = new JsonObject();
        o.addProperty("account", account);
        o.addProperty("item", item);
        o.addProperty("raw", Long.toString(raw));
        balances.add(o);
        return this;
    }

    /** @param place {@code inventory | enderchest | container@x,y,z | bank} */
    public synchronized FixtureKey addBanknote(String player, String place, long raw) {
        JsonObject o = new JsonObject();
        o.addProperty("player", player);
        o.addProperty("place", place);
        o.addProperty("raw", Long.toString(raw));
        banknotes.add(o);
        return this;
    }

    /** @param a, b player uuids (lowercase, with dashes) */
    public synchronized FixtureKey addLink(String a, String b, String strength, String way) {
        return addLink(a, b, strength, way, null);
    }

    /**
     * @param a, b     player uuids (lowercase, with dashes); for way notes {@code a} = the one who withdrew
     * @param strength {@code proven | likely | weak}; @param way {@code account | transfer | notes | market}
     * @param item     namespace:id for way market, else null
     */
    public synchronized FixtureKey addLink(String a, String b, String strength, String way, @Nullable String item) {
        JsonObject o = new JsonObject();
        o.addProperty("a", a);
        o.addProperty("b", b);
        o.addProperty("strength", strength);
        o.addProperty("way", way);
        o.addProperty("item", item);
        links.add(o);
        return this;
    }

    /** @param items item → raw amount to remove */
    public synchronized FixtureKey addTakeBack(long cutoff, int account, long keep, long take, Map<String, Long> items) {
        JsonObject o = new JsonObject();
        o.addProperty("cutoff", cutoff);
        o.addProperty("account", account);
        o.addProperty("keep", Long.toString(keep));
        o.addProperty("take", Long.toString(take));
        JsonArray arr = new JsonArray();
        items.forEach((item, remove) -> {
            JsonObject i = new JsonObject();
            i.addProperty("item", item);
            i.addProperty("remove", Long.toString(remove));
            arr.add(i);
        });
        o.add("items", arr);
        takeBack.add(o);
        return this;
    }

    public synchronized JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.add("scenario", scenario.deepCopy());
        root.addProperty("generated", System.currentTimeMillis());
        root.add("servers", servers.deepCopy());
        root.add("players", players.deepCopy());
        root.add("cases", cases.deepCopy());
        root.add("balances", balances.deepCopy());
        root.add("banknotes", banknotes.deepCopy());
        root.add("links", links.deepCopy());
        root.add("takeBack", takeBack.deepCopy());
        return root;
    }

    /** Writes {@code <runDir>/fixture/answer-key.json} (runDir = the slave's server run dir) and returns its path. */
    public Path write(Path runDir) throws IOException {
        Path file = runDir.resolve("fixture").resolve(FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(toJson()), StandardCharsets.UTF_8);
        return file;
    }

    /**
     * One case. {@code Case.of("B1-flag", "flag", "quick-round-trip").subject(uuid).window(from, to)…}; unset fields
     * are written as null (records as empty lists).
     */
    public static final class Case {
        public final JsonObject json = new JsonObject();
        public final JsonObject records = new JsonObject();

        private Case(String id, String expect, String rule) {
            json.addProperty("id", id);
            json.addProperty("expect", expect);
            json.addProperty("rule", rule);
            for (String k : new String[]{"subject", "item", "window", "amount", "tolerance", "transfer", "planted", "note"})
                json.add(k, JsonNull.INSTANCE);
            for (String k : new String[]{"orders", "ledger", "flags", "transfers"}) records.add(k, new JsonArray());
            json.add("records", records);
        }

        /** @param expect {@code flag | clean}; @param rule {@code balance | bank-flag:<n> | admin:<LedgerType> | transfer | last-save | banknotes} */
        public static Case of(String id, String expect, String rule) {
            return new Case(id, expect, rule);
        }

        /** player uuid | account number | market item id | transfer id */
        public Case subject(String subject) { json.addProperty("subject", subject); return this; }

        /** namespace:id or null */
        public Case item(@Nullable String item) { json.addProperty("item", item); return this; }

        public Case window(long fromMs, long toMs) {
            JsonObject w = new JsonObject();
            w.addProperty("from", fromMs);
            w.addProperty("to", toMs);
            json.add("window", w);
            return this;
        }

        public Case amount(long raw) { json.addProperty("amount", Long.toString(raw)); return this; }

        public Case tolerance(long raw) { json.addProperty("tolerance", Long.toString(raw)); return this; }

        /** Decimal string, e.g. {@code "2.5"} (market cases). */
        public Case amount(String amount) { json.addProperty("amount", amount); return this; }

        /** Decimal string, e.g. {@code "0.01"} (market cases). */
        public Case tolerance(String tolerance) { json.addProperty("tolerance", tolerance); return this; }

        public Case orders(Object... ids) { return add("orders", ids); }

        public Case ledger(Object... ids) { return add("ledger", ids); }

        public Case flags(Object... ids) { return add("flags", ids); }

        public Case transfers(Object... ids) { return add("transfers", ids); }

        /**
         * @param cls      {@code ok | parked | twice | lost | created | known-gap | data-gap | after-snapshot}
         * @param sent     item → raw amount sent; {@code received} the same for what arrived
         */
        public Case transfer(String id, String direction, String path, String cls, Map<String, Long> sent, Map<String, Long> received) {
            JsonObject t = new JsonObject();
            t.addProperty("id", id);
            t.addProperty("direction", direction);
            t.addProperty("path", path);
            t.addProperty("class", cls);
            t.add("sent", amounts(sent));
            t.add("received", amounts(received));
            json.add("transfer", t);
            return this;
        }

        /** {@code command | test hook | DB edit} */
        public Case planted(String how) { json.addProperty("planted", how); return this; }

        public Case note(String note) { json.addProperty("note", note); return this; }

        private Case add(String key, Object[] ids) {
            JsonArray arr = records.getAsJsonArray(key);
            for (Object id : ids) arr.add(String.valueOf(id));
            return this;
        }

        private static JsonObject amounts(Map<String, Long> m) {
            JsonObject o = new JsonObject();
            m.forEach((item, raw) -> o.addProperty(item, Long.toString(raw)));
            return o;
        }
    }
}
