package net.kroia.banksystem.item.custom.software;

import net.kroia.banksystem.block.BankSystemBlocks;
import net.kroia.banksystem.block.custom.TerminalBlock;
import net.kroia.banksystem.networking.ui.SyncOpenGUIPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

public class ATMSoftware extends Software{
    public static final String NAME = "atm_software";
    public ATMSoftware() {
        super();
    }

    @Override
    public TerminalBlock getProgrammedBlock()
    {
        return BankSystemBlocks.ATM_BLOCK.get();
    }


    @Override
    protected void onRightClickedServerSide(ServerPlayer player)
    {
        if(player.gameMode.getGameModeForPlayer() == GameType.CREATIVE)
            SyncOpenGUIPacket.send_openATMScreen(player);
    }
}
