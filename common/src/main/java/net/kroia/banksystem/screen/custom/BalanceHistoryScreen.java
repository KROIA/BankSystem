package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.data.table.record.BalanceHistoryRecord;
import net.kroia.banksystem.networking.general.BalanceHistoryRequest;
import net.kroia.banksystem.screen.widgets.BalanceHistoryChart;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.banksystem.util.ItemColorUtil;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.Tag;
import net.kroia.modutilities.ColorUtilities;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.ItemView;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.TextBox;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/**
 * Client screen showing an interactive balance history chart for a bank account.
 * <p>
 * Opened from the Bank Terminal via the "History" button. Displays a
 * {@link BalanceHistoryChart} with one line per item type, plus an optional
 * "Total Wealth" line when an {@link net.kroia.banksystem.api.ItemPriceProvider}
 * is registered.
 * <p>
 * <b>Filter persistence:</b> Disabled item IDs are stored in the user's
 * {@code customData} CompoundTag under {@code balanceHistory.disabledItems}
 * (a ListTag of ShortTags). On open, the screen fetches the user's custom data
 * via {@code getUserCustomData()}, reads the disabled set, and applies it to
 * series visibility. On each toggle click, the updated set is saved back to the
 * server via {@code updateUserCustomData()}.
 * <p>
 * <b>Item colors:</b> Line colors are derived from each item's texture by
 * sampling the sprite pixels and averaging the RGB values. Saturation is boosted
 * 50% and minimum brightness enforced so muted textures remain distinguishable.
 * Colors are cached per ItemID for the session.
 */
public class BalanceHistoryScreen extends BankSystemGuiScreen {

    // NBT keys for filter persistence inside User.customData
    private static final String CUSTOM_DATA_KEY = "balanceHistory";
    private static final String DISABLED_ITEMS_KEY = "disabledItems";
    private static final String VIEWPORT_KEY = "viewport";

    private static final Component FILTER_LABEL_TEXT = Component.translatable("gui." + BankSystemMod.MOD_ID + ".filter");

    private record ToggleRow(GuiElement element, String name) {}

    private final int accountNumber;
    private final BalanceHistoryChart chart;
    private final Label searchLabel;
    private final TextBox searchField;
    private final VerticalListView toggleListView;
    private final Label titleLabel;

    /** The full user custom data tag, fetched on open and updated on each toggle. */
    private CompoundTag userCustomData;
    /** Set of item IDs whose chart lines are hidden. Persisted in userCustomData. */
    private final Set<Short> disabledItems = new HashSet<>();
    private final Map<Short, BalanceHistoryChart.LineSeries> seriesMap = new LinkedHashMap<>();
    /** Wealth toggle row — always at top of list, not affected by search filtering. */
    private GuiElement wealthRow = null;
    /** Item toggle rows — filtered by the search field. */
    private final List<ToggleRow> toggleRows = new ArrayList<>();
    /** Saved viewport from user custom data, applied once after data loads. */
    private CompoundTag pendingViewport = null;
    /** Task #40: if non-null, {@link #applyData} sets the X viewport to [x, y]
     * (fromMs, toMs) via {@link BalanceHistoryChart#autoCenterViewWithinRange}
     * so the chart shows the full requested window even when data only spans
     * part of it. Cleared after use. Null means "auto-fit to data extents". */
    private long[] pendingTimescaleXRange = null;
    private boolean dataLoaded = false;

    // ── Rolling-fetch state (pan-triggered prefetch) ──
    /** Selected timescale window in ms. {@code -1L} == "All history" (rolling fetch disabled).
     *  Set on every timescale-button click. */
    private long windowMs = -1L;
    /** Viewport-center at the moment of the last successful fetch response. Threshold checks
     *  fire when {@code |viewCenter - anchorMs| >= 0.5 * windowMs}. {@code 0L} == not yet initialized
     *  (safeguards the listener against firing during the very first data-apply). */
    private long anchorMs = 0L;
    /** True while a balance-history fetch is in flight — prevents overlapping requests. */
    private boolean fetchInFlight = false;
    /** If the pan threshold is re-crossed while a fetch is in flight, remember it and
     *  dispatch exactly one follow-up fetch when the current response arrives. */
    private boolean pendingRefetch = false;
    /** Generation counter — incremented on every fetch dispatch. Response handlers ignore
     *  results whose generation is stale (user clicked a different timescale mid-flight). */
    private int fetchGeneration = 0;
    /** Guard so the fresh viewport set by autoCenterViewWithinRange during timescale
     *  data-apply doesn't itself re-trigger the pan listener → rolling fetch. */
    private boolean suppressPanListener = false;
    /** Task #38b: per-item raw-units-per-item ratio (from the account-data snapshot).
     *  Missing entries default to {@link BankSystemModSettings#ITEM_FRACTION_SCALE_FACTOR}
     *  so unbound slots still render correctly. */
    private final Map<Short, Long> ratioByItem = new HashMap<>();

    public BalanceHistoryScreen(Screen parent, int accountNumber) {
        super(Component.literal("Balance History"), parent);
        this.accountNumber = accountNumber;

        titleLabel = new Label("Balance History - Account #" + accountNumber);
        titleLabel.setTextFontScale(1.0f);
        addElement(titleLabel);

        chart = new BalanceHistoryChart();
        chart.setTimescaleChangeListener(this::onTimescaleSelected);
        chart.setViewChangeListener(this::onChartViewChanged);
        addElement(chart);

        searchLabel = new Label(FILTER_LABEL_TEXT.getString());
        addElement(searchLabel);

        searchField = new TextBox();
        searchField.setOnTextChanged(this::onSearchChanged);
        addElement(searchField);

        toggleListView = new VerticalListView();
        LayoutGrid toggleLayout = new LayoutGrid();
        toggleLayout.stretchX = true;
        toggleLayout.columns = 1;
        toggleListView.setLayout(toggleLayout);
        addElement(toggleListView);

        fetchAccountName();
        loadUserSettings();
    }

    @Override
    protected void updateLayout(Gui gui) {
        int p = 5;
        int toggleWidth = Math.max(80, getWidth() / 5);
        int titleHeight = 15;

        int searchHeight = 14;
        int toggleX = getWidth() - toggleWidth - p;
        int toggleTop = p + titleHeight + p;

        titleLabel.setBounds(p, p, getWidth() - 2 * p, titleHeight);
        chart.setBounds(p, toggleTop, toggleX - 2 * p, getHeight() - titleHeight - 3 * p);
        int searchLabelWidth = searchLabel.getTextWidth(searchLabel.getText()) + searchLabel.getPadding() * 2;
        searchLabel.setBounds(toggleX, toggleTop, searchLabelWidth, searchHeight);
        searchField.setBounds(toggleX + searchLabelWidth, toggleTop, toggleWidth - searchLabelWidth, searchHeight);
        toggleListView.setBounds(toggleX, toggleTop + searchHeight + p, toggleWidth, getHeight() - toggleTop - searchHeight - 2 * p);
    }

    @Override
    public void removed() {
        if (dataLoaded) saveUserSettings();
        super.removed();
    }

    private void fetchAccountName() {
        getBankManager().getBankAccountDataAsync(accountNumber).thenAccept(data ->
                Minecraft.getInstance().execute(() -> {
                    if (data != null) {
                        titleLabel.setText("Balance History - " + data.accountName + " (#" + accountNumber + ")");
                        ratioByItem.clear();
                        for (Map.Entry<ItemID, net.kroia.banksystem.banking.clientdata.BankData> e : data.bankData.entrySet()) {
                            ratioByItem.put(e.getKey().getShort(), e.getValue().rawUnitsPerItem());
                        }
                    }
                })
        );
    }

    // ── Filter persistence ──
    // Disabled items are stored in User.customData as:
    //   customData -> "balanceHistory" (CompoundTag) -> "disabledItems" (ListTag<ShortTag>)
    // Loaded once on screen open via getUserCustomData() ARRS request.
    // Saved on each toggle via updateUserCustomData() ARRS request.

    private void loadUserSettings() {
        getBankManager().getUserCustomData().thenAccept(customData ->
                Minecraft.getInstance().execute(() -> {
                    userCustomData = customData;
                    readDisabledItems(customData);
                    readViewport(customData);
                    requestData();
                })
        );
    }

    private void requestData() {
        // Initial load on screen open: no timescale selected yet, so route through the
        // legacy all-history path. The rolling-fetch machinery stays dormant until the
        // user picks a specific window (1h/6h/…/30d).
        long now = System.currentTimeMillis();
        windowMs = -1L;
        chart.setMaxViewWidthMs(null);
        anchorMs = 0L;
        pendingRefetch = false;
        fetchGeneration++;
        fetchInFlight = true;
        final int gen = fetchGeneration;
        chart.setClampBounds(null, null); // legacy clamp-to-data mode
        getBankManager()
                .requestBalanceHistory(accountNumber)
                .thenAccept(records -> onFetchResponse(records,
                        BalanceHistoryRequest.Query.ALL_HISTORY_SENTINEL, now, gen, false));
    }

    /**
     * Handles a timescale-button click from the chart (Task #40). Picks a
     * <b>2× window</b> data slice ending at {@code now} so that half the buffered
     * data lies left-of-viewport, ready to reveal as the user drags left. The
     * viewport itself is set to the trailing 1× window {@code [now-windowMs, now]}.
     * <p>
     * From that point on, {@link #onChartViewChanged} tracks the pan-threshold
     * ({@code |viewCenter - anchorMs| >= 0.5 * windowMs}) and issues a
     * {@link #dispatchRollingFetch rolling fetch} to keep buffered data ahead of
     * the user's drag. See spec for the full "pan-triggered rolling fetch" design.
     * <p>
     * A negative window means "all history" — the request is translated to
     * {@link BalanceHistoryRequest.Query#ALL_HISTORY_SENTINEL} and rolling-fetch
     * mode stays disabled (existing behavior; data already spans everything).
     */
    private void onTimescaleSelected(long windowMs) {
        long now = System.currentTimeMillis();
        this.windowMs = windowMs;
        chart.setMaxViewWidthMs(windowMs > 0 ? windowMs : null);
        // Reset per-session rolling state — a fresh timescale click starts a new session.
        anchorMs = 0L;
        pendingRefetch = false;
        fetchGeneration++;
        fetchInFlight = true;
        final int gen = fetchGeneration;

        long fromMs;
        long toMs = now;
        int maxPoints;

        if (windowMs < 0) {
            // "All history" — legacy behavior, no rolling fetch.
            fromMs = BalanceHistoryRequest.Query.ALL_HISTORY_SENTINEL;
            pendingTimescaleXRange = null;
            maxPoints = 500;
            chart.setClampBounds(null, null);
        } else {
            // Finite window: over-fetch 2× (twice the wall time AND twice the point budget)
            // ending at now. Viewport is set to trailing 1× ending at now, leaving half the
            // buffered data left-of-view. See spec §1.
            fromMs = now - 2L * windowMs;
            pendingTimescaleXRange = new long[]{now - windowMs, now};
            maxPoints = 1000;
            // Left clamp is disabled — the anchor row from the server means the chart always
            // has a data point at (or before) the visible left edge, and the user can freely
            // pan into the past. Right edge always clamps at "now" at time of last fetch.
            chart.setClampBounds(null, now);
        }

        final long reqFrom = fromMs;
        final long reqTo = toMs;
        getBankManager()
                .requestBalanceHistory(accountNumber, fromMs, toMs, maxPoints)
                .thenAccept(records -> onFetchResponse(records, reqFrom, reqTo, gen, false));
    }

    /**
     * Called by the chart on every viewport change (drag, scroll-zoom, or programmatic
     * viewport set). Enforces the pan-threshold rule: when the viewport-center has
     * drifted at least 50% of the current window from the last-fetch anchor, dispatch
     * a rolling fetch centered on the current viewCenter.
     * <p>
     * The 50% threshold is well before the loaded-data edge (data extends ±windowMs
     * from anchor, viewport is windowMs wide, so threshold-cross still leaves a full
     * 0.5×windowMs buffer). By the time the fetch response arrives, the user typically
     * hasn't reached the edge yet.
     */
    private void onChartViewChanged(double vx, double vw) {
        if (suppressPanListener) return;
        if (windowMs <= 0) return;      // "All history" mode — no rolling fetch
        if (anchorMs == 0L) return;     // Anchor not yet initialized (initial fetch in progress)
        // Keep the right clamp fresh with actual current time so wall-clock drift doesn't
        // artificially cap the viewport short of "now" between fetches. Left clamp is
        // always null — the anchor row lets the user drag arbitrarily far into the past.
        chart.setClampBounds(null, System.currentTimeMillis());

        double viewCenter = vx + vw / 2.0;
        double drift = Math.abs(viewCenter - anchorMs);
        if (drift >= 0.5 * windowMs) {
            if (fetchInFlight) {
                pendingRefetch = true;
            } else {
                dispatchRollingFetch();
            }
        }
    }

    /**
     * Issues a 2×-window balance-history request centered on the current viewport-center.
     * If {@code viewCenter + windowMs} exceeds {@code now}, {@code toMs} is clamped to
     * {@code now} (we never request future data).
     * <p>
     * Concurrency: caller must guarantee {@code !fetchInFlight} — this method sets it to
     * {@code true}. The fetch generation is incremented so late-arriving responses from
     * previously-superseded requests are dropped.
     */
    private void dispatchRollingFetch() {
        if (windowMs <= 0) return;
        long now = System.currentTimeMillis();
        long viewCenter = (long) (chart.getViewX() + chart.getViewWidth() / 2.0);
        long fromMs = viewCenter - windowMs;
        long toMs = viewCenter + windowMs;
        if (toMs > now) toMs = now;

        fetchGeneration++;
        fetchInFlight = true;
        final int gen = fetchGeneration;
        final long reqFrom = fromMs;
        final long reqTo = toMs;

        getBankManager()
                .requestBalanceHistory(accountNumber, fromMs, toMs, 1000)
                .thenAccept(records -> onFetchResponse(records, reqFrom, reqTo, gen, true));
    }

    /**
     * Unified fetch-response handler. Drops stale results (generation mismatch),
     * refreshes chart clamp bounds, applies the data (either respecting the pending
     * viewport for a timescale click or preserving the current user-driven viewport
     * for a rolling fetch), then updates the anchor and dispatches a queued follow-up
     * fetch if one is pending.
     *
     * @param records          the response records (may be null / empty). May include
     *                         one "anchor" row per item_id with {@code time < reqFromMs}
     *                         — see {@code BalanceHistoryManager.getHistoryBucketed}
     * @param reqFromMs        the {@code fromMs} value passed to this fetch (unused —
     *                         retained for future diagnostic use)
     * @param reqToMs          the {@code toMs} value passed to this fetch (unused —
     *                         retained for future diagnostic use)
     * @param gen              the generation captured at dispatch — must match
     *                         {@link #fetchGeneration} or the response is discarded
     * @param preserveViewport if {@code true}, {@link #applyData} skips the viewport
     *                         reset (rolling-fetch case — the user's drag is source of
     *                         truth). If {@code false}, the standard pendingViewport /
     *                         pendingTimescaleXRange / autoCenterView flow runs.
     */
    private void onFetchResponse(List<BalanceHistoryRecord> records,
                                  long reqFromMs, long reqToMs, int gen,
                                  boolean preserveViewport) {
        if (gen != fetchGeneration) return;
        Minecraft.getInstance().execute(() -> {
            // Re-check on client thread — a newer request may have been dispatched
            // while this response was hopping threads.
            if (gen != fetchGeneration) return;
            fetchInFlight = false;
            if (windowMs > 0) {
                chart.setClampBounds(null, System.currentTimeMillis());
            }

            // Empty response for a timescale click: still snap the viewport to the
            // requested X range so the chart shows the correct empty window (matches
            // pre-rolling-fetch behavior).
            if (records == null || records.isEmpty()) {
                if (!preserveViewport && pendingTimescaleXRange != null) {
                    long from = pendingTimescaleXRange[0];
                    long to = pendingTimescaleXRange[1];
                    pendingTimescaleXRange = null;
                    suppressPanListener = true;
                    try { chart.autoCenterViewWithinRange(from, to); }
                    finally { suppressPanListener = false; }
                }
            } else {
                suppressPanListener = true;
                try { applyData(records, preserveViewport); }
                finally { suppressPanListener = false; }
            }

            // Anchor at the current viewport-center — matches spec §"anchorMs initialization"
            // for both the initial timescale fetch (viewport is trailing 1× ending at now,
            // center = now - windowMs/2) and rolling fetches (viewport unchanged, still
            // sits over the returned data which is centered on viewCenter).
            if (windowMs > 0) {
                anchorMs = (long) (chart.getViewX() + chart.getViewWidth() / 2.0);
            }

            if (pendingRefetch) {
                pendingRefetch = false;
                if (windowMs > 0) dispatchRollingFetch();
            }
        });
    }

    private void applyData(List<BalanceHistoryRecord> records) {
        applyData(records, false);
    }

    /**
     * @param preserveViewport if {@code true}, skip the viewport reset at the end
     *     (pendingViewport / pendingTimescaleXRange / autoCenterView) — the caller's
     *     current viewport is the source of truth. Used by rolling fetches so a
     *     silent data replacement doesn't yank the chart out from under the user
     *     mid-drag.
     */
    private void applyData(List<BalanceHistoryRecord> records, boolean preserveViewport) {
        Map<Short, List<BalanceHistoryRecord>> grouped = new LinkedHashMap<>();
        for (BalanceHistoryRecord r : records) {
            grouped.computeIfAbsent(r.itemId(), k -> new ArrayList<>()).add(r);
        }

        chart.clearSeries();
        chart.clearHoverBindings();
        toggleListView.removeChilds();
        seriesMap.clear();
        toggleRows.clear();
        wealthRow = null;

        int colorIndex = 0;
        // Wealth uses the aggregate "cent" unit (global constant) — not slot-item-ratio dependent.
        double wealthScaleFactor = BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;

        // Wealth series (from ItemPriceProvider) — added first, pinned on top
        List<BalanceHistoryRecord> wealthRecords = grouped.remove(BalanceHistoryRecord.WEALTH_ITEM_ID);
        if (wealthRecords != null && !wealthRecords.isEmpty()) {
            addWealthSeries(wealthRecords, wealthScaleFactor);
        }

        for (Map.Entry<Short, List<BalanceHistoryRecord>> entry : grouped.entrySet()) {
            short itemId = entry.getKey();
            // Task #24: skip series for items this client can't resolve (a mod on the master but
            // not here) — they would plot under an air / wrong-item icon. Display-only.
            if (!ItemIDManager.isResolvableOnThisServer(new ItemID(itemId)))
                continue;
            List<BalanceHistoryRecord> itemRecords = entry.getValue();

            ItemStack itemStack = getItemStack(itemId);
            int color = ItemColorUtil.getColor(itemId, itemStack, colorIndex);
            String name = getItemName(itemId);

            // Task #38b: per-item ratio from the account snapshot (defaults to
            // ITEM_FRACTION_SCALE_FACTOR when the item isn't in the snapshot).
            long ratio = ratioByItem.getOrDefault(itemId,
                    (long) BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR);
            double scaleFactor = ratio > 0 ? (double) ratio : BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
            BalanceHistoryChart.LineSeries series = new BalanceHistoryChart.LineSeries(name, color);
            series.visible = !disabledItems.contains(itemId);
            for (BalanceHistoryRecord r : itemRecords) {
                double totalBalance = (r.balance() + r.lockedBalance()) / scaleFactor;
                series.points.add(new BalanceHistoryChart.DataPoint(r.time(), totalBalance));
            }
            chart.addSeries(series);
            seriesMap.put(itemId, series);

            GuiElement row = new GuiElement(0, 0, 100, 18) {
                @Override protected void render() {}
                @Override protected void layoutChanged() {
                    for (var child : getChilds()) {
                        if (child instanceof ItemView iv) {
                            iv.setBounds(1, 1, 16, 16);
                        } else if (child instanceof Button btn) {
                            btn.setBounds(19, 0, getWidth() - 19, 18);
                        }
                    }
                }
            };
            row.setEnableBackground(false);
            row.setEnableOutline(false);

            ItemView itemView = new ItemView();
            itemView.setItemStack(itemStack);
            itemView.setShowTooltip(true);
            row.addChild(itemView);

            chart.bindHoverElement(row, series);

            int activeColor = ColorUtilities.setAlpha(color, 0.8f);
            int hoverColor = ColorUtilities.setBrightness(color, 1.4f);
            int inactiveColor = ColorUtilities.getRGB(60, 60, 60);
            Button toggleButton = new Button(name);
            toggleButton.setBackgroundColor(series.visible ? activeColor : inactiveColor);
            toggleButton.setHoverColor(hoverColor);
            final short fItemId = itemId;
            final BalanceHistoryChart.LineSeries toggleSeries = series;
            toggleButton.setOnFallingEdge(() -> {
                toggleSeries.visible = !toggleSeries.visible;
                toggleButton.setBackgroundColor(toggleSeries.visible ? activeColor : inactiveColor);
                if (toggleSeries.visible) {
                    disabledItems.remove(fItemId);
                } else {
                    disabledItems.add(fItemId);
                }
                saveUserSettings();
            });
            row.addChild(toggleButton);

            String searchableName = buildSearchableName(name, itemStack);
            toggleRows.add(new ToggleRow(row, searchableName));
            toggleListView.addChild(row);
            colorIndex++;
        }

        if (preserveViewport) {
            // Rolling fetch: leave viewport exactly where the user's drag put it. Y
            // is also left untouched (per spec — user can hit SPACE to auto-center Y).
        } else if (pendingViewport != null) {
            double y = pendingViewport.getDouble("y");
            double w = pendingViewport.getDouble("w");
            double h = pendingViewport.getDouble("h");
            if (pendingViewport.getBoolean("atPresent")) {
                chart.setView(0, y, w, h);
                chart.scrollToLatestData();
            } else {
                chart.setView(pendingViewport.getDouble("x"), y, w, h);
            }
            pendingViewport = null;
            // Saved-viewport restore: bootstrap rolling-fetch mode from the restored
            // window. The initial all-history fetch may not have dense coverage around
            // the saved viewport-center, so kick off a 2×-window fetch centered there.
            // Guard: skip when the saved width looks like an "all history" span (heuristic:
            // wider than ~400 days), which shouldn't be rolling-fetched.
            double restoredWidth = chart.getViewWidth();
            long allHistoryThresholdMs = 400L * 24L * 60L * 60L * 1000L;
            if (restoredWidth > 0 && restoredWidth < allHistoryThresholdMs) {
                windowMs = (long) restoredWidth;
                chart.setMaxViewWidthMs(windowMs);
                pendingRefetch = false;
                chart.setClampBounds(null, System.currentTimeMillis());
                // Fire pan-check logic: onChartViewChanged will be a no-op because
                // anchorMs is still 0 at this point (initial fetch just landed).
                // Instead, we dispatch an explicit rolling fetch to get the 2× buffer.
                dispatchRollingFetch();
            }
        } else if (pendingTimescaleXRange != null) {
            chart.autoCenterViewWithinRange(pendingTimescaleXRange[0], pendingTimescaleXRange[1]);
            pendingTimescaleXRange = null;
        } else {
            chart.autoCenterView();
        }
        dataLoaded = true;
    }

    /**
     * Adds the "Total Wealth" series (gold line, pinned on top).
     * Only present when an ItemPriceProvider is registered.
     * The wealth row stays at the top of the toggle list and is not
     * affected by the search filter.
     */
    private void addWealthSeries(List<BalanceHistoryRecord> wealthRecords, double scaleFactor) {
        int wealthColor = ColorUtilities.getRGB(255, 215, 0);
        String wealthName = "Total Wealth";
        short wealthId = BalanceHistoryRecord.WEALTH_ITEM_ID;

        BalanceHistoryChart.LineSeries wealthSeries = new BalanceHistoryChart.LineSeries(wealthName, wealthColor);
        wealthSeries.visible = !disabledItems.contains(wealthId);
        for (BalanceHistoryRecord r : wealthRecords) {
            wealthSeries.points.add(new BalanceHistoryChart.DataPoint(r.time(), r.balance() / scaleFactor));
        }
        chart.addSeries(wealthSeries);
        chart.setPinnedSeries(wealthSeries);
        seriesMap.put(wealthId, wealthSeries);

        wealthRow = new GuiElement(0, 0, 100, 18) {
            @Override protected void render() {}
            @Override protected void layoutChanged() {
                for (var child : getChilds()) {
                    if (child instanceof Button btn) {
                        btn.setBounds(0, 0, getWidth(), 18);
                    }
                }
            }
        };
        wealthRow.setEnableBackground(false);
        wealthRow.setEnableOutline(false);

        chart.bindHoverElement(wealthRow, wealthSeries);

        int wealthActive = ColorUtilities.setAlpha(wealthColor, 0.8f);
        int wealthHover = ColorUtilities.setBrightness(wealthColor, 1.4f);
        int wealthInactive = ColorUtilities.getRGB(60, 60, 60);
        Button wealthToggle = new Button(wealthName);
        wealthToggle.setBackgroundColor(wealthSeries.visible ? wealthActive : wealthInactive);
        wealthToggle.setHoverColor(wealthHover);
        wealthToggle.setOnFallingEdge(() -> {
            wealthSeries.visible = !wealthSeries.visible;
            wealthToggle.setBackgroundColor(wealthSeries.visible ? wealthActive : wealthInactive);
            if (wealthSeries.visible) disabledItems.remove(wealthId);
            else disabledItems.add(wealthId);
            saveUserSettings();
        });
        wealthRow.addChild(wealthToggle);
        toggleListView.addChild(wealthRow);
    }

    /** Reads the disabled item set from the user's custom data NBT. */
    private void readDisabledItems(CompoundTag customData) {
        disabledItems.clear();
        if (customData == null || !customData.contains(CUSTOM_DATA_KEY)) return;
        CompoundTag historyTag = customData.getCompound(CUSTOM_DATA_KEY);
        if (!historyTag.contains(DISABLED_ITEMS_KEY)) return;
        ListTag list = historyTag.getList(DISABLED_ITEMS_KEY, Tag.TAG_SHORT);
        for (Tag tag : list) {
            if (tag instanceof ShortTag shortTag) {
                disabledItems.add(shortTag.getAsShort());
            }
        }
    }

    private void readViewport(CompoundTag customData) {
        pendingViewport = null;
        if (customData == null || !customData.contains(CUSTOM_DATA_KEY)) return;
        CompoundTag historyTag = customData.getCompound(CUSTOM_DATA_KEY);
        if (historyTag.contains(VIEWPORT_KEY)) {
            pendingViewport = historyTag.getCompound(VIEWPORT_KEY);
        }
    }

    /**
     * Persists the current disabled item set to the server.
     * Writes to customData["balanceHistory"]["disabledItems"] as a ListTag of ShortTags,
     * then sends the full customData via updateUserCustomData() ARRS request.
     */
    private void saveUserSettings() {
        if (userCustomData == null) userCustomData = new CompoundTag();
        CompoundTag historyTag = new CompoundTag();
        ListTag list = new ListTag();
        for (short id : disabledItems) {
            list.add(ShortTag.valueOf(id));
        }
        historyTag.put(DISABLED_ITEMS_KEY, list);

        if (dataLoaded) {
            CompoundTag viewportTag = new CompoundTag();
            boolean atPresent = chart.isAtPresent();
            viewportTag.putBoolean("atPresent", atPresent);
            if (!atPresent) {
                viewportTag.putDouble("x", chart.getViewX());
            }
            viewportTag.putDouble("y", chart.getViewY());
            viewportTag.putDouble("w", chart.getViewWidth());
            viewportTag.putDouble("h", chart.getViewHeight());
            historyTag.put(VIEWPORT_KEY, viewportTag);
        }

        userCustomData.put(CUSTOM_DATA_KEY, historyTag);
        getBankManager().updateUserCustomData(userCustomData);
    }

    private String getItemName(short itemId) {
        ItemStack stack = getItemStack(itemId);
        if (stack != null && !stack.isEmpty()) {
            return stack.getHoverName().getString();
        }
        return "Item #" + itemId;
    }

    private ItemStack getItemStack(short itemId) {
        ItemID id = new ItemID(itemId);
        return id.getStack();
    }

    /**
     * Builds a search string from the item's display name + all tag paths.
     * Allows searching by tag (e.g. "log" matches items tagged minecraft:logs).
     */
    private String buildSearchableName(String displayName, ItemStack stack) {
        StringBuilder sb = new StringBuilder(displayName.toLowerCase());
        if (stack != null && !stack.isEmpty()) {
            stack.getTags().forEach(tagKey -> {
                String path = tagKey.location().getPath();
                sb.append(' ').append(path.replace('_', ' ').replace('/', ' '));
            });
        }
        return sb.toString();
    }

    /**
     * Filters the toggle list by search query. The wealth row is always
     * kept at the top regardless of the query. Item rows are matched
     * against their searchable name (display name + tag paths).
     */
    private void onSearchChanged(String text) {
        String query = text.trim().toLowerCase();
        toggleListView.removeChilds();
        if (wealthRow != null) {
            toggleListView.addChild(wealthRow);
        }
        for (ToggleRow row : toggleRows) {
            if (query.isEmpty() || row.name().contains(query)) {
                toggleListView.addChild(row.element());
            }
        }
    }
}
