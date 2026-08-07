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
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/**
 * Task #46 (v2.0.8) / v2.0.9 two-layer redesign — modal editor for a Company's
 * {@link ShareVisuals}. MANAGE-gated: when {@code canManage} is false, save + edit
 * widgets render read-only (Save hidden, boxes disabled).
 *
 * <p>Layout: displayName + description fields above the layer scroll list.
 * The scroll list holds two {@link ShareLayerPanel} rows — <b>Foreground first (top)</b>,
 * Background second (bottom) — matching the "lower in list → bottom of stack" convention.
 * Each panel has a symbol-picker button and R/G/B + hex tint fields.
 *
 * <p>Save calls {@link AsyncCompanyManager#updateShareVisualsAsync} with 4 layer params.
 */
public class ShareVisualEditorScreen extends BankSystemGuiScreen {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".share_visual_editor.";
    private static final Component TITLE        = Component.translatable(PREFIX + "title");
    private static final Component SAVE         = Component.translatable(PREFIX + "save");
    private static final Component DISPLAY_NAME = Component.translatable(PREFIX + "display_name");
    private static final Component DESCRIPTION  = Component.translatable(PREFIX + "description");

    private final GuiScreen parent;
    private final int companyId;
    private final UUID caller;
    private final boolean canManage;

    private Label titleLabel;
    private CloseButton closeButton;
    private Button saveButton;

    private Label displayNameLabel;
    private TextBox displayNameBox;
    private Label descriptionLabel;
    private TextBox descriptionBox;

    private VerticalListView layerList;
    private ShareLayerPanel fgPanel;
    private ShareLayerPanel bgPanel;

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
        titleLabel = new Label(TITLE.getString());
        closeButton = new CloseButton(this::onClose);
        closeButton.setBackgroundColor(0xFFf55a42);
        closeButton.setHoverColor(0xFFe03d24);
        closeButton.setPressedColor(0xFFde2b10);
        closeButton.setOutlineColor(0xFFde2510);

        displayNameLabel = new Label(DISPLAY_NAME.getString());
        displayNameBox = new TextBox();
        displayNameBox.setText(initial.getDisplayName() == null ? "" : initial.getDisplayName());
        displayNameBox.setEnabled(canManage);

        descriptionLabel = new Label(DESCRIPTION.getString());
        descriptionBox = new TextBox();
        descriptionBox.setText(initial.getDescription() == null ? "" : initial.getDescription());
        descriptionBox.setEnabled(canManage);

        // FG first (top of scroll list = foreground visual layer), BG second.
        fgPanel = new ShareLayerPanel("Foreground",
                initial.getFgLayer().symbolId(), initial.getFgLayer().tint());
        bgPanel = new ShareLayerPanel("Background",
                initial.getBgLayer().symbolId(), initial.getBgLayer().tint());

        layerList = new VerticalListView();
        LayoutGrid layout = new LayoutGrid();
        layout.columns = 1;
        layout.spacing = 4;
        layout.padding = 0;
        layout.stretchX = true;
        layout.stretchY = false;
        layout.alignment = GuiElement.Alignment.TOP;
        layerList.setLayout(layout);
        layerList.addChild(fgPanel);
        layerList.addChild(bgPanel);

        if (canManage) {
            saveButton = new Button(SAVE.getString(), this::onSaveClicked);
        }

        addElement(titleLabel);
        addElement(closeButton);
        addElement(displayNameLabel);
        addElement(displayNameBox);
        addElement(descriptionLabel);
        addElement(descriptionBox);
        addElement(layerList);
        if (saveButton != null) addElement(saveButton);
    }

    private void onSaveClicked() {
        if (!canManage) return;
        String displayName = displayNameBox.getText();
        String description = descriptionBox.getText();
        String bgSym = bgPanel.getSymbolId();
        int bgTint = bgPanel.getEffectiveTint();
        String fgSym = fgPanel.getSymbolId();
        int fgTint = fgPanel.getEffectiveTint();

        AsyncCompanyManager.updateShareVisualsAsync(
                        companyId, bgSym, bgTint, fgSym, fgTint, displayName, description, caller)
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

        displayNameLabel.setBounds(padding, y, col - spacing, 20);
        displayNameBox.setBounds(padding + col, y, col - spacing, 20);
        y += 22;

        descriptionLabel.setBounds(padding, y, col - spacing, 20);
        descriptionBox.setBounds(padding + col, y, col - spacing, 20);
        y += 22;

        int listHeight = ShareLayerPanel.PANEL_HEIGHT * 2 + 4 + 8;
        layerList.setBounds(padding, y, width, listHeight);
        y += listHeight + spacing;

        if (saveButton != null) {
            saveButton.setBounds(padding, getHeight() - 25, width, 20);
        }
    }

    // -----------------------------------------------------------------------
    // Inner panel for one visual layer
    // -----------------------------------------------------------------------

    /**
     * A self-contained {@link GuiElement} representing one visual layer (FG or BG).
     * Contains:
     * <ul>
     *   <li>Bold title label ("Foreground" / "Background")</li>
     *   <li>Symbol row: label + button showing selected preset id</li>
     *   <li>Color row: "Color:" label + hex TextBox + R/G/B TextBoxes</li>
     * </ul>
     */
    private class ShareLayerPanel extends GuiElement {

        static final int PANEL_HEIGHT = 20 + 22 + 22 + 18; // title + symbol row + hex color row + rgb row

        private final Label titleLabel;
        private final Label symbolLabel;
        private final Button symbolButton;
        private final Label colorLabel;
        private final TextBox hexBox;
        private final TextBox rBox;
        private final TextBox gBox;
        private final TextBox bBox;

        private String selectedSymbolId;
        private boolean syncingColor = false;

        ShareLayerPanel(String title, String initialSymbolId, int initialTint) {
            super();
            setHeight(PANEL_HEIGHT);
            selectedSymbolId = initialSymbolId == null ? "" : initialSymbolId;

            titleLabel = new Label(title);
            symbolLabel = new Label("Symbol:");
            symbolButton = new Button(
                    selectedSymbolId.isEmpty() ? "-" : selectedSymbolId,
                    this::onPickSymbol);
            symbolButton.setEnabled(canManage);

            colorLabel = new Label("Color:");
            int r = (initialTint >> 16) & 0xFF;
            int g = (initialTint >> 8) & 0xFF;
            int b = initialTint & 0xFF;
            hexBox = new TextBox();
            hexBox.setText(String.format("%06X", initialTint & 0xFFFFFF));
            hexBox.setEnabled(canManage);
            rBox = channelBox(r);
            gBox = channelBox(g);
            bBox = channelBox(b);

            hexBox.setOnTextChanged(t -> {
                if (syncingColor) return;
                syncingColor = true;
                try {
                    int parsed = parseHex(t);
                    rBox.setText(String.valueOf((parsed >> 16) & 0xFF));
                    gBox.setText(String.valueOf((parsed >> 8) & 0xFF));
                    bBox.setText(String.valueOf(parsed & 0xFF));
                } finally {
                    syncingColor = false;
                }
            });
            Runnable syncHex = () -> {
                if (syncingColor) return;
                syncingColor = true;
                try {
                    int rc = parseCh(rBox), gc = parseCh(gBox), bc = parseCh(bBox);
                    hexBox.setText(String.format("%06X", (rc << 16) | (gc << 8) | bc));
                } finally {
                    syncingColor = false;
                }
            };
            rBox.setOnTextChanged(t -> syncHex.run());
            gBox.setOnTextChanged(t -> syncHex.run());
            bBox.setOnTextChanged(t -> syncHex.run());

            addChild(titleLabel);
            addChild(symbolLabel);
            addChild(symbolButton);
            addChild(colorLabel);
            addChild(hexBox);
            addChild(rBox);
            addChild(gBox);
            addChild(bBox);
        }

        private TextBox channelBox(int value) {
            TextBox tb = new TextBox();
            tb.setText(String.valueOf(value & 0xFF));
            tb.setEnabled(canManage);
            return tb;
        }

        private void onPickSymbol() {
            if (!canManage) return;
            // Inline preset-picker: reuse the existing VerticalListView inside a small popup.
            // For simplicity, open the standard PresetPickerPopup from SharesTabBody approach.
            net.kroia.banksystem.util.BankSystemGuiScreen.switchScreen(
                    new net.kroia.banksystem.screen.uiElements.PresetPickerPopup(
                            ShareVisualEditorScreen.this,
                            presetId -> {
                                selectedSymbolId = presetId == null ? "" : presetId;
                                symbolButton.setText(selectedSymbolId.isEmpty() ? "-" : selectedSymbolId);
                            }));
        }

        String getSymbolId() {
            return selectedSymbolId;
        }

        int getEffectiveTint() {
            String hex = hexBox.getText().trim();
            if (hex.matches("(?i)#?[0-9a-f]{6}")) {
                return 0xFF000000 | parseHex(hex);
            }
            int r = parseCh(rBox), g = parseCh(gBox), b = parseCh(bBox);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }

        private int parseHex(String s) {
            try {
                if (s == null) return 0xFFFFFF;
                s = s.trim();
                if (s.startsWith("#")) s = s.substring(1);
                return Integer.parseInt(s, 16) & 0xFFFFFF;
            } catch (NumberFormatException e) {
                return 0xFFFFFF;
            }
        }

        private int parseCh(TextBox tb) {
            try {
                int v = Integer.parseInt(tb.getText().trim());
                if (v < 0) v = 0;
                if (v > 255) v = 255;
                return v;
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        @Override
        protected void render() {}

        @Override
        protected void layoutChanged() {
            int w = getWidth();
            int spacing = 4;
            int col = w / 2;
            // Row 0: title
            titleLabel.setBounds(0, 0, w, 20);
            // Row 1: symbol
            symbolLabel.setBounds(0, 22, col - spacing, 20);
            symbolButton.setBounds(col, 22, col, 20);
            // Row 2: color
            colorLabel.setBounds(0, 44, col - spacing, 20);
            int hexW = Math.max(1, col - spacing);
            hexBox.setBounds(col, 44, hexW, 20);
            int chW = Math.max(1, (w - col - 2 * spacing) / 3);
            // Place R/G/B below to avoid crowding; but we only have 3 rows so put them inline after hex.
            // Use the remaining half for 3 small channel boxes side by side.
            rBox.setBounds(col, 66, chW, 14);
            gBox.setBounds(col + chW + spacing, 66, chW, 14);
            bBox.setBounds(col + 2 * (chW + spacing), 66, chW, 14);
        }
    }

    /** Prevents unused-import warnings from being surfaced as compile errors. */
    @SuppressWarnings("unused")
    private static final List<String> _PRESETS_KEEPALIVE = SharePresetRegistry.orderedIds();
}
