package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankmanager.IClientBankManager;
import net.kroia.modutilities.gui.client.GuiScreen;
import net.kroia.modutilities.gui.elements.TextBox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.util.UUID;

public abstract class BankSystemGuiScreen extends GuiScreen {

    protected static BankSystemModBackend.Instances BACKEND_INSTANCES;

    private static boolean jeiModLoaded = false;
    public static void setJeiModLoaded(boolean loaded) { jeiModLoaded = loaded; }
    public static boolean isJeiModLoaded() { return jeiModLoaded; }
    public static final float guiScale = 0.8f;
    protected BankSystemGuiScreen(Component pTitle) {
        super(pTitle);
        setGuiScale(guiScale);
    }
    protected BankSystemGuiScreen(Component pTitle, Screen parent) {
        super(pTitle, parent);
        setGuiScale(guiScale);
    }

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    /**
     * v2.1.0 hotfix (crash on unpause/save) — swap the active screen from inside a
     * GUI event callback. {@code Minecraft.setScreen} mutates the screen/GUI tree;
     * calling it while ModUtilities is still iterating element children (click
     * dispatch, {@code GuiElement.init}) throws {@link java.util.ConcurrentModificationException}.
     * {@code tell(...)} always enqueues (unlike {@code execute(...)}, which runs
     * inline when already on the render thread), so the swap happens after the
     * current event pass completes.
     */
    public static void switchScreen(Screen newScreen) {
        Minecraft.getInstance().tell(() -> Minecraft.getInstance().setScreen(newScreen));
    }

    /**
     * Tooltip auto-flip — elements hovered on the right half of the screen show
     * their tooltip to the LEFT of the cursor so it never runs off-screen.
     * ModUtilities only supports a static per-element alignment
     * (see MC_ModUtilities bugreport "tooltip auto-flip"), so we retarget the
     * alignment every frame based on the mouse position before the tooltip pass.
     */
    /**
     * Elements whose tooltip side is fixed by the caller. {@link #applyTooltipAutoFlip}
     * leaves these alone instead of re-deciding the side from the mouse position — used
     * for controls that sit at the right edge of a narrow row, where the auto-flip
     * heuristic (screen halves) picks the wrong side.
     */
    private static final java.util.Map<net.kroia.modutilities.gui.elements.base.GuiElement,
            net.kroia.modutilities.gui.elements.base.GuiElement.Alignment> PINNED_TOOLTIPS =
            new java.util.WeakHashMap<>();

    public static void pinTooltipAlignment(net.kroia.modutilities.gui.elements.base.GuiElement element,
                                           net.kroia.modutilities.gui.elements.base.GuiElement.Alignment alignment) {
        PINNED_TOOLTIPS.put(element, alignment);
        element.setHoverTooltipMousePositionAlignment(alignment);
    }

    protected static void applyTooltipAutoFlip(net.kroia.modutilities.gui.Gui gui, int mouseX, int screenWidth) {
        if (gui == null) return;
        net.kroia.modutilities.gui.elements.base.GuiElement.Alignment alignment =
                mouseX > screenWidth / 2
                        ? net.kroia.modutilities.gui.elements.base.GuiElement.Alignment.TOP_RIGHT
                        : net.kroia.modutilities.gui.elements.base.GuiElement.Alignment.TOP_LEFT;
        for (net.kroia.modutilities.gui.elements.base.GuiElement element : gui.getElements()) {
            applyTooltipAlignmentRecursive(element, alignment);
        }
    }

    private static void applyTooltipAlignmentRecursive(
            net.kroia.modutilities.gui.elements.base.GuiElement element,
            net.kroia.modutilities.gui.elements.base.GuiElement.Alignment alignment) {
        net.kroia.modutilities.gui.elements.base.GuiElement.Alignment pinned = PINNED_TOOLTIPS.get(element);
        element.setHoverTooltipMousePositionAlignment(pinned != null ? pinned : alignment);
        for (net.kroia.modutilities.gui.elements.base.GuiElement child : element.getChilds()) {
            applyTooltipAlignmentRecursive(child, alignment);
        }
    }

    protected IClientBankManager getBankManager() {
        return BACKEND_INSTANCES.CLIENT_BANK_MANAGER;
    }

    /**
     * @return true if the server this client is connected to is the MASTER server
     *         (or a regular single server, which acts as its own master). Synced at
     *         player join via {@code PlayerJoinSyncPacket}; used to gate master-only
     *         UI such as the "Mod Settings" button. UI gating only — the server
     *         independently enforces admin + master status in {@code ModSettingsRequest}.
     */
    protected static boolean isMasterServer() {
        return BACKEND_INSTANCES.CLIENT_SETTINGS.isMasterServer();
    }

    /**
     * Invisible vanilla {@link EditBox} that mirrors the focus state of the
     * ModUtilities text-input elements for OTHER mods' benefit — same mechanism
     * as in {@code BankSystemGuiContainerScreen}, see the detailed javadoc
     * there. Covers the standalone screens (e.g. the BalanceHistory search box).
     */
    private final EditBox modTextInputFocusProxy =
            new EditBox(Minecraft.getInstance().font, 0, 0, 0, 0, Component.empty());

    @Override
    public void render(GuiGraphics pGuiGraphics, int pMouseX, int pMouseY, float pPartialTick) {
        modTextInputFocusProxy.setFocused(getGui().getFocusedElement() instanceof TextBox);
        applyTooltipAutoFlip(getGui(), pMouseX, this.width);
        super.render(pGuiGraphics, pMouseX, pMouseY, pPartialTick);
    }

    protected LocalPlayer getThisPlayer()
    {
        return Minecraft.getInstance().player;
    }
    protected UUID getThisPlayerUUID()
    {
        return getThisPlayer().getUUID();
    }
    protected String getThisPlayerName()
    {
        return getThisPlayer().getDisplayName().getString();
    }


    protected void info(String msg)
    {
        BACKEND_INSTANCES.LOGGER.info("[BankSystemGuiScreen] " + msg);
    }
    protected void error(String msg)
    {
        BACKEND_INSTANCES.LOGGER.error("[BankSystemGuiScreen] " + msg);
    }
    protected void error(String msg, Throwable e)
    {
        BACKEND_INSTANCES.LOGGER.error("[BankSystemGuiScreen] " + msg, e);
    }
    protected void warn(String msg)
    {
        BACKEND_INSTANCES.LOGGER.warn("[BankSystemGuiScreen] " + msg);
    }
    protected void debug(String msg)
    {
        BACKEND_INSTANCES.LOGGER.debug("[BankSystemGuiScreen] " + msg);
    }
}
