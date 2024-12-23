package net.kroia.banksystem.item;

import net.kroia.banksystem.BankSystemMod;
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

    public static void register(IEventBus eventBus)
    {
        ITEMS.register(eventBus);
    }
}
