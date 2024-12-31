package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemMod;
import net.minecraft.network.chat.Component;

public class BankSystemTextMessages {
    private static class Variables
    {
        public static final String AMOUNT = "{amount}";
        public static final String BALANCE = "{balance}";
        public static final String LOCKED_BALANCE = "{locked_balance}";

        public static final String ITEM_NAME = "{item_name}";
        public static final String USER = "{user_name}";
        public static final String RECEIVER = "{receiver}";
        public static final String SENDER = "{sender}";
    }

    private static final String prefix  = "message."+BankSystemMod.MOD_ID+".";



    private static final Component TRANSFERED_TO_USER = Component.translatable(prefix+"transfered_to_user");
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




    //--------------------------------------------------------------------------------------------------------
    // Helper methods
    //--------------------------------------------------------------------------------------------------------

    private static String replaceVariable(String message, String variable, String replacement)
    {
        if(!message.contains(variable))
        {
            throw new IllegalArgumentException("Message: \""+message+"\" does not contain variable: \""+variable+"\" which should be replaced with: \""+replacement+"\"");
        }
        // Replace first occurrence of variable
        int indexOccurrence = message.indexOf(variable);
        return message.substring(0, indexOccurrence) + replacement + message.substring(indexOccurrence + variable.length());
    }
}
