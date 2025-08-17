package net.kroia.banksystem.banking;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.IBank;
import net.kroia.banksystem.api.IBankAccount;
import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.banking.clientdata.BankUserData;
import net.kroia.banksystem.banking.clientdata.UserData;
import net.kroia.banksystem.item.custom.money.MoneyItem;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.JsonUtilities;
import net.kroia.modutilities.ServerSaveable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class BankAccount implements ServerSaveable, IBankAccount {

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BankAccount.BACKEND_INSTANCES = backend;
        User.setBackend(backend);
    }



    private int accountNumber;
    private String accountName = ""; // Optional account name, can be empty
    private @Nullable ItemID accountIcon;

    /**
     * The owner of the personal bank account, if this is a personal bank account.
     * If this is not a personal bank account, this will be null.
     * A personal bank account can still have multiple users, but only one personal bank owner.
     */
    private @Nullable User personalBankOwner;
    private final Map<UUID, BankUser> users = new HashMap<>();
    private final Map<ItemID, Bank> banks = new HashMap<>();

    private BankAccount(int accountNumber) {
        this.accountNumber = accountNumber;
    }
    private BankAccount(int accountNumber, @Nullable User personalBankOwner, List<BankUser> users, Map<ItemID, Bank> banks) {
        this.accountNumber = accountNumber;
        this.personalBankOwner = personalBankOwner;
        if( personalBankOwner != null) {
            this.accountName = personalBankOwner.getName()+"'s Bank Account";
        }
        this.banks.putAll(banks);
        for (BankUser user : users) {
            this.users.put(user.getUUID(), user);
        }
    }
    private BankAccount()
    {

    }

    public static @Nullable BankAccount create(int accountNumber)
    {
        if (accountNumber <= 0) {
            return null; // Invalid account number
        }
        return new BankAccount(accountNumber);
    }
    public static @Nullable BankAccount create(int accountNumber, List<BankUser> users, Map<ItemID, Bank> banks) {
        if (accountNumber <= 0 || users == null || banks == null) {
            return null; // Invalid account number or data
        }
        return new BankAccount(accountNumber, null, users, banks);
    }
    public static @Nullable BankAccount createPersonal(int accountNumber, User user, float startMoneyBalance) {
        if (user == null || accountNumber <= 0) {
            return null; // Invalid user or account number
        }

        Bank moneyBank = Bank.create(MoneyItem.getItemID(), startMoneyBalance);
        if (moneyBank == null) {
            return null; // Failed to create money bank
        }
        Map<ItemID, Bank> banks = new HashMap<>();
        banks.put(MoneyItem.getItemID(), moneyBank); // Add money bank to the account
        return new BankAccount(accountNumber, user, new ArrayList<>(), banks);
    }
    public static @Nullable BankAccount createFromTag(CompoundTag tag) {
        BankAccount account = new BankAccount();
        if (!account.load(tag)) {
            return null; // Invalid data
        }
        return account;
    }




    @Override
    public BankAccountData getAccountData()
    {
        UserData personalBankOwnerData = null;
        if (this.personalBankOwner != null) {
            personalBankOwnerData = this.personalBankOwner.getUserData(); // Convert User to UserData
        }
        Map<UUID, BankUserData> users = new HashMap<>();
        Map<ItemID, BankData> bankData = new HashMap<>();

        for(Map.Entry<UUID, BankUser> entry : this.users.entrySet()) {
            UUID userUUID = entry.getKey();
            BankUser user = entry.getValue();
            users.put(userUUID, user.toBankUserData()); // Convert BankUser to BankUserData
        }

        for(Map.Entry<ItemID, Bank> entry : this.banks.entrySet()) {
            ItemID itemID = entry.getKey();
            Bank bank = entry.getValue();
            bankData.put(itemID, bank.getMinimalData()); // Convert Bank to BankData
        }

        return new BankAccountData(accountNumber, accountName, accountIcon, personalBankOwnerData, users, bankData);
    }


    /**
     * Only contains the bank data for the given item ID.
     * @param itemID The item ID of the bank to get data for.
     * @return BankAccountData containing only the bank data for the given item ID, or null if the item ID is invalid.
     */
    @Override
    public @Nullable BankAccountData getAccountData(ItemID itemID)
    {
        if (itemID == null) {
            return null; // Invalid item ID
        }
        UserData personalBankOwnerData = null;
        if (this.personalBankOwner != null) {
            personalBankOwnerData = this.personalBankOwner.getUserData(); // Convert User to UserData
        }
        Map<UUID, BankUserData> users = new HashMap<>();
        Map<ItemID, BankData> bankData = new HashMap<>();

        for(Map.Entry<UUID, BankUser> entry : this.users.entrySet()) {
            UUID userUUID = entry.getKey();
            BankUser user = entry.getValue();
            users.put(userUUID, user.toBankUserData()); // Convert BankUser to BankUserData
        }

        Bank bank = this.banks.get(itemID);
        if (bank != null) {
            bankData.put(itemID, bank.getMinimalData()); // Convert Bank to BankData
            return new BankAccountData(accountNumber, accountName, accountIcon, personalBankOwnerData, users, bankData);
        }
        else {
            return null; // No bank found for the item ID
        }
    }
    @Override
    public @Nullable BankData getBankData(ItemID itemID)
    {
        if (itemID == null) {
            return null; // Invalid item ID
        }
        Bank bank = banks.get(itemID);
        if (bank != null) {
            return bank.getMinimalData(); // Get minimal data for the bank with the given item ID
        }
        return null; // No bank found for the item ID
    }
    @Override
    public List<BankData> getBankData()
    {
        return banks.values().stream()
                .map(Bank::getMinimalData)
                .toList(); // Get minimal data for all banks in the account
    }
    @Override
    public @Nullable BankUserData getUserData(UUID userUUID) {
        if (userUUID == null) {
            return null; // Invalid user UUID
        }
        BankUser user = users.get(userUUID);
        if (user != null) {
            return user.toBankUserData(); // Convert BankUser to BankUserData
        }
        return null; // No user found with the given UUID
    }
    @Override
    public List<BankUserData> getUserData() {
        return users.values().stream()
                .map(BankUser::toBankUserData)
                .toList(); // Get data for all users in the account
    }
    @Override
    public @Nullable UserData getPersonalBankOwnerData() {
        if (personalBankOwner != null) {
            return personalBankOwner.getUserData(); // Convert User to UserData
        }
        return null; // No personal bank owner
    }





    @Override
    public int getAccountNumber() {
        return accountNumber;
    }
    @Override
    public void setAccountName(String accountName) {
        if (accountName == null || accountName.isEmpty()) {
            accountName = "";
        }
        this.accountName = accountName; // Set the name of the bank account
    }
    @Override
    public String getAccountName() {
        return accountName; // Get the name of the bank account
    }
    @Override
    public void setAccountIcon(@Nullable ItemID accountIcon) {
        this.accountIcon = accountIcon; // Set the icon for the bank account
    }
    @Override
    public @Nullable ItemID getAccountIcon() {
        return accountIcon; // Get the icon of the bank account
    }






    @Override
    public int getPermission(UUID userUUID) {
        BankUser user = users.get(userUUID);
        if (user != null) {
            return user.getPermission();
        }
        if(personalBankOwner != null && personalBankOwner.getUUID().equals(userUUID)) {
            return BankPermission.getSelfOwnerPermissions(); // Return owner permissions if the user is the personalBankOwnerData
        }
        return 0; // Default to no permission if user not found
    }
    @Override
    public boolean hasPermission(UUID userUUID, int permission)
    {
        if (userUUID == null || permission < 0) {
            return false; // Invalid user UUID or permission
        }
        BankUser user = users.get(userUUID);
        if (user != null) {
            return BankPermission.hasPermission(user.getPermission(), permission); // Check user's permissions
        }
        return personalBankOwner != null && personalBankOwner.getUUID().equals(userUUID); // Personal bank owner has all permissions
// User not found, no permission
    }
    @Override
    public void setPermission(UUID userUUID, int permission)
    {
        if (userUUID == null || permission < 0) {
            return;
        }
        BankUser user = users.get(userUUID);
        if (user == null) {
            return;
        }
        user.setPermission(permission); // Update existing user's permission
    }
    @Override
    public void addUser(User user, int permission)
    {
        if (user == null || permission < 0) {
            return;
        }
        if(personalBankOwner != null && personalBankOwner.getUUID().equals(user.getUUID())) {
            return; // Do not add the personalBankOwnerData as a user again
        }
        BankUser existingUser = users.get(user.getUUID());
        if (existingUser != null) {
            existingUser.setPermission(permission); // Update existing user's permission
            return;
        }
        users.put(user.getUUID(), new BankUser(user, permission)); // Add new user
    }
    @Override
    public void setUsers(Map<User, Integer> userList)
    {
        if (userList == null) {
            return; // Invalid user list
        }
        users.clear(); // Clear existing users
        for (Map.Entry<User, Integer> entry : userList.entrySet()) {
            User user = entry.getKey();
            int permission = entry.getValue();
            if (user != null && permission >= 0) {
                if(personalBankOwner != null && personalBankOwner.getUUID().equals(user.getUUID())) {
                    continue; // Do not add the personalBankOwnerData as a user again
                }
                users.put(user.getUUID(), new BankUser(user, permission)); // Add new user
            }
        }
    }
    @Override
    public void removeUser(UUID userUUID) {
        if (userUUID == null) {
            return;
        }
        users.remove(userUUID); // Remove user by UUID
        if(personalBankOwner != null && personalBankOwner.getUUID().equals(userUUID)) {
            personalBankOwner = null; // If the removed user is the personalBankOwnerData, set personalBankOwnerData to null
        }
    }
    @Override
    public boolean hasAnyUser() {
        return (!users.isEmpty() || personalBankOwner != null); // Check if there are any users
    }
    @Override
    public boolean hasUser(UUID userUUID) {
        return userUUID != null && (users.containsKey(userUUID) ||
                (personalBankOwner != null && personalBankOwner.getUUID().equals(userUUID))); // Check if user exists
    }
    @Override
    public @Nullable User getPersonalBankOwner() {
        return personalBankOwner; // Get the personalBankOwnerData of the bank account
    }






    @Override
    public @Nullable IBank createBank(ItemID itemID, float startBalance)
    {
        if (itemID == null) {
            return null; // Invalid item ID
        }
        if (banks.containsKey(itemID)) {
            return banks.get(itemID); // Return existing bank if it already exists
        }
        Bank bank = Bank.create(itemID, startBalance); // Create a new bank with 0 balance
        if (bank != null) {
            banks.put(itemID, bank); // Add new bank to the account
            return bank;
        }
        return null; // Failed to create bank
    }
    @Override
    public void removeBank(ItemID itemID) {
        if (itemID == null) {
            return; // Invalid item ID
        }
        banks.remove(itemID); // Remove bank by item ID
    }
    @Override
    public List<ItemID> removeEmptyBanks()
    {
        List<ItemID> emptyBanks = new ArrayList<>();
        for (Map.Entry<ItemID, Bank> entry : banks.entrySet()) {
            Bank bank = entry.getValue();
            if (bank.getTotalBalance() <= 0) {
                emptyBanks.add(entry.getKey()); // Collect empty banks
            }
        }
        for (ItemID itemID : emptyBanks) {
            banks.remove(itemID); // Remove empty banks from the account
        }
        return emptyBanks; // Return list of removed empty banks
    }
    @Override
    public void removeAllBanks() {
        banks.clear(); // Clear all banks in the account
    }
    @Override
    public boolean hasAnyBank() {
        return !banks.isEmpty(); // Check if there are any banks
    }
    @Override
    public boolean hasBank(ItemID itemID) {
        return itemID != null && banks.containsKey(itemID); // Check if bank exists for the item ID
    }
    @Override
    public @Nullable IBank getBank(ItemID itemID) {
        if (itemID == null) {
            return null; // Invalid item ID
        }
        return banks.get(itemID); // Get bank by item ID
    }
    @Override
    public @Nullable IBank getOrCreateBank(ItemID itemID)
    {
        if (itemID == null) {
            return null; // Invalid item ID
        }
        Bank bank = banks.get(itemID);
        if (bank == null) {
            bank = Bank.create(itemID, 0); // Create a new bank with 0 balance if it doesn't exist
            if (bank != null) {
                banks.put(itemID, bank); // Add new bank to the account
            }
        }
        return bank; // Return the existing or newly created bank
    }
    @Override
    public Map<ItemID, IBank> getAllBanks() {
        return new HashMap<>(banks); // Return a copy of all banks in the account
    }

    /*
    public long getRawBalance(ItemID itemID)
    {
        if (itemID == null) {
            return 0; // Invalid item ID
        }
        Bank bank = banks.get(itemID);
        if (bank != null) {
            return bank.getBalance(); // Get balance from the bank
        }
        return 0; // No bank found for the item ID
    }
    public long getRawLockedBalance(ItemID itemID)
    {
        if (itemID == null) {
            return 0; // Invalid item ID
        }
        Bank bank = banks.get(itemID);
        if (bank != null) {
            return bank.getLockedBalance(); // Get locked balance from the bank
        }
        return 0; // No bank found for the item ID
    }
    public long getRawTotalBalance(ItemID itemID)
    {
        if (itemID == null) {
            return 0; // Invalid item ID
        }
        Bank bank = banks.get(itemID);
        if (bank != null) {
            return bank.getTotalBalance(); // Get total balance from the bank
        }
        return 0; // No bank found for the item ID
    }
    public float getRealBalance(ItemID itemID) {
        if (itemID == null) {
            return 0; // Invalid item ID
        }
        Bank bank = banks.get(itemID);
        if (bank != null) {
            return bank.getRealBalance(); // Get real balance from the bank
        }
        return 0; // No bank found for the item ID
    }
    public float getRealLockedBalance(ItemID itemID) {
        if (itemID == null) {
            return 0; // Invalid item ID
        }
        Bank bank = banks.get(itemID);
        if (bank != null) {
            return bank.getRealLockedBalance(); // Get real locked balance from the bank
        }
        return 0; // No bank found for the item ID
    }
    public float getRealTotalBalance(ItemID itemID) {
        if (itemID == null) {
            return 0; // Invalid item ID
        }
        Bank bank = banks.get(itemID);
        if (bank != null) {
            return bank.getRealTotalBalance(); // Get real total balance from the bank
        }
        return 0; // No bank found for the item ID
    }




    public IBank.Status setRawBalance(ItemID item, long amount)
    {
        if (item == null)
            return IBank.Status.FAILED_INVALID_ITEM_ID; // Invalid item ID
        if(amount < 0)
            return IBank.Status.FAILED_NEGATIVE_VALUE; // Invalid amount
        Bank bank = banks.get(item);
        if (bank != null)
        {
            bank.setBalance(amount);
            return IBank.Status.SUCCESS;
        }
        return IBank.Status.FAILED_NO_BANK; // No bank found for the item ID
    }
    public IBank.Status setRealBalance(ItemID item, float amount)
    {
        if (item == null)
            return IBank.Status.FAILED_INVALID_ITEM_ID; // Invalid item ID
        if(amount < 0)
            return IBank.Status.FAILED_NEGATIVE_VALUE; // Invalid amount
        Bank bank = banks.get(item);
        if (bank != null) {
            bank.setRealBalance(amount); // Set balance in the bank
            return IBank.Status.SUCCESS;
        }
        return IBank.Status.FAILED_NO_BANK; // No bank found for the item ID
    }


    public IBank.Status depositRaw(ItemID itemID, long amount)
    {
        if (itemID == null) {
            return IBank.Status.FAILED_INVALID_ITEM_ID; // Invalid item ID
        }
        Bank bank = banks.get(itemID);
        if (bank != null) {
            return bank.deposit(amount); // Deposit raw amount into the bank
        }
        return IBank.Status.FAILED_NO_BANK; // No bank found for the item ID
    }
    public IBank.Status depositReal(ItemID itemID, float amount)
    {
        if (itemID == null) {
            return IBank.Status.FAILED_INVALID_ITEM_ID; // Invalid item ID
        }
        Bank bank = banks.get(itemID);
        if (bank != null) {
            return bank.depositReal(amount); // Deposit raw amount into the bank
        }
        return IBank.Status.FAILED_NO_BANK; // No bank found for the item ID
    }


    public IBank.Status withdrawRaw(ItemID itemID, long amount)
    {
        if (itemID == null) {
            return IBank.Status.FAILED_INVALID_ITEM_ID; // Invalid item ID
        }
        Bank bank = banks.get(itemID);
        if (bank != null) {
            return bank.withdraw(amount); // Withdraw raw amount from the bank
        }
        return IBank.Status.FAILED_NO_BANK; // No bank found for the item ID
    }
    public IBank.Status withdrawReal(ItemID itemID, float amount)
    {
        if (itemID == null) {
            return IBank.Status.FAILED_INVALID_ITEM_ID; // Invalid item ID
        }
        Bank bank = banks.get(itemID);
        if (bank != null) {
            return bank.withdrawReal(amount); // Withdraw real amount from the bank
        }
        return IBank.Status.FAILED_NO_BANK; // No bank found for the item ID
    }


    public IBank.Status transferRaw(ItemID itemID, long amount, BankAccount targetAccount) {
        if (itemID == null || targetAccount == null) {
            return IBank.Status.FAILED_INVALID_ITEM_ID; // Invalid item ID or target account
        }
        Bank bank = banks.get(itemID);
        Bank targetBank = targetAccount.banks.get(itemID);
        if(bank == null || targetBank == null) {
            return IBank.Status.FAILED_NO_BANK; // No bank found for the item ID in either account
        }
        return bank.transfer(amount, targetBank);
    }
    public IBank.Status transferReal(ItemID itemID, float amount, BankAccount targetAccount) {
        if (itemID == null || targetAccount == null) {
            return IBank.Status.FAILED_INVALID_ITEM_ID; // Invalid item ID or target account
        }
        Bank bank = banks.get(itemID);
        Bank targetBank = targetAccount.banks.get(itemID);
        if(bank == null || targetBank == null) {
            return IBank.Status.FAILED_NO_BANK; // No bank found for the item ID in either account
        }
        return bank.transferReal(amount, targetBank);
    }
    
    public IBank.Status transferRawFromLocked(ItemID itemID, long amount, BankAccount targetAccount) {
        if (itemID == null || targetAccount == null) {
            return IBank.Status.FAILED_INVALID_ITEM_ID; // Invalid item ID or target account
        }
        Bank bank = banks.get(itemID);
        Bank targetBank = targetAccount.banks.get(itemID);
        if(bank == null || targetBank == null) {
            return IBank.Status.FAILED_NO_BANK; // No bank found for the item ID in either account
        }
        return bank.transferFromLocked(amount, targetBank);
    }
    public IBank.Status transferRealFromLocked(ItemID itemID, float amount, BankAccount targetAccount) {
        if (itemID == null || targetAccount == null) {
            return IBank.Status.FAILED_INVALID_ITEM_ID; // Invalid item ID or target account
        }
        Bank bank = banks.get(itemID);
        Bank targetBank = targetAccount.banks.get(itemID);
        if(bank == null || targetBank == null) {
            return IBank.Status.FAILED_NO_BANK; // No bank found for the item ID in either account
        }
        return bank.transferFromLockedReal(amount, targetBank);
    }
    public IBank.Status transferRawFromLockedPrefered(ItemID itemID, long amount, BankAccount targetAccount) {
        if (itemID == null || targetAccount == null) {
            return IBank.Status.FAILED_INVALID_ITEM_ID; // Invalid item ID or target account
        }
        Bank bank = banks.get(itemID);
        Bank targetBank = targetAccount.banks.get(itemID);
        if(bank == null || targetBank == null) {
            return IBank.Status.FAILED_NO_BANK; // No bank found for the item ID in either account
        }
        return bank.transferFromLockedPrefered(amount, targetBank);
    }
    public IBank.Status transferRealFromLockedPrefered(ItemID itemID, float amount, BankAccount targetAccount) {
        if (itemID == null || targetAccount == null) {
            return IBank.Status.FAILED_INVALID_ITEM_ID; // Invalid item ID or target account
        }
        Bank bank = banks.get(itemID);
        Bank targetBank = targetAccount.banks.get(itemID);
        if(bank == null || targetBank == null) {
            return IBank.Status.FAILED_NO_BANK; // No bank found for the item ID in either account
        }
        return bank.transferFromLockedPreferedReal(amount, targetBank);
    }


    public IBank.Status lockRawAmount(ItemID itemID, long amount) {
        if (itemID == null) {
            return IBank.Status.FAILED_INVALID_ITEM_ID; // Invalid item ID
        }
        Bank bank = banks.get(itemID);
        if (bank != null) {
            return bank.lockAmount(amount); // Lock raw amount in the bank
        }
        return IBank.Status.FAILED_NO_BANK; // No bank found for the item ID
    }
    public IBank.Status lockRealAmount(ItemID itemID, float amount) {
        if (itemID == null) {
            return IBank.Status.FAILED_INVALID_ITEM_ID; // Invalid item ID
        }
        Bank bank = banks.get(itemID);
        if (bank != null) {
            return bank.lockAmountReal(amount); // Lock real amount in the bank
        }
        return IBank.Status.FAILED_NO_BANK; // No bank found for the item ID
    }



    public IBank.Status unlockRawAmount(ItemID itemID, long amount) {
        if (itemID == null) {
            return IBank.Status.FAILED_INVALID_ITEM_ID; // Invalid item ID
        }
        Bank bank = banks.get(itemID);
        if (bank != null) {
            return bank.unlockAmount(amount); // Unlock raw amount in the bank
        }
        return IBank.Status.FAILED_NO_BANK; // No bank found for the item ID
    }
    public IBank.Status unlockRealAmount(ItemID itemID, float amount) {
        if (itemID == null) {
            return IBank.Status.FAILED_INVALID_ITEM_ID; // Invalid item ID
        }
        Bank bank = banks.get(itemID);
        if (bank != null) {
            return bank.unlockAmountReal(amount); // Unlock real amount in the bank
        }
        return IBank.Status.FAILED_NO_BANK; // No bank found for the item ID
    }
    public IBank.Status unlockAll(ItemID itemID) {
        if (itemID == null) {
            return IBank.Status.FAILED_INVALID_ITEM_ID; // Invalid item ID
        }
        Bank bank = banks.get(itemID);
        if (bank != null) {
            bank.unlockAll(); // Unlock all amounts in the bank
            return IBank.Status.SUCCESS; // Successfully unlocked all amounts
        }
        return IBank.Status.FAILED_NO_BANK; // No bank found for the item ID
    }

    public String getNormalizedBalance(ItemID itemID) {
        if (itemID == null) {
            return "0"; // Invalid item ID
        }
        Bank bank = banks.get(itemID);
        if (bank != null) {
            return bank.getNormalizedBalance(); // Get normalized balance from the bank
        }
        return "0"; // No bank found for the item ID
    }
    public String getNormalizedLockedBalance(ItemID itemID) {
        if (itemID == null) {
            return "0"; // Invalid item ID
        }
        Bank bank = banks.get(itemID);
        if (bank != null) {
            return bank.getNormalizedLockedBalance(); // Get normalized locked balance from the bank
        }
        return "0"; // No bank found for the item ID
    }
    public String getNormalizedTotalBalance(ItemID itemID) {
        if (itemID == null) {
            return "0"; // Invalid item ID
        }
        Bank bank = banks.get(itemID);
        if (bank != null) {
            return bank.getNormalizedTotalBalance(); // Get normalized total balance from the bank
        }
        return "0"; // No bank found for the item ID
    }
    public String getFormattedBalance(ItemID itemID) {
        if (itemID == null) {
            return "0"; // Invalid item ID
        }
        Bank bank = banks.get(itemID);
        if (bank != null) {
            return bank.getFormattedBalance(); // Get formatted balance from the bank
        }
        return "0"; // No bank found for the item ID
    }
    public String getFormattedLockedBalance(ItemID itemID) {
        if (itemID == null) {
            return "0"; // Invalid item ID
        }
        Bank bank = banks.get(itemID);
        if (bank != null) {
            return bank.getFormattedLockedBalance(); // Get formatted locked balance from the bank
        }
        return "0"; // No bank found for the item ID
    }
    public String getFormattedTotalBalance(ItemID itemID) {
        if (itemID == null) {
            return "0"; // Invalid item ID
        }
        Bank bank = banks.get(itemID);
        if (bank != null) {
            return bank.getFormattedTotalBalance(); // Get formatted total balance from the bank
        }
        return "0"; // No bank found for the item ID
    }
*/





    @Override
    public boolean save(CompoundTag tag) {
        tag.putInt("accountNumber", accountNumber);
        tag.putString("accountName", accountName); // Save the account name

        if(personalBankOwner != null)
            tag.putUUID("personalBankOwnerDataUUID", personalBankOwner.getUUID());

        if (accountIcon != null) {
            CompoundTag iconTag = new CompoundTag();
            accountIcon.save(iconTag); // Save the account icon if set
            tag.put("accountIcon", iconTag);
        }

        ListTag usersTag = new ListTag();
        for (BankUser user : users.values()) {
            UUID userUUID = user.getUser().getUUID();
            int permissions = user.getPermission();
            CompoundTag userTag = new CompoundTag();
            userTag.putUUID("userUUID", userUUID);
            userTag.putInt("permissions", permissions);
            usersTag.add(userTag);
        }
        tag.put("users", usersTag);

        ListTag banksTag = new ListTag();
        for (Map.Entry<ItemID, Bank> entry : banks.entrySet()) {
            Bank bank = entry.getValue();
            CompoundTag bankTag = new CompoundTag();
            bank.save(bankTag);
            banksTag.add(bankTag);
        }
        tag.put("banks", banksTag);
        return true;
    }

    @Override
    public boolean load(CompoundTag tag) {
        if (    !tag.contains("accountNumber") ||
                !tag.contains("users") ||
                !tag.contains("banks")) {
            return false; // Invalid data
        }
        this.accountNumber = tag.getInt("accountNumber");
        if(tag.contains("accountName")) {
            this.accountName = tag.getString("accountName");
        } else {
            this.accountName = ""; // Default to empty if not set
        }

        if(tag.contains("personalBankOwnerDataUUID")) {
            UUID personalBankOwnerDataUUID = tag.getUUID("personalBankOwnerDataUUID");
            this.personalBankOwner = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getUserByUUID(personalBankOwnerDataUUID);
        } else {
            this.personalBankOwner = null; // No personalBankOwnerData set
        }

        if(tag.contains("accountIcon")) {
            CompoundTag iconTag = tag.getCompound("accountIcon");
            this.accountIcon = ItemID.createFromTag(iconTag); // Load account icon if set
        } else {
            this.accountIcon = null; // No account icon set
        }



        ListTag usersTag = tag.getList("users", 10);
        for (int i = 0; i < usersTag.size(); i++) {
            CompoundTag userTag = usersTag.getCompound(i);
            UUID userUUID = userTag.getUUID("userUUID");
            int permissions = userTag.getInt("permissions");
            User user = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getUserByUUID(userUUID);
            if (user != null) {
                users.put(user.getUUID(), new BankUser(user, permissions));
            }
        }

        ListTag banksTag = tag.getList("banks", 10);
        for (int i = 0; i < banksTag.size(); i++) {
            CompoundTag bankTag = banksTag.getCompound(i);
            Bank bank = Bank.createFromTag(bankTag);
            if (bank != null) {
                banks.put(bank.getItemID(), bank);
            }
        }
        return true;
    }

    public JsonElement toJson()
    {
        JsonObject jsonObject = new JsonObject();

        if(personalBankOwner != null)
        {
            jsonObject.add("personalBankOwner", personalBankOwner.toJson());
        }
        if(accountIcon != null) {
            jsonObject.add("accountIcon", accountIcon.toJson()); // Add account icon if set
        }

        jsonObject.addProperty("accountNumber", accountNumber);
        jsonObject.addProperty("accountName", accountName);
        JsonArray usersJson = new JsonArray();
        for (BankUser user : users.values()) {
            usersJson.add(user.toJson());
        }
        jsonObject.add("users", usersJson);

        JsonArray banksJson = new JsonArray();
        for (Map.Entry<ItemID, Bank> entry : banks.entrySet()) {
            banksJson.add(entry.getValue().toJson());
        }
        jsonObject.add("banks", banksJson);
        return jsonObject;
    }
    public String toJsonString()
    {
        return JsonUtilities.toPrettyString(toJson());
    }

    @Override
    public String toString() {
        return toJsonString();
    }
}
