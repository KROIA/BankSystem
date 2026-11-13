package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.kroia.modutilities.UtilitiesPlatform;
import net.kroia.modutilities.networking.multi_server.MultiServerManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class MultiServerUtils {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }


    public static boolean checkConnectionToMaster()
    {
        return MultiServerManager.isRunning() && MultiServerManager.isSlave();
    }
    public static boolean checkConnectionToMaster(UUID executor)
    {
        if(checkConnectionToMaster())
            return true;
        ServerPlayer player = ServerPlayerUtilities.getOnlinePlayer(executor);
        if(player != null)
            player.sendSystemMessage(getNoMasterMessage());
        return false;
    }

    /** Chat line shown when a slave refuses an action because it has no connection to its master. */
    public static Component getNoMasterMessage()
    {
        return Component.translatable("message."+BankSystemMod.MOD_ID+".not_connected_to_master");
    }

    /** BS-272: red chat line for a bank action the master refuses because it does not trust this slave. */
    public static Component getUntrustedSlaveMessage()
    {
        return Component.translatable("gui."+BankSystemMod.MOD_ID+".bank_account_management_screen.untrusted_server").withStyle(ChatFormatting.RED);
    }

    /**
     * BS-272: the master answers an untrusted slave's account reads (e.g. {@code hasPermissionAsync}) with false and
     * refuses its writes, so a refusal there looks like a missing permission. On a connected slave this asks the master
     * ({@code isSlaveServerTrustedAsync("")}, same as the command trust gate) and, if it does not trust this slave,
     * sends {@code player} the red untrusted line.
     *
     * @return true if the untrusted line was sent; false on the master, without a master link, on a trusted slave or
     *         without an answer, so the caller shows its own refusal message. Completes on the server main thread.
     */
    public static CompletableFuture<Boolean> notifyIfUntrustedSlave(UUID player)
    {
        if(!BACKEND_INSTANCES.isSlaveServer || !checkConnectionToMaster())
            return CompletableFuture.completedFuture(false);
        CompletableFuture<Boolean> sent = new CompletableFuture<>();
        BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().isSlaveServerTrustedAsync("").whenComplete((trusted, failure) -> {
            Runnable reply = () -> {
                boolean untrusted = failure == null && !Boolean.TRUE.equals(trusted);
                ServerPlayer online = ServerPlayerUtilities.getOnlinePlayer(player);
                if(untrusted && online != null)
                    online.sendSystemMessage(getUntrustedSlaveMessage());
                sent.complete(untrusted);
            };
            MinecraftServer server = UtilitiesPlatform.getServer();
            if(server != null) server.execute(reply);
            else reply.run();
        });
        return sent;
    }

    public static boolean canInteractWithBankSystem()
    {
        if(BACKEND_INSTANCES.isSlaveServer)
        {
            return checkConnectionToMaster();
        }
        return true;
    }
    public static boolean canInteractWithBankSystem(UUID executor)
    {
        if(BACKEND_INSTANCES.isSlaveServer)
        {
            return checkConnectionToMaster(executor);
        }
        return true;
    }

}
