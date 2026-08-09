package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.banking.company.ShareVisuals;
import net.kroia.banksystem.client.cache.ShareVisualCache;
import net.kroia.banksystem.client.company.SharePresetRegistry;
import net.kroia.banksystem.client.render.ShareVisualPreview;
import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem;
import net.kroia.banksystem.screen.uiElements.ColorPickerPopup;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.client.GuiScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.CloseButton;
import net.kroia.modutilities.gui.elements.ItemView;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.TextBox;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

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
    private static final Component PREVIEW      = Component.translatable(PREFIX + "preview");
    private static final Component CARD_COLOR   = Component.translatable(PREFIX + "card_color");

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

    private Label cardColorLabel;
    private Button cardColorSwatch;
    private int selectedBaseTint;

    private Label previewLabel;
    private ScaledItemView previewItem;

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

        // Stage 1 — base card tint (applies to the stamped_share card texture itself).
        selectedBaseTint = initial.getBaseTint();
        if ((selectedBaseTint & 0xFF000000) == 0) selectedBaseTint |= 0xFF000000;
        cardColorLabel = new Label(CARD_COLOR.getString());
        cardColorSwatch = new Button("", this::onPickCardColor);
        applyCardSwatchColor(selectedBaseTint);
        cardColorSwatch.setEnabled(canManage);

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

        // Live item preview — renders the actual stamped share stack; the model +
        // tint handlers pick up the unsaved editor state via ShareVisualPreview.
        previewLabel = new Label(PREVIEW.getString());
        previewItem = new ScaledItemView();
        previewItem.setItemStack(StampedShareItem.ofCompany(
                BankSystemItems.STAMPED_SHARE.get(), companyId));
        previewItem.setShowTooltip(false);

        addElement(titleLabel);
        addElement(closeButton);
        addElement(displayNameLabel);
        addElement(displayNameBox);
        addElement(descriptionLabel);
        addElement(descriptionBox);
        addElement(cardColorLabel);
        addElement(cardColorSwatch);
        addElement(layerList);
        addElement(previewLabel);
        addElement(previewItem);
        if (saveButton != null) addElement(saveButton);

        updatePreview();
    }

    /** Publish the current (unsaved) editor state so the preview stack renders it. */
    private void updatePreview() {
        ShareVisuals v = new ShareVisuals(
                new ShareVisuals.ShareLayer(bgPanel.getSymbolId(), bgPanel.getEffectiveTint()),
                new ShareVisuals.ShareLayer(fgPanel.getSymbolId(), fgPanel.getEffectiveTint()),
                selectedBaseTint,
                displayNameBox.getText(), descriptionBox.getText());
        ShareVisualPreview.set(companyId, v);
    }

    private void applyCardSwatchColor(int argb) {
        cardColorSwatch.setBackgroundColor(argb);
        cardColorSwatch.setHoverColor(argb);
        cardColorSwatch.setPressedColor(argb);
    }

    private void onPickCardColor() {
        if (!canManage) return;
        BankSystemGuiScreen.switchScreen(
                new ColorPickerPopup(this, selectedBaseTint, argb -> {
                    selectedBaseTint = argb;
                    applyCardSwatchColor(argb);
                    updatePreview();
                }));
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
                        companyId, bgSym, bgTint, fgSym, fgTint, selectedBaseTint, displayName, description, caller)
                .thenAccept(out -> Minecraft.getInstance().execute(this::closeToParent));
    }

    private void closeToParent() {
        ShareVisualPreview.clear();
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

        cardColorLabel.setBounds(padding, y, col - spacing, 20);
        cardColorSwatch.setBounds(padding + col, y, 40, 20);
        y += 22;

        int listHeight = ShareLayerPanel.PANEL_HEIGHT * 2 + 8;
        layerList.setBounds(padding, y, width, listHeight);
        y += listHeight + spacing;

        int previewSize = 48;
        previewLabel.setBounds(padding, y, col - spacing, previewSize);
        previewItem.setBounds(padding + col, y, previewSize, previewSize);

        if (saveButton != null) {
            saveButton.setBounds(padding, getHeight() - 25, width, 20);
        }
    }

    // -----------------------------------------------------------------------
    // Inner panel for one visual layer
    // -----------------------------------------------------------------------

    /**
     * One visual layer (Foreground or Background).
     * Row 0: bold title label.
     * Row 1: "Symbol:" label + button showing selected preset id (opens PresetPickerPopup).
     * Row 2: "Color:" label + colored swatch button (opens ColorPickerPopup).
     */
    private class ShareLayerPanel extends GuiElement {

        static final int PANEL_HEIGHT = 20 + 22 + 22; // title + symbol row + color row

        private final Label titleLabel;
        private final Label symbolLabel;
        private final Button symbolButton;
        private final Label colorLabel;
        private final Button colorSwatch;

        private String selectedSymbolId;
        private int selectedTint;

        ShareLayerPanel(String title, String initialSymbolId, int initialTint) {
            super();
            setHeight(PANEL_HEIGHT);
            selectedSymbolId = initialSymbolId == null ? "" : initialSymbolId;
            selectedTint = (initialTint & 0xFF000000) == 0 ? (initialTint | 0xFF000000) : initialTint;

            titleLabel = new Label("§l" + title);
            symbolLabel = new Label("Symbol:");
            symbolButton = new Button(
                    selectedSymbolId.isEmpty() ? "(none)" : selectedSymbolId,
                    this::onPickSymbol);
            symbolButton.setEnabled(canManage);

            colorLabel = new Label("Color:");
            colorSwatch = new Button("", this::onPickColor);
            applySwatchColor(selectedTint);
            colorSwatch.setEnabled(canManage);

            addChild(titleLabel);
            addChild(symbolLabel);
            addChild(symbolButton);
            addChild(colorLabel);
            addChild(colorSwatch);
        }

        private void applySwatchColor(int argb) {
            colorSwatch.setBackgroundColor(argb);
            colorSwatch.setHoverColor(argb);
            colorSwatch.setPressedColor(argb);
        }

        private void onPickSymbol() {
            if (!canManage) return;
            BankSystemGuiScreen.switchScreen(
                    new net.kroia.banksystem.screen.uiElements.PresetPickerPopup(
                            ShareVisualEditorScreen.this,
                            presetId -> {
                                selectedSymbolId = presetId == null ? "" : presetId;
                                symbolButton.setText(selectedSymbolId.isEmpty() ? "(none)" : selectedSymbolId);
                                updatePreview();
                            }));
        }

        private void onPickColor() {
            if (!canManage) return;
            BankSystemGuiScreen.switchScreen(
                    new ColorPickerPopup(ShareVisualEditorScreen.this, selectedTint, argb -> {
                        selectedTint = argb;
                        applySwatchColor(argb);
                        updatePreview();
                    }));
        }

        String getSymbolId() { return selectedSymbolId; }
        int getEffectiveTint() { return selectedTint; }

        @Override protected void render() {}

        @Override
        protected void layoutChanged() {
            int w = getWidth();
            int spacing = 4;
            int col = w / 2;
            titleLabel.setBounds(0, 0, w, 20);
            symbolLabel.setBounds(0, 22, col - spacing, 20);
            symbolButton.setBounds(col, 22, col, 20);
            colorLabel.setBounds(0, 44, col - spacing, 20);
            colorSwatch.setBounds(col, 44, 40, 20);
        }
    }

    /**
     * {@link ItemView} that scales the item render up to fill its bounds (the stock
     * ItemView only scales down; oversized bounds render a centered 16px icon).
     */
    private static final class ScaledItemView extends ItemView {
        @Override
        protected void render() {
            ItemStack stack = getItemStack();
            if (stack == null || stack.isEmpty()) return;
            int size = Math.min(getWidth(), getHeight());
            var graphics = getGraphics();
            graphics.pushPose();
            graphics.translate((getWidth() - size) / 2, (getHeight() - size) / 2, 0);
            float scale = size / (float) DEFAULT_WIDTH;
            graphics.scale(scale, scale, 1);
            drawItem(stack, 0, 0);
            graphics.popPose();
        }
    }

    @SuppressWarnings("unused")
    private static final List<String> _PRESETS_KEEPALIVE = SharePresetRegistry.orderedIds();
}
