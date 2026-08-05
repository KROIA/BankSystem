package net.kroia.banksystem.minecraft.block.custom;

// TODO_ART Task #47 (v2.0.8) — placeholder model reuses iron_block texture; dedicated art pending.

import net.kroia.banksystem.minecraft.entity.BankSystemEntities;
import net.kroia.banksystem.minecraft.entity.custom.ShareStamperBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static dev.architectury.registry.menu.MenuRegistry.openExtendedMenu;

public class ShareStamperBlock extends Block implements EntityBlock {

    public static final String NAME = "share_stamper";
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public ShareStamperBlock() {
        super(Properties.ofFullCopy(Blocks.IRON_BLOCK)
                .requiresCorrectToolForDrops()
                .strength(3.5f, 6.0f)
                .isRedstoneConductor((s, l, p) -> false));
        this.registerDefaultState(this.defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ShareStamperBlockEntity(pos, state);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof ShareStamperBlockEntity s) s.dropContents();
            super.onRemove(state, level, pos, newState, isMoving);
        }
    }

    @Override
    protected final @NotNull InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                              Player player, BlockHitResult hitResult) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof ShareStamperBlockEntity stamper)) return InteractionResult.SUCCESS;

        if (stamper.getBoundCompanyId() < 0) {
            player.sendSystemMessage(Component.translatable(
                    "gui.banksystem.share_stamper.bind_prompt"));
            return InteractionResult.SUCCESS;
        }
        if (!stamper.hasManagePermission(player.getUUID())) {
            String companyName = stamper.getBoundCompanyName();
            player.sendSystemMessage(Component.translatable(
                    "gui.banksystem.share_stamper.no_permission", companyName));
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer sPlayer) {
            openExtendedMenu(sPlayer, stamper, buf -> buf.writeBlockPos(pos));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        return type == BankSystemEntities.SHARE_STAMPER_BLOCK_ENTITY.get()
                ? ShareStamperBlockEntity::tick : null;
    }
}
