package net.kroia.banksystem;

import net.kroia.banksystem.entity.custom.BankTerminalBlockEntity;
import net.kroia.banksystem.screen.custom.BankTerminalScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

public class BankSystemClientHooks {
    public static InteractionResult openBankTerminalBlockScreen(BlockEntity entity, BlockPos pos, Inventory playerInventory)
    {
        if(entity instanceof BankTerminalBlockEntity bankTerminalBlockEntity)
        {
            //DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> Minecraft.getInstance().setScreen(new BankTerminalScreen(bankTerminalBlockEntity, playerInventory)));
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> Minecraft.getInstance().setScreen(new BankTerminalScreen(null, playerInventory, Component.translatable("container.bank_terminal"))));
        }
        else
        {
            BankSystemMod.LOGGER.warn("Block entity at position: "+pos+" is not of type BankTerminalBlockEntity");
            return InteractionResult.FAIL;
        }
        return InteractionResult.SUCCESS;
    }
}
