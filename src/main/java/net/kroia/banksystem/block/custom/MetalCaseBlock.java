package net.kroia.banksystem.block.custom;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public class MetalCaseBlock extends Block {
    public static final String NAME = "metal_case_block";

    public MetalCaseBlock() {
        super(Properties.copy(Blocks.IRON_BLOCK));
        this.registerDefaultState(this.defaultBlockState()); // Default facing
    }
    public MetalCaseBlock(Properties pProperties) {
        super(pProperties);
    }
}
