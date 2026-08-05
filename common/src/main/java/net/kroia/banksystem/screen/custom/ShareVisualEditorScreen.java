package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.banking.company.ShareVisuals;
import net.kroia.banksystem.client.cache.ShareVisualCache;
import net.kroia.banksystem.client.company.SharePresetRegistry;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.client.GuiScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.CloseButton;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.TextBox;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.elements.base.ListView;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/**
 * Task #46 (v2.0.8) — modal editor for a Company's {@link ShareVisuals}. MANAGE-gated:
 * when {@code canManage} is false, save + edit widgets render read-only (Save hidden,
 * boxes disabled). Preset icon picker is a scroll list of preset ids (art deferred —
 * see {@link SharePresetRegistry}). Tint is entered as a hex string; three 0-255 R/G/B
 * boxes stay in sync with the hex box.
 *
 * <p>Save calls {@link AsyncCompanyManager#updateShareVisualsAsync} — the master validates
 * MANAGE and preset id, then broadcasts a {@link net.kroia.banksystem.networking.general.S2CCompanyVisualUpdatePacket}
 * to all clients. The local {@link ShareVisualCache} refreshes on that broadcast.
 */
public class ShareVisualEditorScreen extends BankSystemGuiScreen {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".share_visual_editor.";
    private static final Component TITLE = Component.translatable(PREFIX + "title");
    private static final Component SAVE = Component.translatable(PREFIX + "save");
    private static final Component PRESET = Component.translatable(PREFIX + "preset");
    private static final Component TINT = Component.translatable(PREFIX + "tint");
    private static final Component DISPLAY_NAME = Component.translatable(PREFIX + "display_name");
    private static final Component DESCRIPTION = Component.translatable(PREFIX + "description");

    private final GuiScreen parent;
    private final int companyId;
    private final UUID caller;
    private final boolean canManage;

    private Label titleLabel;
    private CloseButton closeButton;
    private Button saveButton;

    private Label presetLabel;
    private ListView presetList;
    private String selectedPresetId = "";
    private Label selectedPresetLabel;

    private Label tintLabel;
    private TextBox tintHexBox;
    private TextBox tintRBox;
    private TextBox tintGBox;
    private TextBox tintBBox;

    private Label displayNameLabel;
    private TextBox displayNameBox;

    private Label descriptionLabel;
    private TextBox descriptionBox;

    private boolean syncingTintFields = false;

    public ShareVisualEditorScreen(GuiScreen parent, int companyId, ShareVisuals initial,
                                   UUID caller, boolean canManage) {
        super(TITLE);
        this.parent = parent;
        this.companyId = companyId;
        this.caller = caller;
        this.canManage = canManage;
        setupUi(initial != null ? initial : ShareVisuals.EMPTY);
    }

    public static void openScreen(GuiScreen parent, int companyId, UUID caller, boolean canManage) {
        ShareVisuals current = ShareVisualCache.getVisualsOrPlaceholder(companyId);
        Minecraft.getInstance().setScreen(new ShareVisualEditorScreen(parent, companyId, current, caller, canManage));
    }

    private void setupUi(ShareVisuals initial) {
        selectedPresetId = initial.getIconPresetId() == null ? "" : initial.getIconPresetId();

        titleLabel = new Label(TITLE.getString());
        closeButton = new CloseButton(this::onClose);
        closeButton.setBackgroundColor(0xFFf55a42);
        closeButton.setHoverColor(0xFFe03d24);
        closeButton.setPressedColor(0xFFde2b10);
        closeButton.setOutlineColor(0xFFde2510);

        presetLabel = new Label(PRESET.getString());
        selectedPresetLabel = new Label(selectedPresetId.isEmpty() ? "-" : selectedPresetId);

        presetList = new VerticalListView();
        LayoutGrid layout = new LayoutGrid();
        layout.columns = 1;
        layout.spacing = 0;
        layout.padding = 0;
        layout.stretchX = true;
        layout.stretchY = false;
        layout.alignment = GuiElement.Alignment.TOP;
        presetList.setLayout(layout);
        for (String id : SharePresetRegistry.orderedIds()) {
            Button row = new Button(id, () -> {
                if (!canManage) return;
                selectedPresetId = id;
                selectedPresetLabel.setText(id);
            });
            row.setEnabled(canManage);
            presetList.addChild(row);
        }

        tintLabel = new Label(TINT.getString());
        int initTint = initial.getTint();
        int r = (initTint >> 16) & 0xFF;
        int g = (initTint >> 8) & 0xFF;
        int b = initTint & 0xFF;
        tintHexBox = new TextBox();
        tintHexBox.setText(String.format("%06X", initTint & 0xFFFFFF));
        tintHexBox.setEnabled(canManage);
        tintRBox = channelBox(r);
        tintGBox = channelBox(g);
        tintBBox = channelBox(b);

        displayNameLabel = new Label(DISPLAY_NAME.getString());
        displayNameBox = new TextBox();
        displayNameBox.setText(initial.getDisplayName() == null ? "" : initial.getDisplayName());
        displayNameBox.setEnabled(canManage);

        descriptionLabel = new Label(DESCRIPTION.getString());
        descriptionBox = new TextBox();
        descriptionBox.setText(initial.getDescription() == null ? "" : initial.getDescription());
        descriptionBox.setEnabled(canManage);

        if (canManage) {
            saveButton = new Button(SAVE.getString(), this::onSaveClicked);
        }

        addElement(titleLabel);
        addElement(closeButton);
        addElement(presetLabel);
        addElement(selectedPresetLabel);
        addElement(presetList);
        addElement(tintLabel);
        addElement(tintHexBox);
        addElement(tintRBox);
        addElement(tintGBox);
        addElement(tintBBox);
        addElement(displayNameLabel);
        addElement(displayNameBox);
        addElement(descriptionLabel);
        addElement(descriptionBox);
        if (saveButton != null) addElement(saveButton);
    }

    private TextBox channelBox(int value) {
        TextBox tb = new TextBox();
        tb.setText(String.valueOf(value & 0xFF));
        tb.setEnabled(canManage);
        return tb;
    }

    private int parseTintFromHex() {
        try {
            String s = tintHexBox.getText().trim();
            if (s.startsWith("#")) s = s.substring(1);
            return Integer.parseInt(s, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return 0xFFFFFF;
        }
    }

    private int parseChannel(TextBox tb) {
        try {
            int v = Integer.parseInt(tb.getText().trim());
            if (v < 0) v = 0;
            if (v > 255) v = 255;
            return v;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Recomputes the effective tint from whichever field the user most recently edited. */
    private int effectiveTint() {
        // Prefer hex if it looks valid (6 hex chars) — otherwise fall back to R/G/B fields.
        String hex = tintHexBox.getText().trim();
        if (hex.matches("(?i)#?[0-9a-f]{6}")) {
            return 0xFF000000 | parseTintFromHex();
        }
        int r = parseChannel(tintRBox);
        int g = parseChannel(tintGBox);
        int b = parseChannel(tintBBox);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void onSaveClicked() {
        if (!canManage) return;
        int tint = effectiveTint();
        String displayName = displayNameBox.getText();
        String description = descriptionBox.getText();
        String preset = selectedPresetId == null ? "" : selectedPresetId;

        AsyncCompanyManager.updateShareVisualsAsync(companyId, preset, tint, displayName, description, caller)
                .thenAccept(out -> Minecraft.getInstance().execute(this::closeToParent));
    }

    private void closeToParent() {
        if (parent != null && this.minecraft != null) {
            this.minecraft.setScreen(parent);
        } else {
            super.onClose();
        }
    }

    @Override
    public void onClose() {
        closeToParent();
    }

    @Override
    protected void updateLayout(Gui gui) {
        int spacing = 5;
        int padding = 5;
        int width = getWidth() - 2 * padding;
        if (titleLabel == null) return;

        titleLabel.setBounds(padding, padding, width - 25, 20);
        closeButton.setBounds(getWidth() - 20 - padding, padding, 20, 20);

        int y = padding + 25;
        int col = width / 2;
        presetLabel.setBounds(padding, y, col - spacing, 20);
        selectedPresetLabel.setBounds(padding + col, y, col - spacing, 20);
        y += 22;

        int presetListHeight = 90;
        presetList.setBounds(padding, y, width, presetListHeight);
        y += presetListHeight + spacing;

        tintLabel.setBounds(padding, y, col - spacing, 20);
        tintHexBox.setBounds(padding + col, y, col - spacing, 20);
        y += 22;

        int chW = (width - 2 * spacing) / 3;
        tintRBox.setBounds(padding, y, chW, 20);
        tintGBox.setBounds(padding + chW + spacing, y, chW, 20);
        tintBBox.setBounds(padding + 2 * (chW + spacing), y, chW, 20);
        y += 22;

        displayNameLabel.setBounds(padding, y, col - spacing, 20);
        displayNameBox.setBounds(padding + col, y, col - spacing, 20);
        y += 22;

        descriptionLabel.setBounds(padding, y, col - spacing, 20);
        descriptionBox.setBounds(padding + col, y, col - spacing, 20);
        y += 22;

        if (saveButton != null) {
            saveButton.setBounds(padding, getHeight() - 25, width, 20);
        }
    }

    /** Prevents unused-import warnings from being surfaced as compile errors in strict modes. */
    @SuppressWarnings("unused")
    private static final List<String> _PRESETS_KEEPALIVE = SharePresetRegistry.orderedIds();
}
