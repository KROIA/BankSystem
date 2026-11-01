package net.kroia.banksystem.minecraft.compat;

import dev.architectury.event.events.common.TickEvent;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.api.MoneyFormat;
import net.kroia.banksystem.api.bankmanager.IAsyncBankManager;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.modutilities.placeholder.PlaceholderRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * BankSystem placeholders for TAB / Placeholder API via ModUtilities' {@link PlaceholderRegistry}.
 * <p>
 * Threading: TAB evaluates placeholders off the server thread, and the async bank API completes
 * synchronously on the master. Placeholder functions therefore never touch the bank API; they only
 * read immutable snapshots. The snapshots are refreshed from the server tick (one request in flight
 * per player / for the server stats) and work on master and slave servers alike.
 */
public final class BankSystemPlaceholders {

    private static final String NS = BankSystemMod.MOD_ID;

    private record PlayerSnapshot(long balance, long locked, String accountNumber) {}

    private record TopEntry(String name, long total) {}

    private record ServerSnapshot(long circulation, long circulationLocked, int accountCount, int playerCount,
                                  long averageTotal, List<TopEntry> top, Map<UUID, Integer> rank) {
        static final ServerSnapshot EMPTY = new ServerSnapshot(0, 0, 0, 0, 0, List.of(), Map.of());
    }

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    private static final Map<UUID, PlayerSnapshot> PLAYERS = new ConcurrentHashMap<>();
    private static final Set<UUID> PLAYERS_IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static volatile ServerSnapshot server = ServerSnapshot.EMPTY;
    private static volatile boolean serverInFlight = false;
    private static long lastPlayerRefresh = 0;
    private static long lastServerRefresh = 0;
    private static boolean tickRegistered = false;
    /** Placeholders are registered once per JVM; functions only read the static snapshots. */
    private static boolean placeholdersRegistered = false;

    private static final TickEvent.Server TICK = BankSystemPlaceholders::tick;

    private BankSystemPlaceholders() {
    }

    /**
     * Called at server start (master and slave). Placeholders register once per JVM, so refresh
     * rates and TOP_COUNT are taken from the first server start (TOP_COUNT is restart-required).
     */
    public static void start(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
        BankSystemModSettings.Placeholder settings = backend.SERVER_SETTINGS.PLACEHOLDER;
        int playerMs = Math.max(1, settings.PLAYER_REFRESH_MS.get());
        int serverMs = Math.max(1, settings.SERVER_REFRESH_MS.get());
        int topCount = Math.max(0, settings.TOP_COUNT.get());
        MoneyFormat.setPattern(settings.MONEY_FORMAT.get());

        if (!placeholdersRegistered) {
            placeholdersRegistered = true;
            player("balance", playerMs, p -> p == null ? "" : money(p.balance()));
            player("balance_raw", playerMs, p -> p == null ? "" : raw(p.balance()));
            player("locked", playerMs, p -> p == null ? "" : money(p.locked()));
            player("locked_raw", playerMs, p -> p == null ? "" : raw(p.locked()));
            player("total", playerMs, p -> p == null ? "" : money(p.balance() + p.locked()));
            player("total_raw", playerMs, p -> p == null ? "" : raw(p.balance() + p.locked()));
            player("account_number", playerMs, p -> p == null ? "" : p.accountNumber());
            PlaceholderRegistry.registerPlayer(NS, "rank", serverMs, sp -> {
                Integer rank = server.rank().get(sp.getUUID());
                return rank == null ? "" : String.valueOf(rank);
            });

            server("circulation", serverMs, () -> money(server.circulation()));
            server("circulation_raw", serverMs, () -> raw(server.circulation()));
            server("circulation_locked", serverMs, () -> money(server.circulationLocked()));
            server("circulation_locked_raw", serverMs, () -> raw(server.circulationLocked()));
            server("account_count", serverMs, () -> String.valueOf(server.accountCount()));
            server("player_count", serverMs, () -> String.valueOf(server.playerCount()));
            server("average_balance", serverMs, () -> money(server.averageTotal()));
            server("average_balance_raw", serverMs, () -> raw(server.averageTotal()));
            for (int i = 1; i <= topCount; i++) {
                final int index = i - 1;
                server("top_" + i + "_name", serverMs, () -> {
                    List<TopEntry> top = server.top();
                    return index < top.size() ? top.get(index).name() : "";
                });
                server("top_" + i + "_balance", serverMs, () -> {
                    List<TopEntry> top = server.top();
                    return money(index < top.size() ? top.get(index).total() : 0);
                });
                server("top_" + i + "_balance_raw", serverMs, () -> {
                    List<TopEntry> top = server.top();
                    return raw(index < top.size() ? top.get(index).total() : 0);
                });
            }
        }

        if (!tickRegistered) {
            TickEvent.SERVER_POST.register(TICK);
            tickRegistered = true;
        }
    }

    public static void stop() {
        if (tickRegistered) {
            TickEvent.SERVER_POST.unregister(TICK);
            tickRegistered = false;
        }
        PLAYERS.clear();
        PLAYERS_IN_FLIGHT.clear();
        server = ServerSnapshot.EMPTY;
        serverInFlight = false;
        lastPlayerRefresh = 0;
        lastServerRefresh = 0;
    }

    // ------------------------------------------------------------------
    // Refresh (server thread)
    // ------------------------------------------------------------------

    private static void tick(MinecraftServer mcServer) {
        if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.SERVER_BANK_MANAGER == null || BACKEND_INSTANCES.SERVER_SETTINGS == null)
            return;
        BankSystemModSettings.Placeholder settings = BACKEND_INSTANCES.SERVER_SETTINGS.PLACEHOLDER;
        MoneyFormat.setPattern(settings.MONEY_FORMAT.get());
        long now = System.currentTimeMillis();
        IAsyncBankManager bankManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync();

        if (now - lastPlayerRefresh >= settings.PLAYER_REFRESH_MS.get()) {
            lastPlayerRefresh = now;
            Set<UUID> online = new HashSet<>();
            for (ServerPlayer player : mcServer.getPlayerList().getPlayers()) {
                UUID uuid = player.getUUID();
                online.add(uuid);
                if (PLAYERS_IN_FLIGHT.add(uuid))
                    refreshPlayer(bankManager, uuid);
            }
            PLAYERS.keySet().retainAll(online); // drop logged-out players
        }

        if (!serverInFlight && now - lastServerRefresh >= settings.SERVER_REFRESH_MS.get()) {
            lastServerRefresh = now;
            serverInFlight = true;
            refreshServer(bankManager);
        }
    }

    private static void refreshPlayer(IAsyncBankManager bankManager, UUID uuid) {
        try {
            bankManager.getPersonalBankAccountDataAsync(uuid).whenComplete((account, error) -> {
                try {
                    if (error == null) {
                        if (account == null) {
                            PLAYERS.remove(uuid);
                        } else {
                            BankData money = account.bankData.get(MoneyItem.getItemID());
                            PLAYERS.put(uuid, new PlayerSnapshot(
                                    money == null ? 0 : money.balance(),
                                    money == null ? 0 : money.lockedBalance(),
                                    String.valueOf(account.accountNumber)));
                        }
                    }
                } finally {
                    PLAYERS_IN_FLIGHT.remove(uuid);
                }
            });
        } catch (RuntimeException e) {
            PLAYERS_IN_FLIGHT.remove(uuid);
        }
    }

    private static void refreshServer(IAsyncBankManager bankManager) {
        try {
            CompletableFuture<Double> circulation = bankManager.getRealMoneyCirculationAsync();
            CompletableFuture<Double> circulationLocked = bankManager.getRealLockedMoneyCirculationAsync();
            // ponytail: full account dump per refresh; add a dedicated top-N request if servers get huge.
            bankManager.getBankManagerBankAccountsDataAsync()
                    .thenCombine(circulation, (accounts, circ) -> buildSnapshot(accounts == null ? Map.of() : accounts.bankAccountMap(), circ))
                    .thenCombine(circulationLocked, (snapshot, locked) -> new ServerSnapshot(snapshot.circulation(),
                            toRaw(locked), snapshot.accountCount(), snapshot.playerCount(),
                            snapshot.averageTotal(), snapshot.top(), snapshot.rank()))
                    .whenComplete((snapshot, error) -> {
                        if (snapshot != null)
                            server = snapshot;
                        else if (error != null && BACKEND_INSTANCES != null)
                            BACKEND_INSTANCES.LOGGER.warn("Placeholder server stats refresh failed: " + error.getMessage());
                        serverInFlight = false;
                    });
        } catch (RuntimeException e) {
            serverInFlight = false;
        }
    }

    private static ServerSnapshot buildSnapshot(Map<Integer, BankAccountData> accounts, Double circulation) {
        List<TopEntry> personal = new ArrayList<>();
        List<UUID> owners = new ArrayList<>();
        long sum = 0;
        for (BankAccountData account : accounts.values()) {
            if (account.personalBankOwnerData == null)
                continue;
            BankData money = account.bankData.get(MoneyItem.getItemID());
            long total = money == null ? 0 : money.balance() + money.lockedBalance();
            personal.add(new TopEntry(account.personalBankOwnerData.userName(), total));
            owners.add(account.personalBankOwnerData.userUUID());
            sum += total;
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < personal.size(); i++) order.add(i);
        order.sort(Comparator.comparingLong((Integer i) -> personal.get(i).total()).reversed());
        List<TopEntry> top = new ArrayList<>(order.size());
        Map<UUID, Integer> rank = new HashMap<>();
        for (int position = 0; position < order.size(); position++) {
            int i = order.get(position);
            top.add(personal.get(i));
            rank.put(owners.get(i), position + 1);
        }
        long average = personal.isEmpty() ? 0 : sum / personal.size();
        return new ServerSnapshot(toRaw(circulation), 0, accounts.size(), personal.size(), average,
                List.copyOf(top), Map.copyOf(rank));
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    private static long toRaw(Double realAmount) {
        return realAmount == null ? 0 : Math.round(realAmount * BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR);
    }

    private static String raw(long rawAmount) {
        return MoneyFormat.formatMoneyRaw(rawAmount);
    }

    private static String money(long rawAmount) {
        return MoneyFormat.formatMoney(rawAmount);
    }

    private static void player(String name, int refreshMs, Function<PlayerSnapshot, String> value) {
        PlaceholderRegistry.registerPlayer(NS, name, refreshMs, sp -> value.apply(PLAYERS.get(sp.getUUID())));
    }

    private static void server(String name, int refreshMs, Supplier<String> value) {
        PlaceholderRegistry.registerServer(NS, name, refreshMs, value);
    }
}
