package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.bank.ServerBank;
import net.kroia.banksystem.screen.custom.ATMScreen;
import net.minecraft.network.chat.Component;

public class BankSystemTextMessages {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
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
        public static final String REASON = "{reason}";
        public static final String ACCOUNT = "{account_number}";
    }

    private static final String prefix  = "message."+BankSystemMod.MOD_ID+".";


    public static void setBackend(BankSystemModBackend.Instances backend) {
        BankSystemTextMessages.BACKEND_INSTANCES = backend;
    }

    private static final Component TRANSFERED_TO_USER = Component.translatable(prefix+"transferred_to_user");
    public static String getTransferedMessage(String amount, String itemName, String receiver)
    {
        String msg = TRANSFERED_TO_USER.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, amount);
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        msg = replaceVariable(msg, Variables.RECEIVER, receiver);
        return msg;
    }


    private static final Component RECEIVED_FROM_USER = Component.translatable(prefix+"received_from_user");
    public static String getReceivedMessage(String amount, String itemName, String sender)
    {
        String msg = RECEIVED_FROM_USER.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, amount);
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        msg = replaceVariable(msg, Variables.SENDER, sender);
        return msg;
    }

    private static final Component REMOVED_FROM_USER = Component.translatable(prefix+"removed_from_user");
    public static String getRemovedMessage(String amount, String itemName, String user)
    {
        String msg = REMOVED_FROM_USER.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, amount);
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        msg = replaceVariable(msg, Variables.USER, user);
        return msg;
    }
    private static final Component BANK_BALANCE_LOST = Component.translatable(prefix+"bank_balance_lost");
    public static String getBankBalanceLostMessage(String amount, String itemName)
    {
        String msg = BANK_BALANCE_LOST.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, amount);
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        return msg;
    }

    private static final Component ADDED_TO_USER = Component.translatable(prefix+"added_to_user");
    public static String getAddedMessage(String amountStr, String itemName, String user)
    {
        String msg = ADDED_TO_USER.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, amountStr);
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        msg = replaceVariable(msg, Variables.USER, user);
        return msg;
    }
    private static final Component CANT_ADD_TO_USER = Component.translatable(prefix+"cant_add_to_user");
    public static String getCantAddMessage(String amountStr, String itemName, String user, String reason)
    {
        String msg = CANT_ADD_TO_USER.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, amountStr);
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        msg = replaceVariable(msg, Variables.USER, user);
        msg = replaceVariable(msg, Variables.REASON, reason);
        return msg;
    }

    private static final Component SET_BALANCE_TO = Component.translatable(prefix+"set_balance_to");
    public static String getSetBalanceMessage(String amountStr, String itemName, String user)
    {
        String msg = SET_BALANCE_TO.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, amountStr);
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        msg = replaceVariable(msg, Variables.USER, user);
        return msg;
    }

    private static final Component PROBLEM_WHILE_TRYING_SET_BALANCE = Component.translatable(prefix+"problem_while_trying_set_balance");
    public static String getProblemWhileTryingSetBalanceMessage(String itemName, String currentBalance, String targetBalance, String lockedBalance, String newLockedBalance)
    {
        String msg = PROBLEM_WHILE_TRYING_SET_BALANCE.getString();
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        msg = replaceVariable(msg, Variables.AMOUNT, targetBalance);
        msg = replaceVariable(msg, Variables.LOCKED_BALANCE, lockedBalance);
        msg = replaceVariable(msg, Variables.BALANCE, currentBalance);
        msg = replaceVariable(msg, Variables.LOCKED_BALANCE, newLockedBalance);
        return msg;
    }

    private static final Component NOT_ENOUGH_TO_TRANSFER = Component.translatable(prefix+"not_enough_to_transfer");
    public static String getNotEnoughMoneyForTransfer(String sender, String receiver, String amount, String itemName)
    {
        String msg = NOT_ENOUGH_TO_TRANSFER.getString();
        msg = replaceVariable(msg, Variables.SENDER, sender);
        msg = replaceVariable(msg, Variables.RECEIVER, receiver);
        msg = replaceVariable(msg, Variables.AMOUNT, amount);
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
    public static String getTransferFailedMessage(String sender, String receiver, String amount, String itemName, String reason)
    {
        String msg = TRANSFER_FAILED.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, amount);
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        msg = replaceVariable(msg, Variables.SENDER, sender);
        msg = replaceVariable(msg, Variables.RECEIVER, receiver);
        msg = replaceVariable(msg, Variables.REASON, reason);
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
    public static String getYourBalanceMessage(String amount)
    {
        String msg = YOUR_BALANCE.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, String.valueOf(amount));
        return msg;
    }

    private static final Component BALANCE = Component.translatable(prefix+"balance");
    public static String getBalanceMessage(String amount)
    {
        String msg = BALANCE.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, String.valueOf(amount));
        return msg;
    }
    private static final Component BALANCE_DETAILED = Component.translatable(prefix+"balance_detailed");

    // (1000 means 10.00 currency units)
    public static String getBalanceDetailedMessage(String balance, String lockedBalance)
    {
        String msg = BALANCE_DETAILED.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, balance);
        msg = replaceVariable(msg, Variables.LOCKED_BALANCE, lockedBalance);
        return msg;
    }


    private static final Component USER_NOT_FOUND = Component.translatable(prefix+"user_not_found");
    public static String getUserNotFoundMessage(String user)
    {
        String msg = USER_NOT_FOUND.getString();
        msg = replaceVariable(msg, Variables.USER, user);
        return msg;
    }
    private static final Component BANKACCOUNT_NOT_FOUND = Component.translatable(prefix+"bankaccount_not_found");
    public static String getBankAccountNotFoundMessage(String user)
    {
        String msg = BANKACCOUNT_NOT_FOUND.getString();
        msg = replaceVariable(msg, Variables.USER, user);
        return msg;
    }

    private static final Component CANT_CREATE_BANK_ACCOUNT = Component.translatable(prefix+"cant_create_bank_account");
    public static String getCantCreateBankAccountMessage()
    {
        return CANT_CREATE_BANK_ACCOUNT.getString();
    }

    private static final Component NO_PERMISSION_DEPOSIT = Component.translatable(prefix+"no_permission_deposit");
    private static final Component NO_PERMISSION_WITHDRAW = Component.translatable(prefix+"no_permission_withdraw");
    private static final Component NO_PERMISSION_MANAGE = Component.translatable(prefix+"no_permission_manage");
    public static String getNoBankPermissionMessage(String accountName, BankPermission permission)
    {
        String msg = switch (permission) {
            case DEPOSIT -> NO_PERMISSION_DEPOSIT.getString();
            case WITHDRAW -> NO_PERMISSION_WITHDRAW.getString();
            case MANAGE -> NO_PERMISSION_MANAGE.getString();
        };
        msg = replaceVariable(msg, Variables.ACCOUNT, accountName);
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
    private static final Component DEPOSIT_REJECTED_ITEM_CONDITION = Component.translatable(prefix+"deposit_rejected_item_condition");
    /**
     * Message shown when the deposit gate refuses an item because its state-gated
     * components (e.g. spoiled food) don't match what the bank would hand back.
     */
    public static String getDepositRejectedItemConditionMessage(String itemName)
    {
        String msg = DEPOSIT_REJECTED_ITEM_CONDITION.getString();
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        return msg;
    }
    private static final Component DEPOSIT_ITEM_UNKNOWN_ON_MASTER = Component.translatable(prefix+"deposit_item_unknown_on_master");
    /**
     * Message shown on a slave when a player tries to deposit an item whose identity is
     * unknown to the master server's registry (e.g. a mod installed only on the slave).
     * Unlike the deposit-gate rejection, such an item can never be banked on this setup at
     * all. Emitted from the deposit action so it fires on every attempt, per player — not
     * from the ID-registration path, which is deduplicated by the slave's negative cache.
     */
    public static String getDepositItemUnknownOnMasterMessage(String itemName)
    {
        String msg = DEPOSIT_ITEM_UNKNOWN_ON_MASTER.getString();
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        return msg;
    }
    private static final Component CRAFTING_MISSING_BANK_ITEMS = Component.translatable(prefix+"crafting_missing_bank_items");
    /**
     * Message shown when a bank-assisted craft is aborted because a required
     * ingredient could not be locked in the bank (all-or-nothing deduction).
     */
    public static String getCraftingMissingBankItemsMessage(String itemName)
    {
        String msg = CRAFTING_MISSING_BANK_ITEMS.getString();
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        return msg;
    }
    private static final Component CRAFTING_DEPOSIT_FALLBACK = Component.translatable(prefix+"crafting_deposit_fallback");
    /**
     * Message shown when the crafted output could not be auto-deposited into the
     * bank and was handed to the player inventory instead (never blocked, never
     * silently dropped).
     */
    public static String getCraftingDepositFallbackMessage(String itemName)
    {
        String msg = CRAFTING_DEPOSIT_FALLBACK.getString();
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        return msg;
    }
    private static final Component ITEM_NOT_ALLOWED_FAILED = Component.translatable(prefix+"item_not_allowed_failed");
    public static String getItemNotAllowedFailedMessage(String itemName)
    {
        String msg = ITEM_NOT_ALLOWED_FAILED.getString();
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        return msg;
    }
    private static final Component ITEM_NOW_ALLOWED = Component.translatable(prefix+"item_now_allowed");
    public static String getItemNowAllowedMessage(String itemName, String smallestAmount)
    {
        String msg = ITEM_NOW_ALLOWED.getString();
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        msg = replaceVariable(msg, Variables.AMOUNT, smallestAmount);
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

    private static final Component NO_ITEM_IN_HAND = Component.translatable(prefix+"no_item_in_hand");
    public static String getNoItemInHandMessage()
    {
        String msg = NO_ITEM_IN_HAND.getString();
        return msg;
    }



    private static final Component CIRCULATION = Component.translatable(prefix+"circulation");
    public static String getCirculationMessage(String amount, String itemName)
    {
        String msg = CIRCULATION.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, amount);
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        return msg;
    }

    private static final Component CURRENCY = Component.translatable("item."+BankSystemMod.MOD_ID+".currency");
    public static String getCurrencyName()
    {
        return CURRENCY.getString();
    }



    private static final Component ITEMINFO_WIDGET_TOTAL_SUPLY = Component.translatable("gui."+BankSystemMod.MOD_ID+".iteminfo_widget.total_supply");
    public static String getItemInfoWidgetTotalSuplyMessage(String suply)
    {
        String msg = ITEMINFO_WIDGET_TOTAL_SUPLY.getString();
        msg = replaceVariable(msg, Variables.AMOUNT, suply);
        return msg;
    }
    private static final Component ITEMINFO_WIDGET_TOTAL_LOCKED = Component.translatable("gui."+BankSystemMod.MOD_ID+".iteminfo_widget.total_locked");
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
    public static String getBankAccountManagementItemAskRemoveMessage(String itemName, int accountNumber)
    {
        String  msg = BANK_ACCOUNT_MANAGEMENT_ITEM_ASK_REMOVE_MSG.getString();
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        msg = replaceVariable(msg, Variables.ACCOUNT, String.valueOf(accountNumber));
        msg = replaceVariable(msg, Variables.ITEM_NAME, itemName);
        return msg;
    }

    private static final Component DELETE_ACCOUNT_ASK_POPUP_TITLE = Component.translatable("gui."+BankSystemMod.MOD_ID+".bank_account_management_screen.delete_account_ask_popup_title");
    public static String getDeleteAccountAskPopupTitleMessage(String accountName)
    {
        String msg = DELETE_ACCOUNT_ASK_POPUP_TITLE.getString();
        msg = replaceVariable(msg, Variables.ACCOUNT, accountName);
        return msg;
    }
    private static final Component DELETE_ACCOUNT_ASK_POPUP_MESSAGE = Component.translatable("gui."+BankSystemMod.MOD_ID+".bank_account_management_screen.delete_account_ask_popup_message");
    public static String getDeleteAccountAskPopupMessage(String accountName)
    {
        String msg = DELETE_ACCOUNT_ASK_POPUP_MESSAGE.getString();
        msg = replaceVariable(msg, Variables.ACCOUNT, accountName);
        return msg;
    }


    private static final Component BANK_ACCOUNT_MANAGEMENT_BANK_OWNER = Component.translatable("gui."+BankSystemMod.MOD_ID+".bank_account_management_item.account_number");
    public static String getBankAccountManagementBankOwnerMessage(int accountNumber)
    {
        String msg = BANK_ACCOUNT_MANAGEMENT_BANK_OWNER.getString();
        msg = replaceVariable(msg, Variables.ACCOUNT, String.valueOf(accountNumber));
        return msg;
    }

    private static final Component BANK_SETTING_START_BALANCE_SET = Component.translatable(prefix+"bank_setting_start_balance_set");
    public static String getBankSettingStartBalanceSetMessage(String amount)
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


    private static final Component NEED_CREATIVE_MODE_FOR_THIS_SCREEN = Component.translatable(prefix+"need_creative_mode_for_this_screen");
    public static String getNeedCreativeModeForThisScreenMessage()
    {
        return NEED_CREATIVE_MODE_FOR_THIS_SCREEN.getString();
    }


    private static final Component ATM_SUM_TEXT = Component.translatable(ATMScreen.COMPONENT_STR_START + "sum_text");
    public static String getATMSumTextMessage(long sum)
    {
        String msg = ATM_SUM_TEXT.getString();
        String normalized = ServerBank.getFormattedAmountStatic(sum);
        msg = replaceVariable(msg, Variables.AMOUNT, normalized);
        return msg;
    }

    private static final Component ATM_AVAILABLE_TEXT = Component.translatable(ATMScreen.COMPONENT_STR_START + "available_text");
    public static String getATMAvailableTextMessage(long sum)
    {
        String msg = ATM_AVAILABLE_TEXT.getString();
        String normalized = ServerBank.getFormattedAmountStatic(sum);
        msg = replaceVariable(msg, Variables.AMOUNT, normalized);
        return msg;
    }

    private static final Component ATM_NOT_ENOUGH_BALANCE_TEXT = Component.translatable(ATMScreen.COMPONENT_STR_START + "not_enough_money_in_bank");
    public static String getATMNotEnoughBalance(long sum)
    {
        String msg = ATM_NOT_ENOUGH_BALANCE_TEXT.getString();
        String normalized = ServerBank.getFormattedAmountStatic(sum);
        msg = replaceVariable(msg, Variables.AMOUNT, normalized);
        return msg;
    }



    //--------------------------------------------------------------------------------------------------------
    // Helper methods
    //--------------------------------------------------------------------------------------------------------

    private static String replaceVariable(String message, String variable, String replacement)
    {
        if(!message.contains(variable))
        {
            // BACKEND_INSTANCES is always initialized before replaceVariable is called
            BACKEND_INSTANCES.LOGGER.debug("[BankSystemTextMessages] Message: \""+message+"\" does not contain variable: \""+variable+"\" which should be replaced with: \""+replacement+"\"");
            return message;
            //throw new IllegalArgumentException("Message: \""+message+"\" does not contain variable: \""+variable+"\" which should be replaced with: \""+replacement+"\"");
        }
        // Replace first occurrence of variable
        int indexOccurrence = message.indexOf(variable);
        return message.substring(0, indexOccurrence) + replacement + message.substring(indexOccurrence + variable.length());
    }
}
