package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.general.AuditPageRequest;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.banksystem.util.MoneyFormat;
import net.kroia.banksystem.util.TimeFormat;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.DropDownMenu;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.elements.base.ListView;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Task #110 (v2.2.0) — admin audit screen: an account's ledger rows, newest first, one server
 * page ({@link #PAGE_SIZE} rows) at a time, filtered by type and time range. Every page is
 * checked on the master ({@link AuditPageRequest}).
 */
public class AuditScreen extends BankSystemGuiScreen {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".audit_screen.";
    private static final Component TITLE = Component.translatable(PREFIX + "title");
    private static final Component ALL_TYPES = Component.translatable(PREFIX + "all_types");
    private static final Component PREV = Component.translatable(PREFIX + "prev");
    private static final Component NEXT = Component.translatable(PREFIX + "next");
    private static final Component DENIED = Component.translatable(PREFIX + "denied");
    private static final Component EMPTY = Component.translatable(PREFIX + "empty");
    private static final Component EXPORT = Component.translatable(PREFIX + "export");
    private static final Component COULD_NOT_LOAD = Component.translatable("gui.banksystem.request.could_not_load");
    private static final String[] RANGE_KEYS = {"range_1h", "range_24h", "range_7d", "range_all"};
    private static final long[] RANGE_MS = {3_600_000L, 86_400_000L, 7 * 86_400_000L, 0L};
    static final int PAGE_SIZE = 100;
    /** Issue #166: the export fetches every page of the filter, up to this many rows. */
    static final int MAX_EXPORT_ROWS = 10_000;
    /** Columns: time, type, item, amount, counterparty, actor, note, ref. Header, rows and CSV share this order. */
    private static final String[] COL_KEYS = {"col_time", "col_type", "col_item", "col_amount", "col_counterparty",
            "col_actor", "col_note", "col_ref"};
    /** Weight of a flexible column in the width the fixed ones leave; 0 = fixed, sized to its widest value. */
    private static final int[] FLEX = {0, 0, 3, 2, 0, 2, 4, 0};
    private static final String SAMPLE_ACCOUNT = "#0000000";
    private static final String SAMPLE_REF = "00000000-0000-0000-0000-000000000000"; // transfer ids are UUIDs

    /** Types whose stored (unsigned) amount leaves the account; shown with a minus. */
    private static final Set<LedgerType> OUTFLOW = EnumSet.of(LedgerType.WITHDRAW, LedgerType.ADMIN_REMOVE,
            LedgerType.BANK_CLOSED, LedgerType.CONVERTER_WITHDRAW, LedgerType.DOWNLOAD_WITHDRAW,
            LedgerType.CRAFT_CONSUME, LedgerType.ACCOUNT_DELETE_PAYOUT, LedgerType.ADMIN_DISALLOW_ITEM,
            LedgerType.ACCOUNT_DELETE_LOSS);

    private final int accountNumber;
    private int typeCode = -1;
    private int rangeIndex = 3;
    private int page = 0;
    private boolean hasMore = false;
    private int generation = 0;
    private long since = 0;
    private final int[] colX = new int[COL_KEYS.length];
    private final int[] colW = new int[COL_KEYS.length];

    private final Label titleLabel;
    private DropDownMenu typeMenu;
    private DropDownMenu rangeMenu;
    private final Button prevButton;
    private final Button nextButton;
    private final Button historyButton;
    private final Button exportButton;
    private final Label pageLabel;
    private final Label[] headerCells = new Label[COL_KEYS.length];
    private final Label statusLabel;
    private final ListView listView;

    public AuditScreen(int accountNumber) {
        this(null, accountNumber);
    }

    /** BS-229: closing returns to {@code parent} (e.g. the account management screen). */
    public AuditScreen(net.minecraft.client.gui.screens.Screen parent, int accountNumber) {
        super(TITLE, parent);
        this.accountNumber = accountNumber;
        titleLabel = new Label(TITLE.getString() + " #" + accountNumber);

        typeMenu = new DropDownMenu(ALL_TYPES.getString(), (i, el) -> runLater(() -> {
            typeCode = i == 0 ? -1 : LedgerType.values()[i - 1].code();
            reload(0);
        }));
        typeMenu.addOption(ALL_TYPES.getString());
        for (LedgerType t : LedgerType.values()) typeMenu.addOption(t.name());
        typeMenu.setMaxExpandedHeight(200);

        rangeMenu = new DropDownMenu(Component.translatable(PREFIX + RANGE_KEYS[rangeIndex]).getString(), (i, el) -> runLater(() -> {
            rangeIndex = i;
            reload(0);
        }));
        for (String k : RANGE_KEYS) rangeMenu.addOption(Component.translatable(PREFIX + k).getString());

        prevButton = new Button(PREV.getString(), () -> reload(page - 1));
        nextButton = new Button(NEXT.getString(), () -> reload(page + 1));
        // Task #116: the account's balance history with its suspicion flags as markers
        historyButton = new Button(Component.translatable(PREFIX + "history").getString(), () ->
                net.kroia.modutilities.gui.client.ClientCompat.setScreen(new BalanceHistoryScreen(this, accountNumber, true)));
        exportButton = new Button(EXPORT.getString(), this::export);
        pageLabel = new Label("");
        statusLabel = new Label("");
        String[] header = headerTexts();
        for (int i = 0; i < header.length; i++) headerCells[i] = new Label(header[i]);

        listView = new VerticalListView();
        LayoutGrid layout = new LayoutGrid();
        layout.columns = 1;
        layout.spacing = 0;
        layout.padding = 0;
        layout.stretchX = true;
        layout.stretchY = false;
        layout.alignment = GuiElement.Alignment.TOP;
        listView.setLayout(layout);

        addElement(titleLabel);
        addElement(prevButton);
        addElement(pageLabel);
        addElement(nextButton);
        addElement(historyButton);
        addElement(exportButton);
        for (Label l : headerCells) addElement(l);
        addElement(listView);
        addElement(statusLabel);
        addElement(typeMenu);  // added last: the expanded menus overlay the list
        addElement(rangeMenu);
        reload(0);
    }

    public static void openScreen(int accountNumber) {
        net.kroia.modutilities.gui.client.ClientCompat.setScreen(new AuditScreen(accountNumber));
    }

    private void reload(int newPage) {
        if (newPage < 0) return;
        page = newPage;
        int gen = ++generation;
        since = RANGE_MS[rangeIndex] == 0 ? 0 : System.currentTimeMillis() - RANGE_MS[rangeIndex];
        prevButton.setEnabled(false);
        nextButton.setEnabled(false);
        BankSystemNetworking.AUDIT_PAGE_REQUEST.sendRequestToServer(
                        new AuditPageRequest.Query(accountNumber, typeCode, since, page * PAGE_SIZE, PAGE_SIZE))
                .whenComplete((p, ex) -> runLater(() -> {
                    if (gen != generation) return; // a newer filter/page is loading
                    listView.removeChilds();
                    pageLabel.setText(String.valueOf(page + 1));
                    if (ex != null || p == null) { // Issue #95: no answer; keep the old paging so the user can retry
                        prevButton.setEnabled(page > 0);
                        nextButton.setEnabled(hasMore);
                        listView.addChild(textRow(COULD_NOT_LOAD.getString()));
                        return;
                    }
                    hasMore = p.hasMore();
                    prevButton.setEnabled(page > 0);
                    nextButton.setEnabled(hasMore);
                    if (p.denied()) listView.addChild(textRow(DENIED.getString()));
                    else if (p.rows().isEmpty()) listView.addChild(textRow(EMPTY.getString()));
                    else for (AuditPageRequest.Row r : p.rows()) listView.addChild(new RowElement(r));
                }));
    }

    /**
     * Issue #166: CSV of every row of the current filter. The dialog blocks, so it runs on its own
     * thread; requests and formatting run on the render thread, the file write on a pool thread.
     */
    private void export() {
        exportButton.setEnabled(false);
        statusLabel.setText(Component.translatable(PREFIX + "exporting").getString());
        int account = accountNumber, type = typeCode;
        long from = since;
        String title = EXPORT.getString();
        Thread t = new Thread(() -> {
            Path path;
            try {
                path = AuditExport.askPath(title, "audit_" + account + ".csv");
            } catch (Throwable e) { // e.g. no dialog tool on a Linux desktop
                runLater(() -> exportDone(failed(e)));
                return;
            }
            if (path == null) {
                runLater(() -> exportDone(""));
                return;
            }
            runLater(() -> fetchAll(account, type, from, 0, new LinkedHashMap<>()).whenComplete((rows, ex) -> runLater(() -> {
                if (ex != null) {
                    exportDone(failed(ex));
                    return;
                }
                List<String[]> lines = new ArrayList<>(rows.size());
                for (AuditPageRequest.Row r : rows.values()) lines.add(cells(r));
                String csv = AuditExport.csv(headerTexts(), lines);
                String done = Component.translatable(PREFIX + (rows.size() >= MAX_EXPORT_ROWS ? "export_truncated" : "export_saved"),
                        rows.size(), path.toString()).getString();
                CompletableFuture.runAsync(() -> {
                    try {
                        AuditExport.write(path, csv);
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }).whenComplete((v, wex) -> runLater(() -> exportDone(wex == null ? done : failed(wex))));
            })));
        }, "BankSystem-AuditExport");
        t.setDaemon(true);
        t.start();
    }

    private void exportDone(String status) {
        exportButton.setEnabled(true);
        statusLabel.setText(status);
    }

    private static String failed(Throwable e) {
        Throwable c = e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause() : e;
        BACKEND_INSTANCES.LOGGER.warn("[AuditScreen] CSV export failed: " + c);
        return Component.translatable(PREFIX + "export_failed", String.valueOf(c.getMessage())).getString();
    }

    /** Pages through the filter; rows logged meanwhile shift the offsets, the id map drops the repeats. */
    private static CompletableFuture<Map<Long, AuditPageRequest.Row>> fetchAll(int account, int type, long from, int offset,
                                                                             Map<Long, AuditPageRequest.Row> into) {
        return BankSystemNetworking.AUDIT_PAGE_REQUEST.sendRequestToServer(new AuditPageRequest.Query(account, type, from, offset, PAGE_SIZE))
                .thenCompose(p -> {
                    if (p == null || p.denied()) throw new IllegalStateException(DENIED.getString());
                    for (AuditPageRequest.Row r : p.rows()) {
                        if (into.size() >= MAX_EXPORT_ROWS) break;
                        into.putIfAbsent(r.id(), r);
                    }
                    if (!p.hasMore() || p.rows().isEmpty() || into.size() >= MAX_EXPORT_ROWS)
                        return CompletableFuture.completedFuture(into);
                    return fetchAll(account, type, from, offset + p.rows().size(), into);
                });
    }

    private static String[] headerTexts() {
        String[] h = new String[COL_KEYS.length];
        for (int i = 0; i < h.length; i++) h[i] = Component.translatable(PREFIX + COL_KEYS[i]).getString();
        return h;
    }

    /** One row's cell texts, in {@link #COL_KEYS} order. */
    private static String[] cells(AuditPageRequest.Row r) {
        LedgerType type = LedgerType.fromCode(r.typeCode());
        return new String[]{TimeFormat.formatTimestamp(r.time()), type.name(), itemName(r.itemId()),
                formatAmount(type, r.itemId(), r.amount()), r.otherAccount() == null ? "" : "#" + r.otherAccount(),
                r.actor(), r.note() == null ? "" : r.note(), r.ref() == null ? "" : r.ref()};
    }

    /**
     * Issue #166: fixed columns get their own width (shrunk if they would take more than 60 %),
     * the flexible ones share the rest by weight.
     */
    static void layoutColumns(int total, int[] fixed, int[] flex, int[] x, int[] w) {
        int fixedSum = 0, flexSum = 0;
        for (int i = 0; i < fixed.length; i++) { fixedSum += fixed[i]; flexSum += flex[i]; }
        int maxFixed = total * 3 / 5;
        double shrink = fixedSum > maxFixed ? maxFixed / (double) fixedSum : 1;
        int rest = total;
        for (int i = 0; i < fixed.length; i++) if (flex[i] == 0) rest -= (int) (fixed[i] * shrink);
        int pos = 0;
        for (int i = 0; i < fixed.length; i++) {
            w[i] = flex[i] == 0 ? (int) (fixed[i] * shrink) : rest * flex[i] / Math.max(1, flexSum);
            x[i] = pos;
            pos += w[i];
        }
    }

    private static GuiElement textRow(String text) {
        Label l = new Label(text);
        l.setHeight(15);
        return l;
    }

    static String formatAmount(LedgerType type, short itemId, long amount) {
        long signed = OUTFLOW.contains(type) ? -amount : amount;
        if (itemId == 0) return signed == 0 ? "" : String.valueOf(signed);
        ItemID id = new ItemID(itemId);
        if (MoneyItem.isMoney(id)) return MoneyFormat.format(signed);
        double items = signed / (double) BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
        return items == Math.rint(items) ? String.valueOf((long) items) : String.valueOf(items);
    }

    private static String itemName(short itemId) {
        if (itemId == 0) return "";
        try {
            return ItemIDManager.getItemStack(new ItemID(itemId)).getHoverName().getString();
        } catch (RuntimeException e) {
            return "#" + itemId;
        }
    }

    private final class RowElement extends GuiElement {
        private final Label[] cells;
        private final String[] text;

        RowElement(AuditPageRequest.Row r) {
            setHeight(12);
            text = cells(r);
            cells = new Label[text.length];
            for (int i = 0; i < text.length; i++) {
                cells[i] = new Label(text[i]);
                addChild(cells[i]);
            }
            String full = String.join(" | ", text);
            setHoverTooltipSupplier(() -> full);
        }

        @Override
        protected void render() {}

        /** Issue #166: a click copies the row, tab-separated, in column order. */
        @Override
        protected boolean mouseClickedOverElement(int button) {
            if (button != 0) return false;
            Minecraft.getInstance().keyboardHandler.setClipboard(String.join("\t", text));
            statusLabel.setText(Component.translatable(PREFIX + "copied").getString());
            return true;
        }

        @Override
        protected void layoutChanged() {
            for (int i = 0; i < cells.length; i++) cells[i].setBounds(colX[i], 0, colW[i] - 2, getHeight());
        }
    }

    @Override
    protected void updateLayout(Gui gui) {
        int p = 5, h = 20, s = 4;
        int w = getWidth() - 2 * p;
        int x = p;
        titleLabel.setBounds(x, p, w / 6, h);
        x += w / 6 + s;
        typeMenu.setBounds(x, p, w / 4, h);
        x += w / 4 + s;
        rangeMenu.setBounds(x, p, w / 6, h);
        x += w / 6 + s;
        prevButton.setBounds(x, p, 30, h);
        pageLabel.setBounds(x + 30 + s, p, 30, h);
        nextButton.setBounds(x + 60 + 2 * s, p, 30, h);
        historyButton.setBounds(x + 90 + 3 * s, p, 60, h);
        exportButton.setBounds(x + 150 + 4 * s, p, 60, h);
        int y = p + h + s;

        // Issue #166: a fixed column fits its widest value or its header, whichever is wider.
        Label m = headerCells[0];
        String widestType = "";
        for (LedgerType t : LedgerType.values())
            if (m.getTextWidth(t.name()) > m.getTextWidth(widestType)) widestType = t.name();
        String[] samples = {TimeFormat.formatTimestamp(System.currentTimeMillis()), widestType, null, null,
                SAMPLE_ACCOUNT, null, null, SAMPLE_REF};
        int[] fixed = new int[COL_KEYS.length];
        for (int i = 0; i < fixed.length; i++)
            if (FLEX[i] == 0) fixed[i] = Math.max(m.getTextWidth(samples[i]), m.getTextWidth(headerCells[i].getText())) + 6;
        layoutColumns(w, fixed, FLEX, colX, colW);
        for (int i = 0; i < headerCells.length; i++) headerCells[i].setBounds(p + colX[i], y, colW[i] - 2, 12);

        int listH = getHeight() - y - 14 - p - 14;
        listView.setBounds(p, y + 14, w, listH);
        statusLabel.setBounds(p, y + 14 + listH + 2, w, 12);
    }
}
