package net.kroia.banksystem.command;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.ISyncServerBank;
import net.kroia.banksystem.api.bankaccount.IAsyncBankAccount;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.api.command.IAsyncBankSystemCommandHandler;
import net.kroia.banksystem.api.command.IServerBankSystemCommandHandler;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.bank.ServerBank;
import net.kroia.banksystem.banking.bankaccount.AsyncBankAccount;
import net.kroia.banksystem.item.custom.money.MoneyItem;
import net.kroia.banksystem.networking.packet.server_sender.SyncOpenGUIPacket;
import net.kroia.banksystem.networking.packet.server_server.ClientConsoleMessagePacket;
import net.kroia.banksystem.networking.packet.server_server.ServerInfoRequest;
import net.kroia.banksystem.networking.packet.server_server.ServerNetworkInfoRequest;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.kroia.modutilities.UtilitiesPlatform;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ServerBankSystemCommandHandler implements IServerBankSystemCommandHandler, IAsyncBankSystemCommandHandler {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    @Override
    public CompletableFuture<Boolean> banksystem_manage_async(@NotNull UUID executor) {
        if(!isPlayerAdmin(executor))
            return CompletableFuture.completedFuture(false);
        ServerPlayer player = ServerPlayerUtilities.getOnlinePlayer(executor);
        if(BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().isBanksystemAdmin(executor))
        {
            // Open screen for settings GUI
            SyncOpenGUIPacket.send_openBankSystemManageScreen(player);
            return CompletableFuture.completedFuture(true);
        }
        else
        {
            ServerPlayerUtilities.printToClientConsole(player,"This command is only for BankSystem admins!");
            return CompletableFuture.completedFuture(false);
        }
    }




    @Override
    public CompletableFuture<Boolean> banksystem_testScreen_async(@NotNull UUID executor) {
        ServerPlayer player = ServerPlayerUtilities.getOnlinePlayer(executor);
        if(player != null) {
            SyncOpenGUIPacket.send_openTestScreen(player);
            return CompletableFuture.completedFuture(true);
        }
        return CompletableFuture.completedFuture(false);
    }





    @Override
    public boolean banksystem_setBankSystemAdminMode_user(@NotNull UUID executor, String userName, boolean isAdmin) {
        //if(!isPlayerAdmin(executor))
        //    return false;
        @Nullable UUID playerUUID = tryGetPlayerUUID(userName);
        if(playerUUID == null)
        {
            sendMessage(executor, "No UUID found for Player: "+userName);
            return false;
        }
        if(BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().setBanksystemAdminMode(playerUUID, isAdmin))
        {
            sendMessage(executor, "Banksystem admin mode set to: "+(isAdmin?"ON":"OFF") + " for player: "+userName);
            if(!executor.equals(playerUUID))
                sendMessage(playerUUID, "Banksystem admin mode set to: "+(isAdmin?"ON":"OFF") + " for player: "+userName);
            return true;
        }
        return false;
    }
    @Override
    public CompletableFuture<Boolean> banksystem_setBankSystemAdminMode_user_async(@NotNull UUID executor, String userName, boolean isAdmin) {
        return CompletableFuture.completedFuture(banksystem_setBankSystemAdminMode_user(executor, userName, isAdmin));
    }



    @Override
    public boolean banksystem_setBankSystemAdminMode(@NotNull UUID executor, boolean isAdmin) {
        return banksystem_setBankSystemAdminMode_user(executor, tryGetPlayerName(executor), isAdmin);
    }
    @Override
    public CompletableFuture<Boolean> banksystem_setBankSystemAdminMode_async(@NotNull UUID executor, boolean isAdmin) {
        return CompletableFuture.completedFuture(banksystem_setBankSystemAdminMode(executor, isAdmin));
    }




    @Override
    public boolean banksystem_allowItem(@NotNull UUID executor, ItemID itemID) {
        if(!isPlayerAdmin(executor))
            return false;
        if (!itemID.isValid()) {
            sendMessage(executor, BankSystemTextMessages.getInvalidItemIDMessage(itemID.getName()));
            return false;
        }

        if(BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().allowItemID(itemID)) {
            sendMessage(executor, BankSystemTextMessages.getItemNowAllowedMessage(itemID.getName(), ServerBank.getFormattedAmountStatic(1)));
            return true;
        }
        else {
            sendMessage(executor, BankSystemTextMessages.getItemNowAllowedFailedMessage(itemID.getName()));
            return false;
        }
    }
    @Override
    public CompletableFuture<Boolean> banksystem_allowItem_async(@NotNull UUID executor, ItemID itemID) {
        return CompletableFuture.completedFuture(banksystem_allowItem(executor,itemID));
    }







    @Override
    public boolean banksystem_disallowItem(@NotNull UUID executor, ItemID itemID) {
        if(!isPlayerAdmin(executor))
            return false;
        if (!itemID.isValid()) {
            sendMessage(executor, BankSystemTextMessages.getInvalidItemIDMessage(itemID.getName()));
            return false;
        }

        if(BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().disallowItemID(itemID)) {
            sendMessage(executor, BankSystemTextMessages.getItemNotAllowedMessage(itemID.getName()));
            return true;
        }
        else {
            sendMessage(executor, BankSystemTextMessages.getItemNotAllowedFailedMessage(itemID.getName()));
            return false;
        }
    }
    @Override
    public CompletableFuture<Boolean> banksystem_disallowItem_async(@NotNull UUID executor, ItemID itemID) {
        return CompletableFuture.completedFuture(banksystem_disallowItem(executor,itemID));
    }

    @Override
    public CompletableFuture<Boolean> banksystem_serverInfo_async(@NotNull UUID executor)
    {
        MinecraftServer server = UtilitiesPlatform.getServer();
        if(server == null)
            return CompletableFuture.completedFuture(false);

        ServerPlayerUtilities.printToClientConsole(executor, ServerInfoRequest.createInfo(server).toString());
        return CompletableFuture.completedFuture(true);
    }
    @Override
    public CompletableFuture<Boolean> banksystem_serverNetworkInfo_async(@NotNull UUID executor)
    {
        MinecraftServer server = UtilitiesPlatform.getServer();
        if(server == null)
            return CompletableFuture.completedFuture(false);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        ServerNetworkInfoRequest.sendRequest().thenAccept(serverNetworkInfo -> {
            StringBuilder builder = new StringBuilder();
            builder.append("§8============================================\n");
            List<ServerInfoRequest.ServerInfo> servers = serverNetworkInfo.servers();
            for(int i = 0; i < servers.size(); i++)
            {
                builder.append(servers.get(i));
                if(i < servers.size()-1)
                    builder.append("\n");
            }
            builder.append("\n§8============================================");
            ServerPlayerUtilities.printToClientConsole(executor, builder.toString());
            future.complete(true);
        });
        return future;
    }


    @Override
    public boolean money_add(@NotNull UUID executor, float amount) {
        String userName = tryGetPlayerName(executor);
        return money_add_user(executor, userName, amount);
    }
    @Override
    public CompletableFuture<Boolean> money_add_async(@NotNull UUID executor, float amount) {
        return CompletableFuture.completedFuture(money_add(executor, amount));
    }





    @Override
    public boolean money_add_user(@NotNull UUID executor, String userName, float amount) {
        if(!isPlayerAdmin(executor))
            return false;
        ISyncServerBankManager bankManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        ISyncServerBank bank = bankManager.getOrCreatePersonalBank(userName, MoneyItem.getItemID());
        if(bank == null)
        {
            ClientConsoleMessagePacket.sendMessageFromMaster(executor, BankSystemTextMessages.getBankNotFoundMessage(userName, MoneyItem.getName()));
            return false;
        }

        BankStatus status = bank.depositReal(amount);
        if(status != BankStatus.SUCCESS){
            sendMessage(executor,
                    BankSystemTextMessages.getCantAddMessage(
                            bank.getFormattedAmount(amount),
                            MoneyItem.getName(),
                            userName,
                            status.toString()));
            return false;
        }
        return true;
    }
    @Override
    public CompletableFuture<Boolean> money_add_user_async(@NotNull UUID executor, String userName, float amount) {
        return CompletableFuture.completedFuture(money_add_user(executor, userName, amount));
    }





    @Override
    public boolean money_set(@NotNull UUID executor, float amount) {
        return money_set_user(executor, tryGetPlayerName(executor), amount);
    }
    @Override
    public CompletableFuture<Boolean> money_set_async(@NotNull UUID executor, float amount) {
        return CompletableFuture.completedFuture(money_set(executor, amount));
    }





    @Override
    public boolean money_set_user(@NotNull UUID executor, String userName, float amount) {
        if(!isPlayerAdmin(executor))
            return false;
        ISyncServerBankManager bankManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        ISyncServerBank bank = bankManager.getOrCreatePersonalBank(userName, MoneyItem.getItemID());
        if(bank == null)
        {
            sendMessage(executor, BankSystemTextMessages.getBankNotFoundMessage(userName, MoneyItem.getName()));
            return false;
        }
        bank.setRealBalance(amount);
        UUID bankOwner = Objects.requireNonNull(bankManager.getUserByName(userName)).getUUID();
        String message = BankSystemTextMessages.getSetBalanceMessage(bank.getFormattedAmount(amount), MoneyItem.getName(), userName);
        sendMessage(executor, message);
        if(!executor.equals(bankOwner))
            sendMessage(bankOwner, message);
        return true;
    }
    @Override
    public CompletableFuture<Boolean> money_set_user_async(@NotNull UUID executor, String userName, float amount) {
        return CompletableFuture.completedFuture(money_set_user(executor, userName, amount));
    }




    @Override
    public boolean money_remove(@NotNull UUID executor, float amount) {
        return money_remove_user(executor, tryGetPlayerName(executor), amount);
    }
    @Override
    public CompletableFuture<Boolean> money_remove_async(@NotNull UUID executor, float amount) {
        return CompletableFuture.completedFuture(money_remove(executor, amount));
    }





    @Override
    public boolean money_remove_user(@NotNull UUID executor, String userName, float amount) {
        if(!isPlayerAdmin(executor))
            return false;
        ISyncServerBankManager bankManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        ISyncServerBank bank = bankManager.getOrCreatePersonalBank(userName, MoneyItem.getItemID());
        if(bank == null)
        {
            sendMessage(executor, BankSystemTextMessages.getBankNotFoundMessage(userName, MoneyItem.getName()));
            return false;
        }
        if(bank.getBalance() >= amount) {
            bank.withdrawReal(amount);
        }
        else {
            sendMessage(executor, BankSystemTextMessages.getNotEnoughInAccountMessage(userName, MoneyItem.getName()));
            return false;
        }
        return true;
    }
    @Override
    public CompletableFuture<Boolean> money_remove_user_async(@NotNull UUID executor, String userName, float amount) {
        return CompletableFuture.completedFuture(money_remove_user(executor, userName, amount));
    }





    @Override
    public boolean money_send_user(@NotNull UUID executor, String toUserName, float amount) {
        String fromUserName = tryGetPlayerName(executor);
        ISyncServerBankManager bankManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        ISyncServerBank fromBank = bankManager.getOrCreatePersonalBank(fromUserName, MoneyItem.getItemID());
        if(fromBank == null)
        {
            sendMessage(executor, BankSystemTextMessages.getBankNotFoundMessage(fromUserName, MoneyItem.getName()));
            return false;
        }
        ISyncServerBank toBank = bankManager.getOrCreatePersonalBank(toUserName, MoneyItem.getItemID());
        if(toBank == null)
        {
            sendMessage(executor, BankSystemTextMessages.getBankNotFoundMessage(toUserName, MoneyItem.getName()));
            return false;
        }

        if(fromBank == toBank)
        {
            sendMessage(executor, BankSystemTextMessages.getTransferToSameAccountMessage(MoneyItem.getName()));
            return false;
        }
        BankStatus status = fromBank.transfer(fromBank.convertToRawAmount(amount), toBank);
        if(status != BankStatus.SUCCESS) {
            if (fromBank.getBalance() < amount)
                sendMessage(executor, BankSystemTextMessages.getNotEnoughMoneyForTransfer(fromUserName, toUserName, fromBank.getFormattedAmount(amount), MoneyItem.getName()));
            else
                sendMessage(executor, BankSystemTextMessages.getTransferFailedMessage(fromUserName, toUserName, fromBank.getFormattedAmount(amount), MoneyItem.getName(), status.toString()));
            return false;
        }
        return true;
    }
    @Override
    public CompletableFuture<Boolean> money_send_user_async(@NotNull UUID executor, String toUserName, float amount) {
        return CompletableFuture.completedFuture(money_send_user(executor, toUserName, amount));
    }




    @Override
    public boolean money_circulation(@NotNull UUID executor) {
        double circulation = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().getRealMoneyCirculation();
        sendMessage(executor, BankSystemTextMessages.getCirculationMessage(ServerBank.getFormattedAmountStatic(circulation), MoneyItem.getName()));
        return true;
    }
    @Override
    public CompletableFuture<Boolean> money_circulation_async(@NotNull UUID executor) {
        return CompletableFuture.completedFuture(money_circulation(executor));
    }
















    @Override
    public boolean bank_enableNotifications(@NotNull UUID executor) {
        User bankUser = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().getUserByUUID(executor);
        if(bankUser == null)
        {
            sendMessage(executor, BankSystemTextMessages.getUserNotFoundMessage(tryGetPlayerName(executor)));
            return false;
        }
        bankUser.setEnableBankNotifications(true);
        sendMessage(executor, BankSystemTextMessages.getBankUserNotificationEnabledMessage());
        return true;
    }
    @Override
    public CompletableFuture<Boolean> bank_enableNotifications_async(@NotNull UUID executor) {
        return CompletableFuture.completedFuture(bank_enableNotifications(executor));
    }





    @Override
    public boolean bank_disableNotifications(@NotNull UUID executor) {
        User bankUser = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().getUserByUUID(executor);
        if(bankUser == null)
        {
            sendMessage(executor, BankSystemTextMessages.getUserNotFoundMessage(tryGetPlayerName(executor)));
            return false;
        }
        bankUser.setEnableBankNotifications(false);
        sendMessage(executor, BankSystemTextMessages.getBankUserNotificationEnabledMessage());
        return true;
    }
    @Override
    public CompletableFuture<Boolean> bank_disableNotifications_async(@NotNull UUID executor) {
        return CompletableFuture.completedFuture(bank_disableNotifications(executor));
    }



    @Override
    public boolean bank_manage(@NotNull UUID executor)
    {
        IServerBankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().getOrCreatePersonalBankAccount(executor);
        boolean isAdmin = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().isBanksystemAdmin(executor);

        if(account == null)
        {
            return false;
        }
        ServerPlayer player = ServerPlayerUtilities.getOnlinePlayer(executor);
        SyncOpenGUIPacket.send_openBankAccountScreen(player, player.getUUID(), account.getAccountNumberAsync(), isAdmin);
        return true;
    }
    @Override
    public CompletableFuture<Boolean> bank_manage_async(@NotNull UUID executor)
    {
        return CompletableFuture.completedFuture(bank_manage(executor));
    }




    @Override
    public boolean bank_manage_account(@NotNull UUID executor, String accountName)
    {
        IServerBankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().getBankAccountByName(accountName);
        if(account == null)
            return false;

        boolean isAdmin = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().isBanksystemAdmin(executor);
        boolean hasPermission = account.hasUser(executor);
        if(!hasPermission && !isAdmin)
            return false;

        ServerPlayer player = ServerPlayerUtilities.getOnlinePlayer(executor);
        if(player != null)
            SyncOpenGUIPacket.send_openBankAccountScreen(player, player.getUUID(), account.getAccountNumberAsync(), isAdmin);
        return true;
    }
    @Override
    public CompletableFuture<Boolean> bank_manage_account_async(@NotNull UUID executor, String accountName)
    {
        return CompletableFuture.completedFuture(bank_manage_account(executor, accountName));
    }




    @Override
    public boolean bank_manage_account(@NotNull UUID executor, int accountNr)
    {
        IServerBankManager manager =  BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        IServerBankAccount account = manager.getBankAccount(accountNr);
        if(account == null)
            return false;

        boolean isAdmin = manager.isBanksystemAdmin(executor);
        boolean hasPermission = account.hasUser(executor);
        if(!hasPermission && !isAdmin)
            return false;

        ServerPlayer player = ServerPlayerUtilities.getOnlinePlayer(executor);
        if(player != null)
            SyncOpenGUIPacket.send_openBankAccountScreen(player, player.getUUID(), account.getAccountNumberAsync(), isAdmin);
        return true;
    }
    @Override
    public CompletableFuture<Boolean> bank_manage_account_async(@NotNull UUID executor, int accountNr)
    {
        return CompletableFuture.completedFuture(bank_manage_account(executor, accountNr));
    }




    @Override
    public int bank_create(@NotNull UUID executor, String accountName)
    {
        User user = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().getUserByUUID(executor);
        if(user == null)
        {
            sendMessage(executor, BankSystemTextMessages.getUserNotFoundMessage(tryGetPlayerName(executor)));
            return 0;
        }
        IServerBankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().createBankAccount(accountName);
        if(account == null)
        {
            sendMessage(executor, BankSystemTextMessages.getCantCreateBankAccountMessage());
            return 0;
        }
        boolean isAdmin = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().isBanksystemAdmin(executor);
        account.addUser(user, BankPermission.getAllPermissions());
        ServerPlayer player = ServerPlayerUtilities.getOnlinePlayer(executor);
        if(player != null)
        {
            SyncOpenGUIPacket.send_openBankAccountScreen(player, player.getUUID(), account.getAccountNumber(), isAdmin);
            //SyncOpenGUIPacket.send_openBankAccountScreen(player, player.getUUID(), account.getAccountNumber(), isAdmin);
        }
        return account.getAccountNumberAsync();
    }
    @Override
    public CompletableFuture<IAsyncBankAccount> bank_create_async(@NotNull UUID executor, String accountName)
    {
        return CompletableFuture.completedFuture(AsyncBankAccount.createSlaveServerBank(bank_create(executor, accountName)));
    }


    public static String tryGetPlayerName(UUID player)
    {

        if(UtilitiesPlatform.getServer() != null) {
            ServerPlayer serverPlayer = ServerPlayerUtilities.getOnlinePlayer(player);
            if (serverPlayer != null) {
                return serverPlayer.getName().getString();
            }
        }
        String playerName;
        if(BACKEND_INSTANCES.SERVER_BANK_MANAGER != null) {
            ISyncServerBankManager serverBankManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
            if (serverBankManager != null) {
                User user = serverBankManager.getUserByUUID(player);
                if (user != null) {
                    playerName = user.getName();
                } else
                    playerName = player.toString();
            } else
                playerName = player.toString();
        }
        else
        {
            playerName = player.toString();
        }
        return playerName;
    }
    @Nullable UUID tryGetPlayerUUID(String playerName)
    {
        if(UtilitiesPlatform.getServer() != null) {
            ServerPlayer serverPlayer = ServerPlayerUtilities.getOnlinePlayer(playerName);
            if (serverPlayer != null) {
                return serverPlayer.getUUID();
            }
        }
        UUID playerUUID = null;
        if(BACKEND_INSTANCES.SERVER_BANK_MANAGER != null) {
            ISyncServerBankManager serverBankManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
            if (serverBankManager != null) {
                User user = serverBankManager.getUserByName(playerName);
                if (user != null) {
                    playerUUID = user.getUUID();
                }
            }
        }
        return playerUUID;
    }
    public static boolean isPlayerAdmin(UUID player)
    {
        if(BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().isBanksystemAdmin(player))
            return true;
        sendNoAdminMessage(player);
        return false;
    }
    public static void sendNoAdminMessage(UUID executor)
    {
        sendMessage(executor, "This command can only be used by BankSystem admins!");
    }
    public static void sendMessage(UUID player, String message)
    {
        ClientConsoleMessagePacket.sendMessageFromMaster(player, message);
    }


}
