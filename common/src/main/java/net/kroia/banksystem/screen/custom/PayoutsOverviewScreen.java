package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.banking.company.PayoutSchedule;
import net.kroia.banksystem.screen.uiElements.PayoutRowWidget;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.client.GuiScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.CloseButton;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.elements.base.ListView;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Task #45a (v2.1.0) — payouts overview list for a company-linked bank account.
 * <p>
 * Deviation from spec: implemented as a dedicated screen (parent = {@link BankAccountManagementScreen})
 * rather than an inline tab on the management screen — cleaner separation and easier navigation.
 */
public class PayoutsOverviewScreen extends BankSystemGuiScreen {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".payouts_overview_screen.";
    private static final Component TITLE = Component.translatable(PREFIX + "title");
    private static final Component NEW_PAYOUT = Component.translatable(PREFIX + "new_payout");
    private static final Component PAY_DIVIDEND = Component.translatable(PREFIX + "pay_dividend");
    private static final String TOTAL_PER_HOUR_KEY = PREFIX + "total_per_hour";
    private static final String COUNT_KEY = PREFIX + "schedule_count";
    private static final String FAILED_KEY = PREFIX + "failed_24h";
    private static final Component NO_SCHEDULES = Component.translatable(PREFIX + "no_schedules");
    private static final Component COL_TARGET = Component.translatable(PREFIX + "col_target");
    private static final Component COL_AMOUNT = Component.translatable(PREFIX + "col_amount");
    private static final Component COL_INTERVAL = Component.translatable(PREFIX + "col_interval");
    private static final Component COL_NEXT = Component.translatable(PREFIX + "col_next");
    private static final Component COL_STATUS = Component.translatable(PREFIX + "col_status");

    private final GuiScreen parent;
    private final int accountNumber;
    private final int companyId;
    private final boolean canManage;

    private CloseButton closeButton;
    private Button newPayoutButton;
    private Button payDividendButton;
    private Label totalLabel;
    private Label countLabel;
    private Label failedLabel;
    private Label emptyLabel;
    private Label headerTarget;
    private Label headerAmount;
    private Label headerInterval;
    private Label headerNext;
    private Label headerStatus;
    private ListView listView;
    private static boolean screenIsOpen = false;

    public PayoutsOverviewScreen(GuiScreen parent, int accountNumber, int companyId, boolean canManage) {
        super(TITLE);
        this.parent = parent;
        this.accountNumber = accountNumber;
        this.companyId = companyId;
        this.canManage = canManage;
        setupUi();
        refresh();
    }

    public static void openScreen(GuiScreen parent, int accountNumber, int companyId, boolean canManage) {
        screenIsOpen = true;
        // BUG 1 fix — deferred swap (openScreen is typically invoked from click callbacks).
        switchScreen(new PayoutsOverviewScreen(parent, accountNumber, companyId, canManage));
    }

    private void setupUi() {
        screenIsOpen = true;
        closeButton = new CloseButton(this::onClose);
        newPayoutButton = new Button(NEW_PAYOUT.getString(), this::onNewPayoutClicked);
        newPayoutButton.setEnabled(canManage);
        // Task #49 (v2.1.0) — one-shot dividend distribution entry-point.
        payDividendButton = new Button(PAY_DIVIDEND.getString(), this::onPayDividendClicked);
        payDividendButton.setEnabled(canManage);
        totalLabel = new Label("");
        totalLabel.setHoverTooltipSupplier(() ->
                Component.translatable(PREFIX + "total_per_hour_tooltip").getString());
        countLabel = new Label("");
        failedLabel = new Label("");
        emptyLabel = new Label(NO_SCHEDULES.getString());
        // Spec A.4 — column legend above the schedule list.
        headerTarget = new Label(COL_TARGET.getString());
        headerAmount = new Label(COL_AMOUNT.getString());
        headerInterval = new Label(COL_INTERVAL.getString());
        headerNext = new Label(COL_NEXT.getString());
        headerStatus = new Label(COL_STATUS.getString());
        // REDESIGN 4 — smaller font so more text fits per column.
        for (Label header : new Label[]{headerTarget, headerAmount, headerInterval, headerNext, headerStatus}) {
            header.setTextFontScale(PayoutRowWidget.ROW_FONT_SCALE);
        }
        listView = new VerticalListView();
        LayoutGrid layout = new LayoutGrid();
        layout.columns = 1;
        layout.spacing = 0;
        layout.padding = 0;
        layout.stretchX = true;
        layout.stretchY = false;
        layout.alignment = GuiElement.Alignment.TOP;
        listView.setLayout(layout);

        addElement(closeButton);
        addElement(newPayoutButton);
        addElement(payDividendButton);
        addElement(totalLabel);
        addElement(countLabel);
        addElement(failedLabel);
        addElement(emptyLabel);
        addElement(headerTarget);
        addElement(headerAmount);
        addElement(headerInterval);
        addElement(headerNext);
        addElement(headerStatus);
        addElement(listView);
    }

    private void refresh() {
        // Bug D fix (v2.1.0) — dispatch mutation onto the render thread. Direct
        // mutation from the CompletableFuture thread races with GUI init and CMEs
        // in VerticalListView.updateElementPositions / GuiElement.updateTransform.
        AsyncCompanyManager.listSchedulesAsync(companyId).thenAccept(out ->
                Minecraft.getInstance().tell(() -> {
                    if (!screenIsOpen) return;
                    List<PayoutSchedule> schedules = new ArrayList<>();
                    if (out != null) {
                        for (AsyncCompanyManager.ScheduleWire w : out.schedules()) schedules.add(w.toSchedule());
                    }
                    listView.removeChilds();
                    long nowTick = out != null ? out.nowTick() : 0L;
                    long perHour = 0L;
                    for (PayoutSchedule s : schedules) {
                        listView.addChild(new PayoutRowWidget(companyId, s, getThisPlayerUUID(), canManage, this, this::refresh, nowTick));
                        // Money-only recurring schedules — items and one-time are not $-comparable.
                        if (!s.isPaused() && s.getIntervalTicks() > 0
                                && s.isMoneyCurrency() && !s.isOneTime()) {
                            perHour += s.getAmount() * 72000L / s.getIntervalTicks();
                        }
                    }
                    totalLabel.setText(Component.translatable(TOTAL_PER_HOUR_KEY,
                            net.kroia.banksystem.util.MoneyFormat.format(perHour)).getString());
                    countLabel.setText(Component.translatable(COUNT_KEY, schedules.size()).getString());
                    emptyLabel.setText(schedules.isEmpty() ? NO_SCHEDULES.getString() : "");
                }));
        AsyncCompanyManager.getFailureCount24hAsync(companyId).thenAccept(out ->
                Minecraft.getInstance().tell(() -> {
                    if (!screenIsOpen || out == null) return;
                    failedLabel.setText(Component.translatable(FAILED_KEY, out.failedCount()).getString());
                }));
    }

    private void onNewPayoutClicked() {
        if (!canManage) return;
        // BUG 1 fix — deferred swaps; direct setScreen inside a click callback CMEs.
        switchScreen(new PayoutEditScreen(this, companyId, null, getThisPlayerUUID(), true, this::refresh));
    }

    private void onPayDividendClicked() {
        if (!canManage) return;
        switchScreen(new PayDividendScreen(this, companyId, getThisPlayerUUID()));
    }

    @Override
    public void onClose() {
        screenIsOpen = false;
        switchScreen(parent);
    }

    @Override
    protected void updateLayout(Gui gui) {
        int spacing = 5;
        int padding = 5;
        int width = getWidth() - 2 * padding;
        int height = getHeight() - 2 * padding;
        if (closeButton == null) return;

        closeButton.setBounds(getWidth() - 20 - padding, padding, 20, 20);
        int btnW = closeButton.getTextWidth(NEW_PAYOUT.getString()) + 10;
        newPayoutButton.setBounds(closeButton.getLeft() - spacing - btnW, padding, btnW, 20);
        int divW = closeButton.getTextWidth(PAY_DIVIDEND.getString()) + 10;
        payDividendButton.setBounds(newPayoutButton.getLeft() - spacing - divW, padding, divW, 20);

        totalLabel.setBounds(padding, padding, width / 2, 20);
        countLabel.setBounds(padding, totalLabel.getBottom() + spacing, width / 2, 20);
        failedLabel.setBounds(padding + width / 2, totalLabel.getBottom() + spacing, width / 2, 20);

        // Spec A.4 / BUG 3 fix (v2.1.0) — mirrors PayoutRowWidget's column layout:
        // 18px icon gutter, reserved slot for Pay-Missed (always), and weighted
        // columns Target 35 / Amount 12 / Interval 12 / Next 15 / Status 26.
        int headerTop = countLabel.getBottom() + spacing;
        int editW = closeButton.getTextWidth("Edit") + 10;
        int missedW = closeButton.getTextWidth("Pay Missed") + 10;
        int rest = Math.max(0, width - editW - missedW - 18);
        int targetW = rest * 35 / 100;
        int amountW = rest * 12 / 100;
        int intervalW = rest * 12 / 100;
        int nextW = rest * 15 / 100;
        int statusW = rest - targetW - amountW - intervalW - nextW;
        int hx = padding + 18;
        headerTarget.setBounds(hx, headerTop, targetW, 15); hx += targetW;
        headerAmount.setBounds(hx, headerTop, amountW, 15); hx += amountW;
        headerInterval.setBounds(hx, headerTop, intervalW, 15); hx += intervalW;
        headerNext.setBounds(hx, headerTop, nextW, 15); hx += nextW;
        headerStatus.setBounds(hx, headerTop, statusW, 15);

        int listTop = headerTop + 17;
        listView.setBounds(padding, listTop, width, height - listTop + padding);
        emptyLabel.setBounds(padding, listTop, width, 20);
    }
}
