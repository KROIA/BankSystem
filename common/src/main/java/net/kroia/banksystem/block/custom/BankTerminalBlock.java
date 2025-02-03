package net.kroia.banksystem.block.custom;

import net.kroia.banksystem.entity.BankSystemEntities;
import net.kroia.banksystem.entity.custom.BankTerminalBlockEntity;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDataPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.UUID;

import static dev.architectury.registry.menu.MenuRegistry.openExtendedMenu;

public class BankTerminalBlock extends TerminalBlock implements EntityBlock {

    public static final String NAME = "bank_terminal_block";

    public BankTerminalBlock()
    {
        super();
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return BankSystemEntities.BANK_TERMINAL_BLOCK_ENTITY.get().create(pos, state);
    }

    @Override
    public void openGui(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof BankTerminalBlockEntity blockEntity))
            return;

        if (level.isClientSide())
            return;

        // open screen
        if (player instanceof ServerPlayer sPlayer) {
            MenuProvider menuProvider = blockEntity.getMenuProvider();
            // Open the menu
            SyncBankDataPacket.sendPacket(sPlayer);
            openExtendedMenu(sPlayer, menuProvider, (menu) -> {
                // Set the block position
                menu.writeBlockPos(pos);
            });
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof BankTerminalBlockEntity blockEntity) {
                HashMap<UUID, BankTerminalBlockEntity.TerminalInventory> inventories = blockEntity.getPlayerInventories();
                for (BankTerminalBlockEntity.TerminalInventory inventory : inventories.values()) {
                    for (int index = 0; index < inventory.getContainerSize(); index++) {
                        ItemStack stack = inventory.getItem(index);
                        var entity = new ItemEntity(level, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, stack);
                        level.addFreshEntity(entity);
                    }
                }
            }
        }

        super.onRemove(state, level, pos, newState, isMoving);
    }



    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return type == BankSystemEntities.BANK_TERMINAL_BLOCK_ENTITY.get() ? BankTerminalBlockEntity::tick : null;
    }
}