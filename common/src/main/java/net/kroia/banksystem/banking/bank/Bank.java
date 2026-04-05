package net.kroia.banksystem.banking.bank;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.api.IBank;
import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.item.BankSystemItems;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.JsonUtilities;
import net.kroia.modutilities.persistence.ServerSaveable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Bank implements ServerSaveable, IBank {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        Bank.BACKEND_INSTANCES = backend;
    }


    public record BankSQL_Data(int bankAccountNr, ItemID itemID, long balance, long lockedBalance)
    {

    }

    protected long balance;
    protected long lockedBalance;
    private ItemID itemID;

    private Bank()
    {

    }
    private Bank(ItemID itemID, long balance)
    {
        this.itemID = itemID;
        this.balance = Math.max(balance, 0); // Ensure balance is not negative
        this.lockedBalance = 0;
    }

    public static @Nullable Bank create(ItemID itemID, long balance) {
        if (itemID == null || balance < 0) {
            return null; // Invalid parameters
        }
        ServerBankManager bankManager = (ServerBankManager)BACKEND_INSTANCES.SERVER_BANK_MANAGER;
        if(!bankManager.isItemIDAllowed_direct(itemID)) {
            return null; // Item not allowed in bank
        }

        return new Bank(itemID, balance);
    }
    public static @Nullable Bank createFromTag(CompoundTag tag)
    {
        Bank bank = new Bank();
        if(bank.load(tag)) {
            return bank;
        }
        return null; // Invalid data
    }

    public BankData getMinimalData() {
        return new BankData(
                itemID,
                balance,
                lockedBalance
        );
    }

    /*public static Bank loadFromTag(BankUserOld owner, CompoundTag tag) {
        BankType type = BankType.valueOf(tag.getString("BankType"));
        return switch (type) {
            case MONEY -> new MoneyBank(owner, tag);
            case ITEM -> new ItemBank(owner, tag);
        };
    }*/


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
    public double getRealBalance() {
        return convertToRealAmount(balance);
    }
    @Override
    public double getRealLockedBalance() {
        return convertToRealAmount(lockedBalance);
    }
    @Override
    public double getRealTotalBalance() {
        return convertToRealAmount(balance + lockedBalance);
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
            /*warnUser(BankSystemTextMessages.getProblemWhileTryingSetBalanceMessage(getItemName(),
                    getFormattedAmount(this.balance, getItemFractionScaleFactor()),
                    getFormattedAmount(balance, getItemFractionScaleFactor()),
                    getFormattedAmount(lockedBalance, getItemFractionScaleFactor()),
                    getFormattedAmount(balance, getItemFractionScaleFactor())));

             */

            lockedBalance = balance;
            setBalanceInternal(0);
            return false;
        }

        setBalanceInternal(newBalance);
        //if(owner.isBankNotificationEnabled())
        //    notifyUser(BankSystemTextMessages.getSetBalanceMessage(getFormattedAmount(getBalance(), getItemFractionScaleFactor()), getItemName(), owner.getPlayerName()));
        return true;
    }

    @Override
    public boolean setRealBalance(double balance)
    {
        return setBalance(convertToRawAmount(balance));
    }

   /* @Override
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
    }*/




    @Override
    public Status deposit(long amount) {
        if(amount < 0)
            return Status.FAILED_NEGATIVE_VALUE;
        if(willOverflow(amount))
            return Status.FAILED_OVERFLOW;
        addBalanceInternal(amount);
        //if(owner.isBankNotificationEnabled())
        //    notifyUser(BankSystemTextMessages.getAddedMessage(getFormattedAmount(amount, getItemFractionScaleFactor()), getItemName(), owner.getPlayerName()));
        return Status.SUCCESS;
    }

    @Override
    public Status depositReal(double amount) {
        return deposit(convertToRawAmount(amount));
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
        //if(owner.isBankNotificationEnabled())
        //    notifyUser(BankSystemTextMessages.getRemovedMessage(getFormattedAmount(amount, getItemFractionScaleFactor()), getItemName(), owner.getPlayerName()));
        return Status.SUCCESS;
    }

    @Override
    public Status withdrawReal(double amount) {
        return withdraw(convertToRawAmount(amount));
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
    public Status withdrawLockedReal(double amount) {
        return withdrawLocked(convertToRawAmount(amount));
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
    public Status withdrawLockedPreferedReal(double amount) {
        return withdrawLockedPrefered(convertToRawAmount(amount));
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
            //notifyUser_transfer(amount, other);
            return Status.SUCCESS;
        }
        addBalanceInternal(amount);
        return otherStatus;
    }
    @Override
    public Status transferReal(double amount, @NotNull IBank other) {
        return transfer(convertToRawAmount(amount), other);
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
            //notifyUser_transfer(amount, other);
            return Status.SUCCESS;
        }
        lockedBalance += amount;
        return otherStatus;
    }
    @Override
    public Status transferFromLockedReal(double amount, @NotNull IBank other) {
        return transferFromLocked(convertToRawAmount(amount), other);
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
                //notifyUser_transfer(origAmount, other);
                return Status.SUCCESS;
            }
            lockedBalance = lastLocked;
            return otherStatus;
        }

        lockedBalance -= amount;
        Status otherStatus = other.deposit(amount);
        if(otherStatus == Status.SUCCESS) {
            //notifyUser_transfer(amount, other);
            return Status.SUCCESS;
        }
        lockedBalance = lastLocked;
        return otherStatus;
    }
    @Override
    public Status transferFromLockedPreferedReal(double amount, @NotNull IBank other) {
        return transferFromLockedPrefered(convertToRawAmount(amount), other);
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
    public Status lockAmountReal(double amount) {
        return lockAmount(convertToRawAmount(amount));
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
    public Status unlockAmountReal(double amount) {
        return unlockAmount(convertToRawAmount(amount));
    }

    @Override
    public void unlockAll()
    {
        addBalanceInternal(lockedBalance);
        lockedBalance = 0;
    }

    @Override
    public long convertToRawAmount(double realAmount)
    {
        return convertToRawAmountStatic(realAmount);
    }

    @Override
    public double convertToRealAmount(long rawAmount)
    {
        return convertToRealAmountStatic(rawAmount);
    }

    //public static long convertToRawAmountStatic(double realAmount)
    //{
    //    return Math.round(realAmount * BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR);
    //}
    public static long convertToRawAmountStatic(double realAmount)
    {
        return Math.round(realAmount * BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR);
    }
    public static long convertToRawAmountStatic(String realTextboxText) // 1864165.05
    {
        if(realTextboxText == null)
            return 0;
        if(realTextboxText.isEmpty())
            return 0;
        int decimalPlaces = realTextboxText.lastIndexOf(".");
        if(decimalPlaces == -1)
            decimalPlaces =  realTextboxText.lastIndexOf(",");
        try {
            if (decimalPlaces == -1)
                return Long.parseLong(realTextboxText) * BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
        }catch (NumberFormatException e) {
            return 0;
        }
        long A = 0;
        long B = 0;
        try{
            A = Long.parseLong(realTextboxText.substring(0, decimalPlaces)) * BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
        }catch (NumberFormatException ignored) {

        }
        try {
            B = Long.parseLong(realTextboxText.substring(decimalPlaces + 1));
        }catch (NumberFormatException ignored) {

        }
        return A + B;
    }
    public static double convertToRealAmountStatic(long rawAmount)
    {
        return (float)rawAmount / (float)BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
    }
    //public static float convertToRealAmountStatic(double rawAmount)
    //{
    //    return (float)rawAmount / (float)BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
    //}

    @Override
    public String getNormalizedBalance()
    {
        return getNormalizedAmount(balance);
    }

    @Override
    public String getNormalizedLockedBalance()
    {
        return getNormalizedAmount(lockedBalance);
    }

    @Override
    public String getNormalizedTotalBalance()
    {
        return getNormalizedAmount(balance + lockedBalance);
    }

    @Override
    public String getFormattedBalance()
    {
        return getFormattedAmount(balance);
    }

    @Override
    public String getFormattedLockedBalance()
    {
        return getFormattedAmount(lockedBalance);
    }

    @Override
    public String getFormattedTotalBalance()
    {
        return getFormattedAmount(balance + lockedBalance);
    }

    //@Override
    //public String getNormalizedAmount(float realAmount)
    //{
    //    long amount = convertToRawAmountStatic(realAmount);
    //    return getNormalizedAmountStatic(amount);
    //}
    @Override
    public String getNormalizedAmount(double realAmount)
    {
        long amount = convertToRawAmountStatic(realAmount);
        return getNormalizedAmountStatic(amount);
    }
    @Override
    public String getNormalizedAmount(long rawAmount)
    {
        return getNormalizedAmountStatic(rawAmount);
    }
    //@Override
    //public String getFormattedAmount(float realAmount)
    //{
    //    long amount = convertToRawAmountStatic(realAmount);
    //    return getFormattedAmountStatic(amount);
    //}
    @Override
    public String getFormattedAmount(double realAmount)
    {
        long amount = convertToRawAmountStatic(realAmount);
        return getFormattedAmountStatic(amount);
    }
    @Override
    public String getFormattedAmount(long rawAmount)
    {
        return getFormattedAmountStatic(rawAmount);
    }

    @Override
    public String toString()
    {
        return toJsonString();
        //return "Owner: "+getPlayerName()+" "+toStringNoOwner();
    }

    @Override
    public String toStringNoOwner()
    {
        StringBuilder content = new StringBuilder(getItemName() + getFormattedTotalBalance());
        if(lockedBalance > 0) {
            content.append("(").append(BankSystemTextMessages.getBalanceDetailedMessage(
                    getFormattedAmount(balance),
                    getFormattedAmount(lockedBalance))).append(")");
        }

        return content.toString();
    }

    @Override
    public JsonElement toJson()
    {
        JsonObject json = new JsonObject();
        json.add("itemID", itemID.toJson());
        json.addProperty("balance", getNormalizedBalance());
        json.addProperty("lockedBalance", getNormalizedLockedBalance());
        return json;
    }
    @Override
    public String toJsonString()
    {
        return JsonUtilities.toPrettyString(toJson());
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

        if(tag.contains("itemID",Tag.TAG_STRING))
        {
            String itemIDStr = tag.getString("itemID");
            itemID = null; // Reset itemID
            if(itemIDStr.equals("$") || itemIDStr.equals("money")) {
                itemID = ItemID.getFromItemStack(BankSystemItems.MONEY.get().getDefaultInstance());
            }
            else {
                ItemStack itemStack = ItemUtilities.createItemStackFromId(itemIDStr);
                if (itemStack == ItemStack.EMPTY || itemStack == null || itemStack.is(Items.AIR))
                    itemID = null; // Invalid item ID
                else {
                    itemID = ItemID.getFromItemStack(itemStack);
                }
            }
        }


        //itemID = MoneyBank.compatibilityMoneyItemIDConvert(tag.getString("itemID"));
        // Compatibility with old money item ID format
        if(itemID == null)
        {
            CompoundTag itemTag = tag.getCompound("itemID");
            itemID = ItemID.createFromTag(itemTag);
        }


        long balance = tag.getLong("balance");
        lockedBalance = tag.getLong("lockedBalance");

        /*if(!tag.contains("centScaleFactor"))
        {
            centScaleFactor = (long)tag.getInt("centScaleFactor");
        }*/
        setBalanceInternal(balance);
        return balance >= 0 && lockedBalance >= 0 && itemID != null;
    }

    private void addBalanceInternal(long balance) {
        setBalanceInternal(this.balance + balance);
    }
    private void setBalanceInternal(long balance) {
        /*if(balance < 0) {
            dbg_invalid_balance(balance, getItemFractionScaleFactor());
        }*/

        this.balance = balance;
    }

    /*private static void dbg_invalid_balance(long balance, int centScaleFactor) {
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
    }*/




    // (1000 means 10.00 currency units)
    public static String getNormalizedAmountStatic(long amount)
    {
        // depending on the exponent of the amount add a "k", "M", "G", "T", "P", "E", "Z", "Y"
        // 1.0e3 = 1k
        // 1.0e6 = 1M
        // 1.0e9 = 1G
        // 1.0e12 = 1T
        // 1.0e15 = 1P
        // 1.0e18 = 1E
        String exponents = "kMGTPEZY";

        long wholeUnits = amount / BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;



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
            float cents = (amount % BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR) / (float)BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR; // Convert to cents

            String centsString = String.valueOf((cents));

            // Remove "0." prefix if cents are zero
            if(centsString.startsWith("0.")) {
                centsString = centsString.substring(2);
            }
            if(BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR > 1)
                amountString = amountString + "." + centsString;
        }
        return amountString;
    }
    public static String getNormalizedAmountStatic(double realAmount)
    {
        long amount = convertToRawAmountStatic(realAmount);
        return getNormalizedAmountStatic(amount);
    }

    public static String getFormattedAmountStatic(double realAmount)
    {
        long amount = convertToRawAmountStatic(realAmount);
        return getFormattedAmountStatic(amount);
    }

    public static String getFormattedAmountStatic(long rawAmount)
    {
        String nr = String.valueOf(rawAmount/ BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR);
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
        if(rawAmount % BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR != 0)
        {

            float cents = (rawAmount % BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR) / (float)BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR; // Convert to cents

            String centsString = String.valueOf(cents);

            // Remove "0." prefix if cents are zero
            if(centsString.startsWith("0.")) {
                centsString = centsString.substring(1);
            }
            sb.append(centsString);
        }
        return sb.toString();
    }
    public static String getTextFieldString(long amount)
    {
        long A = amount / BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
        long B = amount % BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;

        StringBuilder sb = new StringBuilder();
        sb.append(A);
        sb.append(".");

        // Add filling zeros if needed
        int digitsCount = (int)Math.log10(BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR);
        String BStr = String.valueOf(B);
        int addZeroCount = digitsCount - BStr.length();
        sb.append("0".repeat(Math.max(0, addZeroCount)));
        sb.append(BStr);
        return sb.toString();
    }

    public static int getMaxDecimalDigitsCount()
    {
        return (int)Math.log10(BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR);
        /*if(BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR == 1)
            return 0;
        String centsString = String.valueOf(1.f/BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR);
        // Remove "0." prefix if cents are zero
        centsString = centsString.substring(2);
        // Count the number of digits after the decimal point
        return centsString.length();*/
    }

    private boolean willOverflow(long tryToAddAmount)
    {
        return willAdditionOverflow(balance + lockedBalance, tryToAddAmount);
    }
    private static boolean willAdditionOverflow(long a, long b) {
        return Long.MAX_VALUE - a < b;
    }



}
