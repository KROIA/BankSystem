package net.kroia.banksystem.minecraft.block.custom;

import com.mojang.serialization.MapCodec;
import net.kroia.banksystem.minecraft.entity.custom.BankSystemDisplayBlockEntity;
import net.kroia.modutilities.gui.display.AbstractDisplayBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class BankSystemDisplayBlock extends AbstractDisplayBlock {

    public static final String NAME = "banksystem_display_block";

    private static final VoxelShape SHAPE_NORTH = Block.box(0, 0, 14, 16, 16, 16);
    private static final VoxelShape SHAPE_SOUTH = Block.box(0, 0, 0, 16, 16, 2);
    private static final VoxelShape SHAPE_EAST  = Block.box(0, 0, 0, 2, 16, 16);
    private static final VoxelShape SHAPE_WEST  = Block.box(14, 0, 0, 16, 16, 16);

    public static final MapCodec<BankSystemDisplayBlock> CODEC = simpleCodec(p -> new BankSystemDisplayBlock());

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    public BankSystemDisplayBlock() {
        super(BlockBehaviour.Properties.of().strength(2.0f).noOcclusion());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Direction facing = state.getValue(FACING);
        return switch (facing) {
            case NORTH -> SHAPE_NORTH;
            case SOUTH -> SHAPE_SOUTH;
            case EAST  -> SHAPE_EAST;
            case WEST  -> SHAPE_WEST;
            default -> SHAPE_NORTH;
        };
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BankSystemDisplayBlockEntity(pos, state);
    }
}
