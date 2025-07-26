package net.kroia.banksystem.banking.bank;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.api.IBank;
import net.kroia.banksystem.banking.BankUser;
import net.kroia.banksystem.banking.clientdata.MinimalBankData;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.PlayerUtilities;
import net.kroia.modutilities.ServerSaveable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class Bank implements ServerSaveable, IBank {
    private static final Component WARNING = Component.translatable("message."+ BankSystemMod.MOD_ID+".bank.warning");
    private static final Component INFO = Component.translatable("message."+BankSystemMod.MOD_ID+".bank.info");


    private final BankUser owner;
    protected long balance;
    protected long lockedBalance;
    private ItemID itemID;


    public Bank(BankUser owner, ItemID itemID, long balance) {
        this.owner = owner;
        this.itemID = itemID;
        balance = Math.max(balance, 0); // Ensure balance is not negative
        setBalanceInternal(balance);
        this.lockedBalance = 0;
    }
    public Bank(BankUser owner, CompoundTag tag) {
        this.owner = owner;
        load(tag);
    }

    public MinimalBankData getMinimalData() {
        return new MinimalBankData(this);
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


    @Override
    public long getBalance() {
        return balance;
    }
    @Override
    public long getLockedBalance() {
        return lockedBalance;
    }

    @Override
    public long getTotalBalance() {
        return balance + lockedBalance;
    }
    @Override
    public ItemID getItemID() {
        return itemID;
    }
    @Override
    public String getItemName()
    {
        String name = itemID.getName();
        if(name == null)
            return "unknown";
        if(name.contains(":"))
            return name.substring(name.lastIndexOf(":")+1);
        return name;
    }

    @Override
    public boolean setBalance(long balance) {
        if(balance < 0)
            return false;
        long newBalance = balance - this.lockedBalance;
        if(newBalance < 0)
        {
            warnUser(BankSystemTextMessages.getProblemWhileTryingSetBalanceMessage(getItemName(), this.balance, balance, lockedBalance, balance));

            lockedBalance = balance;
            setBalanceInternal(0);
            return false;
        }

        setBalanceInternal(newBalance);
        if(owner.isBankNotificationEbabled())
            notifyUser(BankSystemTextMessages.getSetBalanceMessage(getBalance(), getItemName(), owner.getPlayerName()));
        return true;
    }

    @Override
    public UUID getPlayerUUID() {
        return owner.getPlayerUUID();
    }

    @Override
    public ServerPlayer getUser()
    {
        return owner.getPlayer();
    }

    @Override
    public String getPlayerName()
    {
        return owner.getPlayerName();
    }


    @Override
    public Status deposit(long amount) {
        if(amount < 0)
            return Status.FAILED_NEGATIVE_VALUE;
        if(willOverflow(amount))
            return Status.FAILED_OVERFLOW;
        addBalanceInternal(amount);
        if(owner.isBankNotificationEbabled())
            notifyUser(BankSystemTextMessages.getAddedMessage(amount, getItemName(), owner.getPlayerName()));
        return Status.SUCCESS;
    }


    @Override
    public boolean hasSufficientFunds(long amount) {
        return balance >= amount;
    }

    @Override
    public Status withdraw(long amount) {
        if(amount < 0)
            return Status.FAILED_NEGATIVE_VALUE;
        if (balance < amount) {
            return Status.FAILED_NOT_ENOUGH_FUNDS;
        }
        addBalanceInternal(-amount);
        if(owner.isBankNotificationEbabled())
            notifyUser(BankSystemTextMessages.getRemovedMessage(amount, getItemName(), owner.getPlayerName()));
        return Status.SUCCESS;
    }

    @Override
    public Status withdrawLocked(long amount) {
        if(amount < 0)
            return Status.FAILED_NEGATIVE_VALUE;
        if (lockedBalance < amount) {
            return Status.FAILED_NOT_ENOUGH_FUNDS;
        }
        lockedBalance -= amount;
        return Status.SUCCESS;
    }

    @Override
    public Status withdrawLockedPrefered(long amount) {
        if(amount < 0)
            return Status.FAILED_NEGATIVE_VALUE;
        long origAmount = amount;
        long lastLocked = lockedBalance;
        if (lockedBalance < amount) {
            if (balance+lockedBalance < amount) {
                return Status.FAILED_NOT_ENOUGH_FUNDS;
            }
            amount -= lockedBalance;
            lockedBalance = 0;
            addBalanceInternal(-amount);
            return Status.SUCCESS;
        }

        lockedBalance -= amount;
        return Status.SUCCESS;
    }

    @Override
    public Status transfer(long amount, IBank other) {
        if(amount < 0)
            return Status.FAILED_NEGATIVE_VALUE;
        if (balance < amount) {
            return Status.FAILED_NOT_ENOUGH_FUNDS;
        }
        if(other == this)
            return Status.SUCCESS;
        dbg_checkValueIsNegative(amount);

        addBalanceInternal(-amount);
        Status otherStatus = other.deposit(amount);
        if(otherStatus == Status.SUCCESS) {
            notifyUser_transfer(amount, other);
            return Status.SUCCESS;
        }
        addBalanceInternal(amount);
        return otherStatus;
    }

    @Override
    public Status transferFromLocked(long amount, IBank other) {
        if(amount < 0)
            return Status.FAILED_NEGATIVE_VALUE;
        if (lockedBalance < amount) {
            return Status.FAILED_NOT_ENOUGH_FUNDS;
        }
        lockedBalance -= amount;
        Status otherStatus = other.deposit(amount);
        if(otherStatus == Status.SUCCESS) {
            notifyUser_transfer(amount, other);
            return Status.SUCCESS;
        }
        lockedBalance += amount;
        return otherStatus;
    }

    @Override
    public Status transferFromLockedPrefered(long amount, IBank other) {
        if(amount < 0)
            return Status.FAILED_NEGATIVE_VALUE;
        long origAmount = amount;
        long lastLocked = lockedBalance;
        if (lockedBalance < amount) {
            if (balance+lockedBalance < amount) {
                return Status.FAILED_NOT_ENOUGH_FUNDS;
            }
            amount -= lockedBalance;
            lockedBalance = 0;
            Status otherStatus = other.deposit(origAmount);
            if(otherStatus == Status.SUCCESS) {
                addBalanceInternal(-amount);
                notifyUser_transfer(origAmount, other);
                return Status.SUCCESS;
            }
            lockedBalance = lastLocked;
            return otherStatus;
        }

        lockedBalance -= amount;
        Status otherStatus = other.deposit(amount);
        if(otherStatus == Status.SUCCESS) {
            notifyUser_transfer(amount, other);
            return Status.SUCCESS;
        }
        lockedBalance = lastLocked;
        return otherStatus;
    }

    public static Status exchangeFromLockedPrefered(IBank from1, IBank to1, long amount1, IBank from2, IBank to2, long amount2)
    {
        dbg_checkValueIsNegative(amount1);
        dbg_checkValueIsNegative(amount2);
        if ((from1 instanceof  Bank castedFrom1) &&
            (to1 instanceof  Bank castedTo1) &&
            (from2 instanceof  Bank castedFrom2) &&
            (to2 instanceof  Bank castedTo2))
        {
            // Both transactions must be possible, otherwise no transaction is done
            // Copy original data
            long origFrom1LockedBalance1 = castedFrom1.lockedBalance;
            long origFrom2LockedBalance2 = castedFrom2.lockedBalance;
            long origFrom1Balance1 = castedFrom1.balance;
            long origFrom2Balance2 = castedFrom2.balance;
            long origTo1LockedBalance1 = castedTo1.lockedBalance;
            long origTo2LockedBalance2 = castedTo2.lockedBalance;
            long origTo1Balance1 = castedTo1.balance;
            long origTo2Balance2 = castedTo2.balance;


            // Try to transfer from locked balance
            Status status1 = from1.transferFromLockedPrefered(amount1, to1);
            Status status2 = from2.transferFromLockedPrefered(amount2, to2);
            if(status1 == Status.SUCCESS && status2 == Status.SUCCESS)
            {
                return Status.SUCCESS;
            }
            // If not possible, revert changes
            castedFrom1.lockedBalance = origFrom1LockedBalance1;
            castedFrom2.lockedBalance = origFrom2LockedBalance2;
            castedFrom1.balance = origFrom1Balance1;
            castedFrom2.balance = origFrom2Balance2;
            castedTo1.lockedBalance = origTo1LockedBalance1;
            castedTo2.lockedBalance = origTo2LockedBalance2;
            castedTo1.balance = origTo1Balance1;
            castedTo2.balance = origTo2Balance2;
            if(status1 == Status.SUCCESS)
                return status2;
            return status1;
        }
        return Status.FAILED_WRONG_INSTANCE_TYPE;
    }

    @Override
    public Status lockAmount(long amount) {
        dbg_checkValueIsNegative(amount);
        if (balance < amount) {
            return Status.FAILED_NOT_ENOUGH_FUNDS;
        }
        addBalanceInternal(-amount);
        lockedBalance += amount;
        dbg_checkValueIsNegative(lockedBalance);
        return Status.SUCCESS;
    }

    @Override
    public Status unlockAmount(long amount) {
        dbg_checkValueIsNegative(amount);
        if (lockedBalance < amount) {
            return Status.FAILED_NOT_ENOUGH_FUNDS;
        }
        addBalanceInternal(amount);
        lockedBalance -= amount;
        return Status.SUCCESS;
    }

    public void unlockAll()
    {
        addBalanceInternal(lockedBalance);
        lockedBalance = 0;
    }

    @Override
    public String toString()
    {
        return "Owner: "+getPlayerName()+" "+toStringNoOwner();
    }

    @Override
    public String toStringNoOwner()
    {
        //StringBuilder content = new StringBuilder(getItemName() +" Balance: "+(balance+lockedBalance));
        StringBuilder content = new StringBuilder(getItemName() +BankSystemTextMessages.getBalanceMessage(balance+lockedBalance));
        if(lockedBalance > 0)
            content.append("("+BankSystemTextMessages.getBalanceDetailedMessage(balance, lockedBalance)+")");

        return content.toString();
    }

    @Override
    public boolean save(CompoundTag tag) {
        CompoundTag itemTag = new CompoundTag();
        itemID.save(itemTag);
        tag.put("itemID", itemTag);
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
        // Compatibility with old money item ID format
        if(itemID == null)
        {
            CompoundTag itemTag = tag.getCompound("itemID");
            itemID = new ItemID(itemTag);
        }


        setBalanceInternal(tag.getLong("balance"));
        lockedBalance = tag.getLong("lockedBalance");
        return balance >= 0 && lockedBalance >= 0;
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


    protected void notifyUser_transfer(long amount, IBank other) {
        if (amount == 0)
            return;

        if (owner.isBankNotificationEbabled())
            notifyUser(BankSystemTextMessages.getTransferedMessage(amount, getItemName(), other.getPlayerName()));
        if (other instanceof  Bank casted){
            if (casted.owner.isBankNotificationEbabled())
                casted.notifyUser(BankSystemTextMessages.getReceivedMessage(amount, getItemName(), owner.getPlayerName()));
        }
    }

    protected void warnUser(String msg) {
        PlayerUtilities.printToClientConsole(getPlayerUUID(), WARNING.getString()+msg);
    }

    protected void notifyUser(String msg) {
        PlayerUtilities.printToClientConsole(getPlayerUUID(), INFO.getString()+msg);
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
        }
        return amountString;
    }
    public static String getFormattedAmount(long amount)
    {
        String nr = String.valueOf(amount);
        // add ' for every 3 digits
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for(int j = nr.length()-1; j >= 0; j--)
        {
            sb.append(nr.charAt(j));
            i++;
            if(i % 3 == 0 && j > 0)
                sb.append('\'');
        }
        return sb.reverse().toString();
    }

    private boolean willOverflow(long tryToAddAmount)
    {
        return Long.MAX_VALUE - tryToAddAmount < (balance+lockedBalance);
    }



}
