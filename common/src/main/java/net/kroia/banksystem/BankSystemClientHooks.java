package net.kroia.banksystem;

import net.kroia.banksystem.screen.custom.ATMScreen;
import net.kroia.banksystem.screen.custom.BankAccountManagementScreen;
import net.kroia.banksystem.screen.custom.BankSystemSettingScreen;
import net.minecraft.client.Minecraft;
import java.util.UUID;


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

    public static void openBankAccountScreen(UUID playerUUID)
    {
        // Ensuring the code runs on the main thread
        Minecraft.getInstance().submit(() -> {
            BankAccountManagementScreen.openScreen(playerUUID);
        });
    }
}
