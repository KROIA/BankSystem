package net.kroia.banksystem.menu;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.menu.custom.BankTerminalContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class BankSystemMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, BankSystemMod.MODID);

    public static final RegistryObject<MenuType<BankTerminalContainerMenu>> BANK_TERMINAL_CONTAINER_MENU = MENUS.register("bank_terminal_container_menu",
            () -> IForgeMenuType.create(BankTerminalContainerMenu::new));


    public static void register(IEventBus eventBus) {
        MENUS.register(eventBus);
    }
}