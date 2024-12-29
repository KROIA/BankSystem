package net.kroia.banksystem;

import com.mojang.logging.LogUtils;
import net.kroia.banksystem.block.BankSystemBlocks;
import net.kroia.banksystem.command.BankSystemCommands;
import net.kroia.banksystem.entity.BankSystemEntities;
import net.kroia.banksystem.item.BankSystemCreativeModeTab;
import net.kroia.banksystem.item.BankSystemItems;
import net.kroia.banksystem.menu.BankSystemMenus;
import net.kroia.banksystem.screen.custom.BankTerminalScreen;
import net.kroia.banksystem.networking.ModMessages;
import net.kroia.banksystem.util.DataHandler;
import net.kroia.modutilities.ItemUtilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

import java.io.File;


// The value here should match an entry in the META-INF/mods.toml file
@Mod(BankSystemMod.MODID)
public class BankSystemMod
{
    // Define mod id in a common place for everything to reference
    public static final String MODID = "banksystem";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();


    public BankSystemMod()
    {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();


        BankSystemCreativeModeTab.register(modEventBus);
        BankSystemItems.register(modEventBus);
        BankSystemBlocks.register(modEventBus);
        BankSystemEntities.register(modEventBus);
        BankSystemMenus.register(modEventBus);

        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register ourselves for server and other game events we are interested in
        MinecraftForge.EVENT_BUS.register(this);

        MinecraftForge.EVENT_BUS.addListener(BankSystemMod::onRegisterCommands);
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        BankSystemCommands.register(event.getDispatcher());
    }


    private void commonSetup(final FMLCommonSetupEvent event)
    {
        ModMessages.register();
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event)
    {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting");
        ItemUtilities.getAllItemIDs("ores");
    }

    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents
    {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event)
        {
            // Some client setup code
            LOGGER.info("HELLO FROM CLIENT SETUP");
            LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());

            MenuScreens.register(BankSystemMenus.BANK_TERMINAL_CONTAINER_MENU.get(), BankTerminalScreen::new);


        }
    }

    public static void loadDataFromFiles(MinecraftServer server)
    {
        File rootSaveFolder = server.getWorldPath(LevelResource.ROOT).toFile();
        // Load data from the root save folder
        DataHandler.setSaveFolder(rootSaveFolder);
        DataHandler.loadAll();
    }
    public static void saveDataToFiles(MinecraftServer server)
    {
        File rootSaveFolder = server.getWorldPath(LevelResource.ROOT).toFile();
        // Load data from the root save folder
        DataHandler.setSaveFolder(rootSaveFolder);
        DataHandler.saveAll();
    }
    public static boolean isDataLoaded() {
        return DataHandler.isLoaded();
    }
}
