package net.kroia.banksystem.block;

import com.google.common.base.Suppliers;
import dev.architectury.registry.registries.Registrar;
import dev.architectury.registry.registries.RegistrarManager;
import dev.architectury.registry.registries.RegistrySupplier;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.block.custom.*;
import net.kroia.banksystem.item.BankSystemItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.util.function.Supplier;

public class BankSystemBlocks {
    // 1.19.4
    public static final Supplier<RegistrarManager> MANAGER = Suppliers.memoize(() -> RegistrarManager.get(BankSystemMod.MOD_ID));
    private static final Registrar<Block> BLOCKS = MANAGER.get().get(Registries.BLOCK);

    // 1.19.3 or below
    //public static final Supplier<Registries> REGISTRIES = Suppliers.memoize(() -> Registries.get(BankSystemMod.MOD_ID));
    //public static final Registrar<Block> BLOCKS = REGISTRIES.get().get(Registry.BLOCK_KEY);

    private static boolean initialized = false;
    public static void init(){
        // Force the class to be loaded to initialize static fields
        if(initialized) return;
        initialized = true;
    }

    public static final RegistrySupplier<Block> METAL_CASE_BLOCK = registerBlock(MetalCaseBlock.NAME, MetalCaseBlock::new);
    public static final RegistrySupplier<TerminalBlock> TERMINAL_BLOCK = registerBlock(TerminalBlock.NAME, TerminalBlock::new);
    public static final RegistrySupplier<TerminalBlock> BANK_TERMINAL_BLOCK = registerBlock(BankTerminalBlock.NAME, BankTerminalBlock::new);
    public static final RegistrySupplier<Block> BANK_UPLOAD_BLOCK = registerBlock(BankUploadBlock.NAME, BankUploadBlock::new);
    public static final RegistrySupplier<Block> BANK_DOWNLOAD_BLOCK = registerBlock(BankDownloadBlock.NAME, BankDownloadBlock::new);


    public static <T extends Block> RegistrySupplier<T> registerBlock(String name, Supplier<T> block)
    {
        RegistrySupplier<T> toReturn = BLOCKS.register(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, name), block);
        BankSystemItems.registerBlockItem(name, toReturn);
        return toReturn;
    }
}
