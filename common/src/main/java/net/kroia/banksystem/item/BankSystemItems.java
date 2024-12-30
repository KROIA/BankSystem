package net.kroia.banksystem.item;


import com.google.common.base.Suppliers;
import dev.architectury.registry.registries.Registrar;
import dev.architectury.registry.registries.RegistrarManager;
import dev.architectury.registry.registries.RegistrySupplier;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.item.custom.money.*;
import net.kroia.banksystem.item.custom.software.BankingSoftware;
import net.kroia.banksystem.item.custom.software.Software;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.function.Supplier;

public class BankSystemItems {
    // 1.19.4
    public static final Supplier<RegistrarManager> MANAGER = Suppliers.memoize(() -> RegistrarManager.get(BankSystemMod.MOD_ID));
    public static final Registrar<Item> ITEMS = MANAGER.get().get(Registries.ITEM);

    // 1.19.3 or below
    //public static final Supplier<Registries> REGISTRIES = Suppliers.memoize(() -> Registries.get(BankSystemMod.MOD_ID));
    //public static final Registrar<Item> ITEMS = REGISTRIES.get().get(Registry.ITEM_KEY);

    private static boolean initialized = false;
    public static void init(){
        // Force the class to be loaded to initialize static fields
        if(initialized) return;
        initialized = true;

    }


    public static final Supplier<Item> DISPLAY = registerItem("display", () -> new Item(new Item.Properties().arch$tab(BankSystemCreativeModeTab.BANK_SYSTEM_TAB)));
    public static final Supplier<Item> CIRCUIT_BOARD = registerItem("circuit_board", () -> new Item(new Item.Properties().arch$tab(BankSystemCreativeModeTab.BANK_SYSTEM_TAB)));

    // Software
    public static final Supplier<Item> SOFTWARE = registerItem(Software.NAME, Software::new);
    public static final Supplier<Item> BANKING_SOFTWARE = registerItem(BankingSoftware.NAME, BankingSoftware::new);

    // Money
    public static final Supplier<Item> MONEY     = registerItem(MoneyItem.NAME, MoneyItem::new);
    public static final Supplier<Item> MONEY5    = registerItem(MoneyItem5.NAME, MoneyItem5::new);
    public static final Supplier<Item> MONEY10   = registerItem(MoneyItem10.NAME, MoneyItem10::new);
    public static final Supplier<Item> MONEY20   = registerItem(MoneyItem20.NAME, MoneyItem20::new);
    public static final Supplier<Item> MONEY50   = registerItem(MoneyItem50.NAME, MoneyItem50::new);
    public static final Supplier<Item> MONEY100  = registerItem(MoneyItem100.NAME, MoneyItem100::new);
    public static final Supplier<Item> MONEY200  = registerItem(MoneyItem200.NAME, MoneyItem200::new);
    public static final Supplier<Item> MONEY500  = registerItem(MoneyItem500.NAME, MoneyItem500::new);
    public static final Supplier<Item> MONEY1000 = registerItem(MoneyItem1000.NAME, MoneyItem1000::new);


    public static <T extends Item> RegistrySupplier<T> registerItem(String name, Supplier<T> item)
    {
        return ITEMS.register(new ResourceLocation(BankSystemMod.MOD_ID, name), item);
    }
    public static <T extends Block> RegistrySupplier<Item> registerBlockItem(String name, RegistrySupplier<T> block)
    {
        //return registerItem(name, () -> new BlockItem(block.get(), new Item.Properties().tab(BankSystemCreativeModeTab.BANK_SYSTEM_TAB))); // 1.19.3 or below
        return registerItem(name, () -> new BlockItem(block.get(), new Item.Properties().arch$tab(BankSystemCreativeModeTab.BANK_SYSTEM_TAB)));
    }
}
