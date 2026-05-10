package net.kroia.banksystem.minecraft.block.custom;

import net.kroia.banksystem.networking.ui.SyncOpenGUIPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class ATMBlock  extends TerminalBlock {

    public static final String NAME = "atm_block";


    @Override
    public void openGui(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {

        if (level.isClientSide())
            return;

        // open screen
        if (player instanceof ServerPlayer sPlayer) {
            SyncOpenGUIPacket.send_openATMScreen(sPlayer);
        }
    }
}
