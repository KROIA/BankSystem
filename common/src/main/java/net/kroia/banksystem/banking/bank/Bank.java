package net.kroia.banksystem.banking.bank;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.BankUser;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.PlayerUtilities;
import net.kroia.modutilities.ServerSaveable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class Bank implements ServerSaveable {
    private static final Component WARNING = Component.translatable("message."+BankSystemMod.MOD_ID+".bank.warning");
    private static final Component INFO = Component.translatable("message."+BankSystemMod.MOD_ID+".bank.info");

    public enum BankType
    {
        MONEY,
        ITEM
    }

    private final BankUser owner;
    protected long balance;
    protected long lockedBalance;

    String itemID;


    public Bank(BankUser owner, String itemID, long balance) {
        this.owner = owner;
        this.itemID = itemID;
        setBalanceInternal(balance);
        this.lockedBalance = 0;
    }
    public Bank(BankUser owner, CompoundTag tag) {
        this.owner = owner;
        load(tag);
    }

    public static Bank loadFromTag(BankUser owner, CompoundTag tag) {
        BankType type = BankType.valueOf(tag.getString("BankType"));
        switch(type)
        {
            case MONEY:
                return new MoneyBank(owner, tag);
            case ITEM:
                return new ItemBank(owner, tag);
            default:
                return null;
        }
    }


    public long getBalance() {
        return balance;
    }
    public long getLockedBalance() {
        return lockedBalance;
    }

    public long getTotalBalance() {
        return balance + lockedBalance;
    }
    public String getItemID() {
        return itemID;
    }
    public String getItemName()
    {
        String name = ItemUtilities.getItemName(itemID);
        if(name == null)
            return "unknown";
        if(name.contains(":"))
            return name.substring(name.lastIndexOf(":")+1);
        return name;
    }

    public void setBalance(long balance) {
        if(balance < 0)
            return;
        long newBalance = balance - this.lockedBalance;
        if(newBalance < 0)
        {
            warnUser(BankSystemTextMessages.getProblemWhileTryingSetBalanceMessage(getItemName(), this.balance, balance, lockedBalance, balance));
            //warnUser("Problem while trying to set balance to "+balance+
            //        ".\nLocked balance is "+lockedBalance+" and your current balance is "+this.balance+
            //        ".\nBalance will be set to 0 and locked balance to "+balance+
            //        ".\nBecause of that, some limit orders may be cancelled.");

            lockedBalance = balance;
            setBalanceInternal(0);
            return;
        }

        setBalanceInternal(newBalance);
        if(owner.isBankNotificationEbabled())
            notifyUser(BankSystemTextMessages.getSetBalanceMessage(getBalance(), getItemName(), owner.getPlayerName()));
        //notifyUser("Balance set to " + balance + ".");
    }

    public UUID getPlayerUUID() {
        return owner.getPlayerUUID();
    }
    public ServerPlayer getUser()
    {
        return owner.getPlayer();
    }

    public String getPlayerName()
    {
        return owner.getPlayerName();
    }


    public boolean deposit(long amount) {
        if(amount <= 0)
            return false;
        //dbg_checkValueIsNegative(amount);
        addBalanceInternal(amount);
        //notifyUser("Deposited " + amount+ ".");
        return true;
    }


    public boolean hasSufficientFunds(long amount) {
        return balance >= amount;
    }

    public boolean withdraw(long amount) {
        if (balance < amount || amount < 0) {
            return false;
        }
        //dbg_checkValueIsNegative(amount);
        addBalanceInternal(-amount);
        //notifyUser("Withdrew " + amount + ".");
        return true;
    }
    public boolean transfer(long amount, Bank other) {
        if (balance < amount || amount < 0) {
            return false;
        }
        if(other == this)
            return true;
        dbg_checkValueIsNegative(amount);
        addBalanceInternal(-amount);
        other.deposit(amount);

        notifyUser_transfer(amount, other);
        return true;
    }
    public boolean transferFromLocked(long amount, Bank other) {
        if (lockedBalance < amount || amount < 0) {
            return false;
        }
        //dbg_checkValueIsNegative(amount);
        lockedBalance -= amount;
        other.deposit(amount);
        notifyUser_transfer(amount, other);
        return true;
    }
    public boolean transferFromLockedPrefered(long amount, Bank other) {
        if(amount < 0)
            return false;
        //dbg_checkValueIsNegative(amount);
        long origAmount = amount;
        if (lockedBalance < amount) {
            if (balance+lockedBalance < amount) {
                return false;
            }
            amount -= lockedBalance;
            lockedBalance = 0;
            addBalanceInternal(-amount);
            other.deposit(origAmount);
            notifyUser_transfer(origAmount, other);
            return true;
        }
        lockedBalance -= amount;
        other.deposit(amount);
        notifyUser_transfer(amount, other);
        return true;
    }

    public static boolean exchangeFromLockedPrefered(Bank from1, Bank to1, long amount1, Bank from2, Bank to2, long amount2)
    {
        dbg_checkValueIsNegative(amount1);
        dbg_checkValueIsNegative(amount2);

        // Both transactions must be possible, otherwise no transaction is done
        // Copy original data
        long origLockedBalance1 = from1.lockedBalance;
        long origLockedBalance2 = from2.lockedBalance;
        long origBalance1 = from1.balance;
        long origBalance2 = from2.balance;

        // Try to transfer from locked balance
        if(from1.transferFromLockedPrefered(amount1, to1) && from2.transferFromLockedPrefered(amount2, to2))
        {
            return true;
        }
        // If not possible, revert changes
        from1.lockedBalance = origLockedBalance1;
        from2.lockedBalance = origLockedBalance2;
        from1.balance = origBalance1;
        from2.balance = origBalance2;
        return false;
    }

    public boolean lockAmount(long amount) {
        dbg_checkValueIsNegative(amount);
        if (balance < amount) {
            return false;
        }
        addBalanceInternal(-amount);
        lockedBalance += amount;
        dbg_checkValueIsNegative(lockedBalance);
        //notifyUser("Locked " + amount+".");
        return true;
    }
    public boolean unlockAmount(long amount) {
        dbg_checkValueIsNegative(amount);
        if (lockedBalance < amount) {
            return false;
        }
        addBalanceInternal(amount);
        lockedBalance -= amount;
        //notifyUser("Unlocked " + amount+".");
        return true;
    }

    public void unlockAll()
    {
        addBalanceInternal(lockedBalance);
        lockedBalance = 0;
    }

    @Override
    public boolean save(CompoundTag tag) {
        tag.putString("itemID", itemID);
        tag.putLong("balance", balance);
        tag.putLong("lockedBalance", lockedBalance);
        return true;
    }

    @Override
    public boolean load(CompoundTag tag) {
        if(tag == null)
            return false;
        if(     !tag.contains("itemID") ||
                !tag.contains("balance") ||
                !tag.contains("lockedBalance"))
            return false;
        itemID = MoneyBank.compatibilityMoneyItemIDConvert(tag.getString("itemID"));

        setBalanceInternal(tag.getLong("balance"));
        lockedBalance = tag.getLong("lockedBalance");
        return balance >= 0 && lockedBalance >= 0 && !itemID.isEmpty();
    }

    private void addBalanceInternal(long balance) {
        setBalanceInternal(this.balance + balance);
    }
    private void setBalanceInternal(long balance) {
        if(balance < 0)
            dbg_invalid_balance(balance);

        this.balance = balance;
    }

    private static void dbg_invalid_balance(long balance) {
        throw new IllegalArgumentException("Balance is negative: "+balance);
    }
    private static void dbg_checkValueIsNegative(long value) {
        if(value < 0)
            throw new IllegalArgumentException("Value is negative: "+value);
    }


    protected void notifyUser_transfer(long amount, Bank other) {
        if(amount == 0)
            return;

        if(owner.isBankNotificationEbabled())
            notifyUser(BankSystemTextMessages.getTransferedMessage(amount, getItemName(), other.getPlayerName()));
        if(other.owner.isBankNotificationEbabled())
            other.notifyUser(BankSystemTextMessages.getReceivedMessage(amount, getItemName(), owner.getPlayerName()));
        //notifyUser("Transferred " + amount + " → user: "+ other.getPlayerName());
    }

    protected void warnUser(String msg) {
        PlayerUtilities.printToClientConsole(getPlayerUUID(), WARNING.getString()+msg);
    }

    protected void notifyUser(String msg) {
        PlayerUtilities.printToClientConsole(getPlayerUUID(), INFO.getString()+msg);
    }

    public String toString()
    {
        return "Owner: "+getPlayerName()+" "+toStringNoOwner();
    }

    public String toStringNoOwner()
    {
        //StringBuilder content = new StringBuilder(getItemName() +" Balance: "+(balance+lockedBalance));
        StringBuilder content = new StringBuilder(getItemName() +BankSystemTextMessages.getBalanceMessage(balance+lockedBalance));
        if(lockedBalance > 0)
            content.append("("+BankSystemTextMessages.getBalanceDetailedMessage(balance, lockedBalance)+")");

        return content.toString();
    }

    public static String getNormalizedAmount(long amount)
    {
        // depending on the exponent of the amount add a "k", "M", "G", "T", "P", "E", "Z", "Y"
        // 1.0e3 = 1k
        // 1.0e6 = 1M
        // 1.0e9 = 1G
        // 1.0e12 = 1T
        // 1.0e15 = 1P
        // 1.0e18 = 1E
        String exponents = "kMGTPEZY";

        String amountString = String.valueOf(amount);
        int exponent = (int)(Math.log((double)amount)/Math.log(10));
        int exponent3 = exponent/3;
        if(exponent3 > 0)
        {
            int modValue = (exponent)%3+1;
            String firstPart = amountString.substring(0, modValue);
            if(firstPart.isEmpty())
                firstPart = "0";
            String secondPart = amountString.substring(modValue, modValue+2);

            amountString = firstPart+"."+secondPart+exponents.charAt(exponent3-1);
            //int exponent3mod = exponent%3;
            //if(exponent3mod == 0)
            //    return amountString.substring(0, amountString.length()-exponent3*3)+"."+amountString.substring(amountString.length()-exponent3*3, amountString.length()-exponent3*3+2)+" "+("kMGTPEZY".charAt(exponent3-1));
            //return amountString.substring(0, amountString.length()-exponent3*3+exponent3mod)+"."+amountString.substring(amountString.length()-exponent3*3+exponent3mod,amountString.length()-exponent3*3+2)+" "+("kMGTPEZY".charAt(exponent3-1));
        }
        return amountString;
    }

}
