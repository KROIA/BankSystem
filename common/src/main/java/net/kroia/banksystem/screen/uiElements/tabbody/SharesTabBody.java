package net.kroia.banksystem.screen.uiElements.tabbody;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.banking.company.ShareVisuals;
import net.kroia.banksystem.client.cache.ShareVisualCache;
import net.kroia.banksystem.screen.custom.CompanyManagementScreen;
import net.kroia.banksystem.screen.uiElements.AskPopupScreen;
import net.kroia.banksystem.screen.uiElements.ColorPickerPopup;
import net.kroia.banksystem.screen.uiElements.InfoPopupScreen;
import net.kroia.banksystem.screen.uiElements.PresetPickerPopup;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.TextBox;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Task #51 (v2.0.8, spec §4) — Shares tab: share visuals editor (preset picker,
 * tint via {@link ColorPickerPopup}, display name, share description) + the list
 * of Share Stamper blocks bound to this company.
 *
 * <p>Tint discipline (spec §4.4): the current tint is re-sourced from
 * {@link ShareVisualCache} on every rebuild — no stale local field survives a save.
 */
public class SharesTabBody extends TabBody {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".company_management_screen.";

    private final Label introLabel;
    private final Label presetLabel;
    private final Button presetButton;
    private final Label tintLabel;
    private final Button tintSwatch;
    private final Label displayNameLabel;
    private final TextBox displayNameBox;
    private final Label descriptionLabel;
    private final TextBox descriptionBox;
    private final Button saveButton;
    private final Label stamperHeader;
    private final Button refreshButton;
    private final VerticalListView stamperList;

    private final boolean editable;
    private String selectedPresetId;
    private int selectedTint;

    public SharesTabBody(CompanyManagementScreen screen) {
        super(screen);
        this.editable = screen.canManageNow() || screen.isFounderNow();

        ShareVisuals visuals = ShareVisualCache.getVisualsOrPlaceholder(screen.getCompanyId());
        selectedPresetId = visuals.getIconPresetId() == null ? "" : visuals.getIconPresetId();
        selectedTint = visuals.getTint();

        introLabel = new Label(Component.translatable(PREFIX + "shares_intro").getString());
        introLabel.setAlignment(Label.Alignment.LEFT);

        presetLabel = rightLabel("preset");
        presetButton = new Button(presetButtonText(), this::onPickPreset);
        tintLabel = rightLabel("tint");
        tintSwatch = new Button("", this::onPickTint);
        tintSwatch.setBackgroundColor(selectedTint);
        tintSwatch.setHoverColor(selectedTint);
        tintSwatch.setPressedColor(selectedTint);

        displayNameLabel = rightLabel("display_name");
        displayNameBox = new TextBox();
        displayNameBox.setText(visuals.getDisplayName() == null ? "" : visuals.getDisplayName());
        displayNameBox.setOnTextChanged(t -> {
            if (t != null && t.length() > 24) displayNameBox.setText(t.substring(0, 24));
        });

        descriptionLabel = rightLabel("description");
        descriptionBox = new TextBox();
        descriptionBox.setText(visuals.getDescription() == null ? "" : visuals.getDescription());
        descriptionBox.setOnTextChanged(t -> {
            if (t != null && t.length() > 120) descriptionBox.setText(t.substring(0, 120));
        });

        saveButton = new Button(Component.translatable(PREFIX + "save_visuals").getString(), this::onSave);

        presetButton.setEnabled(editable);
        tintSwatch.setEnabled(editable);
        displayNameBox.setEnabled(editable);
        descriptionBox.setEnabled(editable);
        saveButton.setEnabled(editable);

        stamperHeader = new Label(Component.translatable(PREFIX + "stamper_bindings").getString() + ":");
        stamperHeader.setAlignment(Label.Alignment.LEFT);
        refreshButton = new Button(Component.translatable(PREFIX + "refresh").getString(), this::fetchStampers);
        stamperList = new VerticalListView();
        LayoutGrid l = new LayoutGrid();
        l.columns = 1; l.rows = 0; l.spacing = 2; l.padding = 2;
        l.stretchX = true; l.stretchY = false;
        l.alignment = GuiElement.Alignment.TOP;
        stamperList.setLayout(l);

        addChild(introLabel);
        addChild(presetLabel);
        addChild(presetButton);
        addChild(tintLabel);
        addChild(tintSwatch);
        addChild(displayNameLabel);
        addChild(displayNameBox);
        addChild(descriptionLabel);
        addChild(descriptionBox);
        addChild(saveButton);
        addChild(stamperHeader);
        addChild(refreshButton);
        addChild(stamperList);

        fetchStampers();
    }

    private Label rightLabel(String key) {
        Label label = new Label(Component.translatable(PREFIX + key).getString() + ":");
        label.setAlignment(Label.Alignment.RIGHT);
        return label;
    }

    private String presetButtonText() {
        return selectedPresetId.isEmpty()
                ? Component.translatable(PREFIX + "choose_preset").getString()
                : selectedPresetId;
    }

    private void onPickPreset() {
        if (!editable) return;
        net.kroia.banksystem.util.BankSystemGuiScreen.switchScreen(new PresetPickerPopup(screen, presetId -> {
            selectedPresetId = presetId == null ? "" : presetId;
            presetButton.setText(presetButtonText());
        }));
    }

    private void onPickTint() {
        if (!editable) return;
        net.kroia.banksystem.util.BankSystemGuiScreen.switchScreen(new ColorPickerPopup(screen, selectedTint, argb -> {
            selectedTint = argb;
            tintSwatch.setBackgroundColor(argb);
            tintSwatch.setHoverColor(argb);
            tintSwatch.setPressedColor(argb);
        }));
    }

    private void onSave() {
        if (!editable) return;
        int companyId = screen.getCompanyId();
        String displayName = displayNameBox.getText();
        String description = descriptionBox.getText();
        // Spec §0.7 — optimistic cache update so the current frame reflects the change.
        ShareVisualCache.put(companyId,
                new ShareVisuals(selectedPresetId, selectedTint, displayName, description),
                ShareVisualCache.getIssued(companyId), ShareVisualCache.getMax(companyId));
        AsyncCompanyManager.updateShareVisualsAsync(companyId, selectedPresetId, selectedTint,
                        displayName, description, screen.callerUUID())
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

    private void fetchStampers() {
        AsyncCompanyManager.listStamperBindingsAsync(screen.getCompanyId())
                .thenAccept(out -> onClientThread(() -> {
                    stamperList.removeChilds();
                    if (out == null || out.positions().isEmpty()) {
                        stamperList.addChild(new Label(
                                Component.translatable(PREFIX + "no_stampers").getString()));
                    } else {
                        for (BlockPos pos : out.positions()) {
                            stamperList.addChild(new StamperRow(pos));
                        }
                    }
                    layoutChangedInternal();
                }));
    }

    /** Spec §4.3 — asks for confirmation, then forwards the MANAGE-gated unbind to master. */
    private void onUnbindClicked(BlockPos pos) {
        if (!editable) return;
        AskPopupScreen popup = AskPopupScreen.warningPopup(screen,
                () -> doUnbind(pos),
                () -> {},
                Component.translatable(PREFIX + "unbind_confirm_title").getString(),
                Component.translatable(PREFIX + "unbind_confirm_msg",
                        pos.getX(), pos.getY(), pos.getZ()).getString());
        net.kroia.banksystem.util.BankSystemGuiScreen.switchScreen(popup);
    }

    private void doUnbind(BlockPos pos) {
        AsyncCompanyManager.unbindStamperAsync(screen.getCompanyId(), pos, screen.callerUUID())
                .thenAccept(out -> onClientThread(() -> {
                    if (out == null || out.resultCode() != AsyncCompanyManager.CODE_OK) {
                        net.kroia.banksystem.util.BankSystemGuiScreen.switchScreen(new InfoPopupScreen(screen,
                                Component.translatable(PREFIX + "error_title").getString(),
                                Component.translatable(PREFIX + "action_failed").getString()));
                        // Refresh anyway — a NOT_FOUND result means the list entry was stale.
                        fetchStampers();
                        return;
                    }
                    fetchStampers();
                }));
    }

    /** Spec §4.3 — one bound-stamper row: "(x, y, z)" label + trailing Unbind button (MANAGE/founder only). */
    private class StamperRow extends GuiElement {
        private final Label posLabel;
        private final Button unbindButton;

        StamperRow(BlockPos pos) {
            super();
            setHeight(ROW_HEIGHT);
            posLabel = new Label("(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")");
            posLabel.setAlignment(Label.Alignment.LEFT);
            unbindButton = new Button(Component.translatable(PREFIX + "unbind").getString(),
                    () -> onUnbindClicked(pos));
            unbindButton.setEnabled(editable);
            addChild(posLabel);
            addChild(unbindButton);
        }

        @Override
        protected void render() {}

        @Override
        protected void layoutChanged() {
            int w = getWidth();
            int h = getHeight();
            int btnW = Math.min(60, Math.max(40, w / 4));
            posLabel.setBounds(0, 0, Math.max(0, w - btnW - ROW_SPACING), h);
            unbindButton.setBounds(Math.max(0, w - btnW), 0, btnW, h);
        }
    }

    @Override
    protected void layoutChanged() {
        int w = getWidth();
        int h = getHeight();
        int inputX = PADDING + LABEL_WIDTH + ROW_SPACING;
        int inputW = w - inputX - PADDING;
        int y = PADDING;
        introLabel.setBounds(PADDING, y, w - 2 * PADDING, ROW_HEIGHT);
        y += ROW_HEIGHT + ROW_SPACING;
        presetLabel.setBounds(PADDING, y, LABEL_WIDTH, ROW_HEIGHT);
        presetButton.setBounds(inputX, y, Math.min(160, inputW), ROW_HEIGHT);
        y += ROW_HEIGHT + ROW_SPACING;
        tintLabel.setBounds(PADDING, y, LABEL_WIDTH, ROW_HEIGHT);
        tintSwatch.setBounds(inputX, y, 40, ROW_HEIGHT);
        y += ROW_HEIGHT + ROW_SPACING;
        displayNameLabel.setBounds(PADDING, y, LABEL_WIDTH, ROW_HEIGHT);
        displayNameBox.setBounds(inputX, y, inputW, ROW_HEIGHT);
        y += ROW_HEIGHT + ROW_SPACING;
        descriptionLabel.setBounds(PADDING, y, LABEL_WIDTH, ROW_HEIGHT);
        descriptionBox.setBounds(inputX, y, inputW, ROW_HEIGHT);
        y += ROW_HEIGHT + ROW_SPACING;
        saveButton.setBounds(w - PADDING - 100, y, 100, ROW_HEIGHT);
        y += ROW_HEIGHT + SECTION_SPACING;
        stamperHeader.setBounds(PADDING, y, w - 2 * PADDING - 60, ROW_HEIGHT);
        refreshButton.setBounds(w - PADDING - 55, y, 55, ROW_HEIGHT);
        y += ROW_HEIGHT + ROW_SPACING;
        int listHeight = Math.min(120, Math.max(ROW_HEIGHT, h - y - PADDING));
        stamperList.setBounds(PADDING, y, w - 2 * PADDING, listHeight);
    }
}
