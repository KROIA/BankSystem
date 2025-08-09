package net.kroia.banksystem.banking.bank;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.IBank;
import net.kroia.banksystem.banking.BankUser;
import net.kroia.banksystem.banking.clientdata.MinimalBankData;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.kroia.modutilities.ServerSaveable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public abstract class Bank implements ServerSaveable, IBank {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        Bank.BACKEND_INSTANCES = backend;
    }
    private static final Component WARNING = Component.translatable("message."+ BankSystemMod.MOD_ID+".bank.warning");
    private static final Component INFO = Component.translatable("message."+BankSystemMod.MOD_ID+".bank.info");


    private final BankUser owner;

    protected long balance;
    protected long lockedBalance;
   // protected long centScaleFactor = 1;
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
        return switch (type) {
            case MONEY -> new MoneyBank(owner, tag);
            case ITEM -> new ItemBank(owner, tag);
        };
    }

    @Override
    public final int getItemFractionScaleFactor()
    {
        return BACKEND_INSTANCES.SERVER_BANK_MANAGER.getItemFractionScaleFactor(itemID);
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
            warnUser(BankSystemTextMessages.getProblemWhileTryingSetBalanceMessage(getItemName(),
                    getFormattedAmount(this.balance, getItemFractionScaleFactor()),
                    getFormattedAmount(balance, getItemFractionScaleFactor()),
                    getFormattedAmount(lockedBalance, getItemFractionScaleFactor()),
                    getFormattedAmount(balance, getItemFractionScaleFactor())));

            lockedBalance = balance;
            setBalanceInternal(0);
            return false;
        }

        setBalanceInternal(newBalance);
        if(owner.isBankNotificationEnabled())
            notifyUser(BankSystemTextMessages.getSetBalanceMessage(getFormattedAmount(getBalance(), getItemFractionScaleFactor()), getItemName(), owner.getPlayerName()));
        return true;
    }

    @Override
    public UUID getPlayerUUID() {
        return owner.getPlayerUUID();
    }

    @Override
    public @Nullable ServerPlayer getUser()
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
        if(owner.isBankNotificationEnabled())
            notifyUser(BankSystemTextMessages.getAddedMessage(getFormattedAmount(amount, getItemFractionScaleFactor()), getItemName(), owner.getPlayerName()));
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
        if(owner.isBankNotificationEnabled())
            notifyUser(BankSystemTextMessages.getRemovedMessage(getFormattedAmount(amount, getItemFractionScaleFactor()), getItemName(), owner.getPlayerName()));
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
    public Status transfer(long amount, @NotNull IBank other) {
        if(amount < 0)
            return Status.FAILED_NEGATIVE_VALUE;
        if (balance < amount) {
            return Status.FAILED_NOT_ENOUGH_FUNDS;
        }
        if(other == this)
            return Status.SUCCESS;

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
    public Status transferFromLocked(long amount, @NotNull IBank other) {
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
    public Status transferFromLockedPrefered(long amount, @NotNull IBank other) {
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

    public static Status exchangeFromLockedPrefered(@NotNull IBank from1,@NotNull IBank to1, long amount1,
                                                    @NotNull IBank from2,@NotNull IBank to2, long amount2)
    {
        if(amount1 < 0 || amount2 < 0)
            return Status.FAILED_NEGATIVE_VALUE;

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
        if(amount < 0)
            return Status.FAILED_NEGATIVE_VALUE;
        if (balance < amount) {
            return Status.FAILED_NOT_ENOUGH_FUNDS;
        }
        addBalanceInternal(-amount);
        lockedBalance += amount;
        return Status.SUCCESS;
    }

    @Override
    public Status unlockAmount(long amount) {
        if(amount < 0)
            return Status.FAILED_NEGATIVE_VALUE;
        if (lockedBalance < amount) {
            return Status.FAILED_NOT_ENOUGH_FUNDS;
        }
        addBalanceInternal(amount);
        lockedBalance -= amount;
        return Status.SUCCESS;
    }

    @Override
    public void unlockAll()
    {
        addBalanceInternal(lockedBalance);
        lockedBalance = 0;
    }

    @Override
    public long convertToRawAmount(float realAmount)
    {
        return (long)(realAmount * getItemFractionScaleFactor());
    }

    @Override
    public float convertToRealAmount(long rawAmount)
    {
        return (float)rawAmount / getItemFractionScaleFactor();
    }

    @Override
    public String getNormalizedAmount(float realAmount)
    {
        return getNormalizedAmount(realAmount, getItemFractionScaleFactor());
    }
    @Override
    public String getNormalizedAmount(long rawAmount)
    {
        return getNormalizedAmount(rawAmount, getItemFractionScaleFactor());
    }
    @Override
    public String getFormattedAmount(float realAmount)
    {
        return getFormattedAmount(realAmount, getItemFractionScaleFactor());
    }
    @Override
    public String getFormattedAmount(long rawAmount)
    {
        return getFormattedAmount(rawAmount, getItemFractionScaleFactor());
    }

    @Override
    public String toString()
    {
        return "Owner: "+getPlayerName()+" "+toStringNoOwner();
    }

    @Override
    public String toStringNoOwner()
    {
        StringBuilder content = new StringBuilder(getItemName() +BankSystemTextMessages.getBalanceMessage(getFormattedAmount(balance+lockedBalance, getItemFractionScaleFactor())));
        if(lockedBalance > 0) {
            content.append("(").append(BankSystemTextMessages.getBalanceDetailedMessage(
                    getFormattedAmount(balance, getItemFractionScaleFactor()),
                    getFormattedAmount(lockedBalance, getItemFractionScaleFactor()))).append(")");
        }

        return content.toString();
    }

    @Override
    public boolean save(CompoundTag tag) {
        CompoundTag itemTag = new CompoundTag();
        itemID.save(itemTag);
        //tag.putInt("centScaleFactor", (int)centScaleFactor); // Marker for backwards compatibility, to check if this value exists or not.
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


        long balance = tag.getLong("balance");
        lockedBalance = tag.getLong("lockedBalance");

        /*if(!tag.contains("centScaleFactor"))
        {
            centScaleFactor = (long)tag.getInt("centScaleFactor");
        }*/
        setBalanceInternal(balance);
        return balance >= 0 && lockedBalance >= 0;
    }

    private void addBalanceInternal(long balance) {
        setBalanceInternal(this.balance + balance);
    }
    private void setBalanceInternal(long balance) {
        if(balance < 0) {
            dbg_invalid_balance(balance, getItemFractionScaleFactor());
        }

        this.balance = balance;
    }

    private static void dbg_invalid_balance(long balance, int centScaleFactor) {
        throw new IllegalArgumentException("Balance is negative: "+getFormattedAmount(balance, centScaleFactor));
    }

    protected void notifyUser_transfer(long amount, IBank other) {
        if (amount == 0)
            return;

        if (owner.isBankNotificationEnabled())
            notifyUser(BankSystemTextMessages.getTransferedMessage(getFormattedAmount(amount, getItemFractionScaleFactor()), getItemName(), other.getPlayerName()));
        if (other instanceof  Bank casted){
            if (casted.owner.isBankNotificationEnabled())
                casted.notifyUser(BankSystemTextMessages.getReceivedMessage(getFormattedAmount(amount, getItemFractionScaleFactor()), getItemName(), owner.getPlayerName()));
        }
    }

    protected void warnUser(String msg) {
        ServerPlayerUtilities.printToClientConsole(getPlayerUUID(), WARNING.getString()+msg);
    }

    protected void notifyUser(String msg) {
        ServerPlayerUtilities.printToClientConsole(getPlayerUUID(), INFO.getString()+msg);
    }




    // (1000 means 10.00 currency units)
    public static String getNormalizedAmount(long amount, int itemFractionScaleFactor)
    {
        // depending on the exponent of the amount add a "k", "M", "G", "T", "P", "E", "Z", "Y"
        // 1.0e3 = 1k
        // 1.0e6 = 1M
        // 1.0e9 = 1G
        // 1.0e12 = 1T
        // 1.0e15 = 1P
        // 1.0e18 = 1E
        String exponents = "kMGTPEZY";

        long wholeUnits = amount / itemFractionScaleFactor;
        long cents = amount % itemFractionScaleFactor;

        // Remove trailing zeros from cents
        int leadingZeros = (int)Math.log10(itemFractionScaleFactor);
        while(cents % 10 == 0 && cents > 0) {
            cents /= 10;
            leadingZeros--;
        }
        StringBuilder centsString = new StringBuilder(String.valueOf(cents)); // Ensure cents are always two digits
        // Fill leading zeros if necessary
        while (centsString.length() < leadingZeros) {
            centsString.insert(0, "0");
        }


        String amountString = String.valueOf(wholeUnits);
        int exponent = (int)(Math.log((double)wholeUnits)/Math.log(10));
        int exponent3 = exponent/3;
        if(exponent3 > 0)
        {
            int modValue = (exponent)%3+1;
            String firstPart = amountString.substring(0, modValue);
            if(firstPart.isEmpty())
                firstPart = "0";
            String secondPart = amountString.substring(modValue, modValue+2);

            amountString = firstPart+"."+secondPart + exponents.charAt(exponent3-1);
        }
        else
        {
            if(itemFractionScaleFactor > 1)
                amountString = amountString + "." + centsString;
        }
        return amountString;
    }
    public static String getNormalizedAmount(float realAmount, int centScaleFactor)
    {
        long amount = (long)(realAmount * centScaleFactor);
        return getFormattedAmount(amount, centScaleFactor);
    }
    public static String getFormattedAmount(long amount, int itemFractionScaleFactor)
    {
        String nr = String.valueOf(amount/itemFractionScaleFactor);
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
        sb.reverse();
        if(amount % itemFractionScaleFactor != 0)
        {
            sb.append('.');
            long cents = amount % itemFractionScaleFactor;

            // remove trailing zeros
            int leadingZeros = (int)Math.log10(itemFractionScaleFactor);
            while(cents % 10 == 0 && cents > 0) {
                cents /= 10;
                leadingZeros--;
            }

            StringBuilder centsString = new StringBuilder(String.valueOf(cents)); // Ensure cents are always two digits
            // Fill leading zeros if necessary
            while (centsString.length() < leadingZeros) {
                centsString.insert(0, "0");
            }

            sb.append(centsString);
        }
        return sb.toString();
    }
    public static String getFormattedAmount(float realAmount, int centScaleFactor)
    {
        long amount = (long)(realAmount * centScaleFactor);
        return getFormattedAmount(amount, centScaleFactor);
    }

    private boolean willOverflow(long tryToAddAmount)
    {
        return willAdditionOverflow(balance + lockedBalance, tryToAddAmount);
    }
    private static boolean willAdditionOverflow(long a, long b) {
        return Long.MAX_VALUE - a < b;
    }



}
