package net.kroia.banksystem;

import com.mojang.logging.LogUtils;
import net.kroia.banksystem.block.ModBlocks;
import net.kroia.banksystem.command.ModCommands;
import net.kroia.banksystem.entity.ModEntities;
import net.kroia.banksystem.item.ModCreativeModTabs;
import net.kroia.banksystem.item.ModItems;
import net.kroia.banksystem.menu.ModMenus;
import net.kroia.banksystem.screen.custom.BankTerminalScreen;
import net.kroia.banksystem.networking.ModMessages;
import net.kroia.modutilities.ItemUtilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

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

        ModCreativeModTabs.register(modEventBus);
        ModItems.register(modEventBus);
        ModBlocks.register(modEventBus);
        ModEntities.register(modEventBus);
        ModMenus.register(modEventBus);

        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register ourselves for server and other game events we are interested in
        MinecraftForge.EVENT_BUS.register(this);

        MinecraftForge.EVENT_BUS.addListener(BankSystemMod::onRegisterCommands);
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        ModCommands.register(event.getDispatcher());
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

            //MenuScreens.register(ModMenus.BANK_TERMINAL_CONTAINER_MENU.get(), BankTerminalScreen::new);

        }
    }
}
