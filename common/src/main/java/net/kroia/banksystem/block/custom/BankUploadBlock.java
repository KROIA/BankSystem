package net.kroia.banksystem.block.custom;

import net.kroia.banksystem.entity.custom.BankUploadBlockEntity;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankUploadDataPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

import static dev.architectury.registry.menu.MenuRegistry.openExtendedMenu;

public class BankUploadBlock extends Block implements EntityBlock {
    public enum ConnectionState implements StringRepresentable {
        CONNECTED("connected"),
        NOT_CONNECTED("not_connected");

        private final String name;

        ConnectionState(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }
    public enum SendingState implements StringRepresentable {
        SENDING("sending"),
        NOT_SENDING("not_sending");

        private final String name;

        SendingState(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }


    public static final String NAME = "bank_upload_block";
    public static final EnumProperty<ConnectionState> CONNECTION_STATE = EnumProperty.create("connection_state", ConnectionState.class);
    public static final EnumProperty<SendingState> SENDING_STATE = EnumProperty.create("sending_state", SendingState.class);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public BankUploadBlock() {
        super(Properties.ofFullCopy(net.minecraft.world.level.block.Blocks.CHEST).isRedstoneConductor((state, level, pos) -> false));
        this.registerDefaultState(this.stateDefinition.any().setValue(CONNECTION_STATE, ConnectionState.NOT_CONNECTED));
        this.registerDefaultState(this.stateDefinition.any().setValue(SENDING_STATE, SendingState.NOT_SENDING));
        this.registerDefaultState(this.defaultBlockState().setValue(FACING, Direction.NORTH)); // Default facing
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CONNECTION_STATE);
        builder.add(SENDING_STATE);
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BankUploadBlockEntity(pos, state);
    }


    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        // Drop items only if the block is replaced by a different block
        if (!state.is(newState.getBlock())) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof BankUploadBlockEntity storageBlockEntity) {
                storageBlockEntity.dropContents();
            }
            super.onRemove(state, level, pos, newState, isMoving); // Ensure the block gets properly removed
        }
    }


    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean isMoving) {
       if (level.isClientSide)
            return;
        level.scheduleTick(pos, this, 4);
        super.neighborChanged(state, level, pos, block, fromPos, isMoving); // Call super to handle other changes
    }

    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        boolean isPowered = level.hasNeighborSignal(pos);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if(blockEntity instanceof BankUploadBlockEntity storageBlockEntity) {
            storageBlockEntity.redstoneSignalChanged(isPowered);
        }
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(CONNECTION_STATE, ConnectionState.NOT_CONNECTED)
                .setValue(SENDING_STATE, SendingState.NOT_SENDING);
    }

    @Override
    public final @NotNull InteractionResult use(@NotNull BlockState state,
                                                @NotNull Level level,
                                                @NotNull BlockPos pos,
                                                @NotNull Player player,
                                                @NotNull InteractionHand hand,
                                                @NotNull BlockHitResult hit) {

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof BankUploadBlockEntity blockEntity))
            return InteractionResult.SUCCESS;

        if (level.isClientSide())
            return InteractionResult.SUCCESS;

        // open screen
        if (player instanceof ServerPlayer sPlayer) {
            UUID playerOwner = blockEntity.getPlayerOwner();
            if(playerOwner == null || playerOwner.equals(player.getUUID())) {
                MenuProvider menuProvider = blockEntity.getMenuProvider();
                // Open the menu
                SyncBankUploadDataPacket.sendPacket(sPlayer, blockEntity);
                openExtendedMenu(sPlayer, menuProvider, (menu) -> {
                    // Set the block position
                    menu.writeBlockPos(pos);
                });
            }
        }

        return InteractionResult.SUCCESS;
    }
}
