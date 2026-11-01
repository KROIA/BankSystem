package net.kroia.banksystem.screen.uiElements.tabbody;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.banking.company.PayoutSchedule;
import net.kroia.banksystem.client.cache.CompanyInfoCache;
import net.kroia.banksystem.client.cache.ShareVisualCache;
import net.kroia.banksystem.screen.custom.CompanyManagementScreen;
import net.kroia.banksystem.screen.uiElements.InfoPopupScreen;
import net.kroia.banksystem.screen.uiElements.ItemBalancePickerPopup;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.ItemView;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.MultiLineTextBox;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Task #51 (v2.1.0, spec §1) — Overview tab: read-only company summary rows +
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

    // Fix 10 — company currency picker (MANAGE-gated)
    private final Label currencyLabel;
    private final Button currencyButton;
    private final ItemView currencyIcon;
    private short currentCurrency = PayoutSchedule.MONEY_CURRENCY;

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

        // Fix 10 — company currency picker (MANAGE-gated)
        currencyLabel = new Label(Component.translatable(PREFIX + "company_currency").getString() + ":");
        currencyLabel.setAlignment(Label.Alignment.RIGHT);
        currencyLabel.setHoverTooltipSupplier(() -> Component.translatable(PREFIX + "company_currency_tooltip").getString());
        currencyIcon = new ItemView(ItemStack.EMPTY);
        currencyIcon.setShowCount(false);
        // Button has no setLabel; we use an icon-only button with the icon as child.
        currencyButton = new Button("", this::onPickCurrencyClicked);
        currencyButton.addChild(currencyIcon);
        if (editable) {
            addChild(currencyLabel);
            addChild(currencyButton);
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

        // Fix 10 — sync currency picker state from server info
        if (editable) {
            currentCurrency = info.companyCurrency();
            applyCurrencyDisplay(currentCurrency);
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

    private void applyCurrencyDisplay(short currency) {
        ItemStack stack;
        if (currency == PayoutSchedule.MONEY_CURRENCY) {
            stack = net.kroia.banksystem.minecraft.item.BankSystemItems.MONEY.get().getDefaultInstance();
        } else {
            stack = ItemStack.EMPTY;
            for (java.util.Map.Entry<ItemID, ItemStack> e : ItemIDManager.getItemIDMap().entrySet()) {
                if (e.getKey().getShort() == currency) { stack = e.getValue(); break; }
            }
        }
        currencyIcon.setItemStack(stack);
    }

    private void onPickCurrencyClicked() {
        if (!editable) return;
        AsyncCompanyManager.CompanyInfoOutput info = screen.info();
        if (info == null || !info.present()) return;
        BankSystemGuiScreen.switchScreen(new ItemBalancePickerPopup(screen, info.companyId(),
                screen.callerUUID(), this::onCurrencyPicked));
    }

    private void onCurrencyPicked(short currency) {
        currentCurrency = currency;
        applyCurrencyDisplay(currency);
        AsyncCompanyManager.CompanyInfoOutput info = screen.info();
        if (info == null || !info.present()) return;
        AsyncCompanyManager.setCompanyCurrencyAsync(info.companyId(), currency, screen.callerUUID())
                .thenAccept(out -> onClientThread(screen::refreshInfo));
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
        int h = getHeight();
        int labelW = 120; // wider than LABEL_WIDTH to fit "Company Currency:" label
        int y = PADDING;
        for (int i = 0; i < rowLabels.length; i++) {
            rowLabels[i].setBounds(PADDING, y, labelW, ROW_HEIGHT);
            rowValues[i].setBounds(PADDING + labelW + ROW_SPACING, y,
                    w - 2 * PADDING - labelW - ROW_SPACING, ROW_HEIGHT);
            y += ROW_HEIGHT - 4;
        }
        if (editable) {
            currencyLabel.setBounds(PADDING, y, labelW, ROW_HEIGHT);
            int btnX = PADDING + labelW + ROW_SPACING;
            currencyButton.setBounds(btnX, y, 20, 20);
            currencyIcon.setBounds(1, 1, 18, 18);
            y += ROW_HEIGHT + ROW_SPACING;
        }

        y += SECTION_SPACING;
        descriptionHeader.setBounds(PADDING, y, w - 2 * PADDING, ROW_HEIGHT);
        y += ROW_HEIGHT + ROW_SPACING;

        int saveW = 80;
        // Save button pinned to bottom-right of canvas.
        int saveY = h - PADDING - ROW_HEIGHT;
        if (editable) {
            // Layout save button first, then stretch textbox to its bottom edge.
            saveButton.setBounds(w - PADDING - saveW, saveY, saveW, ROW_HEIGHT);
            int descH = Math.max(ROW_HEIGHT, (saveY + ROW_HEIGHT) - y);
            descriptionBox.setBounds(PADDING, y, w - 2 * PADDING - saveW - ROW_SPACING, descH);
        } else {
            int descH = Math.max(ROW_HEIGHT, h - PADDING - y);
            descriptionLabel.setBounds(PADDING, y, w - 2 * PADDING, descH);
        }
    }
}
