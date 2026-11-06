package net.kroia.banksystem.minecraft.item.custom.software;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.api.bankaccount.IAsyncBankAccount;
import net.kroia.banksystem.minecraft.block.BankSystemBlocks;
import net.kroia.banksystem.minecraft.block.custom.TerminalBlock;
import net.kroia.banksystem.networking.ui.SyncOpenGUIPacket;
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
import java.util.concurrent.CompletableFuture;

public class BankingSoftware extends Software {


    public static final String NAME = "banking_software";
    //private long cooldownTimer;
    public BankingSoftware() {
        super(BankSystemMod.id(NAME));
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
            CompletableFuture<IAsyncBankAccount> bankAccountFuture = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().getPersonalBankAccountAsync(player.getUUID());
            bankAccountFuture.thenAcceptAsync((bankAccount) -> {
                if(bankAccount != null) {
                    SyncOpenGUIPacket.send_openBankAccountScreen(player, player.getUUID(), bankAccount.getAccountNumberAsync(), true);
                }
            });
        }
    }


    @Override
    public @NotNull InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        Level level = player.level();

        if (target instanceof Player && player instanceof ServerPlayer serverPlayer) {
            if (!level.isClientSide()) {

                if(net.kroia.modutilities.PermissionCompat.hasPermission(serverPlayer, net.kroia.banksystem.util.BankSystemPermissions.SOFTWARE, net.kroia.banksystem.util.BankSystemPermissions.FALLBACK_LEVEL) && serverPlayer.gameMode.getGameModeForPlayer() == GameType.CREATIVE) {
                    UUID targetUUID = target.getUUID();
                    // Task #103: another player's account also requires the BankSystem admin flag.
                    BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().isBanksystemAdminAsync(serverPlayer.getUUID())
                            .thenCompose(isAdmin -> Boolean.TRUE.equals(isAdmin)
                                    ? BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().getPersonalBankAccountAsync(targetUUID)
                                    : CompletableFuture.<IAsyncBankAccount>completedFuture(null))
                            .thenAcceptAsync((bankAccount) -> {
                        if(bankAccount != null) {
                            SyncOpenGUIPacket.send_openBankAccountScreen(serverPlayer, targetUUID, bankAccount.getAccountNumberAsync(), true);
                        }
                    });
                    return InteractionResult.CONSUME;
                }
            }
            return InteractionResult.CONSUME;
        }

        return super.interactLivingEntity(stack, player, target, hand);
    }
}
