package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.banking.ClientBankManager;
import net.kroia.modutilities.gui.GuiScreen;
import net.minecraft.network.chat.Component;

public abstract class BankSystemGuiScreen extends GuiScreen {

    protected static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static final float guiScale = 1.0f;
    protected BankSystemGuiScreen(Component pTitle) {
        super(pTitle);
        setGuiScale(guiScale);
    }

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    protected ClientBankManager getBankManager() {
        return BACKEND_INSTANCES.CLIENT_BANK_MANAGER;
    }
}
