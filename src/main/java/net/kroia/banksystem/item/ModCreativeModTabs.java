package net.kroia.banksystem.item;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.block.ModBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public class ModCreativeModTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BankSystemMod.MODID);

    public static final RegistryObject<CreativeModeTab> STOCK_MARKET_TAB = CREATIVE_MODE_TABS.register("banking_tab",
            () -> CreativeModeTab.builder().title(Component.translatable("Banking System"))
                    .displayItems((pParameters, pOutput) -> {
                        pOutput.accept(ModBlocks.METAL_CASE_BLOCK.get());
                        pOutput.accept(ModBlocks.TERMINAL_BLOCK.get());
                        pOutput.accept(ModBlocks.BANK_TERMINAL_BLOCK.get());


                        pOutput.accept(ModItems.DISPLAY.get());
                        pOutput.accept(ModItems.CIRCUIT_BOARD.get());


                        pOutput.accept(ModItems.SOFTWARE.get());
                        pOutput.accept(ModItems.BANKING_SOFTWARE.get());
                    })
                    .icon(() -> new ItemStack(ModBlocks.BANK_TERMINAL_BLOCK.get()))
                    .build());


    public static void register(IEventBus eventBus) {
        CREATIVE_MODE_TABS.register(eventBus);
    }
}