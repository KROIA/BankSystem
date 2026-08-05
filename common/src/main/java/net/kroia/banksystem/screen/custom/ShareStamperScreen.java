package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.minecraft.entity.custom.ShareStamperBlockEntity;
import net.kroia.banksystem.minecraft.menu.custom.ShareStamperContainerMenu;
import net.kroia.banksystem.networking.entity.SetStamperBindingRequest;
import net.kroia.banksystem.networking.entity.StampSharesRequest;
import net.kroia.banksystem.screen.uiElements.AskPopupScreen;
import net.kroia.banksystem.util.BankSystemGuiContainerScreen;
import net.kroia.banksystem.util.BankSystemLogger;
import net.kroia.banksystem.util.BankSystemGuiElement;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.GuiTexture;
import net.kroia.modutilities.gui.client.ContainerView;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.CheckBox;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.elements.Label;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

/**
 * Task #47 (v2.0.8) — Share Stamper screen rebuilt on the ModUtilities framework
 * ({@link BankSystemGuiContainerScreen} + {@link ContainerView}) to match the
 * visual language of {@link BankUploadScreen} / {@link BankDownloadScreen}.
 * <p>
 * v2.0.8 overhaul (Task #56):
 * <ul>
 *   <li>Replaced the "Stamp (N)" queued-stamps counter with a Start / Stop toggle
 *       driven by the BE's new {@code processing} ContainerData slot.</li>
 *   <li>Split the single "Hopper Redeem" checkbox into Auto Input (left of the input
 *       slot, gates INSERT via UP face) and Auto Output (left of the output slot,
 *       gates EXTRACT via DOWN face). Both MANAGE-gated, each with a right-pointing
 *       flow arrow into its slot.</li>
 *   <li>Block area is a vertical stack: input slot on top, static vertical progress
 *       bar (green fill, grows top→bottom in both modes), output slot below.
 *       No moving animation.</li>
 * </ul>
 * Live state is sourced from the menu's {@link net.minecraft.world.inventory.ContainerData}
 * every {@link #containerTick()} — no extra sync packets are needed.
 */
// Background: dedicated banksystem:textures/gui/share_stamper.png (top panel + slot frames +
// flow arrows + vanilla player-inventory strip baked into one 256x256 atlas).
public class ShareStamperScreen extends BankSystemGuiContainerScreen<ShareStamperContainerMenu> {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".share_stamper.";
    private static final Component INVENTORY_NAME_TEXT =
            Component.translatable("container." + BankSystemMod.MOD_ID + ".share_stamper");
    private static final Component START_BUTTON = Component.translatable(PREFIX + "start");
    private static final Component STOP_BUTTON = Component.translatable(PREFIX + "stop");
    private static final Component MODE_STAMP = Component.translatable(PREFIX + "mode.stamp");
    private static final Component MODE_REDEEM = Component.translatable(PREFIX + "mode.redeem");
    private static final Component AUTO_INPUT = Component.translatable(PREFIX + "auto_input");
    private static final Component AUTO_OUTPUT = Component.translatable(PREFIX + "auto_output");
    private static final Component BIND_PROMPT = Component.translatable(PREFIX + "bind_prompt");
    private static final String SUPPLY_KEY = PREFIX + "supply";
    private static final Component UNBIND_BUTTON = Component.translatable(PREFIX + "unbind_button");
    private static final Component CONFIRM_TITLE = Component.translatable(PREFIX + "unbind_confirm_title");
    private static final Component CONFIRM_BODY = Component.translatable(PREFIX + "unbind_confirm_body");
    private static final Component TOOLTIP_AUTO_INPUT = Component.translatable(PREFIX + "tooltip.auto_input");
    private static final Component TOOLTIP_AUTO_OUTPUT = Component.translatable(PREFIX + "tooltip.auto_output");
    private static final Component TOOLTIP_START_STOP = Component.translatable(PREFIX + "tooltip.start_stop");
    private static final Component TOOLTIP_MODE = Component.translatable(PREFIX + "tooltip.mode");
    private static final Component TOOLTIP_UNBIND = Component.translatable(PREFIX + "tooltip.unbind");
    private static final Component TOOLTIP_SUPPLY = Component.translatable(PREFIX + "tooltip.supply");

    // Progress-bar palette (matches the vanilla container panel colors baked
    // into share_stamper.png).
    private static final int COLOR_SLOT_DARK = 0xFF373737;
    private static final int COLOR_BAR_BG = 0xFF8B8B8B;
    private static final int COLOR_BAR_FILL = 0xFF3FBF3F;

    // Dedicated background texture: top panel (bevels, two slot frames, flow
    // arrows) + vanilla player-inventory strip baked into one 256x256 atlas;
    // usable area 176x166 at (0,0).
    private static final GuiTexture BACKGROUND_TEXTURE =
            new GuiTexture(BankSystemMod.MOD_ID, "textures/gui/share_stamper.png", 256, 256);

    /**
     * Right-hand settings panel — Start/Stop + Mode + Unbind plus a live-updated
     * supply + progress line. The hopper toggle moved out of the settings panel to
     * the two block-area checkboxes {@link #autoInputCheckBox} / {@link #autoOutputCheckBox}.
     */
    private class SettingsMenu extends BankSystemGuiElement {
        final Label supplyLabel;
        final Button startStopButton;
        final Button modeButton;
        final Button unbindButton;

        SettingsMenu() {
            supplyLabel = new Label("");
            supplyLabel.setAlignment(Alignment.CENTER);
            startStopButton = new Button(START_BUTTON.getString(), ShareStamperScreen.this::onStartStopClicked);
            modeButton = new Button(modeLabelString(), ShareStamperScreen.this::onModeClicked);
            unbindButton = new Button(UNBIND_BUTTON.getString(), ShareStamperScreen.this::openUnbindConfirm);

            applyTooltip(supplyLabel, TOOLTIP_SUPPLY);
            applyTooltip(startStopButton, TOOLTIP_START_STOP);
            applyTooltip(modeButton, TOOLTIP_MODE);
            applyTooltip(unbindButton, TOOLTIP_UNBIND);

            addChild(supplyLabel);
            addChild(startStopButton);
            addChild(modeButton);
            addChild(unbindButton);
        }

        @Override protected void render() { }

        /** Exposes the mod logger (protected static on BankSystemGuiElement). */
        BankSystemLogger logger() {
            return BACKEND_INSTANCES != null ? BACKEND_INSTANCES.LOGGER : null;
        }

        @Override
        protected void layoutChanged() {
            int padding = 4;
            int w = getWidth() - 2 * padding;
            int rowH = 18;
            int gap = 4;
            int y = padding;
            supplyLabel.setBounds(padding, y, w, rowH); y += rowH + gap;
            startStopButton.setBounds(padding, y, w, rowH); y += rowH + gap;
            modeButton.setBounds(padding, y, w, rowH); y += rowH + gap;
            unbindButton.setBounds(padding, y, w, rowH); y += rowH + padding;
            setHeight(y);
        }
    }

    /**
     * Vertical progress bar drawn between the stacked input (top) and output
     * (bottom) BE slots. Static green fill sized by
     * {@code progress / STAMP_TICKS_PER_CYCLE} — always fills top→bottom in
     * both STAMP and REDEEM mode. No animation / moving highlight.
     */
    private class StamperProgressBar extends BankSystemGuiElement {
        @Override protected void layoutChanged() { }
        @Override
        protected void render() {
            int w = getWidth();
            int h = getHeight();
            // Border + inner background.
            drawRect(0, 0, w, h, COLOR_SLOT_DARK);
            int innerX = 1, innerY = 1, innerW = w - 2, innerH = h - 2;
            drawRect(innerX, innerY, innerW, innerH, COLOR_BAR_BG);

            int total = ShareStamperBlockEntity.STAMP_TICKS_PER_CYCLE;
            int prog = Math.min(total, Math.max(0, menu.getStampProgress()));
            int fillH = total > 0 ? prog * innerH / total : 0;
            if (fillH > 0) {
                // Uniform direction: fill grows top→bottom in both modes.
                drawRect(innerX, innerY, innerW, fillH, COLOR_BAR_FILL);
            }
        }
    }

    private final BlockPos pos;
    private final ContainerView<ShareStamperContainerMenu> inventoryView;
    private final SettingsMenu settingsMenu;
    private final CheckBox autoInputCheckBox;
    private final CheckBox autoOutputCheckBox;
    private final StamperProgressBar progressBar;

    public ShareStamperScreen(ShareStamperContainerMenu pMenu, Inventory pPlayerInventory, Component pTitle) {
        super(pMenu, pPlayerInventory, pTitle);
        this.pos = pMenu.getBlockPos();
        // Task v2.0.8 — arriving at the main stamper screen (either directly or via
        // the post-bind auto-open handoff) closes the suppression window that the
        // bind screen opened. The viewer lock is now this container menu's problem.
        StamperBindScreen.suppressCloseRelease = false;

        // Hide JEI overlays while open (matches BankUpload/BankDownload behavior).
        setHideJeiOverlay(true);

        // Background is a single dedicated texture (share_stamper.png): top panel
        // with bevels, the two 18x18 slot frames at (115,17)/(115,49), the two
        // right-pointing flow arrows, and the vanilla player-inventory strip —
        // all baked in. ContainerView's default renderBackground() blits the
        // element-sized (176x166) region of the 256x256 atlas at u=0,v=0.
        inventoryView = new ContainerView<>(pMenu, pPlayerInventory, INVENTORY_NAME_TEXT,
                BACKGROUND_TEXTURE);
        inventoryView.setSize(176, 166);

        settingsMenu = new SettingsMenu();

        autoInputCheckBox = new CheckBox(AUTO_INPUT.getString(), this::onAutoInputToggled);
        autoInputCheckBox.setChecked(pMenu.isAutoInput());
        applyTooltip(autoInputCheckBox, TOOLTIP_AUTO_INPUT);
        autoOutputCheckBox = new CheckBox(AUTO_OUTPUT.getString(), this::onAutoOutputToggled);
        autoOutputCheckBox.setChecked(pMenu.isAutoOutput());
        applyTooltip(autoOutputCheckBox, TOOLTIP_AUTO_OUTPUT);

        progressBar = new StamperProgressBar();

        addElement(inventoryView);
        addElement(settingsMenu);
        // Checkboxes + progress bar must be CHILDREN of the ContainerView, not
        // top-level siblings: ContainerView.mouseClickedOverElement() consumes
        // every click inside its bounds, and sibling dispatch order gave it the
        // click first — children get click priority over the parent (and render
        // on top of its background), which makes the checkboxes clickable.
        inventoryView.addChild(autoInputCheckBox);
        inventoryView.addChild(autoOutputCheckBox);
        inventoryView.addChild(progressBar);
    }

    /** Shared tooltip styling for the stamper controls. */
    private static void applyTooltip(GuiElement el, Component text) {
        el.setHoverTooltipSupplier(text::getString);
        el.setHoverTooltipFontScale(BankSystemGuiElement.hoverToolTipFontSize);
        el.setHoverTooltipMousePositionAlignment(GuiElement.Alignment.TOP);
    }

    /**
     * Optimistic client-side toggle state. The C2S request + ContainerData
     * round-trip takes several ticks; during that window the menu still reports
     * the OLD value, which previously snapped a clicked checkbox back. While a
     * pending value is set, the UI shows it instead of the server value; the
     * pending value clears as soon as the server confirms (values match) or
     * after {@link #PENDING_TIMEOUT_TICKS} without confirmation (request failed
     * silently — DEBUG-logged, server value wins again).
     */
    private static final int PENDING_TIMEOUT_TICKS = 40;

    private final class PendingToggle {
        private final String name;
        private Boolean value;
        private long expiresAt;

        PendingToggle(String name) { this.name = name; }

        void set(boolean v) {
            value = v;
            expiresAt = clientTick + PENDING_TIMEOUT_TICKS;
        }

        /** Effective UI value: pending while in flight, server value otherwise. */
        boolean resolve(boolean serverValue) {
            if (value == null)
                return serverValue;
            if (serverValue == value) {
                value = null; // Server confirmed.
                return serverValue;
            }
            if (clientTick >= expiresAt) {
                BankSystemLogger logger = settingsMenu != null ? settingsMenu.logger() : null;
                if (logger != null)
                    logger.debug(
                            "ShareStamperScreen: pending '" + name
                                    + "' toggle not confirmed by server within "
                                                    + PENDING_TIMEOUT_TICKS + " ticks — reverting to server state.");
                value = null;
                return serverValue;
            }
            return value;
        }
    }

    private long clientTick = 0L;
    private final PendingToggle pendingAutoInput = new PendingToggle("autoInput");
    private final PendingToggle pendingAutoOutput = new PendingToggle("autoOutput");
    private final PendingToggle pendingProcessing = new PendingToggle("processing");
    private final PendingToggle pendingRedeemMode = new PendingToggle("redeemMode");

    @Override
    public void containerTick() {
        super.containerTick();
        clientTick++;
        // Refresh labels + button text every tick from the effective state —
        // ContainerData (vanilla-synced) overlaid with any in-flight optimistic
        // toggle so clicks don't visually snap back during the round-trip.
        boolean effRedeem = pendingRedeemMode.resolve(menu.getModeOrdinal() == 1);
        settingsMenu.modeButton.setLabel((effRedeem ? MODE_REDEEM : MODE_STAMP).getString());
        boolean effProcessing = pendingProcessing.resolve(menu.isProcessing());
        settingsMenu.startStopButton.setLabel(
                (effProcessing ? STOP_BUTTON : START_BUTTON).getString());

        // Keep the checkboxes in sync with the effective state — server may
        // still auto-flip on a MANAGE change from a different client / command.
        boolean effAutoInput = pendingAutoInput.resolve(menu.isAutoInput());
        if (autoInputCheckBox.isChecked() != effAutoInput)
            autoInputCheckBox.setChecked(effAutoInput);
        boolean effAutoOutput = pendingAutoOutput.resolve(menu.isAutoOutput());
        if (autoOutputCheckBox.isChecked() != effAutoOutput)
            autoOutputCheckBox.setChecked(effAutoOutput);

        boolean bound = menu.getBoundCompanyId() >= 0;
        settingsMenu.unbindButton.setEnabled(bound);
        if (bound) {
            settingsMenu.supplyLabel.setText(Component.translatable(SUPPLY_KEY,
                    menu.getTotalIssued(), menu.getMaxSupply()).getString());
        } else {
            settingsMenu.supplyLabel.setText(BIND_PROMPT.getString());
        }
    }

    private String modeLabelString() {
        return (menu.getModeOrdinal() == 1 ? MODE_REDEEM : MODE_STAMP).getString();
    }

    private void onStartStopClicked() {
        // Optimistic: show the new state immediately; server confirms via ContainerData.
        boolean next = !pendingProcessing.resolve(menu.isProcessing());
        pendingProcessing.set(next);
        StampSharesRequest.send(pos, StampSharesRequest.Op.SET_PROCESSING, 0, next);
    }

    private void onModeClicked() {
        boolean nextRedeem = !pendingRedeemMode.resolve(menu.getModeOrdinal() == 1);
        pendingRedeemMode.set(nextRedeem);
        StampSharesRequest.send(pos, StampSharesRequest.Op.SET_MODE, nextRedeem ? 1 : 0, false);
    }

    private void onAutoInputToggled(Boolean checked) {
        pendingAutoInput.set(checked);
        int bits = (checked ? 1 : 0)
                | (pendingAutoOutput.resolve(menu.isAutoOutput()) ? 2 : 0);
        StampSharesRequest.send(pos, StampSharesRequest.Op.SET_AUTO_IO, bits, false);
    }

    private void onAutoOutputToggled(Boolean checked) {
        pendingAutoOutput.set(checked);
        int bits = (pendingAutoInput.resolve(menu.isAutoInput()) ? 1 : 0)
                | (checked ? 2 : 0);
        StampSharesRequest.send(pos, StampSharesRequest.Op.SET_AUTO_IO, bits, false);
    }

    /**
     * Task v2.0.8 — unbind confirm uses the reusable {@link AskPopupScreen}
     * (see {@code BankAccountManagementScreen.onDeleteAccountButtonClicked} for the
     * canonical usage pattern). Note: opening a non-container screen releases the
     * BE viewer lock via {@code stopOpen}; the SetStamperBindingRequest still
     * arrives on server, executes, and the caller can re-open the stamper.
     */
    private void openUnbindConfirm() {
        AskPopupScreen ask = new AskPopupScreen(
                null,
                () -> SetStamperBindingRequest.send(pos, 0),
                () -> {},
                CONFIRM_TITLE.getString(),
                CONFIRM_BODY.getString()
        );
        ask.setSize(400, 100);
        ask.setColors(0xFFe8711c, 0xFFe04c12, 0xFFf22718, 0xFF70e815);
        Minecraft.getInstance().setScreen(ask);
    }

    @Override
    protected void updateLayout(Gui gui) {
        int width = this.getWidth();
        int height = this.getHeight();
        int spacing = 5;
        int padding = 5;

        int invW = inventoryView.getWidth();
        int invH = inventoryView.getHeight();
        int settingsW = 140;
        int settingsH = 160;

        // Two-column layout centered vertically — settings left, inventory right.
        int totalW = settingsW + spacing + invW;
        int startX = Math.max(padding, (width - totalW) / 2);
        settingsMenu.setBounds(startX, (height - settingsH) / 2, settingsW, settingsH);
        inventoryView.setPosition(settingsMenu.getRight() + spacing, (height - invH) / 2);

        // Block-area overlays — children of inventoryView, so coordinates are
        // view-local. Slot geometry (from ContainerMenu): input (116,18),
        // output (116,50); flow arrows occupy x 98..114 (baked into the PNG).
        // Checkboxes sit left of their slot, vertically centered on it.
        int cbW = 94;
        int cbH = 12;
        autoInputCheckBox.setBounds(9, 20, cbW, cbH);
        autoOutputCheckBox.setBounds(9, 52, cbW, cbH);

        // Vertical progress bar between the input (bg y 17..35) and output
        // (bg y 49..67) slot backgrounds, centered on the slot column with a
        // small 2px gap to each slot.
        progressBar.setBounds(137, 37, 10, 10);
    }

    /**
     * Screen-space bounds of the top-level GUI elements — mirrors
     * {@link BankDownloadScreen#getJeiExclusionAreas()} so JEI can lay out its
     * ingredient panel and overlay buttons in the remaining free space.
     */
    public List<Rect2i> getJeiExclusionAreas() {
        if (!isInitialized())
            return List.of();
        return buildJeiExclusionAreas(settingsMenu, inventoryView);
    }
}
