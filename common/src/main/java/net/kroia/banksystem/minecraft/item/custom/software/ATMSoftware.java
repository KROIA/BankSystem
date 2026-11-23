package net.kroia.banksystem.minecraft.item.custom.software;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.minecraft.block.BankSystemBlocks;
import net.kroia.banksystem.minecraft.block.custom.TerminalBlock;
import net.kroia.banksystem.networking.ui.SyncOpenGUIPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

public class ATMSoftware extends Software{
    public static final String NAME = "atm_software";
    public ATMSoftware() {
        super(BankSystemMod.id(NAME));
    }

    @Override
    public TerminalBlock getProgrammedBlock()
    {
        return BankSystemBlocks.ATM_BLOCK.get();
    }


    @Override
    protected void onRightClickedServerSide(ServerPlayer player)
    {
        if(player.gameMode.getGameModeForPlayer() == GameType.CREATIVE) {
            net.kroia.banksystem.minecraft.block.custom.ATMBlock.setOpenedAt(player.getUUID(), null); // BS-258: not at a block
            SyncOpenGUIPacket.send_openATMScreen(player);
        }
    }
}
