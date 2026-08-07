package net.kroia.banksystem.screen.uiElements.tabbody;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.banking.company.PayoutSchedule;
import net.kroia.banksystem.screen.custom.CompanyManagementScreen;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.banksystem.util.TimeFormat;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.ItemView;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * v2.0.9 — Statistics tab for CompanyManagementScreen.
 */
public class StatisticsTabBody extends TabBody {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".company_management_screen.";
    private static final String[] TIMEFRAME_KEYS = {"tf_24h", "tf_7d", "tf_30d", "tf_90d", "tf_all"};

    /** Runtime-only cache of selected timeframe per company — never persisted to server. */
    private static final java.util.Map<Integer, Integer> TIMEFRAME_CACHE = new java.util.HashMap<>();

    private final Button[] tfButtons;
    private int selectedTimeframe;

    private final Label balanceLabel;
    private final Label netCashflowLabel;
    private final Label insolvencyLabel;
    private final Label missedPayoutsLabel;
    private final Label loadingLabel;

    private final CashflowChartWidget chart;
    private final Label xAxisLeft, xAxisMid, xAxisRight;
    private final Label yAxisTop, yAxisMid, yAxisBot;

    private final ScrollableTableWidget shareholdersTable;
    private final ScrollableTableWidget payoutsTable;

    private AsyncCompanyManager.CompanyStatsPayload stats;
    private List<AsyncCompanyManager.ScheduleWire> schedules = List.of();
    private boolean loading = false;
    private int lastKnownWidth = -1;

    public StatisticsTabBody(CompanyManagementScreen screen) {
        super(screen);

        selectedTimeframe = TIMEFRAME_CACHE.getOrDefault(screen.getCompanyId(), 1);

        tfButtons = new Button[5];
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            tfButtons[i] = new Button(
                    Component.translatable(PREFIX + TIMEFRAME_KEYS[i]).getString(),
                    () -> onTimeframeSelected(idx));
            addChild(tfButtons[i]);
        }
        updateTfButtonStyles();

        balanceLabel = new Label("");
        netCashflowLabel = new Label("");
        insolvencyLabel = new Label("");
        missedPayoutsLabel = new Label("");
        loadingLabel = new Label(Component.translatable(PREFIX + "stats_loading").getString());
        addChild(balanceLabel);
        addChild(netCashflowLabel);
        addChild(insolvencyLabel);
        addChild(missedPayoutsLabel);
        addChild(loadingLabel);

        balanceLabel.setHoverTooltipSupplier(
                () -> Component.translatable(PREFIX + "stats_balance_tooltip").getString());
        balanceLabel.setHoverTooltipMousePositionAlignment(GuiElement.Alignment.TOP_LEFT);
        netCashflowLabel.setHoverTooltipSupplier(
                () -> Component.translatable(PREFIX + "stats_net_cashflow_tooltip").getString());
        netCashflowLabel.setHoverTooltipMousePositionAlignment(GuiElement.Alignment.TOP_LEFT);
        insolvencyLabel.setHoverTooltipSupplier(
                () -> Component.translatable(PREFIX + "stats_insolvency_tooltip").getString());
        insolvencyLabel.setHoverTooltipMousePositionAlignment(GuiElement.Alignment.TOP_RIGHT);
        missedPayoutsLabel.setHoverTooltipSupplier(
                () -> Component.translatable(PREFIX + "stats_missed_payouts_tooltip").getString());
        missedPayoutsLabel.setHoverTooltipMousePositionAlignment(GuiElement.Alignment.TOP_RIGHT);

        chart = new CashflowChartWidget();
        addChild(chart);

        xAxisLeft = new Label(""); xAxisLeft.setAlignment(Label.Alignment.LEFT);
        xAxisMid  = new Label(""); xAxisMid.setAlignment(Label.Alignment.CENTER);
        xAxisRight = new Label(""); xAxisRight.setAlignment(Label.Alignment.RIGHT);
        addChild(xAxisLeft); addChild(xAxisMid); addChild(xAxisRight);

        yAxisTop = new Label(""); yAxisTop.setAlignment(Label.Alignment.RIGHT);
        yAxisMid = new Label("0"); yAxisMid.setAlignment(Label.Alignment.RIGHT);
        yAxisBot = new Label(""); yAxisBot.setAlignment(Label.Alignment.RIGHT);
        addChild(yAxisTop); addChild(yAxisMid); addChild(yAxisBot);

        // Shareholders: col 0 left-align, cols 1+2 center
        Label.Alignment[] shareDataAligns = {
                Label.Alignment.LEFT, Label.Alignment.CENTER, Label.Alignment.CENTER};
        Label.Alignment[] shareHeadAligns = {
                Label.Alignment.LEFT, Label.Alignment.CENTER, Label.Alignment.CENTER};
        shareholdersTable = new ScrollableTableWidget(
                Component.translatable(PREFIX + "stats_shareholders").getString(),
                new String[]{
                        Component.translatable(PREFIX + "stats_col_account").getString(),
                        Component.translatable(PREFIX + "stats_col_shares").getString(),
                        Component.translatable(PREFIX + "stats_col_pct").getString()},
                new int[]{50, 25, 25},
                shareHeadAligns, shareDataAligns);
        addChild(shareholdersTable);

        // Payouts: col 0 left, col 1 left (has item icon)
        payoutsTable = new ScrollableTableWidget(
                Component.translatable(PREFIX + "stats_upcoming_payouts").getString(),
                new String[]{
                        Component.translatable(PREFIX + "stats_col_account").getString(),
                        Component.translatable(PREFIX + "stats_col_amount").getString()},
                new int[]{55, 45},
                null, null);
        addChild(payoutsTable);

        updateXAxisLabels();
        fetchStats();
    }

    private void updateXAxisLabels() {
        String left, mid, right = "Now";
        switch (selectedTimeframe) {
            case 0 -> { left = "24h ago"; mid = "12h ago"; }
            case 1 -> { left = "7d ago";  mid = "3d ago";  }
            case 2 -> { left = "30d ago"; mid = "15d ago"; }
            case 3 -> { left = "90d ago"; mid = "45d ago"; }
            default -> { left = "Oldest"; mid = "";        }
        }
        xAxisLeft.setText(left); xAxisMid.setText(mid); xAxisRight.setText(right);
    }

    private void onTimeframeSelected(int idx) {
        if (selectedTimeframe == idx) return;
        selectedTimeframe = idx;
        TIMEFRAME_CACHE.put(screen.getCompanyId(), idx);
        updateTfButtonStyles();
        updateXAxisLabels();
        fetchStats();
    }

    private void updateTfButtonStyles() {
        for (int i = 0; i < tfButtons.length; i++)
            tfButtons[i].setBackgroundColor(i == selectedTimeframe ? 0xFF3355AA : 0xFF404040);
    }

    private void fetchStats() {
        loading = true;
        loadingLabel.setText(Component.translatable(PREFIX + "stats_loading").getString());
        int companyId = screen.getCompanyId();
        AsyncCompanyManager.getCompanyStatsAsync(companyId, selectedTimeframe)
                .thenAccept(payload -> AsyncCompanyManager.listSchedulesAsync(companyId)
                        .thenAccept(schedulesOut -> onClientThread(() -> {
                            stats = payload;
                            schedules = schedulesOut != null ? new ArrayList<>(schedulesOut.schedules()) : List.of();
                            loading = false;
                            applyStats();
                            layoutChangedInternal();
                        })));
    }

    private void applyStats() {
        if (stats == null) {
            loadingLabel.setText(Component.translatable(PREFIX + "stats_loading").getString());
            balanceLabel.setText(""); netCashflowLabel.setText("");
            insolvencyLabel.setText(""); missedPayoutsLabel.setText("");
            chart.setData(List.of());
            yAxisTop.setText(""); yAxisMid.setText("0"); yAxisBot.setText("");
            shareholdersTable.setData(List.of(), null, null);
            payoutsTable.setData(List.of(), null, null);
            return;
        }
        loadingLabel.setText("");

        balanceLabel.setText(Component.translatable(PREFIX + "stats_balance").getString()
                + ": " + fmtVal(stats.currentBalance()));

        long net = stats.netCashflow();
        netCashflowLabel.setText(Component.translatable(PREFIX + "stats_net_cashflow").getString()
                + ": " + (net >= 0 ? "+" : "") + fmtVal(Math.abs(net)));

        long days = stats.daysToInsolvency();
        String daysText;
        if (days < 0) { daysText = Component.translatable(PREFIX + "stats_solvency_safe").getString(); insolvencyLabel.setTextColor(0xFF55FF55); }
        else if (days > 30) { daysText = days + "d"; insolvencyLabel.setTextColor(0xFF55FF55); }
        else if (days >= 7) { daysText = days + "d"; insolvencyLabel.setTextColor(0xFFFFAA00); }
        else                { daysText = days + "d"; insolvencyLabel.setTextColor(0xFFFF5555); }
        insolvencyLabel.setText(Component.translatable(PREFIX + "stats_days_insolvency").getString() + ": " + daysText);

        missedPayoutsLabel.setText(Component.translatable(PREFIX + "stats_missed_payouts").getString()
                + ": " + stats.missedPayoutCount()
                + " (" + fmtVal(stats.missedPayoutAmount()) + ")");

        List<AsyncCompanyManager.CashflowBucketWire> series = stats.cashflowSeries();
        chart.setData(series);

        long maxVal = 1L;
        for (AsyncCompanyManager.CashflowBucketWire b : series)
            maxVal = Math.max(maxVal, Math.max(b.earnings(), b.spendings()));
        yAxisTop.setText(fmtVal(maxVal));
        yAxisMid.setText("0");
        yAxisBot.setText(fmtVal(maxVal));

        // Shareholders table
        List<AsyncCompanyManager.ShareholderWire> holders = stats.topShareholders();
        if (holders.isEmpty()) {
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{Component.translatable(PREFIX + "stats_no_shareholders").getString(), "", ""});
            shareholdersTable.setData(rows, null, null);
        } else {
            List<String[]> rows = new ArrayList<>();
            for (AsyncCompanyManager.ShareholderWire h : holders) {
                rows.add(new String[]{
                        h.accountName(),
                        fmtVal(h.shares()),
                        String.format("%.1f%%", h.pct() * 100f)
                });
            }
            shareholdersTable.setData(rows, null, null);
        }

        // Upcoming payouts table — icon per row for the amount column
        if (schedules.isEmpty()) {
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{Component.translatable(PREFIX + "stats_no_upcoming_payouts").getString(), ""});
            payoutsTable.setData(rows, null, null);
        } else {
            AsyncCompanyManager.CompanyInfoOutput info = screen.info();
            short companyCurrency = (info != null && info.present())
                    ? info.companyCurrency() : PayoutSchedule.MONEY_CURRENCY;

            List<String[]> rows = new ArrayList<>();
            List<ItemStack[]> rowIcons = new ArrayList<>();
            int[] colors = new int[schedules.size() + 1];
            long currencyTotal = 0L;
            for (int i = 0; i < schedules.size(); i++) {
                AsyncCompanyManager.ScheduleWire s = schedules.get(i);
                String target;
                if (s.targetAccountNr() < 0) {
                    target = Component.translatable(PREFIX + "stats_dividend_target").getString();
                    colors[i] = 0xFF55AAFF;
                } else {
                    target = (s.targetAccountName() != null && !s.targetAccountName().isEmpty())
                            ? s.targetAccountName() : "#" + s.targetAccountNr();
                    colors[i] = 0xFFFFFFFF;
                }
                String amountStr = fmtVal(s.amount());
                ItemStack currencyIcon = resolveCurrencyStack(s.currencyItem());
                if (s.currencyItem() == companyCurrency) {
                    currencyTotal += s.amount();
                }
                String intervalStr = (s.mode() == (byte) PayoutSchedule.Mode.ONE_TIME.ordinal())
                        ? "Once" : TimeFormat.formatTickDuration(s.intervalTicks());
                rows.add(new String[]{target, amountStr + " / " + intervalStr});
                rowIcons.add(new ItemStack[]{null, currencyIcon});
            }
            rows.add(new String[]{"§lTotal", "§l" + fmtVal(currencyTotal)});
            rowIcons.add(new ItemStack[]{null, resolveCurrencyStack(companyCurrency)});
            colors[schedules.size()] = 0xFFFFFFFF;
            payoutsTable.setData(rows, colors, rowIcons.toArray(new ItemStack[0][]));
        }
    }

    @Override public void onInfoUpdated() { fetchStats(); }

    @Override
    protected void render() {
        int w = getWidth();
        if (w > 0 && lastKnownWidth == 0 && !loading) fetchStats();
        lastKnownWidth = w;
    }

    @Override
    protected void layoutChanged() {
        int w = getWidth(), h = getHeight();
        int y = PADDING;

        int tfCount = tfButtons.length;
        int tfW = (w - 2 * PADDING - (tfCount - 1) * ROW_SPACING) / tfCount;
        for (int i = 0; i < tfCount; i++)
            tfButtons[i].setBounds(PADDING + i * (tfW + ROW_SPACING), y, tfW, ROW_HEIGHT);
        y += ROW_HEIGHT + SECTION_SPACING;

        int cardW = (w - 2 * PADDING - 3 * ROW_SPACING) / 4;
        balanceLabel.setBounds(PADDING, y, cardW, ROW_HEIGHT);
        netCashflowLabel.setBounds(PADDING + cardW + ROW_SPACING, y, cardW, ROW_HEIGHT);
        insolvencyLabel.setBounds(PADDING + 2 * (cardW + ROW_SPACING), y, cardW, ROW_HEIGHT);
        missedPayoutsLabel.setBounds(PADDING + 3 * (cardW + ROW_SPACING), y, cardW, ROW_HEIGHT);
        y += ROW_HEIGHT + ROW_SPACING;

        loadingLabel.setBounds(PADDING, y, w - 2 * PADDING, ROW_HEIGHT);
        if (loading) y += ROW_HEIGHT + ROW_SPACING;

        int chartH = 60, chartW = w - 2 * PADDING;
        chart.setBounds(PADDING, y, chartW, chartH);
        int yAxisW = 55;
        yAxisTop.setBounds(PADDING, y + 1, yAxisW, 8);
        yAxisMid.setBounds(PADDING, y + chartH / 2 - 4, yAxisW, 8);
        yAxisBot.setBounds(PADDING, y + chartH - 9, yAxisW, 8);
        y += chartH;

        int xAxisH = 10;
        xAxisLeft.setBounds(PADDING, y, chartW / 3, xAxisH);
        xAxisMid.setBounds(PADDING + chartW / 3, y, chartW / 3, xAxisH);
        xAxisRight.setBounds(PADDING + 2 * (chartW / 3), y, chartW - 2 * (chartW / 3), xAxisH);
        y += xAxisH + SECTION_SPACING;

        int halfW = (w - 2 * PADDING - ROW_SPACING) / 2;
        int tableH = Math.max(40, h - y - PADDING);
        shareholdersTable.setBounds(PADDING, y, halfW, tableH);
        payoutsTable.setBounds(PADDING + halfW + ROW_SPACING, y, halfW, tableH);
    }

    /** Converts a raw scale-100 bank value to a display string with Swiss-style thousands separators. */
    private static String fmtVal(long raw) {
        return net.kroia.banksystem.util.MoneyFormat.format(raw);
    }

    private static ItemStack resolveCurrencyStack(short currencyItem) {
        if (currencyItem == PayoutSchedule.MONEY_CURRENCY) {
            try {
                return net.kroia.banksystem.minecraft.item.BankSystemItems.MONEY.get().getDefaultInstance();
            } catch (Throwable ignored) { return ItemStack.EMPTY; }
        }
        try {
            for (java.util.Map.Entry<ItemID, ItemStack> e : ItemIDManager.getItemIDMap().entrySet()) {
                if (e.getKey().getShort() == currencyItem && !e.getValue().isEmpty())
                    return e.getValue();
            }
        } catch (Throwable ignored) {}
        return ItemStack.EMPTY;
    }

    // -----------------------------------------------------------------------
    // Scrollable table widget.
    //
    // Render order (background → foreground):
    //   BACKGROUND PASS:
    //     ScrollableTableWidget.renderBackground() — nothing (intentionally empty)
    //     ScrollableTableWidget.render() — header/title bg, black dividers, outer border
    //     Children (in insertion order):
    //       titleLabel, headerCols[], scrollList (rows render background → row separator,
    //       then row labels), ColDividerOverlay.renderBackground() ← column lines here
    //       ColDividerOverlay.render() — empty
    // -----------------------------------------------------------------------
    private static class ScrollableTableWidget extends GuiElement {
        private static final int TITLE_H  = ROW_HEIGHT - 4;
        private static final int HEADER_H = ROW_HEIGHT - 4;
        private static final int BORDER_COLOR    = 0xFF888888;
        private static final int HEADER_BG_COLOR = 0xCC202020;

        private final int[] colFractions;
        private final Label.Alignment[] headAligns;
        private final Label.Alignment[] dataAligns;
        private final Label titleLabel;
        private final Label[] headerCols;
        private final VerticalListView scrollList;
        private final ColDividerOverlay dividerOverlay;

        ScrollableTableWidget(String title, String[] colHeaders, int[] fractions,
                              Label.Alignment[] headAligns, Label.Alignment[] dataAligns) {
            this.colFractions = fractions;
            this.headAligns = headAligns;
            this.dataAligns = dataAligns;

            titleLabel = new Label("§l" + title);
            titleLabel.setAlignment(Label.Alignment.CENTER);
            addChild(titleLabel);

            headerCols = new Label[colHeaders.length];
            for (int i = 0; i < colHeaders.length; i++) {
                headerCols[i] = new Label("§l" + colHeaders[i]);
                Label.Alignment ha = (headAligns != null && i < headAligns.length)
                        ? headAligns[i] : Label.Alignment.LEFT;
                headerCols[i].setAlignment(ha);
                addChild(headerCols[i]);
            }

            scrollList = new VerticalListView();
            LayoutGrid lg = new LayoutGrid();
            lg.columns = 1; lg.rows = 0; lg.spacing = 0; lg.padding = 0;
            lg.stretchX = true; lg.stretchY = false;
            lg.alignment = GuiElement.Alignment.TOP;
            scrollList.setLayout(lg);
            addChild(scrollList);

            // Added AFTER scrollList → renders on top of scroll content.
            dividerOverlay = new ColDividerOverlay();
            addChild(dividerOverlay);
        }

        /** rowIcons: per-row array of per-cell ItemStack (null entry = no icon). */
        void setData(List<String[]> rows, int[] colors, ItemStack[][] rowIcons) {
            scrollList.removeChilds();
            for (int r = 0; r < rows.size(); r++) {
                boolean lastRow = (r == rows.size() - 1);
                int color = (colors != null && r < colors.length) ? colors[r] : 0xFFFFFFFF;
                ItemStack[] icons = (rowIcons != null && r < rowIcons.length) ? rowIcons[r] : null;
                scrollList.addChild(
                        new RowElement(rows.get(r), colFractions, !lastRow, color, icons, dataAligns));
            }
        }

        @Override
        protected void render() {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            drawRect(1, 1, w - 2, TITLE_H, HEADER_BG_COLOR);
            drawRect(1, TITLE_H + 1, w - 2, 1, 0xFF000000);
            drawRect(1, TITLE_H + 2, w - 2, HEADER_H, HEADER_BG_COLOR);
            drawRect(1, TITLE_H + HEADER_H + 2, w - 2, 1, 0xFF000000);
            drawRect(0, 0, w, 1, BORDER_COLOR);
            drawRect(0, h - 1, w, 1, BORDER_COLOR);
            drawRect(0, 0, 1, h, BORDER_COLOR);
            drawRect(w - 1, 0, 1, h, BORDER_COLOR);
        }

        @Override
        protected void layoutChanged() {
            int w = getWidth(), h = getHeight();
            int innerX = 1, innerW = w - 2;
            titleLabel.setBounds(innerX + 2, 1, innerW - 4, TITLE_H);

            int cx = 0;
            for (int i = 0; i < headerCols.length; i++) {
                int colW = (i < colFractions.length - 1) ? innerW * colFractions[i] / 100 : innerW - cx;
                headerCols[i].setBounds(innerX + cx + 2, TITLE_H + 2, colW - 4, HEADER_H);
                cx += colW;
            }

            int listTop = TITLE_H + HEADER_H + 3;
            int listH = h - listTop - 1;
            scrollList.setBounds(innerX, listTop, innerW, Math.max(1, listH));
            dividerOverlay.setBounds(innerX, listTop, innerW, Math.max(1, listH));
            dividerOverlay.setFractions(colFractions);
        }

        // Column dividers drawn in renderBackground() — background pass, before row content.
        private static class ColDividerOverlay extends GuiElement {
            private static final int COL_LINE_COLOR = 0x55FFFFFF;
            private int[] fractions = new int[0];

            void setFractions(int[] f) { this.fractions = f; }

            @Override
            protected void renderBackground() {
                int w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0) return;
                int cx = 0;
                for (int i = 0; i < fractions.length - 1; i++) {
                    cx += w * fractions[i] / 100;
                    drawRect(cx, 0, 1, h, COL_LINE_COLOR);
                }
            }

            @Override protected void render() {}
            @Override protected void layoutChanged() {}
        }

        // Row element: separator + multi-cell labels (+ optional item icons).
        private static class RowElement extends GuiElement {
            private static final int SEPARATOR_COLOR = 0x33FFFFFF;
            private final Label[] cells;
            private final ItemView[] cellIcons;
            private final int[] colFractions;
            private final boolean drawSeparator;

            RowElement(String[] values, int[] fractions, boolean drawSep,
                       int textColor, ItemStack[] icons, Label.Alignment[] aligns) {
                this.colFractions = fractions;
                this.drawSeparator = drawSep;
                cells = new Label[values.length];
                cellIcons = new ItemView[values.length];
                for (int i = 0; i < values.length; i++) {
                    if (icons != null && i < icons.length
                            && icons[i] != null && !icons[i].isEmpty()) {
                        cellIcons[i] = new ItemView(icons[i]);
                        cellIcons[i].setShowCount(false);
                        addChild(cellIcons[i]);
                    }
                    cells[i] = new Label(values[i] != null ? values[i] : "");
                    cells[i].setTextFontScale(0.75f);
                    cells[i].setTextColor(textColor);
                    if (aligns != null && i < aligns.length && aligns[i] != null)
                        cells[i].setAlignment(aligns[i]);
                    addChild(cells[i]);
                }
                setHeight(ROW_HEIGHT - 4);
            }

            @Override protected void renderBackground() {
                // Row separator drawn in background pass, so it appears behind the labels.
                if (drawSeparator)
                    drawRect(0, getHeight() - 1, getWidth(), 1, SEPARATOR_COLOR);
            }
            @Override protected void renderOutline() {}
            @Override protected void render() {}

            @Override
            protected void layoutChanged() {
                int w = getWidth(), h = getHeight();
                int cx = 0;
                for (int i = 0; i < cells.length; i++) {
                    int colW = (i < colFractions.length - 1) ? w * colFractions[i] / 100 : w - cx;
                    int iconW = 0;
                    if (cellIcons[i] != null) {
                        int iconSize = Math.max(1, h - 2);
                        cellIcons[i].setBounds(cx + 4, 1, iconSize, iconSize);
                        iconW = iconSize + 5; // 4px left gap + 1px right gap
                    }
                    cells[i].setBounds(cx + iconW + 1, 0, Math.max(0, colW - iconW - 3), h);
                    cx += colW;
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Cashflow bar chart widget
    // -----------------------------------------------------------------------
    private static class CashflowChartWidget extends GuiElement {
        private List<AsyncCompanyManager.CashflowBucketWire> data = List.of();

        void setData(List<AsyncCompanyManager.CashflowBucketWire> data) {
            this.data = data == null ? List.of() : data;
        }

        @Override
        protected void render() {
            if (data.isEmpty()) return;
            int w = getWidth(), h = getHeight();
            int half = h / 2;
            long maxVal = 1L;
            for (AsyncCompanyManager.CashflowBucketWire b : data)
                maxVal = Math.max(maxVal, Math.max(b.earnings(), b.spendings()));
            int bucketCount = data.size();
            float bwf = (float) w / bucketCount;
            for (int i = 0; i < bucketCount; i++) {
                AsyncCompanyManager.CashflowBucketWire b = data.get(i);
                int bx = (int) (i * bwf);
                int bwi = Math.max(1, (int) bwf - 1);
                int earnH = (int) Math.min(half, (b.earnings() * half) / maxVal);
                if (earnH > 0) drawRect(bx, Math.max(1, half - earnH), bwi, earnH, 0x8055CC55);
                int spendH = (int) Math.min(half, (b.spendings() * half) / maxVal);
                int spendBottom = Math.min(h - 1, half + spendH);
                if (spendBottom > half) drawRect(bx, half, bwi, spendBottom - half, 0x80CC5555);
            }
            drawRect(1, half, w - 2, 1, 0xFFAAAAAA);
        }

        @Override protected void layoutChanged() {}
    }
}
