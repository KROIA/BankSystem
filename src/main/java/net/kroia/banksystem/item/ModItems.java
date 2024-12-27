package net.kroia.banksystem.item;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.item.custom.money.*;
import net.kroia.banksystem.item.custom.software.BankingSoftware;
import net.kroia.banksystem.item.custom.software.Software;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, BankSystemMod.MODID);

    public static final RegistryObject<Item> DISPLAY = ITEMS.register("display", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> CIRCUIT_BOARD = ITEMS.register("circuit_board", () -> new Item(new Item.Properties()));


    // Software
    public static final RegistryObject<Item> SOFTWARE = ITEMS.register(Software.NAME, Software::new);
    public static final RegistryObject<Item> BANKING_SOFTWARE = ITEMS.register(BankingSoftware.NAME, BankingSoftware::new);

    // Money
    public static final RegistryObject<Item> MONEY = ITEMS.register(MoneyItem.NAME, MoneyItem::new);
    public static final RegistryObject<Item> MONEY5 = ITEMS.register(MoneyItem5.NAME, MoneyItem5::new);
    public static final RegistryObject<Item> MONEY10 = ITEMS.register(MoneyItem10.NAME, MoneyItem10::new);
    public static final RegistryObject<Item> MONEY20 = ITEMS.register(MoneyItem20.NAME, MoneyItem20::new);
    public static final RegistryObject<Item> MONEY50 = ITEMS.register(MoneyItem50.NAME, MoneyItem50::new);
    public static final RegistryObject<Item> MONEY100 = ITEMS.register(MoneyItem100.NAME, MoneyItem100::new);
    public static final RegistryObject<Item> MONEY200 = ITEMS.register(MoneyItem200.NAME, MoneyItem200::new);
    public static final RegistryObject<Item> MONEY500 = ITEMS.register(MoneyItem500.NAME, MoneyItem500::new);
    public static final RegistryObject<Item> MONEY1000 = ITEMS.register(MoneyItem1000.NAME, MoneyItem1000::new);






    public static void register(IEventBus eventBus)
    {
        ITEMS.register(eventBus);
    }
}
