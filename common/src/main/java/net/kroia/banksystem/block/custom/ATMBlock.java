package net.kroia.banksystem.block.custom;

import net.kroia.banksystem.entity.BankSystemEntities;
//import net.kroia.banksystem.entity.custom.ATMBlockEntity;
import net.kroia.banksystem.entity.custom.BankTerminalBlockEntity;
import net.kroia.banksystem.networking.packet.server_sender.SyncOpenGUIPacket;
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

public class ATMBlock  extends TerminalBlock/* implements EntityBlock*/ {

    public static final String NAME = "atm_block";

    /*@Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return BankSystemEntities.BANK_ATM_BLOCK_ENTITY.get().create(pos, state);
    }*/



    @Override
    public void openGui(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        //BlockEntity be = level.getBlockEntity(pos);
        //if (!(be instanceof ATMBlockEntity blockEntity))
        //    return;

        if (level.isClientSide())
            return;

        // open screen
        if (player instanceof ServerPlayer sPlayer) {
            //MenuProvider menuProvider = blockEntity.getMenuProvider();
            // Open the menu
            SyncBankDataPacket.sendPacket(sPlayer);
            SyncOpenGUIPacket.send_openATMScreen(sPlayer);
            //openExtendedMenu(sPlayer, menuProvider, (menu) -> {
                // Set the block position
            //    menu.writeBlockPos(pos);
            //});
        }
    }


   /* @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return type == BankSystemEntities.BANK_TERMINAL_BLOCK_ENTITY.get() ? BankTerminalBlockEntity::tick : null;
    }*/
}
