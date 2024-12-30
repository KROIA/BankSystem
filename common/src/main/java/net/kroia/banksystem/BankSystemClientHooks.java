package net.kroia.banksystem;

import dev.architectury.registry.menu.MenuRegistry;
import net.kroia.banksystem.screen.custom.BankSystemSettingScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionResult;


public class BankSystemClientHooks {
    public static InteractionResult openBankSystemSettingScreen()
    {
        // Ensuring the code runs on the main thread
        Minecraft.getInstance().submit(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            BankSystemSettingScreen screen = new BankSystemSettingScreen();
            //screen.init(minecraft, minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight());
            minecraft.setScreen(screen);
        });
        return InteractionResult.SUCCESS;
    }
}
