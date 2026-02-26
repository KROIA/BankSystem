package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.banking.ClientBankManager;
import net.kroia.modutilities.gui.GuiContainerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

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

    protected ClientBankManager getBankManager() {
        return BACKEND_INSTANCES.CLIENT_BANK_MANAGER;
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
