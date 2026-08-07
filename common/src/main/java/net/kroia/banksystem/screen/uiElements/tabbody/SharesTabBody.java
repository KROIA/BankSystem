package net.kroia.banksystem.screen.uiElements.tabbody;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.AsyncCompanyManager;
import net.kroia.banksystem.screen.custom.CompanyManagementScreen;
import net.kroia.banksystem.screen.custom.ShareVisualEditorScreen;
import net.kroia.banksystem.screen.uiElements.AskPopupScreen;
import net.kroia.banksystem.screen.uiElements.InfoPopupScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Shares tab — "Edit Layers" button opens {@link ShareVisualEditorScreen} for the
 * two-layer visual editor, plus the list of bound Share Stamper blocks.
 */
public class SharesTabBody extends TabBody {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".company_management_screen.";

    private final Label introLabel;
    private final Button editLayersButton;
    private final Label stamperHeader;
    private final Button refreshButton;
    private final VerticalListView stamperList;

    private final boolean editable;

    public SharesTabBody(CompanyManagementScreen screen) {
        super(screen);
        this.editable = screen.canManageNow() || screen.isFounderNow();

        introLabel = new Label(Component.translatable(PREFIX + "shares_intro").getString());
        introLabel.setAlignment(Label.Alignment.LEFT);

        editLayersButton = new Button(
                Component.translatable(PREFIX + "edit_layers").getString(),
                this::onEditLayers);
        editLayersButton.setEnabled(editable);

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
        addChild(editLayersButton);
        addChild(stamperHeader);
        addChild(refreshButton);
        addChild(stamperList);

        fetchStampers();
    }

    private void onEditLayers() {
        ShareVisualEditorScreen.openScreen(screen, screen.getCompanyId(),
                screen.callerUUID(), editable);
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
        int y = PADDING;
        introLabel.setBounds(PADDING, y, w - 2 * PADDING, ROW_HEIGHT);
        y += ROW_HEIGHT + ROW_SPACING;
        int btnW = Math.min(200, w - 2 * PADDING);
        editLayersButton.setBounds(PADDING, y, btnW, ROW_HEIGHT);
        y += ROW_HEIGHT + SECTION_SPACING;
        stamperHeader.setBounds(PADDING, y, w - 2 * PADDING - 60, ROW_HEIGHT);
        refreshButton.setBounds(w - PADDING - 55, y, 55, ROW_HEIGHT);
        y += ROW_HEIGHT + ROW_SPACING;
        int listHeight = Math.max(ROW_HEIGHT, h - y - PADDING);
        stamperList.setBounds(PADDING, y, w - 2 * PADDING, listHeight);
    }
}
