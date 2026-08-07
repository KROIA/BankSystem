package net.kroia.banksystem.screen.uiElements;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.banking.company.PayoutSchedule;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.banksystem.util.MoneyFormat;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.client.GuiScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.Frame;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.TextBox;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * Spec B.4 (v2.0.8) — "Pay Missed" confirm popup. Shows the missed execution count
 * and total, prefills the input with the full missed amount, and allows a partial
 * amount. Forwards to the MANAGE-gated {@code PAY_MISSED} ARRS function; the master
 * writes a {@code CATCH_UP} history row and floors the missed counter by the number
 * of full executions covered.
 */
public class PayMissedPopupScreen extends BankSystemGuiScreen {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".pay_missed_popup.";
    private static final Component TITLE = Component.translatable(PREFIX + "title");
    private static final String MISSED_KEY = PREFIX + "missed_summary";
    private static final Component AMOUNT = Component.translatable(PREFIX + "amount");
    private static final Component AMOUNT_TOOLTIP_MONEY = Component.translatable(PREFIX + "amount_tooltip_money");
    private static final Component AMOUNT_TOOLTIP_ITEM = Component.translatable(PREFIX + "amount_tooltip_item");
    private static final Component PAY = Component.translatable(PREFIX + "pay");
    private static final Component CANCEL = Component.translatable(PREFIX + "cancel");
    private static final Component RESULT_INVALID = Component.translatable(PREFIX + "result_invalid");
    private static final Component RESULT_INSUFFICIENT = Component.translatable(PREFIX + "result_insufficient");
    private static final Component RESULT_CURRENCY_MISSING = Component.translatable(PREFIX + "result_currency_missing");
    private static final Component RESULT_FAILED = Component.translatable(PREFIX + "result_failed");
    private static final String RESULT_OK_KEY = PREFIX + "result_ok";

    private static final int COLOR_OK = 0xFF10b981;
    private static final int COLOR_ERR = 0xFFe11d48;

    private final GuiScreen parent;
    private final int companyId;
    private final PayoutSchedule schedule;
    private final UUID caller;
    private final Runnable onDirty;
    private final boolean money;

    private final Frame frame = new Frame();
    private final Label titleLabel;
    private final Label summaryLabel;
    private final Label amountLabel;
    private final TextBox amountBox;
    private final Label statusLabel;
    private final Button payButton;
    private final Button cancelButton;

    private int frameWidth = 460;
    private int frameHeight = 150;

    public PayMissedPopupScreen(GuiScreen parent, int companyId, PayoutSchedule schedule,
                                UUID caller, Runnable onDirty) {
        super(TITLE);
        this.parent = parent;
        this.companyId = companyId;
        this.schedule = schedule;
        this.caller = caller;
        this.onDirty = onDirty;
        this.money = schedule.isMoneyCurrency();

        titleLabel = new Label(TITLE.getString());
        // BUG 1 fix (v2.0.8) — fixed-point applies to all currencies.
        String total = MoneyFormat.format(schedule.getMissedAmount()) + (money ? " $" : "x");
        summaryLabel = new Label(Component.translatable(MISSED_KEY,
                String.valueOf(schedule.getMissedCount()), total).getString());

        amountLabel = new Label(AMOUNT.getString());
        amountBox = new TextBox();
        amountBox.setMatchRegex(TextBox.createRegex_onlyNumerical(true, false, 12, 2));
        amountBox.setText(MoneyFormat.format(schedule.getMissedAmount()));
        amountBox.setHoverTooltipSupplier(() ->
                (money ? AMOUNT_TOOLTIP_MONEY : AMOUNT_TOOLTIP_ITEM).getString());

        statusLabel = new Label("");
        payButton = new Button(PAY.getString(), this::onPay);
        cancelButton = new Button(CANCEL.getString(), () -> switchScreen(parent));

        // Orange warning styling — same set as the other destructive confirm popups.
        frame.setBackgroundColor(0xFFe8711c);
        frame.setOutlineColor(0xFFe04c12);

        addElement(frame);
        frame.addChild(titleLabel);
        frame.addChild(summaryLabel);
        frame.addChild(amountLabel);
        frame.addChild(amountBox);
        frame.addChild(statusLabel);
        frame.addChild(payButton);
        frame.addChild(cancelButton);
    }

    private long parsedAmount() {
        // BUG 1 fix — fixed-point applies to all currencies.
        return MoneyFormat.parseToRaw(amountBox.getText());
    }

    private void onPay() {
        long amount = parsedAmount();
        if (amount <= 0L || amount > schedule.getMissedAmount()) {
            statusLabel.setText(RESULT_INVALID.getString());
            statusLabel.setTextColor(COLOR_ERR);
            return;
        }
        payButton.setEnabled(false);
        AsyncCompanyManager.payMissedAsync(companyId, schedule.getScheduleId(), amount, caller)
                .thenAccept(out -> Minecraft.getInstance().execute(() -> {
                    if (out == null) {
                        statusLabel.setText(RESULT_FAILED.getString());
                        statusLabel.setTextColor(COLOR_ERR);
                        payButton.setEnabled(true);
                        return;
                    }
                    switch (out.resultCode()) {
                        case AsyncCompanyManager.CODE_OK -> {
                            statusLabel.setText(Component.translatable(RESULT_OK_KEY,
                                    MoneyFormat.format(amount)).getString());
                            statusLabel.setTextColor(COLOR_OK);
                            if (onDirty != null) onDirty.run();
                            switchScreen(parent);
                        }
                        case AsyncCompanyManager.CODE_INSUFFICIENT_FUNDS -> {
                            statusLabel.setText(RESULT_INSUFFICIENT.getString());
                            statusLabel.setTextColor(COLOR_ERR);
                            payButton.setEnabled(true);
                        }
                        case AsyncCompanyManager.CODE_CURRENCY_ITEM_MISSING -> {
                            statusLabel.setText(RESULT_CURRENCY_MISSING.getString());
                            statusLabel.setTextColor(COLOR_ERR);
                            payButton.setEnabled(true);
                        }
                        case AsyncCompanyManager.CODE_INVALID_INPUT -> {
                            statusLabel.setText(RESULT_INVALID.getString());
                            statusLabel.setTextColor(COLOR_ERR);
                            payButton.setEnabled(true);
                        }
                        default -> {
                            statusLabel.setText(RESULT_FAILED.getString());
                            statusLabel.setTextColor(COLOR_ERR);
                            payButton.setEnabled(true);
                        }
                    }
                }));
    }

    @Override
    public void onClose() {
        // BUG 1 fix — deferred swap; direct setScreen inside a click callback CMEs.
        switchScreen(parent);
    }

    @Override
    protected void updateLayout(Gui gui) {
        int width = getWidth();
        int height = getHeight();
        frame.setBounds((width - frameWidth) / 2, (height - frameHeight) / 2, frameWidth, frameHeight);
        int p = 5;
        int w = frame.getWidth() - 2 * p;
        titleLabel.setBounds(p, p, w, 15);
        summaryLabel.setBounds(p, titleLabel.getBottom() + 4, w, 15);
        amountLabel.setBounds(p, summaryLabel.getBottom() + 6, 120, 20);
        amountBox.setBounds(p + 125, summaryLabel.getBottom() + 6, w - 125, 20);
        statusLabel.setBounds(p, amountBox.getBottom() + 4, w, 15);
        cancelButton.setBounds(frame.getWidth() - p - 80, frame.getHeight() - p - 20, 80, 20);
        payButton.setBounds(cancelButton.getLeft() - 5 - 80, frame.getHeight() - p - 20, 80, 20);
    }
}
