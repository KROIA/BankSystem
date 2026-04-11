package net.kroia.banksystem.api.command;

import net.kroia.banksystem.util.ItemID;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public interface IServerBankSystemCommandHandler {

    //boolean banksystem_manage(@NotNull UUID executor);
    //boolean banksystem_testScreen(@NotNull UUID executor);
    boolean banksystem_setBankSystemAdminMode(@NotNull UUID executor, boolean isAdmin);
    boolean banksystem_setBankSystemAdminMode_user(@NotNull UUID executor, String userName, boolean isAdmin);
    boolean banksystem_allowItem(@NotNull UUID executor, ItemID itemID);
    boolean banksystem_disallowItem(@NotNull UUID executor, ItemID itemID);

    boolean money(@NotNull UUID executor);
    boolean money_add(@NotNull UUID executor, float amount);
    boolean money_add_user(@NotNull UUID executor, String userName, float amount);
    boolean money_set(@NotNull UUID executor, float amount);
    boolean money_set_user(@NotNull UUID executor, String userName, float amount);
    boolean money_remove(@NotNull UUID executor, float amount);
    boolean money_remove_user(@NotNull UUID executor, String userName, float amount);
    boolean money_send_user(@NotNull UUID executor, String toUserName, float amount);
    boolean money_circulation(@NotNull UUID executor);



    boolean bank_enableNotifications(@NotNull UUID executor);
    boolean bank_disableNotifications(@NotNull UUID executor);
    boolean bank_manage(@NotNull UUID executor);
    boolean bank_manage_account(@NotNull UUID executor, String accountName);
    boolean bank_manage_account(@NotNull UUID executor, int accountNr);
    int bank_create(@NotNull UUID executor, String accountName);
    boolean bank_show_user(@NotNull UUID executor, String userName);

}
