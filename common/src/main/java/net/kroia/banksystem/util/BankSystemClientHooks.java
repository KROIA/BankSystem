package net.kroia.banksystem.util;

import net.kroia.banksystem.screen.custom.ATMScreen;
import net.kroia.banksystem.screen.custom.BankAccountManagementScreen;
import net.kroia.banksystem.screen.custom.BankSystemSettingScreen;
import net.minecraft.client.Minecraft;


public class BankSystemClientHooks {
    public static void openBankSystemSettingScreen()
    {
        // Ensuring the code runs on the main thread
        Minecraft.getInstance().submit(BankSystemSettingScreen::openScreen);
    }

    public static void openATMScreen()
    {
        // Ensuring the code runs on the main thread
        Minecraft.getInstance().submit(ATMScreen::openScreen);
    }

    public static void openBankAccountScreen(int accountNumber, boolean isAdminMode)
    {
        // Ensuring the code runs on the main thread
        Minecraft.getInstance().submit(() -> {
            BankAccountManagementScreen.openScreen(accountNumber, isAdminMode);
        });
    }
}
