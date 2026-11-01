package net.kroia.banksystem.minecraft.block.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.modutilities.RegistryCompat;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public class MetalCaseBlock extends Block {
    public static final String NAME = "metal_case_block";

    public MetalCaseBlock() {
        super(RegistryCompat.blockProperties(Properties.ofFullCopy(Blocks.IRON_BLOCK), BankSystemMod.id(NAME)));
        this.registerDefaultState(this.defaultBlockState()); // Default facing
    }
    public MetalCaseBlock(Properties pProperties) {
        super(pProperties);
    }
}
