package net.kroia.banksystem.screen.uiElements;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.screen.custom.PayoutsOverviewScreen;
import net.kroia.modutilities.gui.client.GuiScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.minecraft.network.chat.Component;

/**
 * Task #51 (v2.1.0) — shared launcher panel for the payouts overview UI.
 *
 * <p>Deviation from spec: {@link net.kroia.banksystem.screen.custom.BankAccountManagementScreen}
 * never inlined a payouts body — it always used a header button to open the standalone
 * {@link PayoutsOverviewScreen}. There is therefore no "inline code" to extract; instead
 * this panel provides a compact embeddable widget (header + Manage Payouts button) that
 * the new {@link net.kroia.banksystem.screen.custom.CompanyManagementScreen} can drop into
 * its Payouts tab, while the standalone screen remains the authoritative body. Follow-up:
 * inline the full schedule list here once the shared widget contract stabilises.
 */
public class PayoutsOverviewPanel extends GuiElement {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".company_management_screen.";
    private static final Component MANAGE_PAYOUTS = Component.translatable(PREFIX + "manage_payouts");
    private static final Component PAYOUTS_HEADER = Component.translatable(PREFIX + "payouts_header");
    private static final Component PAYOUTS_INFO = Component.translatable(PREFIX + "payouts_info");

    private final int companyId;
    private final int bankAccountNr;
    private final boolean canManage;
    private final GuiScreen host;

    private final Label headerLabel;
    private final Label infoLabel;
    private final Button openButton;

    public PayoutsOverviewPanel(GuiScreen host, int companyId, int bankAccountNr, boolean canManage) {
        super();
        this.host = host;
        this.companyId = companyId;
        this.bankAccountNr = bankAccountNr;
        this.canManage = canManage;
        headerLabel = new Label(PAYOUTS_HEADER.getString());
        infoLabel = new Label(PAYOUTS_INFO.getString());
        openButton = new Button(MANAGE_PAYOUTS.getString(), this::onOpen);
        addChild(headerLabel);
        addChild(infoLabel);
        addChild(openButton);
    }

    private void onOpen() {
        PayoutsOverviewScreen.openScreen(host, bankAccountNr, companyId, canManage);
    }

    @Override
    protected void render() {}

    @Override
    protected void layoutChanged() {
        int w = getWidth();
        int p = 5;  // inner padding
        int y = p;
        headerLabel.setBounds(p, y, w - 2 * p, 20); y += 22;
        infoLabel.setBounds(p, y, w - 2 * p, 20); y += 24;
        int btnW = Math.min(100, (w - 2 * p) / 2);  // half of available width, max 100px
        openButton.setBounds(p, y, btnW, 20);
    }
}
