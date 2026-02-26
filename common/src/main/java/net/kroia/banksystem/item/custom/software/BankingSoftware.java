package net.kroia.banksystem.item.custom.software;

import net.kroia.banksystem.api.IBankAccount;
import net.kroia.banksystem.block.BankSystemBlocks;
import net.kroia.banksystem.block.custom.TerminalBlock;
import net.kroia.banksystem.networking.packet.server_sender.SyncOpenGUIPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class BankingSoftware extends Software {


    public static final String NAME = "banking_software";
    //private long cooldownTimer;
    public BankingSoftware() {
        super();
    }

    @Override
    public TerminalBlock getProgrammedBlock()
    {
        return BankSystemBlocks.BANK_TERMINAL_BLOCK.get();
    }


    @Override
    protected void onRightClickedServerSide(ServerPlayer player)
    {
        if(player.gameMode.getGameModeForPlayer() == GameType.CREATIVE) {
            IBankAccount bankAccount = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getPersonalBankAccount(player.getUUID());
            if(bankAccount != null) {
                /*long currentTime = System.currentTimeMillis();
                if(currentTime - cooldownTimer < 500)
                {
                    return;
                }
                cooldownTimer = System.currentTimeMillis();*/
                SyncOpenGUIPacket.send_openBankAccountScreen(player, player.getUUID(), bankAccount.getAccountNumber(), true);

            }
        }
    }


    @Override
    public @NotNull InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        Level level = player.level();

        if (target instanceof Player && player instanceof ServerPlayer serverPlayer) {
            if (!level.isClientSide()) {

                if(serverPlayer.hasPermissions(2) && serverPlayer.gameMode.getGameModeForPlayer() == GameType.CREATIVE) {
                    UUID targetUUID = target.getUUID();
                    IBankAccount bankAccount = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getPersonalBankAccount(targetUUID);
                    if(bankAccount != null) {
                        /*long currentTime = System.currentTimeMillis();
                        if(currentTime - cooldownTimer < 500)
                        {
                            return InteractionResult.CONSUME;
                        }
                        cooldownTimer = System.currentTimeMillis();*/
                        SyncOpenGUIPacket.send_openBankAccountScreen(serverPlayer, targetUUID, bankAccount.getAccountNumber(), true);
                        // Prevent block interaction from firing after entity interaction
                        return InteractionResult.CONSUME;
                    }
                }
            }
            return InteractionResult.CONSUME;
        }

        return super.interactLivingEntity(stack, player, target, hand);
    }
}
