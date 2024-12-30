package net.kroia.banksystem.menu;

import com.google.common.base.Suppliers;
import dev.architectury.registry.menu.MenuRegistry;
import dev.architectury.registry.registries.Registrar;
import dev.architectury.registry.registries.RegistrarManager;
import dev.architectury.registry.registries.RegistrySupplier;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.screen.custom.BankTerminalScreen;
import net.kroia.banksystem.entity.BankSystemEntities;
import net.kroia.banksystem.menu.custom.BankTerminalContainerMenu;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;

import java.util.function.Supplier;

public class BankSystemMenus {

    // 1.19.4
    public static final Supplier<RegistrarManager> MANAGER = Suppliers.memoize(() -> RegistrarManager.get(BankSystemMod.MOD_ID));
    public static final Registrar<MenuType<?>> MENUS = MANAGER.get().get(Registries.MENU);

    // 1.19.3 or below
    //public static final Supplier<Registries> REGISTRIES = Suppliers.memoize(() -> Registries.get(BankSystemMod.MOD_ID));
    //public static final Registrar<MenuType<?>> MENUS = REGISTRIES.get().get(Registry.MENU_KEY);

    private static boolean initialized = false;
    public static void init(){
        // Force the class to be loaded to initialize static fields
        if(initialized) return;
        initialized = true;


    }

    public static void setupScreens()
    {
        MenuRegistry.registerScreenFactory(BANK_TERMINAL_CONTAINER_MENU.get(), BankTerminalScreen::new);
    }


    /*public static final RegistrySupplier<MenuType<BankTerminalContainerMenu>> BANK_TERMINAL_CONTAINER_MENU =
            registerMenu(
                    "bank_terminal_container_menu",
                          () -> MenuRegistry.ofExtended(BankTerminalContainerMenu::new),
                          BankTerminalScreen::new);


    public static <T extends AbstractContainerMenu, S extends Screen & MenuAccess<T>> RegistrySupplier<MenuType<T>>
    registerMenu(String name, Supplier<MenuType<T>> menu, MenuRegistry.ScreenFactory<T, S> factory)
    {
        RegistrySupplier<MenuType<T>> toReturn = MENUS.register(new ResourceLocation(BankSystemMod.MOD_ID, name), menu);
        MenuRegistry.registerScreenFactory(toReturn.get(), factory);
        return toReturn;
    }*/


    public static final RegistrySupplier<MenuType<BankTerminalContainerMenu>> BANK_TERMINAL_CONTAINER_MENU =
            MENUS.register(new ResourceLocation(BankSystemMod.MOD_ID, "bank_terminal_container_menu"), () -> MenuRegistry.ofExtended(BankTerminalContainerMenu::new));



}