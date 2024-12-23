package net.kroia.banksystem.block;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.block.custom.BankTerminalBlock;
import net.kroia.banksystem.block.custom.MetalCaseBlock;
import net.kroia.banksystem.block.custom.TerminalBlock;
import net.kroia.banksystem.item.ModItems;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Supplier;

public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, BankSystemMod.MODID);


    public static final RegistryObject<Block> METAL_CASE_BLOCK = registerBlock(MetalCaseBlock.NAME, MetalCaseBlock::new);
    public static final RegistryObject<TerminalBlock> TERMINAL_BLOCK = registerBlock(TerminalBlock.NAME, TerminalBlock::new);
    public static final RegistryObject<TerminalBlock> BANK_TERMINAL_BLOCK = registerBlock(BankTerminalBlock.NAME, BankTerminalBlock::new);


    private static <T extends Block> RegistryObject<T> registerBlock(String name, Supplier<T> block)
    {
        RegistryObject<T> toReturn = BLOCKS.register(name, block);
        registerBlockItem(name, toReturn);
        return toReturn;
    }
    private static <T extends Block>RegistryObject<Item> registerBlockItem(String name, RegistryObject<T> block)
    {
        return ModItems.ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }
    public static void register(IEventBus eventBus)
    {
        BLOCKS.register(eventBus);
    }
}
