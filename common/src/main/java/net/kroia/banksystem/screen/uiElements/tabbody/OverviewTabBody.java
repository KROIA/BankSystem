package net.kroia.banksystem.screen.uiElements.tabbody;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.client.cache.CompanyInfoCache;
import net.kroia.banksystem.client.cache.ShareVisualCache;
import net.kroia.banksystem.screen.custom.CompanyManagementScreen;
import net.kroia.banksystem.screen.uiElements.InfoPopupScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.MultiLineTextBox;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Task #51 (v2.0.8, spec §1) — Overview tab: read-only company summary rows +
 * description editor (MANAGE / founder only).
 */
public class OverviewTabBody extends TabBody {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".company_management_screen.";

    private final Label[] rowLabels;
    private final Label[] rowValues;
    private final Label descriptionHeader;
    private final MultiLineTextBox descriptionBox;   // editable variant
    private final Label descriptionLabel;   // read-only variant
    private final Button saveButton;
    private final boolean editable;

    public OverviewTabBody(CompanyManagementScreen screen) {
        super(screen);
        this.editable = screen.canManageNow() || screen.isFounderNow();

        String[] rowKeys = {"name", "company_id", "bank_account", "max_supply", "issued", "holders", "founders"};
        rowLabels = new Label[rowKeys.length];
        rowValues = new Label[rowKeys.length];
        for (int i = 0; i < rowKeys.length; i++) {
            rowLabels[i] = new Label(Component.translatable(PREFIX + rowKeys[i]).getString() + ":");
            rowLabels[i].setAlignment(Label.Alignment.RIGHT);
            rowValues[i] = new Label("");
            rowValues[i].setAlignment(Label.Alignment.LEFT);
            addChild(rowLabels[i]);
            addChild(rowValues[i]);
        }

        descriptionHeader = new Label(Component.translatable(PREFIX + "description").getString() + ":");
        descriptionHeader.setAlignment(Label.Alignment.LEFT);
        addChild(descriptionHeader);

        descriptionBox = new MultiLineTextBox();
        descriptionBox.setMaxLength(512);
        descriptionLabel = new Label("");
        descriptionLabel.setAlignment(Label.Alignment.TOP_LEFT);
        saveButton = new Button(Component.translatable(PREFIX + "save").getString(), this::onSave);
        if (editable) {
            addChild(descriptionBox);
            addChild(saveButton);
        } else {
            addChild(descriptionLabel);
        }

        applyInfo();
        // Spec §1.3 — refresh the holder count on tab open.
        AsyncCompanyManager.countHoldersForCompanyAsync(screen.getCompanyId()).thenAccept(count ->
                onClientThread(() -> {
                    CompanyInfoCache.updateHolderCount(screen.getCompanyId(), count);
                    applyInfo();
                }));
    }

    private void applyInfo() {
        AsyncCompanyManager.CompanyInfoOutput info = screen.info();
        if (info == null || !info.present()) {
            for (Label v : rowValues) v.setText("?");
            return;
        }
        long issued = ShareVisualCache.getIssued(info.companyId());
        if (issued == 0L) issued = info.totalSharesIssued();
        CompanyInfoCache.Snapshot snap = CompanyInfoCache.get(info.companyId());
        int holders = snap != null ? snap.holderCount() : 0;

        rowValues[0].setText(info.name());
        rowValues[1].setText(String.valueOf(info.companyId()));
        rowValues[2].setText("#" + info.bankAccountNr());
        fetchBankAccountName(info.bankAccountNr());
        rowValues[3].setText(formatNumber(info.maxSupply()));
        rowValues[4].setText(formatNumber(issued));
        rowValues[5].setText(String.valueOf(holders));
        rowValues[6].setText(formatFounders(info.founderNames()));

        if (editable) {
            if (!descriptionBox.isFocused()) descriptionBox.setText(info.description());
        } else {
            descriptionLabel.setText(info.description());
        }
    }

    /** Lightweight follow-up lookup — CompanyInfoOutput only carries the account number,
     *  so the account name is fetched async and patched into the row when it arrives. */
    private void fetchBankAccountName(int accountNr) {
        screen.clientBankManager().getBankAccountDataAsync(accountNr)
                .thenAccept(data -> onClientThread(() -> {
                    if (data == null || data.accountName == null || data.accountName.isEmpty()) return;
                    var current = screen.info();
                    if (current == null || !current.present() || current.bankAccountNr() != accountNr) return;
                    rowValues[2].setText("#" + accountNr + " — " + data.accountName);
                }));
    }

    private static String formatFounders(List<String> founders) {
        if (founders == null || founders.isEmpty()) return "-";
        StringBuilder sb = new StringBuilder();
        for (String f : founders) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(f).append(" ★");
        }
        return sb.toString();
    }

    private void onSave() {
        AsyncCompanyManager.CompanyInfoOutput info = screen.info();
        if (info == null || !info.present()) return;
        String text = descriptionBox.getText();
        // Spec §0.7 — optimistic cache update, then reconcile with authoritative state.
        CompanyInfoCache.updateDescription(info.companyId(), text);
        AsyncCompanyManager.updateDescriptionAsync(info.name(), screen.callerUUID(), text)
                .thenAccept(out -> onClientThread(() -> {
                    if (out == null || out.resultCode() != AsyncCompanyManager.CODE_OK) {
                        net.kroia.banksystem.util.BankSystemGuiScreen.switchScreen(new InfoPopupScreen(screen,
                                Component.translatable(PREFIX + "error_title").getString(),
                                Component.translatable(PREFIX + "action_failed").getString()));
                        return;
                    }
                    screen.refreshInfo();
                }));
    }

    @Override
    public void onInfoUpdated() {
        applyInfo();
        layoutChangedInternal();
    }

    @Override
    protected void layoutChanged() {
        int w = getWidth();
        int y = PADDING;
        for (int i = 0; i < rowLabels.length; i++) {
            rowLabels[i].setBounds(PADDING, y, LABEL_WIDTH, ROW_HEIGHT);
            rowValues[i].setBounds(PADDING + LABEL_WIDTH + ROW_SPACING, y,
                    w - 2 * PADDING - LABEL_WIDTH - ROW_SPACING, ROW_HEIGHT);
            y += ROW_HEIGHT - 4; // compact info rows
        }
        y += SECTION_SPACING;
        descriptionHeader.setBounds(PADDING, y, w - 2 * PADDING, ROW_HEIGHT);
        y += ROW_HEIGHT + ROW_SPACING;
        int descHeight = ROW_HEIGHT * 4; // MultiLineTextBox needs more vertical space
        if (editable) {
            descriptionBox.setBounds(PADDING, y, w - 2 * PADDING, descHeight);
            y += descHeight + ROW_SPACING;
            saveButton.setBounds(w - PADDING - 80, y, 80, ROW_HEIGHT);
        } else {
            descriptionLabel.setBounds(PADDING, y, w - 2 * PADDING, descHeight * 2);
        }
    }
}
