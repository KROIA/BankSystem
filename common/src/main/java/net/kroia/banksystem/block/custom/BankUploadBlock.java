package net.kroia.banksystem.block.custom;

import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.block.BankSystemBlocks;
import net.kroia.banksystem.entity.custom.BankUploadEntity;
import net.kroia.banksystem.item.custom.software.Software;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.modutilities.PlayerUtilities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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


    public static final String NAME = "bank_upload_block";
    public static final EnumProperty<ConnectionState> CONNECTION_STATE = EnumProperty.create("connection_state", ConnectionState.class);

    private boolean wasPowered = false; // Track if the block was previously powered

    public BankUploadBlock() {
        super(Properties.copy(net.minecraft.world.level.block.Blocks.CHEST));
        this.registerDefaultState(this.stateDefinition.any().setValue(CONNECTION_STATE, ConnectionState.NOT_CONNECTED));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CONNECTION_STATE);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BankUploadEntity(pos, state);
    }


    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        // Drop items only if the block is replaced by a different block
        if (!state.is(newState.getBlock())) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof BankUploadEntity storageBlockEntity) {
                storageBlockEntity.dropContents();
            }
            super.onRemove(state, level, pos, newState, isMoving); // Ensure the block gets properly removed
        }
    }


    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean isMoving) {
        // Check if the block received redstone power
        // Get direction from that neighbor to this block
        Direction direction = Direction.fromDelta(fromPos.getX() - pos.getX(), fromPos.getY() - pos.getY(), fromPos.getZ() - pos.getZ());
        boolean isPowered = level.hasSignal(pos, direction);

        // If the power state has changed, perform your action
        if (isPowered != wasPowered) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if(blockEntity instanceof BankUploadEntity storageBlockEntity) {
                storageBlockEntity.setSendingEnabled(isPowered);
            }
            // Update the previous power state
            wasPowered = isPowered;
        }

        super.neighborChanged(state, level, pos, block, fromPos, isMoving); // Call super to handle other changes
    }


    @Override
    public final @NotNull InteractionResult use(@NotNull BlockState state,
                                                @NotNull Level level,
                                                @NotNull BlockPos pos,
                                                @NotNull Player player,
                                                @NotNull InteractionHand hand,
                                                @NotNull BlockHitResult hit) {
        // Get the item in the player's hand
        ItemStack itemInHand = player.getItemInHand(hand);
        //Item item = itemInHand.getItem();

        //Software softwareItem = null;
        //if(item instanceof Software) {
        //    softwareItem = (Software)item;
        //}
        if (!level.isClientSide/* && softwareItem != null*/)
        {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            ServerPlayer serverPlayer = (ServerPlayer) player;
            if(blockEntity instanceof BankUploadEntity storageBlockEntity) {
                if(storageBlockEntity.getPlayerOwner() == null) {
                    storageBlockEntity.setPlayerOwner(player.getUUID());
                    PlayerUtilities.printToClientConsole(serverPlayer, BankSystemTextMessages.getBankUploadBlockOwnedByYouNowMessage());
                }
                else
                {
                    if(!storageBlockEntity.getPlayerOwner().equals(player.getUUID()))
                    {
                        PlayerUtilities.printToClientConsole(serverPlayer, BankSystemTextMessages.getBankUploadBlockAlreadyOwnedByYouMessage());
                    }
                    else {
                        String playerName = "unknown";
                        if(ServerBankManager.getUser(storageBlockEntity.getPlayerOwner()) != null)
                            playerName = ServerBankManager.getUser(storageBlockEntity.getPlayerOwner()).getPlayerName();
                        PlayerUtilities.printToClientConsole(serverPlayer, BankSystemTextMessages.getBankUploadBlockAlreadyOwnedByPlayerMessage(playerName));
                    }
                }
            }
            /*TerminalBlock programmedBlock = softwareItem.getProgrammedBlock();
            if(programmedBlock == null)
            {
                // Replace the block with the programmed block
                level.setBlockAndUpdate(pos, BankSystemBlocks.TERMINAL_BLOCK.get().defaultBlockState().setValue(FACING, state.getValue(FACING)));
            }
            else
            {
                // Replace the block with the programmed block
                level.setBlockAndUpdate(pos, programmedBlock.defaultBlockState().setValue(FACING, state.getValue(FACING)));
            }*/
            return InteractionResult.SUCCESS;
        }
        /*
        if(softwareItem == null) {
            openGui(state, level, pos, player, hand, hit);
        }*/
        return InteractionResult.SUCCESS;
    }
}
