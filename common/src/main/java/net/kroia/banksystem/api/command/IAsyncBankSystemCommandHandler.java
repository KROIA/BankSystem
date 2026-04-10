package net.kroia.banksystem.api.command;

import net.kroia.banksystem.api.bankaccount.IAsyncBankAccount;
import net.kroia.banksystem.util.ItemID;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface IAsyncBankSystemCommandHandler {

    CompletableFuture<Boolean> banksystem_manage_async(@NotNull UUID executor);
    CompletableFuture<Boolean> banksystem_testScreen_async(@NotNull UUID executor);
    CompletableFuture<Boolean> banksystem_setBankSystemAdminMode_async(@NotNull UUID executor, boolean isAdmin);
    CompletableFuture<Boolean> banksystem_setBankSystemAdminMode_user_async(@NotNull UUID executor, String userName, boolean isAdmin);
    CompletableFuture<Boolean> banksystem_allowItem_async(@NotNull UUID executor, ItemID itemID);
    CompletableFuture<Boolean> banksystem_disallowItem_async(@NotNull UUID executor, ItemID itemID);

    CompletableFuture<Boolean> money_add_async(@NotNull UUID executor, float amount);
    CompletableFuture<Boolean> money_add_user_async(@NotNull UUID executor, String userName, float amount);
    CompletableFuture<Boolean> money_set_async(@NotNull UUID executor, float amount);
    CompletableFuture<Boolean> money_set_user_async(@NotNull UUID executor, String userName, float amount);
    CompletableFuture<Boolean> money_remove_async(@NotNull UUID executor, float amount);
    CompletableFuture<Boolean> money_remove_user_async(@NotNull UUID executor, String userName, float amount);
    CompletableFuture<Boolean> money_send_user_async(@NotNull UUID executor, String toUserName, float amount);
    CompletableFuture<Boolean> money_circulation_async(@NotNull UUID executor);



    CompletableFuture<Boolean> bank_enableNotifications_async(@NotNull UUID executor);
    CompletableFuture<Boolean> bank_disableNotifications_async(@NotNull UUID executor);
    CompletableFuture<Boolean> bank_manage_async(@NotNull UUID executor);
    CompletableFuture<Boolean> bank_manage_account_async(@NotNull UUID executor, String accountName);
    CompletableFuture<Boolean> bank_manage_account_async(@NotNull UUID executor, int accountNr);
    CompletableFuture<IAsyncBankAccount> bank_create_async(@NotNull UUID executor, String accountName);

}
