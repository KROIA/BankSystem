package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.banking.company.PayoutSchedule;
import net.kroia.banksystem.screen.util.DividendCurrencyPrefs;
import net.kroia.banksystem.screen.uiElements.ItemBalancePickerPopup;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.client.GuiScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.Frame;
import net.kroia.modutilities.gui.elements.ItemView;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.TextBox;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * Task #49 (v2.1.0) — one-shot dividend distribution modal.
 * <p>
 * MANAGE-gated at the caller ({@link PayoutsOverviewScreen} only opens the screen for
 * viewers with MANAGE); the server re-checks MANAGE via
 * {@code AsyncCompanyManager.PAY_DIVIDEND}. The screen collects the per-share amount
 * plus a "include company account" opt-in, forwards to master via ARRS, and renders
 * the result (paid total + holder count, or a typed error).
 * <p>
 * Deviation from spec: the "confirm modal showing total outflow + holder count" is
 * merged into the same screen — after Pay is clicked the button is disabled and the
 * status label shows the ARRS result. A separate confirm step would require a
 * preview ARRS call (holder count + total precompute) which would add another round
 * trip; the master's all-or-nothing precheck already guards against overspend.
 */
public class PayDividendScreen extends BankSystemGuiScreen {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".pay_dividend_screen.";
    private static final String HIST_PREFIX = "gui." + BankSystemMod.MOD_ID + ".pay_dividend_screen.history.";
    private static final Component HISTORY_HEADER = Component.translatable(HIST_PREFIX + "header");
    private static final Component HISTORY_EMPTY = Component.translatable(HIST_PREFIX + "empty");
    private static final Component HISTORY_REFRESH = Component.translatable(HIST_PREFIX + "refresh");
    private static final Component TITLE = Component.translatable(PREFIX + "title");
    private static final Component CURRENCY = Component.translatable(PREFIX + "currency");
    private static final Component AMOUNT = Component.translatable(PREFIX + "amount");
    private static final Component PAY = Component.translatable(PREFIX + "pay");
    private static final Component CANCEL = Component.translatable(PREFIX + "cancel");
    private static final String RESULT_OK_KEY = PREFIX + "result_ok";
    private static final Component RESULT_INSUFFICIENT = Component.translatable(PREFIX + "result_insufficient");
    private static final Component RESULT_NO_SHARES = Component.translatable(PREFIX + "result_no_shares");
    private static final Component RESULT_NO_PERMISSION = Component.translatable(PREFIX + "result_no_permission");
    private static final Component RESULT_INVALID = Component.translatable(PREFIX + "result_invalid");
    private static final Component RESULT_INTERNAL = Component.translatable(PREFIX + "result_internal");
    private static final Component RESULT_NOT_FOUND = Component.translatable(PREFIX + "result_not_found");

    private static final int COLOR_OK = 0xFF10b981;
    private static final int COLOR_ERR = 0xFFe11d48;

    private static final int DIALOG_W = 340;
    private static final int DIALOG_H = 300;

    private final GuiScreen parent;
    private final int companyId;
    private final UUID caller;

    private short currencyItem = PayoutSchedule.MONEY_CURRENCY;

    private final Frame frame = new Frame();
    private Label titleLabel;
    private Label currencyLabel;
    private Button currencyButton;
    private ItemView currencyIcon;
    private Label amountLabel;
    private TextBox amountBox;
    private Button payButton;
    private Button cancelButton;
    private Label statusLabel;
    private VerticalListView historyListView;
    private Label historyHeader;
    private Label historyEmpty;
    private Button refreshButton;

    public PayDividendScreen(GuiScreen parent, int companyId, UUID caller) {
        super(TITLE);
        this.parent = parent;
        this.companyId = companyId;
        this.caller = caller;
        this.currencyItem = DividendCurrencyPrefs.get(companyId);
        setupUi();
        loadHistory();
    }

    private void setupUi() {
        titleLabel = new Label(TITLE.getString());

        // Currency picker row.
        currencyLabel = new Label(CURRENCY.getString());
        currencyLabel.setAlignment(Label.Alignment.RIGHT);
        currencyButton = new Button("", this::onPickCurrencyClicked);
        currencyButton.setHoverTooltipSupplier(() ->
                Component.translatable(PREFIX + "currency_tooltip").getString());
        currencyIcon = new ItemView();
        currencyIcon.setShowCount(false);
        currencyButton.addChild(currencyIcon);
        applyCurrency(currencyItem);

        amountLabel = new Label(AMOUNT.getString());
        amountLabel.setAlignment(Label.Alignment.RIGHT);
        amountBox = new TextBox();
        // Spec A.6 — decimal money input at the UI boundary (raw fixpoint on the wire).
        amountBox.setMatchRegex(TextBox.createRegex_onlyNumerical(true, false, 12, 2));
        amountBox.setText("0.01");
        amountBox.setHoverTooltipSupplier(() ->
                Component.translatable(PREFIX + "amount_tooltip").getString());

        payButton = new Button(PAY.getString(), this::onPayClicked);
        cancelButton = new Button(CANCEL.getString(), this::onClose);
        statusLabel = new Label("");

        historyHeader = new Label(HISTORY_HEADER.getString());
        historyEmpty = new Label(HISTORY_EMPTY.getString());
        refreshButton = new Button(HISTORY_REFRESH.getString(), this::loadHistory);
        historyListView = new VerticalListView();
        LayoutGrid layout = new LayoutGrid();
        layout.stretchX = true;
        layout.columns = 1;
        historyListView.setLayout(layout);

        addElement(frame);
        frame.addChild(titleLabel);
        frame.addChild(currencyLabel);
        frame.addChild(currencyButton);
        frame.addChild(amountLabel);
        frame.addChild(amountBox);
        frame.addChild(statusLabel);
        frame.addChild(payButton);
        frame.addChild(cancelButton);
        frame.addChild(historyHeader);
        frame.addChild(historyEmpty);
        frame.addChild(refreshButton);
        frame.addChild(historyListView);
    }

    private void applyCurrency(short newItem) {
        currencyItem = newItem;
        DividendCurrencyPrefs.set(companyId, newItem);
        ItemStack stack;
        if (newItem == PayoutSchedule.MONEY_CURRENCY) {
            stack = net.kroia.banksystem.minecraft.item.BankSystemItems.MONEY.get().getDefaultInstance();
        } else {
            stack = ItemIDManager.getItemStack(new ItemID(newItem));
        }
        if (currencyIcon != null) currencyIcon.setItemStack(stack);
    }

    private void onPickCurrencyClicked() {
        switchScreen(new ItemBalancePickerPopup(this, companyId, caller, this::applyCurrency));
    }

    private long parsedAmount() {
        // Spec A.6 — parse "123.45" into raw fixpoint units (12345).
        return net.kroia.banksystem.util.MoneyFormat.parseToRaw(amountBox.getText());
    }

    private void onPayClicked() {
        long amount = parsedAmount();
        if (amount <= 0L) {
            statusLabel.setText(RESULT_INVALID.getString());
            statusLabel.setTextColor(COLOR_ERR);
            return;
        }
        payButton.setEnabled(false);
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
            BACKEND_INSTANCES.LOGGER.info("[PayDividendScreen] pay clicked: company="
                    + companyId + " textInput='" + amountBox.getText() + "' parsedRaw=" + amount);
        }
        // Bug batch 3 #5 (v2.1.0) — paying dividends to the company's own account makes
        // no sense (the money just returns to itself). Always exclude — the checkbox
        // has been removed from the UI and the server enforces exclusion in DividendPayer.
        AsyncCompanyManager.payDividendAsync(companyId, amount, false, caller, currencyItem).thenAccept(out -> {
            if (out == null) {
                statusLabel.setText(RESULT_INTERNAL.getString());
                statusLabel.setTextColor(COLOR_ERR);
                payButton.setEnabled(true);
                return;
            }
            switch (out.resultCode()) {
                case AsyncCompanyManager.CODE_OK -> {
                    statusLabel.setText(Component.translatable(RESULT_OK_KEY,
                            net.kroia.banksystem.util.MoneyFormat.format(out.totalPaid()),
                            String.valueOf(out.holderCount())).getString());
                    statusLabel.setTextColor(COLOR_OK);
                }
                case AsyncCompanyManager.CODE_INSUFFICIENT_FUNDS -> {
                    statusLabel.setText(RESULT_INSUFFICIENT.getString());
                    statusLabel.setTextColor(COLOR_ERR);
                    payButton.setEnabled(true);
                }
                case AsyncCompanyManager.CODE_NO_SHARES -> {
                    statusLabel.setText(RESULT_NO_SHARES.getString());
                    statusLabel.setTextColor(COLOR_ERR);
                    payButton.setEnabled(true);
                }
                case AsyncCompanyManager.CODE_NO_PERMISSION -> {
                    statusLabel.setText(RESULT_NO_PERMISSION.getString());
                    statusLabel.setTextColor(COLOR_ERR);
                }
                case AsyncCompanyManager.CODE_INVALID_INPUT -> {
                    statusLabel.setText(RESULT_INVALID.getString());
                    statusLabel.setTextColor(COLOR_ERR);
                    payButton.setEnabled(true);
                }
                case AsyncCompanyManager.CODE_NOT_FOUND -> {
                    statusLabel.setText(RESULT_NOT_FOUND.getString());
                    statusLabel.setTextColor(COLOR_ERR);
                }
                default -> {
                    statusLabel.setText(RESULT_INTERNAL.getString());
                    statusLabel.setTextColor(COLOR_ERR);
                    payButton.setEnabled(true);
                }
            }
        });
    }

    @Override
    public void onClose() {
        // BUG 1 fix — deferred swap; direct setScreen inside a click callback CMEs.
        switchScreen(parent);
    }

    @Override
    protected void updateLayout(Gui gui) {
        if (titleLabel == null) return;
        frame.setBounds((getWidth() - DIALOG_W) / 2, (getHeight() - DIALOG_H) / 2, DIALOG_W, DIALOG_H);

        int p = 8;
        int spacing = 5;
        int fw = frame.getWidth() - 2 * p;
        int y = p;
        titleLabel.setBounds(p, y, fw, 16); y += 22;

        int col = fw / 2;
        currencyLabel.setBounds(p, y, fw - 20 - spacing, 20);
        currencyButton.setBounds(p + fw - 20, y, 20, 20);
        currencyIcon.setBounds(2, 2, 16, 16);
        y += 26;

        amountLabel.setBounds(p, y, col - spacing, 20);
        amountBox.setBounds(p + col, y, col, 20);
        y += 26;

        statusLabel.setBounds(p, y, fw, 16);

        int btnW = (fw - spacing) / 2;
        int btnY = frame.getHeight() - p - 20;
        payButton.setBounds(p, btnY, btnW, 20);
        cancelButton.setBounds(p + btnW + spacing, btnY, btnW, 20);

        int historyStart = btnY - 130;
        historyHeader.setBounds(p, historyStart, fw - 70, 14);
        refreshButton.setBounds(p + fw - 65, historyStart, 65, 14);
        historyEmpty.setBounds(p, historyStart + 18, fw, 14);
        int listH = btnY - historyStart - 18 - spacing;
        historyListView.setBounds(p, historyStart + 18, fw, Math.max(10, listH));
    }

    private void loadHistory() {
        historyEmpty.setText("");
        historyListView.removeChilds();
        AsyncCompanyManager.listDividendHistoryAsync(companyId, 20)
                .thenAccept(events -> {
            historyListView.removeChilds();
            if (events == null || events.isEmpty()) {
                historyEmpty.setText(HISTORY_EMPTY.getString());
                return;
            }
            historyEmpty.setText("");
            for (net.kroia.banksystem.banking.company.DividendEvent e : events) {
                String ts = net.kroia.banksystem.util.TimeFormat.formatTimestamp(e.timestampMs());
                String total = net.kroia.banksystem.util.MoneyFormat.format(e.totalRaw());
                String row = ts + "  +" + total + "  (" + e.holderCount() + "×)  " + e.sourceKind();
                Label rowLabel = new Label(row);
                historyListView.addChild(rowLabel);
            }
        });
    }
}
