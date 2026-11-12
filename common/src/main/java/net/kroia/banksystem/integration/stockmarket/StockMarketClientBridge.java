package net.kroia.banksystem.integration.stockmarket;

import dev.architectury.platform.Platform;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.util.BankSystemLogger;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.gui.elements.base.GuiElement;

import java.lang.reflect.Method;

/**
 * BS-178 — reflection bridge to StockMarket's client API
 * {@code net.kroia.stockmarket.api.integration.StockMarketClientIntegration}
 * (open the trade screen, embeddable live price chart). No
 * {@code net.kroia.stockmarket.*} imports, so BankSystem runs without StockMarket.
 *
 * <p><b>Client thread only.</b> When StockMarket is missing, or is an older version
 * without this API, {@link #isAvailable()} is false and every call is a no-op.
 */
public final class StockMarketClientBridge {

    private StockMarketClientBridge() {}

    private static boolean initialized = false;
    private static boolean available = false;
    private static Method mOpenTradeScreen;
    private static Method mCreatePriceChart;

    private static void note(Throwable t, String where) {
        try {
            BankSystemModBackend.Instances i = BankSystemModBackend.getInstances_forTesting();
            BankSystemLogger log = i != null ? i.LOGGER : null;
            if (log != null) log.warn("[StockMarketClientBridge] " + where + ": " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : (": " + t.getMessage())));
        } catch (Throwable ignored) {}
    }

    private static boolean init() {
        if (initialized) return available;
        initialized = true;
        if (!Platform.isModLoaded("stockmarket")) return false;
        try {
            Class<?> cls = Class.forName("net.kroia.stockmarket.api.integration.StockMarketClientIntegration");
            mOpenTradeScreen = cls.getMethod("openTradeScreen", ItemID.class);
            mCreatePriceChart = cls.getMethod("createPriceChart", ItemID.class);
            available = true;
        } catch (Throwable t) {
            // Older StockMarket without the client API: no button, no chart.
            note(t, "client API not available");
        }
        return available;
    }

    public static boolean isAvailable() {
        return init();
    }

    /** @return true when StockMarket opened its trade screen for {@code market}. */
    public static boolean openTradeScreen(ItemID market) {
        if (market == null || !init()) return false;
        try {
            return Boolean.TRUE.equals(mOpenTradeScreen.invoke(null, market));
        } catch (Throwable t) {
            note(t, "openTradeScreen");
            return false;
        }
    }

    /**
     * @return a live price chart for {@code market}, or null when unavailable. The
     *         caller must {@link #close} it when the chart leaves the screen.
     */
    public static GuiElement createPriceChart(ItemID market) {
        if (market == null || !init()) return null;
        try {
            return (GuiElement) mCreatePriceChart.invoke(null, market);
        } catch (Throwable t) {
            note(t, "createPriceChart");
            return null;
        }
    }

    /** Stops the chart's price stream. */
    public static void close(GuiElement chart) {
        if (!(chart instanceof AutoCloseable c)) return;
        try {
            c.close();
        } catch (Throwable t) {
            note(t, "close chart");
        }
    }
}
