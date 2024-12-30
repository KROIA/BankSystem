package net.kroia.banksystem.item;

import com.google.common.base.Suppliers;
import dev.architectury.registry.CreativeTabRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.Registrar;
import dev.architectury.registry.registries.RegistrarManager;
import dev.architectury.registry.registries.RegistrySupplier;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.block.BankSystemBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;


// https://docs.architectury.dev/api/creative_tabs

public class BankSystemCreativeModeTab {
    private static boolean initialized = false;
    public static void init(){
        // Force the class to be loaded to initialize static fields
        if(initialized) return;
        initialized = true;
        TABS.register();
    }

/*
    // 1.19.4
    public static final Supplier<RegistrarManager> MANAGER = Suppliers.memoize(() -> RegistrarManager.get(BankSystemMod.MOD_ID));
    public static final Registrar<CreativeModeTab> TABS = MANAGER.get().get(Registries.CREATIVE_MODE_TAB);

    // 1.19.3 or below
    //public static final Supplier<Registries> REGISTRIES = Suppliers.memoize(() -> Registries.get(BankSystemMod.MOD_ID));
    //public static final Registrar<CreativeModeTab> TABS = REGISTRIES.get().get(Registry.CREATIVE_MODE_TAB_KEY);


    public static final Supplier<CreativeModeTab> BANK_SYSTEM_TAB = registerTab("banking_tab", () -> {
        return CreativeTabRegistry.create(
                Component.translatable(BankSystemMod.MOD_ID+".creative_mode_tab_name"), // Tab Name
                () -> new ItemStack(BankSystemBlocks.BANK_TERMINAL_BLOCK.get()) // Icon
        );
    });*/


    public static final DeferredRegister<CreativeModeTab> TABS =
           DeferredRegister.create(BankSystemMod.MOD_ID, Registries.CREATIVE_MODE_TAB);


    public static final RegistrySupplier<CreativeModeTab> BANK_SYSTEM_TAB = TABS.register(
            "test_tab", // Tab ID
            () -> {
                return CreativeTabRegistry.create(
                    Component.translatable(BankSystemMod.MOD_ID+".creative_mode_tab_name"), // Tab Name
                    () -> new ItemStack(BankSystemBlocks.BANK_TERMINAL_BLOCK.get()) // Icon
            );}
    );



    public static <T extends CreativeModeTab> RegistrySupplier<T> registerTab(String name, Supplier<T> tab)
    {
        return TABS.register(new ResourceLocation(BankSystemMod.MOD_ID, name), tab);
    }
/*

    public static void register(IEventBus eventBus) {
        CREATIVE_MODE_TABS.register(eventBus);
    }

    // Method to add a dynamic item
    public static void addDynamicItem(Supplier<ItemStack> itemSupplier) {
        dynamicItems.add(itemSupplier);
    }

    // Method to add a dynamic block
    public static void addDynamicBlock(Supplier<Block> blockSupplier) {
        dynamicBlocks.add(blockSupplier);
    }*/


}