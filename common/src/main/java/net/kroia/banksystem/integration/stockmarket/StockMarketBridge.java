package net.kroia.banksystem.integration.stockmarket;

import dev.architectury.platform.Platform;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.util.BankSystemLogger;
import net.kroia.banksystem.util.ItemID;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Task #1 (v2.1.0) — Reflection-based soft-dependency bridge to the StockMarket
 * Market Creation API. This class deliberately contains NO {@code net.kroia.stockmarket.*}
 * imports so BankSystem builds and runs cleanly without StockMarket on the classpath.
 *
 * <p>All methods are <b>server-thread only</b> (SM contract). Callers must marshal.
 */
public final class StockMarketBridge {

    private StockMarketBridge() {}

    // ---- reflection cache ----
    private static volatile boolean initialized = false;
    private static volatile boolean available   = false;
    private static volatile boolean warnedOnce  = false;

    private static Method   mGetAPI;
    private static Method   mGetIntegration;
    private static Method   mOpenMarket;
    private static Method   mMarketExistsFor;
    private static Method   mCloseMarket;
    private static Method   mSetMarketOpen;
    private static Method   mIsMarketOpen;
    private static Method   mResultStatus;
    private static Method   mResultReason;
    private static Constructor<?> ctorMarketConfig;

    public enum Status { SUCCESS, ALREADY_EXISTS, ITEM_BLACKLISTED, FAILED, UNAVAILABLE }
    public record OpenResult(Status status, String reason) {}
    public enum MarketExists { YES, NO, UNAVAILABLE }
    /** Pause/resume state — YES = market exists AND open for trading; NO = exists but paused; UNAVAILABLE = no market or SM absent. */
    public enum MarketOpen   { YES, NO, UNAVAILABLE }

    private static BankSystemLogger logger() {
        try {
            BankSystemModBackend.Instances i = BankSystemModBackend.getInstances_forTesting();
            return i != null ? i.LOGGER : null;
        } catch (Throwable t) { return null; }
    }

    private static void note(Throwable t, String where) {
        BankSystemLogger log = logger();
        String msg = "[StockMarketBridge] " + where + ": " + t.getClass().getSimpleName()
                + (t.getMessage() == null ? "" : (": " + t.getMessage()));
        if (log == null) return;
        if (!warnedOnce) { warnedOnce = true; log.warn(msg); }
        else log.debug(msg);
    }

    private static synchronized boolean initReflection() {
        if (initialized) return available;
        initialized = true;
        try {
            if (!Platform.isModLoaded("stockmarket")) return false;

            Class<?> smMod       = Class.forName("net.kroia.stockmarket.StockMarketMod");
            Class<?> apiCls      = Class.forName("net.kroia.stockmarket.api.StockMarketAPI");
            Class<?> integCls    = Class.forName("net.kroia.stockmarket.api.integration.IStockMarketIntegration");
            Class<?> cfgCls      = Class.forName("net.kroia.stockmarket.api.integration.MarketConfig");
            Class<?> resultCls   = Class.forName("net.kroia.stockmarket.api.integration.MarketOpenResult$Result");

            mGetAPI          = smMod.getMethod("getAPI");
            mGetIntegration  = apiCls.getMethod("getIntegration");
            mOpenMarket      = integCls.getMethod("openMarket", ItemID.class, cfgCls);
            mMarketExistsFor = integCls.getMethod("marketExistsFor", ItemID.class);
            mCloseMarket     = integCls.getMethod("closeMarket", ItemID.class);
            mSetMarketOpen   = integCls.getMethod("setMarketOpen", ItemID.class, boolean.class);
            mIsMarketOpen    = integCls.getMethod("isMarketOpen", ItemID.class);
            mResultStatus    = resultCls.getMethod("status");
            mResultReason    = resultCls.getMethod("reason");
            ctorMarketConfig = cfgCls.getConstructor(boolean.class, float.class, float.class, boolean.class);

            available = true;
            return true;
        } catch (NoClassDefFoundError | ClassNotFoundException e) {
            note(e, "init (SM classes not on classpath)");
            return false;
        } catch (Throwable t) {
            note(t, "init");
            return false;
        }
    }

    private static Object getIntegration() {
        try {
            Object api = mGetAPI.invoke(null);
            if (api == null) return null;
            return mGetIntegration.invoke(api);
        } catch (Throwable t) {
            note(t, "getIntegration");
            return null;
        }
    }

    public static boolean isAvailable() {
        if (!Platform.isModLoaded("stockmarket")) return false;
        if (!initReflection()) return false;
        return getIntegration() != null;
    }

    public static MarketExists marketExistsFor(ItemID subject) {
        if (subject == null) return MarketExists.UNAVAILABLE;
        if (!initReflection()) return MarketExists.UNAVAILABLE;
        Object integ = getIntegration();
        if (integ == null) return MarketExists.UNAVAILABLE;
        try {
            boolean b = (boolean) mMarketExistsFor.invoke(integ, subject);
            return b ? MarketExists.YES : MarketExists.NO;
        } catch (NoClassDefFoundError e) {
            note(e, "marketExistsFor");
            return MarketExists.UNAVAILABLE;
        } catch (Throwable t) {
            note(t, "marketExistsFor");
            return MarketExists.UNAVAILABLE;
        }
    }

    /**
     * Open a share-style market: no synthetic orderbook, no plugin auto-subscribe,
     * abundance zero, starting price = {@code initialPrice}.
     */
    public static OpenResult openMarket(ItemID subject, float initialPrice) {
        if (subject == null) return new OpenResult(Status.UNAVAILABLE, "null subject");
        if (!initReflection()) return new OpenResult(Status.UNAVAILABLE, "SM not present");
        Object integ = getIntegration();
        if (integ == null) return new OpenResult(Status.UNAVAILABLE, "SM integration null");
        try {
            Object cfg = ctorMarketConfig.newInstance(false, initialPrice, 0.0f, true);
            Object result = mOpenMarket.invoke(integ, subject, cfg);
            if (result == null) return new OpenResult(Status.FAILED, "null result");
            Object statusEnum = mResultStatus.invoke(result);
            Object reasonObj  = mResultReason.invoke(result);
            String name = statusEnum == null ? "FAILED" : ((Enum<?>) statusEnum).name();
            Status mapped = switch (name) {
                case "SUCCESS" -> Status.SUCCESS;
                case "ALREADY_EXISTS" -> Status.ALREADY_EXISTS;
                case "ITEM_BLACKLISTED" -> Status.ITEM_BLACKLISTED;
                default -> Status.FAILED;
            };
            return new OpenResult(mapped, reasonObj == null ? null : reasonObj.toString());
        } catch (NoClassDefFoundError e) {
            note(e, "openMarket");
            return new OpenResult(Status.UNAVAILABLE, e.getClass().getSimpleName());
        } catch (Throwable t) {
            note(t, "openMarket");
            return new OpenResult(Status.FAILED, t.getClass().getSimpleName());
        }
    }

    public static boolean closeMarket(ItemID subject) {
        if (subject == null) return false;
        if (!initReflection()) return false;
        Object integ = getIntegration();
        if (integ == null) return false;
        try {
            mCloseMarket.invoke(integ, subject);
            return true;
        } catch (NoClassDefFoundError e) {
            note(e, "closeMarket");
            return false;
        } catch (Throwable t) {
            note(t, "closeMarket");
            return false;
        }
    }

    /**
     * Pause / resume trading on an existing market via {@code IStockMarketIntegration.setMarketOpen}.
     * DESTRUCTIVE when {@code open == false}: SM cancels every open player order
     * (refunds locked balances via the banking system) and hides the market from
     * the client trade screen. Resuming reopens the market with an empty order book.
     * Returns {@code true} when SM was reachable and the call was made;
     * {@code false} when SM is not present or the reflected call failed. A
     * {@code false} return does NOT distinguish between "no market exists" and
     * "SM unavailable" — the underlying API treats no-market as a no-op.
     */
    public static boolean setMarketOpen(ItemID subject, boolean open) {
        if (subject == null) return false;
        if (!initReflection()) return false;
        Object integ = getIntegration();
        if (integ == null) return false;
        try {
            mSetMarketOpen.invoke(integ, subject, open);
            return true;
        } catch (NoClassDefFoundError e) {
            note(e, "setMarketOpen");
            return false;
        } catch (Throwable t) {
            note(t, "setMarketOpen");
            return false;
        }
    }

    /**
     * Query whether an existing market is currently open for trading.
     * Returns {@link MarketOpen#YES} only when a market exists AND is open;
     * {@link MarketOpen#NO} when a market exists but is paused;
     * {@link MarketOpen#UNAVAILABLE} when no market exists or SM is absent.
     */
    public static MarketOpen isMarketOpen(ItemID subject) {
        if (subject == null) return MarketOpen.UNAVAILABLE;
        if (!initReflection()) return MarketOpen.UNAVAILABLE;
        Object integ = getIntegration();
        if (integ == null) return MarketOpen.UNAVAILABLE;
        try {
            // isMarketOpen returns false for both "no market" and "paused". We
            // distinguish them by first checking existence.
            boolean exists = (boolean) mMarketExistsFor.invoke(integ, subject);
            if (!exists) return MarketOpen.UNAVAILABLE;
            boolean open = (boolean) mIsMarketOpen.invoke(integ, subject);
            return open ? MarketOpen.YES : MarketOpen.NO;
        } catch (NoClassDefFoundError e) {
            note(e, "isMarketOpen");
            return MarketOpen.UNAVAILABLE;
        } catch (Throwable t) {
            note(t, "isMarketOpen");
            return MarketOpen.UNAVAILABLE;
        }
    }
}
