package net.kroia.banksystem.minecraft.entity;

import com.google.common.base.Suppliers;
import dev.architectury.registry.client.rendering.BlockEntityRendererRegistry;
import dev.architectury.registry.client.rendering.RenderTypeRegistry;
import dev.architectury.registry.registries.Registrar;
import dev.architectury.registry.registries.RegistrarManager;
import dev.architectury.registry.registries.RegistrySupplier;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.minecraft.block.BankSystemBlocks;
import net.kroia.banksystem.minecraft.entity.custom.BankDownloadBlockEntity;
import net.kroia.banksystem.minecraft.entity.custom.BankTerminalBlockEntity;
import net.kroia.banksystem.minecraft.entity.custom.BankUploadBlockEntity;
import net.kroia.banksystem.minecraft.entity.custom.BankSystemDisplayBlockEntity;
import net.kroia.banksystem.minecraft.entity.custom.MoneyStockpileBlockEntity;
import net.kroia.banksystem.minecraft.entity.renderer.MoneyStockpileEntityRenderer;
import net.kroia.modutilities.gui.display.client.AbstractDisplayBlockEntityRenderer;
import net.minecraft.core.registries.Registries;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;

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
                    () -> net.minecraft.world.level.block.entity.BlockEntityType.Builder.of(BankTerminalBlockEntity::new, BankSystemBlocks.BANK_TERMINAL_BLOCK.get()).build(null));

    public static final RegistrySupplier<BlockEntityType<?>> BANK_UPLOAD_BLOCK_ENTITY =
            registerBlockEntity("bank_upload_block_entity",
                    () -> BlockEntityType.Builder.of(BankUploadBlockEntity::new, BankSystemBlocks.BANK_UPLOAD_BLOCK.get()).build(null));

    public static final RegistrySupplier<BlockEntityType<?>> BANK_DOWNLOAD_BLOCK_ENTITY =
            registerBlockEntity("bank_download_block_entity",
                    () -> BlockEntityType.Builder.of(BankDownloadBlockEntity::new, BankSystemBlocks.BANK_DOWNLOAD_BLOCK.get()).build(null));


    public static final RegistrySupplier<BlockEntityType<?>> MONEY_STOCKPILE_BLOCK_ENTITY =
            registerBlockEntity("money_stockpile_block_entity",
                    () -> BlockEntityType.Builder.of(MoneyStockpileBlockEntity::new, BankSystemBlocks.MONEY_STOCKPILE_BLOCK.get()).build(null));

    public static final RegistrySupplier<BlockEntityType<?>> BANKSYSTEM_DISPLAY_BLOCK_ENTITY =
            registerBlockEntity("banksystem_display_block_entity",
                    () -> BlockEntityType.Builder.of(BankSystemDisplayBlockEntity::new, BankSystemBlocks.BANKSYSTEM_DISPLAY_BLOCK.get()).build(null));



    public static void registerRenderers()
    {
        // Architectury API method to register BlockEntityRenderer in a platform-neutral way
        BlockEntityRendererRegistry.register((BlockEntityType<MoneyStockpileBlockEntity>) MONEY_STOCKPILE_BLOCK_ENTITY.get(), MoneyStockpileEntityRenderer::new);
        BlockEntityRendererRegistry.register((BlockEntityType<BankSystemDisplayBlockEntity>) BANKSYSTEM_DISPLAY_BLOCK_ENTITY.get(), AbstractDisplayBlockEntityRenderer::new);
        RenderTypeRegistry.register(RenderType.cutout(), BankSystemBlocks.BANKSYSTEM_DISPLAY_BLOCK.get());
    }


    public static <T extends BlockEntityType<?>> RegistrySupplier<T> registerBlockEntity(String name, Supplier<T> item)
    {
        //BankSystemMod.LOGGER.info("Registering block entity: " + name);
        return BLOCK_ENTITIES.register(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, name), item);
    }

}