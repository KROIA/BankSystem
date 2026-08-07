package net.kroia.banksystem.screen.uiElements.tabbody;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.screen.custom.CompanyManagementScreen;
import net.kroia.banksystem.screen.uiElements.AskPopupScreen;
import net.kroia.banksystem.screen.uiElements.InfoPopupScreen;
import net.kroia.banksystem.screen.uiElements.PlayerPickerPopup;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.TextBox;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Task #51 (v2.0.8, spec §6) — Danger Zone tab (founder-only): transfer founder
 * role via {@link PlayerPickerPopup}, or dissolve the company after typing the
 * exact company name. Both actions require an {@link AskPopupScreen} confirmation
 * and close the screen on success.
 */
public class DangerTabBody extends TabBody {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".company_management_screen.";

    private final Label warningLabel;
    private final Label transferHeader;
    private final Label targetLabel;
    private final Button targetPickerButton;
    private final Button transferButton;
    private final net.kroia.banksystem.screen.uiElements.HorizontalDivider divider;
    private final Label dissolveHeader;
    private final Label dissolveHintLabel;
    private final TextBox dissolveConfirmBox;
    private final Button dissolveButton;

    private String selectedTarget = "";

    public DangerTabBody(CompanyManagementScreen screen) {
        super(screen);

        warningLabel = new Label("⚠ " + tr("danger_warning"));
        warningLabel.setAlignment(Label.Alignment.LEFT);
        warningLabel.setTextColor(0xFFFF5555);

        transferHeader = new Label(tr("transfer_founder") + ":");
        transferHeader.setAlignment(Label.Alignment.LEFT);
        targetLabel = new Label(tr("choose_target") + ":");
        targetLabel.setAlignment(Label.Alignment.RIGHT);
        targetPickerButton = new Button(tr("choose_target"), this::onPickTarget);
        transferButton = new Button(tr("transfer_founder"), this::onTransfer);
        transferButton.setEnabled(false);

        // Spec A.2 — primitive 1-px divider instead of a dashes label.
        divider = new net.kroia.banksystem.screen.uiElements.HorizontalDivider();

        dissolveHeader = new Label(tr("dissolve") + ":");
        dissolveHeader.setAlignment(Label.Alignment.LEFT);
        dissolveHintLabel = new Label(tr("dissolve_hint"));
        dissolveHintLabel.setAlignment(Label.Alignment.LEFT);
        dissolveConfirmBox = new TextBox();
        dissolveButton = new Button(tr("dissolve"), this::onDissolve);
        dissolveButton.setEnabled(false);
        dissolveConfirmBox.setOnTextChanged(t -> dissolveButton.setEnabled(nameMatches()));

        addChild(warningLabel);
        addChild(transferHeader);
        addChild(targetLabel);
        addChild(targetPickerButton);
        addChild(transferButton);
        addChild(divider);
        addChild(dissolveHeader);
        addChild(dissolveHintLabel);
        addChild(dissolveConfirmBox);
        addChild(dissolveButton);
    }

    private static String tr(String key) {
        return Component.translatable(PREFIX + key).getString();
    }

    private String canonicalName() {
        var info = screen.info();
        return info != null && info.present() ? info.name() : screen.getCompanyName();
    }

    private boolean nameMatches() {
        String canonical = canonicalName();
        return !canonical.isEmpty() && dissolveConfirmBox.getText().equals(canonical);
    }

    private void onPickTarget() {
        net.kroia.banksystem.util.BankSystemGuiScreen.switchScreen(new PlayerPickerPopup(screen, name -> {
            selectedTarget = name == null ? "" : name;
            targetPickerButton.setText(selectedTarget.isEmpty() ? tr("choose_target") : selectedTarget);
            transferButton.setEnabled(!selectedTarget.isEmpty());
        }));
    }

    private void onTransfer() {
        if (selectedTarget.isEmpty()) return;
        String msg = tr("transfer_confirm_msg")
                .replace("%company%", canonicalName())
                .replace("%target%", selectedTarget);
        AskPopupScreen popup = AskPopupScreen.warningPopup(screen,
                () -> AsyncCompanyManager.transferFounderAsync(canonicalName(), screen.callerUUID(), selectedTarget)
                        .thenAccept(out -> onClientThread(() -> {
                            if (out != null && out.resultCode() == AsyncCompanyManager.CODE_OK) {
                                screen.onClose();
                            } else {
                                net.kroia.banksystem.util.BankSystemGuiScreen.switchScreen(new InfoPopupScreen(screen,
                                        tr("error_title"), tr("action_failed")));
                            }
                        })),
                () -> {},
                tr("transfer_confirm_title"), msg);
        net.kroia.banksystem.util.BankSystemGuiScreen.switchScreen(popup);
    }

    private void onDissolve() {
        if (!nameMatches()) return;
        AskPopupScreen popup = AskPopupScreen.warningPopup(screen,
                () -> AsyncCompanyManager.dissolveCompanyAsync(canonicalName(), screen.callerUUID())
                        .thenAccept(out -> onClientThread(() -> {
                            if (out != null && out.resultCode() == AsyncCompanyManager.CODE_OK) {
                                screen.onClose();
                            } else {
                                net.kroia.banksystem.util.BankSystemGuiScreen.switchScreen(new InfoPopupScreen(screen,
                                        tr("error_title"), tr("action_failed")));
                            }
                        })),
                () -> {},
                tr("dissolve_confirm_title"),
                tr("dissolve_confirm_msg") + "\n" + canonicalName());
        net.kroia.banksystem.util.BankSystemGuiScreen.switchScreen(popup);
    }

    @Override
    protected void layoutChanged() {
        int w = getWidth();
        int inputX = PADDING + LABEL_WIDTH + ROW_SPACING;
        int inputW = w - inputX - PADDING;
        int y = PADDING;
        warningLabel.setBounds(PADDING, y, w - 2 * PADDING, ROW_HEIGHT);
        y += ROW_HEIGHT + SECTION_SPACING;
        transferHeader.setBounds(PADDING, y, w - 2 * PADDING, ROW_HEIGHT);
        y += ROW_HEIGHT + ROW_SPACING;
        targetLabel.setBounds(PADDING, y, LABEL_WIDTH, ROW_HEIGHT);
        targetPickerButton.setBounds(inputX, y, Math.min(160, inputW), ROW_HEIGHT);
        y += ROW_HEIGHT + ROW_SPACING;
        transferButton.setBounds(w - PADDING - 120, y, 120, ROW_HEIGHT);
        y += ROW_HEIGHT + SECTION_SPACING;
        divider.setBounds(PADDING, y + ROW_HEIGHT / 2, w - 2 * PADDING, 1);
        y += ROW_HEIGHT + SECTION_SPACING;
        dissolveHeader.setBounds(PADDING, y, w - 2 * PADDING, ROW_HEIGHT);
        y += ROW_HEIGHT + ROW_SPACING;
        dissolveHintLabel.setBounds(PADDING, y, w - 2 * PADDING, ROW_HEIGHT);
        y += ROW_HEIGHT + ROW_SPACING;
        dissolveConfirmBox.setBounds(PADDING, y, w - 2 * PADDING, ROW_HEIGHT);
        y += ROW_HEIGHT + ROW_SPACING;
        dissolveButton.setBounds(w - PADDING - 120, y, 120, ROW_HEIGHT);
    }
}
