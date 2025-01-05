package net.kroia.banksystem.item.custom.software;

import net.kroia.banksystem.BankSystemClientHooks;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.block.BankSystemBlocks;
import net.kroia.banksystem.block.custom.TerminalBlock;
import net.kroia.banksystem.networking.packet.server_sender.SyncOpenGUIPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.UUID;

public class BankingSoftware extends Software {
    public static final String NAME = "banking_software";
    public BankingSoftware() {
        super();
    }

    @Override
    public TerminalBlock getProgrammedBlock()
    {
        return BankSystemBlocks.BANK_TERMINAL_BLOCK.get();
    }


    @Override
    protected void onRightClickedClientSide()
    {
        if(Minecraft.getInstance().player.hasPermissions(2))
            BankSystemClientHooks.openBankAccountScreen(Minecraft.getInstance().player.getUUID());
    }


    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        Level level = player.level;

        if (target instanceof Player && player instanceof ServerPlayer serverPlayer) {
            if (!level.isClientSide()) {

                if(serverPlayer.hasPermissions(2)) {
                    UUID targetUUID = target.getUUID();
                    SyncOpenGUIPacket.send_openBankAccountScreen(serverPlayer, targetUUID);
                }
            }
            return InteractionResult.SUCCESS;
        }

        return super.interactLivingEntity(stack, player, target, hand);
    }


}
