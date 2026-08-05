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
 * Task #45a (v2.0.8) — payouts overview list for a company-linked bank account.
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
        Minecraft.getInstance().setScreen(new PayoutsOverviewScreen(parent, accountNumber, companyId, canManage));
    }

    private void setupUi() {
        screenIsOpen = true;
        closeButton = new CloseButton(this::onClose);
        newPayoutButton = new Button(NEW_PAYOUT.getString(), this::onNewPayoutClicked);
        newPayoutButton.setEnabled(canManage);
        // Task #49 (v2.0.8) — one-shot dividend distribution entry-point.
        payDividendButton = new Button(PAY_DIVIDEND.getString(), this::onPayDividendClicked);
        payDividendButton.setEnabled(canManage);
        totalLabel = new Label("");
        countLabel = new Label("");
        failedLabel = new Label("");
        emptyLabel = new Label(NO_SCHEDULES.getString());
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
        addElement(listView);
    }

    private void refresh() {
        AsyncCompanyManager.listSchedulesAsync(companyId).thenAccept(out -> {
            if (!screenIsOpen) return;
            List<PayoutSchedule> schedules = new ArrayList<>();
            if (out != null) {
                for (AsyncCompanyManager.ScheduleWire w : out.schedules()) schedules.add(w.toSchedule());
            }
            listView.removeChilds();
            long perHour = 0L;
            for (PayoutSchedule s : schedules) {
                listView.addChild(new PayoutRowWidget(companyId, s, getThisPlayerUUID(), canManage, this, this::refresh));
                if (!s.isPaused() && s.getIntervalTicks() > 0) {
                    perHour += s.getAmount() * 72000L / s.getIntervalTicks();
                }
            }
            totalLabel.setText(Component.translatable(TOTAL_PER_HOUR_KEY, String.valueOf(perHour)).getString());
            countLabel.setText(Component.translatable(COUNT_KEY, schedules.size()).getString());
            emptyLabel.setText(schedules.isEmpty() ? NO_SCHEDULES.getString() : "");
        });
        AsyncCompanyManager.getFailureCount24hAsync(companyId).thenAccept(out -> {
            if (!screenIsOpen || out == null) return;
            failedLabel.setText(Component.translatable(FAILED_KEY, out.failedCount()).getString());
        });
    }

    private void onNewPayoutClicked() {
        if (!canManage) return;
        PayoutEditScreen edit = new PayoutEditScreen(this, companyId, null, getThisPlayerUUID(), true, this::refresh);
        Minecraft.getInstance().setScreen(edit);
    }

    private void onPayDividendClicked() {
        if (!canManage) return;
        Minecraft.getInstance().setScreen(new PayDividendScreen(this, companyId, getThisPlayerUUID()));
    }

    @Override
    public void onClose() {
        screenIsOpen = false;
        if (parent != null && this.minecraft != null) {
            this.minecraft.setScreen(parent);
        } else {
            super.onClose();
        }
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

        int listTop = countLabel.getBottom() + spacing;
        listView.setBounds(padding, listTop, width, height - listTop + padding);
        emptyLabel.setBounds(padding, listTop, width, 20);
    }
}
