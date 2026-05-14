package net.kroia.banksystem.minecraft.block.custom;

import com.mojang.serialization.MapCodec;
import net.kroia.banksystem.minecraft.entity.custom.BankSystemDisplayBlockEntity;
import net.kroia.modutilities.gui.display.AbstractDisplayBlock;
import net.kroia.modutilities.gui.display.AbstractDisplayBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import static net.kroia.banksystem.minecraft.entity.custom.BankSystemDisplayBlockEntity.DisplayType;

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
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof BankSystemDisplayBlockEntity newEntity
                    && newEntity.getDisplayType() == DisplayType.NONE) {
                Direction facing = state.getValue(FACING);
                for (Direction dir : new Direction[]{
                        facing.getClockWise(), facing.getCounterClockWise(),
                        Direction.UP, Direction.DOWN}) {
                    BlockEntity neighbor = level.getBlockEntity(pos.relative(dir));
                    if (neighbor instanceof BankSystemDisplayBlockEntity other
                            && other.getDisplayType() != DisplayType.NONE
                            && other.getBlockState().getValue(FACING) == facing) {
                        newEntity.adoptConfig(other.getDisplayType(), other.getAccountNumber());
                        break;
                    }
                }
            }
        }
        super.onPlace(state, level, pos, oldState, movedByPiston);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof BankSystemDisplayBlockEntity displayBE) || !displayBE.isActive())
            return InteractionResult.PASS;

        AbstractDisplayBlockEntity controllerBase = displayBE.getControllerEntity();
        if (!(controllerBase instanceof BankSystemDisplayBlockEntity ctrl))
            return InteractionResult.PASS;

        if (ctrl.opensSyncedScreenOnUse()) {
            if (ctrl.getGui() == null) return InteractionResult.PASS;
            if (!level.isClientSide()) {
                if (!ctrl.tryAcquireEditor(player.getUUID())) {
                    player.displayClientMessage(
                            Component.literal("Display is being edited by another player."), true);
                    return InteractionResult.FAIL;
                }
            } else {
                ClientScreenHelper.openSyncedScreen(ctrl.getBlockPos());
            }
            return InteractionResult.SUCCESS;
        }

        if (level.isClientSide()) {
            ClientScreenHelper.openConfigScreen(ctrl.getBlockPos(), ctrl.getDisplayType(), ctrl.getAccountNumber());
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BankSystemDisplayBlockEntity(pos, state);
    }

    private static class ClientScreenHelper {
        static void openSyncedScreen(BlockPos controllerPos) {
            net.kroia.modutilities.gui.display.client.DisplayInteractionScreen.open(controllerPos);
        }

        static void openConfigScreen(BlockPos pos, BankSystemDisplayBlockEntity.DisplayType type, int account) {
            net.minecraft.client.Minecraft.getInstance().setScreen(
                    new net.kroia.banksystem.screen.custom.DisplayConfigScreen(pos, type, account));
        }
    }
}
