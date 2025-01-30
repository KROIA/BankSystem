package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemMod;
import net.minecraft.network.chat.Component;

public class BankSystemTextMessages {
    private static boolean initialized = false;
    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
    }

    private static class Variables
    {
        public static final String AMOUNT = "{amount}";
        public static final String BALANCE = "{balance}";
        public static final String LOCKED_BALANCE = "{locked_balance}";

        public static final String ITEM_NAME = "{item_name}";
        public static final String USER = "{user_name}";
        public static final String RECEIVER = "{receiver}";
        public static final String SENDER = "{sender}";
        public static final String PLAYER = "{player_name}";
    }

    private static final String prefix  = "message."+BankSystemMod.MOD_ID+".";



    private static final Component TRANSFERED_TO_USER = Component.translatable(prefix+"transferred_to_user");
    public static String getTransferedMessage(long amount, String itemName, String receiver)
    {
        String msg = TRANSFERED_TO_USER.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, String.valueOf(amount));
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        msg = replaceVariable(msg, Variables.RECEIVER, receiver);
        return msg;
    }


    private static final Component RECEIVED_FROM_USER = Component.translatable(prefix+"received_from_user");
    public static String getReceivedMessage(long amount, String itemName, String sender)
    {
        String msg = RECEIVED_FROM_USER.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, String.valueOf(amount));
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        msg = replaceVariable(msg, Variables.SENDER, sender);
        return msg;
    }

    private static final Component REMOVED_FROM_USER = Component.translatable(prefix+"removed_from_user");
    public static String getRemovedMessage(long amount, String itemName, String user)
    {
        String msg = REMOVED_FROM_USER.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, String.valueOf(amount));
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        msg = replaceVariable(msg, Variables.USER, user);
        return msg;
    }
    private static final Component BANK_BALANCE_LOST = Component.translatable(prefix+"bank_balance_lost");
    public static String getBankBalanceLostMessage(long amount, String itemName)
    {
        String msg = BANK_BALANCE_LOST.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, String.valueOf(amount));
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        return msg;
    }

    private static final Component ADDED_TO_USER = Component.translatable(prefix+"added_to_user");
    public static String getAddedMessage(long amount, String itemName, String user)
    {
        String msg = ADDED_TO_USER.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, String.valueOf(amount));
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        msg = replaceVariable(msg, Variables.USER, user);
        return msg;
    }

    private static final Component SET_BALANCE_TO = Component.translatable(prefix+"set_balance_to");
    public static String getSetBalanceMessage(long amount, String itemName, String user)
    {
        String msg = SET_BALANCE_TO.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, String.valueOf(amount));
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        msg = replaceVariable(msg, Variables.USER, user);
        return msg;
    }

    private static final Component PROBLEM_WHILE_TRYING_SET_BALANCE = Component.translatable(prefix+"problem_while_trying_set_balance");
    public static String getProblemWhileTryingSetBalanceMessage(String itemName, long currentBalance, long targetBalance, long lockedBalance, long newLockedBalance)
    {
        String msg = PROBLEM_WHILE_TRYING_SET_BALANCE.getString();
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        msg = replaceVariable(msg, Variables.AMOUNT, String.valueOf(targetBalance));
        msg = replaceVariable(msg, Variables.LOCKED_BALANCE, String.valueOf(lockedBalance));
        msg = replaceVariable(msg, Variables.BALANCE, String.valueOf(currentBalance));
        msg = replaceVariable(msg, Variables.LOCKED_BALANCE, String.valueOf(newLockedBalance));
        return msg;
    }

    private static final Component NOT_ENOUGH_TO_TRANSFER = Component.translatable(prefix+"not_enough_to_transfer");
    public static String getNotEnoughMoneyForTransfer(String sender, String receiver, long amount, String itemName)
    {
        String msg = NOT_ENOUGH_TO_TRANSFER.getString();
        msg = replaceVariable(msg, Variables.SENDER, sender);
        msg = replaceVariable(msg, Variables.RECEIVER, receiver);
        msg = replaceVariable(msg, Variables.AMOUNT, String.valueOf(amount));
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        return msg;
    }

    private static final Component NOT_ENOUGH_IN_ACCOUNT = Component.translatable(prefix+"not_enough_in_account");
    public static String getNotEnoughInAccountMessage(String itemName, String user)
    {
        String msg = NOT_ENOUGH_IN_ACCOUNT.getString();
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        msg = replaceVariable(msg, Variables.USER, user);
        return msg;
    }


    private static final Component TRANSFER_FAILED = Component.translatable(prefix+"transfer_failed");
    public static String getTransferFailedMessage(String sender, String receiver, long amount, String itemName)
    {
        String msg = TRANSFER_FAILED.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, String.valueOf(amount));
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        msg = replaceVariable(msg, Variables.SENDER, sender);
        msg = replaceVariable(msg, Variables.RECEIVER, receiver);
        return msg;
    }
    private static final Component TRANSFER_TO_SAME_ACCOUNT = Component.translatable(prefix+"transfer_to_same_account");
    public static String getTransferToSameAccountMessage(String itemName)
    {
        String msg = TRANSFER_TO_SAME_ACCOUNT.getString();
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        return msg;
    }


    private static final Component YOUR_BALANCE = Component.translatable(prefix+"your_balance");
    public static String getYourBalanceMessage(long amount)
    {
        String msg = YOUR_BALANCE.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, String.valueOf(amount));
        return msg;
    }

    private static final Component BALANCE = Component.translatable(prefix+"balance");
    public static String getBalanceMessage(long amount)
    {
        String msg = BALANCE.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, String.valueOf(amount));
        return msg;
    }
    private static final Component BALANCE_DETAILED = Component.translatable(prefix+"balance_detailed");
    public static String getBalanceDetailedMessage(long balance, long lockedBalance)
    {
        String msg = BALANCE_DETAILED.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, String.valueOf(balance));
        msg = replaceVariable(msg, Variables.LOCKED_BALANCE, String.valueOf(balance));
        return msg;
    }


    private static final Component USER_NOT_FOUND = Component.translatable(prefix+"user_not_found");
    public static String getUserNotFoundMessage(String user)
    {
        String msg = USER_NOT_FOUND.getString();
        msg = replaceVariable(msg, Variables.USER, user);
        return msg;
    }

    private static final Component BANK_USER_NOTIFICATION_ENABLED = Component.translatable(prefix+"bank_user_notification_enabled");
    public static String getBankUserNotificationEnabledMessage()
    {
        String msg = BANK_USER_NOTIFICATION_ENABLED.getString();
        return msg;
    }

    private static final Component BANK_USER_NOTIFICATION_DISABLED = Component.translatable(prefix+"bank_user_notification_disabled");
    public static String getBankUserNotificationDisabledMessage()
    {
        String msg = BANK_USER_NOTIFICATION_DISABLED.getString();
        return msg;
    }


    private static final Component BANK_NOT_FOUND = Component.translatable(prefix+"bank_not_found");
    public static String getBankNotFoundMessage(String user, String itemName)
    {
        String msg = BANK_NOT_FOUND.getString();
        msg = replaceVariable(msg, Variables.USER, user);
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        return msg;
    }

    private static final Component BANK_OF = Component.translatable(prefix+"bank_of");
    public static String getBankOfMessage(String user)
    {
        String msg = BANK_OF.getString();
        msg = replaceVariable(msg, Variables.USER, user);
        return msg;
    }


    private static final Component CANT_CREATE_BANK = Component.translatable(prefix+"cant_create_bank");
    public static String getCantCreateBankMessage(String user, String itemName)
    {
        String msg = CANT_CREATE_BANK.getString();
        msg = replaceVariable(msg, Variables.USER, user);
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        return msg;
    }
    private static final Component BANK_CREATED = Component.translatable(prefix+"bank_created");
    public static String getBankCreatedMessage(String user, String itemName)
    {
        String msg = BANK_CREATED.getString();
        msg = replaceVariable(msg, Variables.USER, user);
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        return msg;
    }

    private static final Component BANK_DELETED = Component.translatable(prefix+"bank_deleted");
    public static String getBankDeletedMessage(String user, String itemName)
    {
        String msg = BANK_DELETED.getString();
        msg = replaceVariable(msg, Variables.USER, user);
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        return msg;
    }

    private static final Component BANK_ALREADY_EXISTS = Component.translatable(prefix+"bank_already_exists");
    public static String getBankAlreadyExistsMessage(String user, String itemName)
    {
        String msg = BANK_ALREADY_EXISTS.getString();
        msg = replaceVariable(msg, Variables.USER, user);
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        return msg;
    }
    private static final Component MONEY_BANK_ACCESS_HELP = Component.translatable(prefix+"money_bank_access_help");
    public static String getMoneyBankAccessHelpMessage()
    {
        return MONEY_BANK_ACCESS_HELP.getString();
    }


    private static final Component ITEM_NOT_ALLOWED = Component.translatable(prefix+"item_not_allowed");
    public static String getItemNotAllowedMessage(String itemName)
    {
        String msg = ITEM_NOT_ALLOWED.getString();
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        return msg;
    }
    private static final Component ITEM_NOW_ALLOWED = Component.translatable(prefix+"item_now_allowed");
    public static String getItemNowAllowedMessage(String itemName)
    {
        String msg = ITEM_NOW_ALLOWED.getString();
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        return msg;
    }
    private static final Component ITEM_NOW_ALLOWED_FAILED = Component.translatable(prefix+"item_now_allowed_failed");
    public static String getItemNowAllowedFailedMessage(String itemName)
    {
        String msg = ITEM_NOW_ALLOWED_FAILED.getString();
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        return msg;
    }

    private static final Component ITEM_ALREADY_ALLOWED = Component.translatable(prefix+"item_already_allowed");
    public static String getItemAlreadyAllowedMessage(String itemName)
    {
        String msg = ITEM_ALREADY_ALLOWED.getString();
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        return msg;
    }
    private static final Component INVALID_ITEM_ID = Component.translatable(prefix+"invalid_item_id");
    public static String getInvalidItemIDMessage(String itemName)
    {
        String msg = INVALID_ITEM_ID.getString();
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        return msg;
    }



    private static final Component CIRCULATION = Component.translatable(prefix+"circulation");
    public static String getCirculationMessage(long amount, String itemName)
    {
        String msg = CIRCULATION.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, String.valueOf(amount));
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        return msg;
    }

    private static final Component CURRENCY = Component.translatable("item."+BankSystemMod.MOD_ID+".currency");
    public static String getCurrencyName()
    {
        return CURRENCY.getString();
    }



    private static final Component ITEMINFO_WIDGET_TOTAL_SUPLY = Component.translatable("gui."+BankSystemMod.MOD_ID+".iteminfo_widget.total_supply");
    public static String getItemInfoWidgetTotalSuplyMessage(long suply)
    {
        String msg = ITEMINFO_WIDGET_TOTAL_SUPLY.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, String.valueOf(suply));
        return msg;
    }
    public static String getItemInfoWidgetTotalSuplyMessage(String suply)
    {
        String msg = ITEMINFO_WIDGET_TOTAL_SUPLY.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, suply);
        return msg;
    }
    private static final Component ITEMINFO_WIDGET_TOTAL_LOCKED = Component.translatable("gui."+BankSystemMod.MOD_ID+".iteminfo_widget.total_locked");
    public static String getItemInfoWidgetTotalLockedMessage(long locked)
    {
        String msg = ITEMINFO_WIDGET_TOTAL_LOCKED.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, String.valueOf(locked));
        return msg;
    }
    public static String getItemInfoWidgetTotalLockedMessage(String locked)
    {
        String msg = ITEMINFO_WIDGET_TOTAL_LOCKED.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, locked);
        return msg;
    }


    private static final Component BANK_ACCOUNT_MANAGEMENT_ITEM_ASK_REMOVE_TILE = Component.translatable("gui."+BankSystemMod.MOD_ID+".bank_account_management_item.ask_remove_title");
    public static String getBankAccountManagementItemAskRemoveTitleMessage(String itemName)
    {
        String msg = BANK_ACCOUNT_MANAGEMENT_ITEM_ASK_REMOVE_TILE.getString();
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        return msg;
    }

    private static final Component BANK_ACCOUNT_MANAGEMENT_ITEM_ASK_REMOVE_MSG = Component.translatable("gui."+BankSystemMod.MOD_ID+".bank_account_management_item.ask_remove_message");
    public static String getBankAccountManagementItemAskRemoveMessage(String itemName, String playerName)
    {
        String  msg = BANK_ACCOUNT_MANAGEMENT_ITEM_ASK_REMOVE_MSG.getString();
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        msg = replaceVariable(msg, Variables.PLAYER, playerName);
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        return msg;
    }

    private static final Component BANK_ACCOUNT_MANAGEMENT_BANK_OWNER = Component.translatable("gui."+BankSystemMod.MOD_ID+".bank_account_management_item.bank_owner");
    public static String getBankAccountManagementBankOwnerMessage(String playerName)
    {
        String msg = BANK_ACCOUNT_MANAGEMENT_BANK_OWNER.getString();
        msg = replaceVariable(msg, Variables.PLAYER, playerName);
        return msg;
    }

    private static final Component BANK_SETTING_START_BALANCE_SET = Component.translatable(prefix+"bank_setting_start_balance_set");
    public static String getBankSettingStartBalanceSetMessage(long amount)
    {
        String msg = BANK_SETTING_START_BALANCE_SET.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, String.valueOf(amount));
        return msg;
    }

    private static final Component BANK_SETTING_ITEM_TRANSFER_TICK_INTERVAL = Component.translatable(prefix+"bank_setting_item_transfer_tick_interval");
    public static String getBankSettingItemTransferTickIntervalMessage(int interval)
    {
        String msg = BANK_SETTING_ITEM_TRANSFER_TICK_INTERVAL.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, String.valueOf(interval));
        return msg;
    }

    private static final Component BANK_DATA_SAVED = Component.translatable(prefix+"bank_data_saved");
    public static String getBankDataSavedMessage()
    {
        return BANK_DATA_SAVED.getString();
    }

    private static final Component BANK_DATA_SAVE_FAILED = Component.translatable(prefix+"bank_data_save_failed");
    public static String getBankDataSaveFailedMessage()
    {
        return BANK_DATA_SAVE_FAILED.getString();
    }

    private static final Component BANK_DATA_LOAD_FAILED = Component.translatable(prefix+"bank_data_load_failed");
    public static String getBankDataLoadFailedMessage()
    {
        return BANK_DATA_LOAD_FAILED.getString();
    }

    private static final Component BANK_DATA_LOADED = Component.translatable(prefix+"bank_data_loaded");
    public static String getBankDataLoadedMessage()
    {
        return BANK_DATA_LOADED.getString();
    }





    //--------------------------------------------------------------------------------------------------------
    // Helper methods
    //--------------------------------------------------------------------------------------------------------

    private static String replaceVariable(String message, String variable, String replacement)
    {
        if(!message.contains(variable))
        {
            BankSystemMod.LOGGER.error("Message: \""+message+"\" does not contain variable: \""+variable+"\" which should be replaced with: \""+replacement+"\"");
            return message;
            //throw new IllegalArgumentException("Message: \""+message+"\" does not contain variable: \""+variable+"\" which should be replaced with: \""+replacement+"\"");
        }
        // Replace first occurrence of variable
        int indexOccurrence = message.indexOf(variable);
        return message.substring(0, indexOccurrence) + replacement + message.substring(indexOccurrence + variable.length());
    }
}
