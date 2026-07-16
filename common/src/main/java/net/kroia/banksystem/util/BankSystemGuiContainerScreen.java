package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankmanager.IClientBankManager;
import net.kroia.modutilities.gui.client.GuiContainerScreen;
import net.kroia.modutilities.gui.elements.TextBox;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public abstract class BankSystemGuiContainerScreen<T extends AbstractContainerMenu> extends GuiContainerScreen<T> {

    protected static BankSystemModBackend.Instances BACKEND_INSTANCES;


    public static boolean isJeiModLoaded() { return BankSystemGuiScreen.isJeiModLoaded(); }

    protected BankSystemGuiContainerScreen(T pMenu, Inventory pPlayerInventory, Component pTitle) {
        super(pMenu, pPlayerInventory, pTitle);
        setGuiScale(BankSystemGuiScreen.guiScale);
    }
    protected BankSystemGuiContainerScreen(T pMenu, Inventory pPlayerInventory, Component pTitle, Screen parent) {
        super(pMenu, pPlayerInventory, pTitle);
        setGuiScale(BankSystemGuiScreen.guiScale);
    }

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    protected IClientBankManager getBankManager() {
        return BACKEND_INSTANCES.CLIENT_BANK_MANAGER;
    }

    /**
     * Invisible vanilla {@link EditBox} that mirrors the focus state of the
     * ModUtilities text-input elements for OTHER mods' benefit.
     * <p>
     * WHY: mods with global in-GUI keybinds (e.g. JEI's "o" overlay toggle or
     * "r"/"u" recipe lookups) hook the keyboard BEFORE {@code Screen.keyPressed}
     * (Fabric's {@code ScreenKeyboardEvents.allowKeyPress}, NeoForge's pre
     * events), so consuming the event in this screen cannot stop them. Their
     * only "is the user typing?" heuristic is reflection over the screen's
     * fields looking for a focused vanilla {@link EditBox} (JEI 19.21:
     * {@code ClientInputHandler.isContainerTextFieldFocused} via
     * {@code ReflectionUtil.getFieldWithClass}, which walks the superclass
     * chain — so this single field covers every screen derived from this base).
     * The ModUtilities {@link TextBox} is a custom element and invisible to
     * that heuristic; without the proxy, typing "o" into a filter box toggles
     * JEI's ingredient overlay. The proxy is never rendered, ticked or added as
     * a widget — it only carries the focus flag.
     */
    private final EditBox modTextInputFocusProxy =
            new EditBox(Minecraft.getInstance().font, 0, 0, 0, 0, Component.empty());

    @Override
    public void render(GuiGraphics pGuiGraphics, int pMouseX, int pMouseY, float pPartialTick) {
        // Mirror the ModUtilities text-input focus before other mods' key hooks
        // query it. Focus only changes through mouse clicks / key events, so a
        // per-frame sync is always current by the time the next key arrives.
        modTextInputFocusProxy.setFocused(gui.getFocusedElement() instanceof TextBox);
        super.render(pGuiGraphics, pMouseX, pMouseY, pPartialTick);
    }

    /**
     * Converts the bounds of the given top-level GUI elements from GUI element
     * coordinates to screen coordinates and returns them as JEI exclusion
     * rectangles ({@code Rect2i} in the same coordinate space as
     * {@code Screen.width}/{@code Screen.height}).
     * <p>
     * Element coordinates are scaled by {@link #getGuiScale()} (the same
     * conversion the ModUtilities {@code Gui} uses when rendering), with the
     * origin floored and the extent ceiled so the exclusion area always fully
     * covers the element.
     * <p>
     * Only pass elements added directly via {@code addElement} (their local
     * position is their global position).
     */
    protected List<Rect2i> buildJeiExclusionAreas(GuiElement... elements) {
        List<Rect2i> areas = new ArrayList<>(elements.length);
        float scale = getGuiScale();
        for (GuiElement element : elements) {
            int x0 = (int) Math.floor(element.getX() * scale);
            int y0 = (int) Math.floor(element.getY() * scale);
            int x1 = (int) Math.ceil((element.getX() + element.getWidth()) * scale);
            int y1 = (int) Math.ceil((element.getY() + element.getHeight()) * scale);
            areas.add(new Rect2i(x0, y0, x1 - x0, y1 - y0));
        }
        return areas;
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
        BACKEND_INSTANCES.LOGGER.info("[BankSystemGuiContainerScreen] " + msg);
    }
    protected void error(String msg)
    {
        BACKEND_INSTANCES.LOGGER.error("[BankSystemGuiContainerScreen] " + msg);
    }
    protected void error(String msg, Throwable e)
    {
        BACKEND_INSTANCES.LOGGER.error("[BankSystemGuiContainerScreen] " + msg, e);
    }
    protected void warn(String msg)
    {
        BACKEND_INSTANCES.LOGGER.warn("[BankSystemGuiContainerScreen] " + msg);
    }
    protected void debug(String msg)
    {
        BACKEND_INSTANCES.LOGGER.debug("[BankSystemGuiContainerScreen] " + msg);
    }
}
