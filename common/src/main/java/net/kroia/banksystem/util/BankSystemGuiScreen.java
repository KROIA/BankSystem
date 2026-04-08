package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankmanager.IClientBankManager;
import net.kroia.modutilities.gui.GuiScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

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

    protected IClientBankManager getBankManager() {
        return BACKEND_INSTANCES.CLIENT_BANK_MANAGER;
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
