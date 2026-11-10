package net.kroia.banksystem.minecraft.compat;

import dev.architectury.utils.GameInstance;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.api.event.SuspicionFlaggedEvent;
import net.kroia.banksystem.api.event.TransactionLoggedEvent;
import net.kroia.banksystem.api.ledger.LedgerActor;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.banking.company.Company;
import net.kroia.banksystem.banking.company.CompanyManager;
import net.kroia.banksystem.minecraft.command.ServerBankSystemCommandHandler;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.MoneyFormat;
import net.kroia.modutilities.discord.DiscordWebhooks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Task #119 (v2.2.0) — Discord webhook feed of bank events through ModUtilities' {@link DiscordWebhooks}.
 * <p>
 * Master / standalone only, gated live by {@code Integrations.DISCORD_ENABLED}. Which types go to which
 * webhook is configured in {@code config/modutilities/discord/banksystem.json}. Listeners run on the ledger
 * writer's thread (server, network or DB worker); like {@code AdminAudit.alert}, every event hops to the
 * server thread, so name lookups and the coalescer only run there.
 * <p>
 * PAYOUT and DIVIDEND post the recipient leg only. DIVIDEND and ADMIN_DISALLOW_ITEM write one row per holder,
 * so they are summed per tick and posted once by {@link #flush()} (amount = total, holders = rows).
 */
public final class BankSystemDiscord {

    public static final List<String> TYPES = List.of("bank-deposit", "bank-withdraw", "bank-transfer-in",
            "bank-transfer-out", "bank-payout", "bank-dividend", "bank-share", "bank-other", "bank-admin", "bank-suspicion");

    /** Test seam: receives every finished post (DiscordWebhooks' own seams are package-private). */
    public static volatile BiConsumer<String, Map<String, String>> sink =
            (type, values) -> DiscordWebhooks.post(BankSystemMod.MOD_ID, type, values);

    private record Key(LedgerType type, @Nullable Integer companyId, short itemId) {}
    private record Sum(TransactionLoggedEvent first, long total, int count) {
        Sum plus(Sum o) { return new Sum(first, total + o.total, count + o.count); }
    }

    /**
     * Coalesced rows of the current tick; server thread only.
     * ponytail: per-tick window; a run written off-thread (manual dividend via ARRS) whose hopped rows straddle
     * a tick posts as 2 summaries. Key by run if that matters.
     */
    private static final Map<Key, Sum> PENDING = new LinkedHashMap<>();
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    private BankSystemDiscord() {}

    /** Mod init, once per JVM: adds the two event listeners. */
    public static void init(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
        backend.SERVER_EVENTS.TRANSACTION_LOGGED.addListener(BankSystemDiscord::onRow);
        backend.SERVER_EVENTS.SUSPICION_FLAGGED.addListener(BankSystemDiscord::onFlag);
    }

    /** Every master / standalone server start: declares the types and (re)creates the config file. */
    public static void register() {
        DiscordWebhooks.register(BankSystemMod.MOD_ID, TYPES);
    }

    /** One ledger row; no-op unless enabled on the master. */
    public static void onRow(TransactionLoggedEvent e) {
        if (!enabled() || !onServerThread(() -> onRow(e))) return;
        try {
            String type = typeOf(e);
            if (type == null) return;
            if (e.type() == LedgerType.DIVIDEND || e.type() == LedgerType.ADMIN_DISALLOW_ITEM)
                PENDING.merge(new Key(e.type(), e.companyId(), e.itemId()), new Sum(e, Math.abs(e.amount()), 1), Sum::plus);
            else
                sink.accept(type, values(e, Math.abs(e.amount()), 0));
        } catch (RuntimeException ex) {
            warn("row " + e.type() + " not posted: " + ex);
        }
    }

    /** One suspicion flag; no-op unless enabled on the master. */
    public static void onFlag(SuspicionFlaggedEvent e) {
        if (!enabled() || !onServerThread(() -> onFlag(e))) return;
        try {
            Map<String, String> v = new HashMap<>();
            v.put("account", account(e.accountNumber()));
            v.put("flag", e.flagName());
            v.put("severity", String.valueOf(e.severity()));
            v.put("amount", amount(Math.abs(e.amount())));
            v.put("amountText", amountText(Math.abs(e.amount()), true)); // every flag amount is money
            v.put("details", e.details() == null ? "" : e.details());
            v.put("ref", e.ref() == null ? "" : e.ref());
            v.put("time", time(e.timeMs()));
            sink.accept("bank-suspicion", v);
        } catch (RuntimeException ex) {
            warn("flag " + e.flagName() + " not posted: " + ex);
        }
    }

    /** End of every master tick: posts one summary per coalesced (type, company, item). */
    public static void flush() {
        if (PENDING.isEmpty()) return;
        try {
            for (Sum s : PENDING.values()) {
                Map<String, String> v = values(s.first, s.total, s.count);
                v.put("account", ""); // the first holder's account would be misleading
                sink.accept(typeOf(s.first), v);
            }
        } catch (RuntimeException ex) {
            warn("summary not posted: " + ex);
        } finally {
            PENDING.clear();
        }
    }

    /** @return the Discord type of the row, or null if it is not posted. */
    static @Nullable String typeOf(TransactionLoggedEvent e) {
        return switch (e.type()) {
            case DEPOSIT, WITHDRAW_REFUND, CONVERTER_DEPOSIT, UPLOAD_DEPOSIT -> "bank-deposit";
            case WITHDRAW, CONVERTER_WITHDRAW, DOWNLOAD_WITHDRAW, ACCOUNT_DELETE_PAYOUT -> "bank-withdraw";
            case TRANSFER -> e.amount() < 0 ? "bank-transfer-out" : "bank-transfer-in";
            case TRADE_BUY, TRADE_SELL -> null; // StockMarket's own feed covers trades
            case PAYOUT -> companyLeg(e) ? null : "bank-payout";
            case DIVIDEND -> companyLeg(e) ? null : "bank-dividend";
            case SHARE_STAMP, SHARE_REDEEM, SHARE_TRADE -> "bank-share";
            case ADMIN_SET, ADMIN_ADD, ADMIN_REMOVE, ADMIN_UNLOCK, ADMIN_ACCOUNT_EDIT, ACCOUNT_FREEZE,
                 ADMIN_DISALLOW_ITEM -> "bank-admin";
            case BANK_CLOSED -> e.actor().source() == LedgerActor.Source.ADMIN ? "bank-admin" : "bank-other";
            default -> "bank-other"; // UNKNOWN_LEGACY, CRAFT_*, STARTING_BALANCE, future codes
        };
    }

    /** PAYOUT / DIVIDEND rows come in pairs; the one on the company's own account is the paying leg. */
    private static boolean companyLeg(TransactionLoggedEvent e) {
        Company c = company(e.companyId());
        return c != null && c.getBankAccountNr() == e.accountNumber();
    }

    private static Map<String, String> values(TransactionLoggedEvent e, long raw, int holders) {
        LedgerActor a = e.actor();
        Company c = company(e.companyId());
        Map<String, String> v = new HashMap<>();
        v.put("player", a.name() != null ? a.name()
                : a.uuid() != null ? ServerBankSystemCommandHandler.tryGetPlayerName(a.uuid())
                : a.source() == LedgerActor.Source.SYSTEM ? "System" : a.source().name());
        v.put("player_uuid", a.uuid() == null ? "" : a.uuid().toString());
        v.put("account", account(e.accountNumber()));
        v.put("other_account", e.otherAccount() == null ? "" : account(e.otherAccount()));
        v.put("item", item(e.itemId()));
        v.put("amount", amount(raw));
        v.put("amountText", amountText(raw, MoneyItem.isMoney(new ItemID(e.itemId()))));
        v.put("holders", holders > 0 ? String.valueOf(holders) : "");
        v.put("company", e.companyId() == null ? "" : c != null ? c.getName() : "#" + e.companyId());
        v.put("kind", e.type().name());
        v.put("note", e.note() == null ? "" : e.note());
        v.put("time", time(e.timeMs()));
        return v;
    }

    /** {@code #<nr> <name>}, {@code #<nr>} if unknown, empty for 0 (server-wide flags). */
    private static String account(int nr) {
        if (nr == 0) return "";
        ISyncServerBankManager m = BACKEND_INSTANCES.SERVER_BANK_MANAGER == null ? null : BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        IServerBankAccount acc = m == null ? null : m.getBankAccount(nr);
        return acc == null ? "#" + nr : "#" + nr + " " + acc.getAccountName();
    }

    private static String item(short itemId) {
        if (itemId == 0) return "";
        ItemID id = new ItemID(itemId);
        ItemStack stack = id.getStackTemplate();
        return stack.isEmpty() ? id.getName() : stack.getHoverName().getString() + " (" + id.getName() + ")";
    }

    /** Raw units (scale {@code ITEM_FRACTION_SCALE_FACTOR} = 100) as a plain number; empty for 0 so minAmount never hides it. */
    static String amount(long raw) {
        return raw == 0 ? "" : BigDecimal.valueOf(raw, 2).stripTrailingZeros().toPlainString();
    }

    /** Display text of {@link #amount}: money {@code 1'500.00}; other items drop ".00" ({@code 3}, {@code 1'500}, {@code 2.50}); empty for 0. */
    public static String amountText(long raw, boolean money) {
        if (raw == 0) return "";
        String s = MoneyFormat.format(raw);
        return money || raw % MoneyFormat.SCALE != 0 ? s : s.substring(0, s.length() - 3);
    }

    private static String time(long ms) {
        return "<t:" + ms / 1000 + ":f>";
    }

    private static @Nullable Company company(@Nullable Integer companyId) {
        CompanyManager cm = CompanyManager.get();
        return companyId == null || cm == null ? null : cm.getById(companyId);
    }

    private static boolean enabled() {
        BankSystemModBackend.Instances b = BACKEND_INSTANCES;
        return b != null && !b.isSlaveServer && b.SERVER_SETTINGS != null && b.SERVER_SETTINGS.INTEGRATIONS.DISCORD_ENABLED.get();
    }

    /** @return true on the server thread (or without a server); otherwise schedules {@code task} there and returns false. */
    private static boolean onServerThread(Runnable task) {
        MinecraftServer srv = GameInstance.getServer();
        if (srv == null || srv.isSameThread()) return true;
        srv.execute(task);
        return false;
    }

    private static void warn(String msg) {
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null)
            BACKEND_INSTANCES.LOGGER.warn("[Discord] " + msg);
    }
}
