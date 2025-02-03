package net.kroia.banksystem.item;

import dev.architectury.registry.CreativeTabRegistry;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.block.BankSystemBlocks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;


// https://docs.architectury.dev/api/creative_tabs

public class BankSystemCreativeModeTab {
    private static boolean initialized = false;
    public static void init(){
        // Force the class to be loaded to initialize static fields
        if(initialized) return;
        initialized = true;
        //TABS.register(); // mc>=1.20.1
    }

    /*
    // mc>=1.20.1
    public static final DeferredRegister<CreativeModeTab> TABS =
           DeferredRegister.create(BankSystemMod.MOD_ID, Registries.CREATIVE_MODE_TAB);


    public static final RegistrySupplier<CreativeModeTab> BANK_SYSTEM_TAB = TABS.register(
            "bank_system_tab", // Tab ID
            () -> {
                return CreativeTabRegistry.create(
                    Component.translatable("itemGroup."+BankSystemMod.MOD_ID+".bank_system_tab"), // Tab Name
                    () -> new ItemStack(BankSystemBlocks.BANK_TERMINAL_BLOCK.get()) // Icon
            );}
    );
    public static <T extends CreativeModeTab> RegistrySupplier<T> registerTab(String name, Supplier<T> tab)
    {
        return TABS.register(new ResourceLocation(BankSystemMod.MOD_ID, name), tab);
    }

    */


    // mc=1.19.4
    public static final CreativeTabRegistry.TabSupplier BANK_SYSTEM_TAB = CreativeTabRegistry.create(
            new ResourceLocation(BankSystemMod.MOD_ID, "bank_system_tab"), // Tab ID
            () -> new ItemStack(BankSystemBlocks.BANK_TERMINAL_BLOCK.get()) // Icon
    );


    /*
    // mc<1.19.4
    public static final CreativeModeTab BANK_SYSTEM_TAB = CreativeTabsRegistry.create(
            new ResourceLocation(BankSystemMod.MOD_ID, "bank_system_tab"), // Tab ID
            () -> new ItemStack(BankSystemBlocks.BANK_TERMINAL_BLOCK.get()) // Icon
    );
    */


}