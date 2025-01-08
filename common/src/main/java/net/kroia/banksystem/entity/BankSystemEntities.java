package net.kroia.banksystem.entity;

import com.google.common.base.Suppliers;
import dev.architectury.registry.registries.Registrar;
import dev.architectury.registry.registries.RegistrarManager;
import dev.architectury.registry.registries.RegistrySupplier;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.block.BankSystemBlocks;
import net.kroia.banksystem.entity.custom.BankTerminalBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

public class BankSystemEntities {
    // 1.19.4
    public static final Supplier<RegistrarManager> MANAGER = Suppliers.memoize(() -> RegistrarManager.get(BankSystemMod.MOD_ID));
    public static final Registrar<BlockEntityType<?>> BLOCK_ENTITIES = MANAGER.get().get(Registries.BLOCK_ENTITY_TYPE);

    // 1.19.3 or below
    //public static final Supplier<Registries> REGISTRIES = Suppliers.memoize(() -> Registries.get(BankSystemMod.MOD_ID));
    //public static final Registrar<Item> ITEMS = REGISTRIES.get().get(Registry.ITEM_KEY);

    private static boolean initialized = false;
    public static void init(){
        // Force the class to be loaded to initialize static fields
        if(initialized) return;
        initialized = true;
    }

    public static final RegistrySupplier<BlockEntityType<?>> BANK_TERMINAL_BLOCK_ENTITY =
            registerBlockEntity("bank_terminal_block_entity",
                    () -> {
                        Set<Block> blocks = new HashSet<>();
                        blocks.add(BankSystemBlocks.BANK_TERMINAL_BLOCK.get());
                        return new BlockEntityType(BankTerminalBlockEntity::new, blocks);
                    });



    public static <T extends BlockEntityType<?>> RegistrySupplier<T> registerBlockEntity(String name, Supplier<T> item)
    {
        //BankSystemMod.LOGGER.info("Registering block entity: " + name);
        return BLOCK_ENTITIES.register(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, name), item);
    }
}