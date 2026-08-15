package net.kroia.banksystem.neoforge;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.minecraft.menu.BankSystemMenus;
import net.kroia.banksystem.screen.custom.BankDownloadScreen;
import net.kroia.banksystem.screen.custom.BankTerminalScreen;
import net.kroia.banksystem.screen.custom.BankUploadScreen;
import net.kroia.banksystem.screen.custom.ShareStamperScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * NeoForge client-only event handlers. Separated from {@link NeoForgeSetup} so the
 * screen imports do not put {@code Screen} into the constant pool of a class that
 * NeoForge loads on dedicated servers (which have no client classes).
 */
@EventBusSubscriber(modid = BankSystemMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class NeoForgeClientSetup {

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        BankSystemModBackend.onClientSetup();
    }

    /*
     * Workaround: Architectury screen registration does not work with NeoForge,
     * so we register screens via this NeoForge-specific event instead.
     */
    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(BankSystemMenus.BANK_TERMINAL_CONTAINER_MENU.get(), BankTerminalScreen::new);
        event.register(BankSystemMenus.BANK_UPLOAD_CONTAINER_MENU.get(), BankUploadScreen::new);
        event.register(BankSystemMenus.BANK_DOWNLOAD_CONTAINER_MENU.get(), BankDownloadScreen::new);
        event.register(BankSystemMenus.SHARE_STAMPER_CONTAINER_MENU.get(), ShareStamperScreen::new);
    }
}
