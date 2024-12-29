package net.kroia.banksystem;

import net.kroia.banksystem.entity.custom.BankTerminalBlockEntity;
import net.kroia.banksystem.screen.custom.BankSystemSettingScreen;
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
    public static InteractionResult openBankSystemSettingScreen()
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> Minecraft.getInstance().setScreen(new BankSystemSettingScreen()));
        return InteractionResult.SUCCESS;
    }
}
