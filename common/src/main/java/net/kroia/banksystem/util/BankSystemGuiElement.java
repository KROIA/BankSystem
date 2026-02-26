package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.banking.ClientBankManager;
import net.kroia.modutilities.gui.elements.base.GuiElement;

public abstract class BankSystemGuiElement extends GuiElement {
    protected static BankSystemModBackend.Instances BACKEND_INSTANCES;

    public final static float hoverToolTipFontSize = 0.8f;
    public final static int padding = 5;
    public final static int spacing = 5;


    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    public BankSystemGuiElement() {
        super();
    }
    public BankSystemGuiElement(int x, int y, int width, int height) {
        super(x, y, width, height);
    }

    public ClientBankManager getMarketManager() {
        return BACKEND_INSTANCES.CLIENT_BANK_MANAGER;
    }
}
