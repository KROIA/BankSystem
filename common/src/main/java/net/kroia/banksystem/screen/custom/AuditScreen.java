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
import net.minecraft.network.chat.Component;

import java.util.EnumSet;
import java.util.Set;

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
    private static final Component HEADER = Component.translatable(PREFIX + "header");
    private static final String[] RANGE_KEYS = {"range_1h", "range_24h", "range_7d", "range_all"};
    private static final long[] RANGE_MS = {3_600_000L, 86_400_000L, 7 * 86_400_000L, 0L};
    static final int PAGE_SIZE = 100;
    /** Column widths in percent: time, type, item, amount, counterparty, actor, note, ref. */
    private static final int[] COLS = {14, 13, 13, 11, 7, 12, 20, 10};

    /** Types whose stored (unsigned) amount leaves the account; shown with a minus. */
    private static final Set<LedgerType> OUTFLOW = EnumSet.of(LedgerType.WITHDRAW, LedgerType.ADMIN_REMOVE,
            LedgerType.BANK_CLOSED, LedgerType.CONVERTER_WITHDRAW, LedgerType.DOWNLOAD_WITHDRAW,
            LedgerType.CRAFT_CONSUME, LedgerType.ACCOUNT_DELETE_PAYOUT, LedgerType.ADMIN_DISALLOW_ITEM);

    private final int accountNumber;
    private int typeCode = -1;
    private int rangeIndex = 3;
    private int page = 0;
    private boolean hasMore = false;
    private int generation = 0;

    private final Label titleLabel;
    private DropDownMenu typeMenu;
    private DropDownMenu rangeMenu;
    private final Button prevButton;
    private final Button nextButton;
    private final Label pageLabel;
    private final Label headerLabel;
    private final ListView listView;

    public AuditScreen(int accountNumber) {
        super(TITLE);
        this.accountNumber = accountNumber;
        titleLabel = new Label(TITLE.getString() + " #" + accountNumber);

        typeMenu = new DropDownMenu(ALL_TYPES.getString(), (i, el) -> runLater(() -> {
            typeCode = i == 0 ? -1 : LedgerType.values()[i - 1].code();
            typeMenu.setLabelText(i == 0 ? ALL_TYPES.getString() : LedgerType.values()[i - 1].name());
            typeMenu.collapse();
            reload(0);
        }));
        typeMenu.addOption(ALL_TYPES.getString());
        for (LedgerType t : LedgerType.values()) typeMenu.addOption(t.name());
        typeMenu.setMaxExpandedHeight(200);

        rangeMenu = new DropDownMenu(Component.translatable(PREFIX + RANGE_KEYS[rangeIndex]).getString(), (i, el) -> runLater(() -> {
            rangeIndex = i;
            rangeMenu.setLabelText(Component.translatable(PREFIX + RANGE_KEYS[i]).getString());
            rangeMenu.collapse();
            reload(0);
        }));
        for (String k : RANGE_KEYS) rangeMenu.addOption(Component.translatable(PREFIX + k).getString());

        prevButton = new Button(PREV.getString(), () -> reload(page - 1));
        nextButton = new Button(NEXT.getString(), () -> reload(page + 1));
        pageLabel = new Label("");
        headerLabel = new Label(HEADER.getString());

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
        addElement(headerLabel);
        addElement(listView);
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
        long since = RANGE_MS[rangeIndex] == 0 ? 0 : System.currentTimeMillis() - RANGE_MS[rangeIndex];
        prevButton.setEnabled(false);
        nextButton.setEnabled(false);
        BankSystemNetworking.AUDIT_PAGE_REQUEST.sendRequestToServer(
                        new AuditPageRequest.Query(accountNumber, typeCode, since, page * PAGE_SIZE, PAGE_SIZE))
                .thenAccept(p -> runLater(() -> {
                    if (gen != generation) return; // a newer filter/page is loading
                    listView.removeChilds();
                    hasMore = p.hasMore();
                    prevButton.setEnabled(page > 0);
                    nextButton.setEnabled(hasMore);
                    pageLabel.setText(String.valueOf(page + 1));
                    if (p.denied()) listView.addChild(textRow(DENIED.getString()));
                    else if (p.rows().isEmpty()) listView.addChild(textRow(EMPTY.getString()));
                    else for (AuditPageRequest.Row r : p.rows()) listView.addChild(new RowElement(r));
                }));
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

    private static final class RowElement extends GuiElement {
        private final Label[] cells;

        RowElement(AuditPageRequest.Row r) {
            setHeight(12);
            LedgerType type = LedgerType.fromCode(r.typeCode());
            String[] text = {TimeFormat.formatTimestamp(r.time()), type.name(), itemName(r.itemId()),
                    formatAmount(type, r.itemId(), r.amount()), r.otherAccount() == null ? "" : "#" + r.otherAccount(),
                    r.actor(), r.note() == null ? "" : r.note(), r.ref() == null ? "" : r.ref()};
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

        @Override
        protected void layoutChanged() {
            int x = 0;
            for (int i = 0; i < cells.length; i++) {
                int w = getWidth() * COLS[i] / 100;
                cells[i].setBounds(x, 0, w - 2, getHeight());
                x += w;
            }
        }
    }

    @Override
    protected void updateLayout(Gui gui) {
        int p = 5, h = 20, s = 4;
        int w = getWidth() - 2 * p;
        int x = p;
        titleLabel.setBounds(x, p, w / 4, h);
        x += w / 4 + s;
        typeMenu.setBounds(x, p, w / 4, h);
        x += w / 4 + s;
        rangeMenu.setBounds(x, p, w / 6, h);
        x += w / 6 + s;
        prevButton.setBounds(x, p, 30, h);
        pageLabel.setBounds(x + 30 + s, p, 30, h);
        nextButton.setBounds(x + 60 + 2 * s, p, 30, h);
        int y = p + h + s;
        headerLabel.setBounds(p, y, w, 12);
        listView.setBounds(p, y + 14, w, getHeight() - y - 14 - p);
    }
}
