package net.kroia.banksystem.item;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.block.BankSystemBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class BankSystemCreativeModeTab {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BankSystemMod.MODID);

    // Dynamic lists for runtime-added items and blocks
    private static final List<Supplier<ItemStack>> dynamicItems = new ArrayList<>();
    private static final List<Supplier<Block>> dynamicBlocks = new ArrayList<>();
    public static final RegistryObject<CreativeModeTab> STOCK_MARKET_TAB = CREATIVE_MODE_TABS.register("banking_tab",
            () -> CreativeModeTab.builder().title(Component.translatable("Banking System"))
                    .displayItems((pParameters, pOutput) -> {
                        pOutput.accept(BankSystemBlocks.METAL_CASE_BLOCK.get());
                        pOutput.accept(BankSystemBlocks.TERMINAL_BLOCK.get());
                        pOutput.accept(BankSystemBlocks.BANK_TERMINAL_BLOCK.get());


                        pOutput.accept(BankSystemItems.DISPLAY.get());
                        pOutput.accept(BankSystemItems.CIRCUIT_BOARD.get());


                        pOutput.accept(BankSystemItems.SOFTWARE.get());
                        pOutput.accept(BankSystemItems.BANKING_SOFTWARE.get());

                        pOutput.accept(BankSystemItems.MONEY.get());
                        pOutput.accept(BankSystemItems.MONEY5.get());
                        pOutput.accept(BankSystemItems.MONEY10.get());
                        pOutput.accept(BankSystemItems.MONEY20.get());
                        pOutput.accept(BankSystemItems.MONEY50.get());
                        pOutput.accept(BankSystemItems.MONEY100.get());
                        pOutput.accept(BankSystemItems.MONEY200.get());
                        pOutput.accept(BankSystemItems.MONEY500.get());
                        pOutput.accept(BankSystemItems.MONEY1000.get());

                        // Add dynamic items
                        for (Supplier<ItemStack> dynamicItem : dynamicItems) {
                            pOutput.accept(dynamicItem.get());
                        }

                        // Add dynamic blocks
                        for (Supplier<Block> dynamicBlock : dynamicBlocks) {
                            pOutput.accept(new ItemStack(dynamicBlock.get())); // Wrap blocks in ItemStacks
                        }
                    })
                    .icon(() -> new ItemStack(BankSystemBlocks.BANK_TERMINAL_BLOCK.get()))
                    .build());


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
    }


}