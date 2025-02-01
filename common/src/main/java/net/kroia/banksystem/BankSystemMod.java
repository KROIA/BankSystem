package net.kroia.banksystem;

import com.mojang.logging.LogUtils;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.TickEvent;
import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.block.BankSystemBlocks;
import net.kroia.banksystem.command.BankSystemCommands;
import net.kroia.banksystem.entity.BankSystemEntities;
import net.kroia.banksystem.item.BankSystemCreativeModeTab;
import net.kroia.banksystem.item.BankSystemItems;
import net.kroia.banksystem.menu.BankSystemMenus;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.util.BankSystemDataHandler;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.modutilities.ItemUtilities;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.File;

public final class BankSystemMod {
    public static final String MOD_ID = "banksystem";


    public static final Logger LOGGER = LogUtils.getLogger();

    public static void init() {
        BankSystemModSettings.init();
        CommandRegistrationEvent.EVENT.register((dispatcher, registryAccess, environment) -> {
            BankSystemCommands.register(dispatcher);
        });
        BankSystemBlocks.init();
        BankSystemItems.init();
        BankSystemEntities.init();
        BankSystemMenus.init();
        BankSystemCreativeModeTab.init();
        BankSystemTextMessages.init();

        BankSystemNetworking.setupClientReceiverPackets();
        BankSystemNetworking.setupServerReceiverPackets();

        TickEvent.ServerLevelTick.SERVER_POST.register((serverLevel) -> {
            BankSystemDataHandler.tickUpdate();
        });
    }

    public static void onClientSetup()
    {
        BankSystemMenus.setupScreens();

    }

    public static void onServerSetup()
    {
        //BankSystemNetworking.setupServerReceiverPackets();
        ServerBankManager.setPotientialBankItemIDs(ItemUtilities.getAllItemIDs());
    }

    public static void loadDataFromFiles(MinecraftServer server)
    {
        File rootSaveFolder = server.getWorldPath(LevelResource.ROOT).toFile();
        // Load data from the root save folder
        BankSystemDataHandler.setSaveFolder(rootSaveFolder);
        BankSystemDataHandler.loadAll();
    }
    public static void saveDataToFiles(MinecraftServer server)
    {
        File rootSaveFolder = server.getWorldPath(LevelResource.ROOT).toFile();
        // Load data from the root save folder
        BankSystemDataHandler.setSaveFolder(rootSaveFolder);
        BankSystemDataHandler.saveAll();
    }
    public static boolean isDataLoaded() {
        return BankSystemDataHandler.isLoaded();
    }

}
