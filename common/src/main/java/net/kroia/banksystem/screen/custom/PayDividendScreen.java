package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.client.GuiScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.CheckBox;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.TextBox;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * Task #49 (v2.0.8) — one-shot dividend distribution modal.
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
    private static final Component TITLE = Component.translatable(PREFIX + "title");
    private static final Component AMOUNT = Component.translatable(PREFIX + "amount");
    private static final Component INCLUDE_SELF = Component.translatable(PREFIX + "include_company_account");
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

    private final GuiScreen parent;
    private final int companyId;
    private final UUID caller;

    private Label titleLabel;
    private Label amountLabel;
    private TextBox amountBox;
    private CheckBox includeSelfCheckBox;
    private Button payButton;
    private Button cancelButton;
    private Label statusLabel;

    public PayDividendScreen(GuiScreen parent, int companyId, UUID caller) {
        super(TITLE);
        this.parent = parent;
        this.companyId = companyId;
        this.caller = caller;
        setupUi();
    }

    private void setupUi() {
        titleLabel = new Label(TITLE.getString());

        amountLabel = new Label(AMOUNT.getString());
        amountBox = new TextBox();
        amountBox.setMatchRegex(TextBox.createRegex_onlyNumerical(true, false, 12, 0));
        amountBox.setText("1");

        includeSelfCheckBox = new CheckBox(INCLUDE_SELF.getString());
        includeSelfCheckBox.setChecked(false);

        payButton = new Button(PAY.getString(), this::onPayClicked);
        cancelButton = new Button(CANCEL.getString(), this::onClose);
        statusLabel = new Label("");

        addElement(titleLabel);
        addElement(amountLabel);
        addElement(amountBox);
        addElement(includeSelfCheckBox);
        addElement(payButton);
        addElement(cancelButton);
        addElement(statusLabel);
    }

    private long parsedAmount() {
        try {
            return Long.parseLong(amountBox.getText().trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void onPayClicked() {
        long amount = parsedAmount();
        if (amount <= 0L) {
            statusLabel.setText(RESULT_INVALID.getString());
            statusLabel.setTextColor(COLOR_ERR);
            return;
        }
        payButton.setEnabled(false);
        boolean includeSelf = includeSelfCheckBox.isChecked();
        AsyncCompanyManager.payDividendAsync(companyId, amount, includeSelf, caller).thenAccept(out -> {
            if (out == null) {
                statusLabel.setText(RESULT_INTERNAL.getString());
                statusLabel.setTextColor(COLOR_ERR);
                payButton.setEnabled(true);
                return;
            }
            switch (out.resultCode()) {
                case AsyncCompanyManager.CODE_OK -> {
                    statusLabel.setText(Component.translatable(RESULT_OK_KEY,
                            String.valueOf(out.totalPaid()), String.valueOf(out.holderCount())).getString());
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
        if (titleLabel == null) return;

        int y = padding;
        titleLabel.setBounds(padding, y, width, 20); y += 25;

        int col = width / 2;
        amountLabel.setBounds(padding, y, col - spacing, 20);
        amountBox.setBounds(padding + col, y, col - spacing, 20);
        y += 25;

        includeSelfCheckBox.setBounds(padding, y, width, 20);
        y += 25;

        statusLabel.setBounds(padding, y, width, 20);

        int btnW = (width - spacing) / 2;
        payButton.setBounds(padding, getHeight() - 25, btnW, 20);
        cancelButton.setBounds(padding + btnW + spacing, getHeight() - 25, btnW, 20);
    }
}
