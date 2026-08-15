package net.kroia.banksystem.screen.uiElements.tabbody;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.screen.custom.CompanyManagementScreen;
import net.kroia.banksystem.screen.custom.PayDividendScreen;
import net.kroia.banksystem.screen.custom.PayoutEditScreen;
import net.kroia.banksystem.screen.uiElements.PayoutsOverviewPanel;
import net.kroia.modutilities.gui.elements.Button;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Task #51 (v2.1.0, spec §3) — Payouts tab: embeds the shared
 * {@link PayoutsOverviewPanel} (which links out to the authoritative
 * {@code PayoutsOverviewScreen} schedule list) plus the New Payout and
 * Pay Dividend actions.
 */
public class PayoutsTabBody extends TabBody {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".company_management_screen.";

    private final PayoutsOverviewPanel panel;
    private final Button newPayoutButton;
    private final Button payDividendButton;
    private final boolean authorised;

    public PayoutsTabBody(CompanyManagementScreen screen) {
        super(screen);
        this.authorised = screen.canManageNow() || screen.isFounderNow();

        var info = screen.info();
        int bankAccountNr = info != null && info.present() ? info.bankAccountNr() : 0;
        panel = new PayoutsOverviewPanel(screen, screen.getCompanyId(), bankAccountNr, authorised);
        addChild(panel);

        newPayoutButton = new Button(
                Component.translatable(PREFIX + "new_payout").getString(), this::onNewPayout);
        payDividendButton = new Button(
                Component.translatable(PREFIX + "pay_dividend").getString(), this::onPayDividend);
        newPayoutButton.setEnabled(authorised);
        payDividendButton.setEnabled(authorised);
        addChild(newPayoutButton);
        addChild(payDividendButton);
    }

    private void onNewPayout() {
        if (!authorised) return;
        net.kroia.banksystem.util.BankSystemGuiScreen.switchScreen(new PayoutEditScreen(
                screen, screen.getCompanyId(), null, screen.callerUUID(), true, screen::refreshInfo));
    }

    private void onPayDividend() {
        if (!authorised) return;
        net.kroia.banksystem.util.BankSystemGuiScreen.switchScreen(new PayDividendScreen(
                screen, screen.getCompanyId(), screen.callerUUID()));
    }

    @Override
    protected void layoutChanged() {
        int w = getWidth();
        int h = getHeight();
        int buttonRow = h - PADDING - ROW_HEIGHT;
        panel.setBounds(PADDING, PADDING, w - 2 * PADDING,
                Math.max(ROW_HEIGHT, buttonRow - 2 * PADDING - SECTION_SPACING));
        int btnW = Math.min(140, (w - 2 * PADDING - ROW_SPACING) / 2);
        payDividendButton.setBounds(w - PADDING - btnW, buttonRow, btnW, ROW_HEIGHT);
        newPayoutButton.setBounds(payDividendButton.getLeft() - ROW_SPACING - btnW, buttonRow, btnW, ROW_HEIGHT);
    }
}
