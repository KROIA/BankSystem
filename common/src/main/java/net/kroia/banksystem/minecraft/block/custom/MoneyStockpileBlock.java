package net.kroia.banksystem.minecraft.block.custom;

import net.kroia.banksystem.minecraft.entity.custom.MoneyStockpileBlockEntity;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public class MoneyStockpileBlock extends Block implements EntityBlock {

    public static final String NAME = "money_stockpile_block";

    // Copy properties from wool
    public MoneyStockpileBlock() {
        super(Properties.ofFullCopy(net.minecraft.world.level.block.Blocks.WHITE_WOOL));
    }



    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit)
    /*@Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                 Player player, InteractionHand hand, BlockHitResult hit)*/
    {
        if (level.isClientSide)
            return ItemInteractionResult.SUCCESS;

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof MoneyStockpileBlockEntity stockpile))
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

        ItemStack held = player.getItemInHand(hand);


        // Add items
        if (!held.isEmpty() && MoneyItem.isMoney(held)) {
            int added = stockpile.addItems(held);
            held.shrink(added);
            return ItemInteractionResult.CONSUME;
        }

        // Remove one item
        if (held.isEmpty() && stockpile.getCount() > 0) {
            ItemStack extracted = stockpile.removeItems();
            if (!player.addItem(extracted)) {
                player.drop(extracted, false);
            }
            return ItemInteractionResult.CONSUME;
        }

        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }


    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MoneyStockpileBlockEntity(pos, state);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock()) && !level.isClientSide()) {
            // Drop all items from the terminal inventories that have not been transfered to the bank
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof MoneyStockpileBlockEntity blockEntity) {
                blockEntity.dropItems();
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
    /*
    // removed since mc >= 1.20.4
    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        // Prevent pick block from giving anything
        return ItemStack.EMPTY;
    }*/
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(level, pos);
    }
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(level, pos);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE; // don't render vanilla model
    }
    @Override
    public boolean skipRendering(BlockState state, BlockState adjacentBlockState, Direction side) {
        return true;
    }

    @Override
    protected void spawnDestroyParticles(Level level, Player player, BlockPos pos, BlockState state) {

    }
    private VoxelShape getShape(BlockGetter level, BlockPos pos)
    {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof MoneyStockpileBlockEntity stockpile) {
            int usedGridSpaces = stockpile.getUsedGridSpaces();
            float height = stockpile.getHighestColumnHeight();

            int columns = usedGridSpaces % 6;
            int rows = usedGridSpaces / 6 + (columns == 0 ? 0 : 1);
            int maxColumns = Math.min(6, usedGridSpaces);

            double w = (16.0 * maxColumns) / 6;
            double d = (16.0 * rows) / 6;

            return switch (stockpile.getFacing()) {
                case EAST  -> Block.box(16 - d, 0, 0, 16, height, w);
                case SOUTH -> Block.box(16 - w, 0, 16 - d, 16, height, 16);
                case WEST  -> Block.box(0, 0, 16 - w, d, height, 16);
                default    -> Block.box(0, 0, 0, w, height, d); // NORTH
            };
        }
        return Shapes.empty();
    }
}
