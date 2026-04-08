package net.kroia.banksystem.banking.bank;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.IAsyncBank;
import net.kroia.banksystem.api.bank.ISyncServerBank;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
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

import java.util.concurrent.CompletableFuture;

public class SyncServerBank implements ServerSaveable, ISyncServerBank, IAsyncBank {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        SyncServerBank.BACKEND_INSTANCES = backend;
    }


    public record BankSQL_Data(int bankAccountNr, ItemID itemID, long balance, long lockedBalance)
    {

    }

    protected long balance;
    protected long lockedBalance;
    private ItemID itemID;

    private SyncServerBank()
    {

    }
    private SyncServerBank(ItemID itemID, long balance)
    {
        this.itemID = itemID;
        this.balance = Math.max(balance, 0); // Ensure balance is not negative
        this.lockedBalance = 0;
    }

    public static @Nullable SyncServerBank create(ItemID itemID, long balance) {
        if (itemID == null || balance < 0) {
            return null; // Invalid parameters
        }
        ISyncServerBankManager bankManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        if(!bankManager.isItemIDAllowed(itemID)) {
            return null; // Item not allowed in bank
        }

        return new SyncServerBank(itemID, balance);
    }
    public static @Nullable SyncServerBank createFromTag(CompoundTag tag)
    {
        SyncServerBank bank = new SyncServerBank();
        if(bank.load(tag)) {
            return bank;
        }
        return null; // Invalid data
    }


    @Override
    public BankData getMinimalData() {
        return new BankData(
                itemID,
                balance,
                lockedBalance
        );
    }
    @Override
    public CompletableFuture<BankData> getMinimalDataAsync() {
        return CompletableFuture.completedFuture(getMinimalData());
    }


    @Override
    public long getBalance() {
        return balance;
    }
    @Override
    public CompletableFuture<Long> getBalanceAsync() {
        return CompletableFuture.completedFuture(getBalance());
    }



    @Override
    public long getLockedBalance() {
        return lockedBalance;
    }
    @Override
    public CompletableFuture<Long> getLockedBalanceAsync() {
        return CompletableFuture.completedFuture(getLockedBalance());
    }



    @Override
    public long getTotalBalance() {
        return balance + lockedBalance;
    }
    @Override
    public CompletableFuture<Long> getTotalBalanceAsync() {
        return CompletableFuture.completedFuture(getTotalBalance());
    }



    @Override
    public double getRealBalance() {
        return convertToRealAmount(balance);
    }
    @Override
    public CompletableFuture<Double> getRealBalanceAsync() {
        return CompletableFuture.completedFuture(getRealBalance());
    }



    @Override
    public double getRealLockedBalance() {
        return convertToRealAmount(lockedBalance);
    }
    @Override
    public CompletableFuture<Double> getRealLockedBalanceAsync() {
        return CompletableFuture.completedFuture(getRealLockedBalance());
    }



    @Override
    public double getRealTotalBalance() {
        return convertToRealAmount(balance + lockedBalance);
    }
    @Override
    public CompletableFuture<Double> getRealTotalBalanceAsync() {
        return CompletableFuture.completedFuture(getRealTotalBalance());
    }


    @Override
    public ItemID getItemID() {
        return itemID;
    }
    @Override
    public CompletableFuture<ItemID> getItemIDAsync() {
        return CompletableFuture.completedFuture(itemID);
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
    public CompletableFuture<String> getItemNameAsync()
    {
        return CompletableFuture.completedFuture(getItemName());
    }



    @Override
    public boolean setBalance(long balance) {
        if(balance < 0)
            return false;
        long newBalance = balance - this.lockedBalance;
        if(newBalance < 0)
        {
            lockedBalance = balance;
            setBalanceInternal(0);
            return false;
        }
        setBalanceInternal(newBalance);
        return true;
    }
    @Override
    public CompletableFuture<Boolean> setBalanceAsync(long balance) {
        return CompletableFuture.completedFuture(setBalance(balance));
    }



    @Override
    public boolean setRealBalance(double balance) {
        return setBalance(convertToRawAmount(balance));
    }
    @Override
    public CompletableFuture<Boolean> setRealBalanceAsync(double balance) {
        return CompletableFuture.completedFuture(setRealBalance(balance));
    }



    @Override
    public BankStatus deposit(long amount) {
        if(amount < 0)
            return BankStatus.FAILED_NEGATIVE_VALUE;
        if(willOverflow(amount))
            return BankStatus.FAILED_OVERFLOW;
        addBalanceInternal(amount);
        return BankStatus.SUCCESS;
    }
    @Override
    public CompletableFuture<BankStatus> depositAsync(long amount) {
        return CompletableFuture.completedFuture(deposit(amount));
    }



    @Override
    public BankStatus depositReal(double amount) {
        return deposit(convertToRawAmount(amount));
    }
    @Override
    public CompletableFuture<BankStatus> depositRealAsync(double amount) {
        return CompletableFuture.completedFuture(depositReal(amount));
    }



    @Override
    public boolean hasSufficientFunds(long amount) {
        return balance >= amount;
    }
    @Override
    public CompletableFuture<Boolean> hasSufficientFundsAsync(long amount) {
        return CompletableFuture.completedFuture(hasSufficientFunds(amount));
    }



    @Override
    public BankStatus withdraw(long amount) {
        if(amount < 0)
            return BankStatus.FAILED_NEGATIVE_VALUE;
        if (balance < amount) {
            return BankStatus.FAILED_NOT_ENOUGH_FUNDS;
        }
        addBalanceInternal(-amount);
        return BankStatus.SUCCESS;
    }
    @Override
    public CompletableFuture<BankStatus> withdrawAsync(long amount) {
        return CompletableFuture.completedFuture(withdraw(amount));
    }



    @Override
    public BankStatus withdrawReal(double amount) {
        return withdraw(convertToRawAmount(amount));
    }
    @Override
    public CompletableFuture<BankStatus> withdrawRealAsync(double amount) {
        return CompletableFuture.completedFuture(withdrawReal(amount));
    }



    @Override
    public BankStatus withdrawLocked(long amount) {
        if(amount < 0)
            return BankStatus.FAILED_NEGATIVE_VALUE;
        if (lockedBalance < amount) {
            return BankStatus.FAILED_NOT_ENOUGH_FUNDS;
        }
        lockedBalance -= amount;
        return BankStatus.SUCCESS;
    }
    @Override
    public CompletableFuture<BankStatus> withdrawLockedAsync(long amount) {
        return CompletableFuture.completedFuture(withdraw(amount));
    }




    @Override
    public BankStatus withdrawLockedReal(double amount) {
        return withdrawLocked(convertToRawAmount(amount));
    }
    @Override
    public CompletableFuture<BankStatus> withdrawLockedRealAsync(double amount) {
        return CompletableFuture.completedFuture(withdrawLockedReal(amount));
    }


    @Override
    public BankStatus withdrawLockedPrefered(long amount) {
        if(amount < 0)
            return BankStatus.FAILED_NEGATIVE_VALUE;
        if (lockedBalance < amount) {
            if (balance+lockedBalance < amount) {
                return BankStatus.FAILED_NOT_ENOUGH_FUNDS;
            }
            amount -= lockedBalance;
            lockedBalance = 0;
            addBalanceInternal(-amount);
            return BankStatus.SUCCESS;
        }

        lockedBalance -= amount;
        return BankStatus.SUCCESS;
    }
    @Override
    public CompletableFuture<BankStatus> withdrawLockedPreferedAsync(long amount) {
        return CompletableFuture.completedFuture(withdrawLockedPrefered(amount));
    }



    @Override
    public BankStatus withdrawLockedPreferedReal(double amount) {
        return withdrawLockedPrefered(convertToRawAmount(amount));
    }
    @Override
    public CompletableFuture<BankStatus> withdrawLockedPreferedRealAsync(double amount) {
        return CompletableFuture.completedFuture(withdrawLockedPreferedReal(amount));
    }



    @Override
    public BankStatus transfer(long amount, @NotNull ISyncServerBank other) {
        if(amount < 0)
            return BankStatus.FAILED_NEGATIVE_VALUE;
        if (balance < amount) {
            return BankStatus.FAILED_NOT_ENOUGH_FUNDS;
        }
        if(other == this)
            return BankStatus.SUCCESS;

        addBalanceInternal(-amount);
        BankStatus otherBankStatus = other.deposit(amount);
        if(otherBankStatus == BankStatus.SUCCESS) {
            return BankStatus.SUCCESS;
        }
        addBalanceInternal(amount);
        return otherBankStatus;
    }
    //@Override
    //public CompletableFuture<BankStatus> transferAsync(long amount, @NotNull IAsyncBank other) {
    //    return CompletableFuture.completedFuture(transfer(amount, other));
    //}
    @Override
    public CompletableFuture<BankStatus> transferAsync(long amount, int toAccount) {
        ISyncServerBankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().getBankAccount(toAccount);
        if(account == null)
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_BANK);
        ISyncServerBank bank = account.getBank(itemID);
        if(bank == null)
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_BANK);
        return CompletableFuture.completedFuture(transfer(amount, bank));
    }





    @Override
    public BankStatus transferReal(double amount, @NotNull ISyncServerBank other) {
        return transfer(convertToRawAmount(amount), other);
    }
    @Override
    public CompletableFuture<BankStatus> transferRealAsync(double amount, int toAccount) {
        ISyncServerBankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().getBankAccount(toAccount);
        if(account == null)
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_BANK);
        ISyncServerBank bank = account.getBank(itemID);
        if(bank == null)
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_BANK);
        return CompletableFuture.completedFuture(transferReal(amount, bank));
    }


    @Override
    public BankStatus transferFromLocked(long amount, @NotNull ISyncServerBank other) {
        if(amount < 0)
            return BankStatus.FAILED_NEGATIVE_VALUE;
        if (lockedBalance < amount) {
            return BankStatus.FAILED_NOT_ENOUGH_FUNDS;
        }
        lockedBalance -= amount;
        BankStatus otherBankStatus = other.deposit(amount);
        if(otherBankStatus == BankStatus.SUCCESS) {
            return BankStatus.SUCCESS;
        }
        lockedBalance += amount;
        return otherBankStatus;
    }
    @Override
    public CompletableFuture<BankStatus> transferFromLockedAsync(long amount, int toAccount)
    {
        ISyncServerBankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().getBankAccount(toAccount);
        if(account == null)
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_BANK);
        ISyncServerBank bank = account.getBank(itemID);
        if(bank == null)
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_BANK);
        return CompletableFuture.completedFuture(transferFromLocked(amount, bank));
    }



    @Override
    public BankStatus transferFromLockedReal(double amount, @NotNull ISyncServerBank other) {
        return transferFromLocked(convertToRawAmount(amount), other);
    }
    @Override
    public CompletableFuture<BankStatus> transferFromLockedRealAsync(double amount, int toAccount)
    {
        ISyncServerBankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().getBankAccount(toAccount);
        if(account == null)
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_BANK);
        ISyncServerBank bank = account.getBank(itemID);
        if(bank == null)
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_BANK);
        return CompletableFuture.completedFuture(transferFromLockedReal(amount, bank));
    }




    @Override
    public BankStatus transferFromLockedPrefered(long amount, @NotNull ISyncServerBank other) {
        if(amount < 0)
            return BankStatus.FAILED_NEGATIVE_VALUE;
        long origAmount = amount;
        long lastLocked = lockedBalance;
        if (lockedBalance < amount) {
            if (balance+lockedBalance < amount) {
                return BankStatus.FAILED_NOT_ENOUGH_FUNDS;
            }
            amount -= lockedBalance;
            lockedBalance = 0;
            BankStatus otherBankStatus = other.deposit(origAmount);
            if(otherBankStatus == BankStatus.SUCCESS) {
                addBalanceInternal(-amount);
                return BankStatus.SUCCESS;
            }
            lockedBalance = lastLocked;
            return otherBankStatus;
        }

        lockedBalance -= amount;
        BankStatus otherBankStatus = other.deposit(amount);
        if(otherBankStatus == BankStatus.SUCCESS) {
            return BankStatus.SUCCESS;
        }
        lockedBalance = lastLocked;
        return otherBankStatus;
    }
    @Override
    public CompletableFuture<BankStatus> transferFromLockedPreferedAsync(long amount, int toAccount)
    {
        ISyncServerBankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().getBankAccount(toAccount);
        if(account == null)
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_BANK);
        ISyncServerBank bank = account.getBank(itemID);
        if(bank == null)
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_BANK);
        return CompletableFuture.completedFuture(transferFromLockedPrefered(amount, bank));
    }



    @Override
    public BankStatus transferFromLockedPreferedReal(double amount, @NotNull ISyncServerBank other) {
        return transferFromLockedPrefered(convertToRawAmount(amount), other);
    }
    @Override
    public CompletableFuture<BankStatus> transferFromLockedPreferedRealAsync(double amount, int toAccount)
    {
        ISyncServerBankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().getBankAccount(toAccount);
        if(account == null)
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_BANK);
        ISyncServerBank bank = account.getBank(itemID);
        if(bank == null)
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_BANK);
        return CompletableFuture.completedFuture(transferFromLockedPreferedReal(amount, bank));
    }



    @Override
    public BankStatus lockAmount(long amount) {
        if(amount < 0)
            return BankStatus.FAILED_NEGATIVE_VALUE;
        if (balance < amount) {
            return BankStatus.FAILED_NOT_ENOUGH_FUNDS;
        }
        addBalanceInternal(-amount);
        lockedBalance += amount;
        return BankStatus.SUCCESS;
    }
    @Override
    public CompletableFuture<BankStatus> lockAmountAsync(long amount) {
        return CompletableFuture.completedFuture(lockAmount(amount));
    }




    @Override
    public BankStatus lockAmountReal(double amount) {
        return lockAmount(convertToRawAmount(amount));
    }
    @Override
    public CompletableFuture<BankStatus> lockAmountRealAsync(double amount) {
        return CompletableFuture.completedFuture(lockAmountReal(amount));
    }




    @Override
    public BankStatus unlockAmount(long amount) {
        if(amount < 0)
            return BankStatus.FAILED_NEGATIVE_VALUE;
        if (lockedBalance < amount) {
            return BankStatus.FAILED_NOT_ENOUGH_FUNDS;
        }
        addBalanceInternal(amount);
        lockedBalance -= amount;
        return BankStatus.SUCCESS;
    }
    @Override
    public CompletableFuture<BankStatus> unlockAmountAsync(long amount) {
        return CompletableFuture.completedFuture(unlockAmount(amount));
    }




    @Override
    public BankStatus unlockAmountReal(double amount) {
        return unlockAmount(convertToRawAmount(amount));
    }
    @Override
    public CompletableFuture<BankStatus> unlockAmountRealAsync(double amount) {
        return CompletableFuture.completedFuture(unlockAmountReal(amount));
    }



    @Override
    public void unlockAll()
    {
        addBalanceInternal(lockedBalance);
        lockedBalance = 0;
    }
    @Override
    public void unlockAllAsync()
    {
        unlockAll();
    }



    @Override
    public long convertToRawAmount(double realAmount)
    {
        return convertToRawAmountStatic(realAmount);
    }
    @Override
    public CompletableFuture<Long> convertToRawAmountAsync(double realAmount)
    {
        return CompletableFuture.completedFuture(convertToRawAmount(realAmount));
    }




    @Override
    public double convertToRealAmount(long rawAmount)
    {
        return convertToRealAmountStatic(rawAmount);
    }
    @Override
    public CompletableFuture<Double> convertToRealAmountAsync(long rawAmount)
    {
        return CompletableFuture.completedFuture(convertToRealAmountStatic(rawAmount));
    }




    @Override
    public String getNormalizedBalance()
    {
        return getNormalizedAmount(balance);
    }
    @Override
    public CompletableFuture<String> getNormalizedBalanceAsync()
    {
        return CompletableFuture.completedFuture(getNormalizedAmount(balance));
    }




    @Override
    public String getNormalizedLockedBalance()
    {
        return getNormalizedAmount(lockedBalance);
    }
    @Override
    public CompletableFuture<String> getNormalizedLockedBalanceAsync()
    {
        return CompletableFuture.completedFuture(getNormalizedAmount(lockedBalance));
    }




    @Override
    public String getNormalizedTotalBalance()
    {
        return getNormalizedAmount(balance + lockedBalance);
    }
    @Override
    public CompletableFuture<String> getNormalizedTotalBalanceAsync()
    {
        return CompletableFuture.completedFuture(getNormalizedAmount(lockedBalance));
    }





    @Override
    public String getFormattedBalance()
    {
        return getFormattedAmount(balance);
    }
    @Override
    public CompletableFuture<String> getFormattedBalanceAsync()
    {
        return CompletableFuture.completedFuture(getFormattedAmount(balance));
    }




    @Override
    public String getFormattedLockedBalance()
    {
        return getFormattedAmount(lockedBalance);
    }
    @Override
    public CompletableFuture<String> getFormattedLockedBalanceAsync()
    {
        return CompletableFuture.completedFuture(getFormattedAmount(lockedBalance));
    }



    @Override
    public String getFormattedTotalBalance()
    {
        return getFormattedAmount(balance + lockedBalance);
    }
    @Override
    public CompletableFuture<String> getFormattedTotalBalanceAsync()
    {
        return CompletableFuture.completedFuture(getFormattedAmount(lockedBalance));
    }



    @Override
    public String getNormalizedAmount(double realAmount)
    {
        long amount = convertToRawAmountStatic(realAmount);
        return getNormalizedAmountStatic(amount);
    }
    @Override
    public CompletableFuture<String> getNormalizedAmountAsync(double realAmount)
    {
        return CompletableFuture.completedFuture(getNormalizedAmountStatic(realAmount));
    }




    @Override
    public String getNormalizedAmount(long rawAmount)
    {
        return getNormalizedAmountStatic(rawAmount);
    }
    @Override
    public CompletableFuture<String> getNormalizedAmountAsync(long rawAmount)
    {
        return CompletableFuture.completedFuture(getNormalizedAmountStatic(rawAmount));
    }




    @Override
    public String getFormattedAmount(double realAmount)
    {
        long amount = convertToRawAmountStatic(realAmount);
        return getFormattedAmountStatic(amount);
    }
    @Override
    public CompletableFuture<String> getFormattedAmountAsync(double realAmount)
    {
        return CompletableFuture.completedFuture(getFormattedAmountStatic(realAmount));
    }




    @Override
    public String getFormattedAmount(long rawAmount)
    {
        return getFormattedAmountStatic(rawAmount);
    }
    @Override
    public CompletableFuture<String> getFormattedAmountAsync(long rawAmount)
    {
        return CompletableFuture.completedFuture(getFormattedAmountStatic(rawAmount));
    }





    @Override
    public String toString()
    {
        return toJsonString();
    }
    @Override
    public CompletableFuture<String> toStringAsync()
    {
        return CompletableFuture.completedFuture(toJsonString());
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
    public CompletableFuture<String> toStringNoOwnerAsync()
    {
        return CompletableFuture.completedFuture(toJsonString());
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
    public CompletableFuture<JsonElement> toJsonAsync()
    {
        return CompletableFuture.completedFuture(toJson());
    }




    @Override
    public String toJsonString()
    {
        return JsonUtilities.toPrettyString(toJson());
    }
    @Override
    public CompletableFuture<String> toJsonStringAsync()
    {
        return CompletableFuture.completedFuture(JsonUtilities.toPrettyString(toJson()));
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
        if(itemID == null)
        {
            CompoundTag itemTag = tag.getCompound("itemID");
            itemID = ItemID.createFromTag(itemTag);
        }


        long balance = tag.getLong("balance");
        lockedBalance = tag.getLong("lockedBalance");
        setBalanceInternal(balance);
        return balance >= 0 && lockedBalance >= 0 && itemID != null;
    }




    public static BankStatus exchangeFromLockedPrefered(@NotNull ISyncServerBank from1, @NotNull ISyncServerBank to1, long amount1,
                                                        @NotNull ISyncServerBank from2, @NotNull ISyncServerBank to2, long amount2)
    {
        if(amount1 < 0 || amount2 < 0)
            return BankStatus.FAILED_NEGATIVE_VALUE;

        if ((from1 instanceof  SyncServerBank castedFrom1) &&
                (to1 instanceof  SyncServerBank castedTo1) &&
                (from2 instanceof  SyncServerBank castedFrom2) &&
                (to2 instanceof  SyncServerBank castedTo2))
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
            BankStatus BankStatus1 = from1.transferFromLockedPrefered(amount1, to1);
            BankStatus BankStatus2 = from2.transferFromLockedPrefered(amount2, to2);
            if(BankStatus1 == BankStatus.SUCCESS && BankStatus2 == BankStatus.SUCCESS)
            {
                return BankStatus.SUCCESS;
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
            if(BankStatus1 == BankStatus.SUCCESS)
                return BankStatus2;
            return BankStatus1;
        }
        return BankStatus.FAILED_WRONG_INSTANCE_TYPE;
    }

    private void addBalanceInternal(long balance) {
        setBalanceInternal(this.balance + balance);
    }
    private void setBalanceInternal(long balance) {
        this.balance = balance;
    }


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
    }

    private boolean willOverflow(long tryToAddAmount)
    {
        return willAdditionOverflow(balance + lockedBalance, tryToAddAmount);
    }
    private static boolean willAdditionOverflow(long a, long b) {
        return Long.MAX_VALUE - a < b;
    }
}
