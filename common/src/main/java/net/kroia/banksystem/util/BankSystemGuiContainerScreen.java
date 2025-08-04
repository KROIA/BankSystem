package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.banking.ClientBankManager;
import net.kroia.modutilities.gui.GuiContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

public abstract class BankSystemGuiContainerScreen<T extends AbstractContainerMenu> extends GuiContainerScreen<T> {

    protected static BankSystemModBackend.Instances BACKEND_INSTANCES;

    protected BankSystemGuiContainerScreen(T pMenu, Inventory pPlayerInventory, Component pTitle) {
        super(pMenu, pPlayerInventory, pTitle);
        setGuiScale(BankSystemGuiScreen.guiScale);
    }

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    protected ClientBankManager getBankManager() {
        return BACKEND_INSTANCES.CLIENT_BANK_MANAGER;
    }
}
