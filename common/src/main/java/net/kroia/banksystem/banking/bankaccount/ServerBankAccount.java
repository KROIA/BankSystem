package net.kroia.banksystem.banking.bankaccount;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bank.IAsyncBank;
import net.kroia.banksystem.api.bank.IServerBank;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.BankUser;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.bank.ServerBank;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.banking.clientdata.BankUserData;
import net.kroia.banksystem.banking.clientdata.UserData;
import net.kroia.banksystem.item.custom.money.MoneyItem;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.JsonUtilities;
import net.kroia.modutilities.persistence.ServerSaveable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class ServerBankAccount implements ServerSaveable, IServerBankAccount {

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        ServerBankAccount.BACKEND_INSTANCES = backend;
        User.setBackend(backend);
        ServerBank.setBackend(backend);
    }
    public static final int INVALID_ACCOUNT_NUMBER = 0;

    public record BankAccountSQL_Data(int bankAccountNr, String accountName, @Nullable ItemID accountIcon, @Nullable UUID personalBankOwner)
    {

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
    private final Map<ItemID, ServerBank> banks = new HashMap<>();

    private ServerBankAccount(int accountNumber) {
        this.accountNumber = accountNumber;
        this.accountIcon = ItemID.getOrRegisterFromItemStackServerSide_direct(Items.CHEST.getDefaultInstance());
    }
    private ServerBankAccount(int accountNumber, @Nullable User personalBankOwner, List<BankUser> users, Map<ItemID, ServerBank> banks) {
        this.accountNumber = accountNumber;
        this.personalBankOwner = personalBankOwner;
        this.accountIcon = ItemID.getOrRegisterFromItemStackServerSide_direct(Items.CHEST.getDefaultInstance());
        if( personalBankOwner != null) {
            this.accountName = personalBankOwner.getName()+"'s ServerBank Account";
        }
        this.banks.putAll(banks);
        for (BankUser user : users) {
            this.users.put(user.getUUID(), user);
        }
    }
    private ServerBankAccount()
    {

    }

    public static @Nullable ServerBankAccount create(int accountNumber)
    {
        if (accountNumber <= 0) {
            return null; // Invalid account number
        }
        ServerBankAccount acc = new ServerBankAccount(accountNumber);
        BACKEND_INSTANCES.SERVER_EVENTS.BANK_ACCOUNT_CREATED.notifyListeners(acc); // Notify listeners that a new bank account has been created
        return acc; // Return the newly created bank account
    }
    public static @Nullable ServerBankAccount create(int accountNumber, List<BankUser> users, Map<ItemID, ServerBank> banks) {
        if (accountNumber <= 0 || users == null || banks == null) {
            return null; // Invalid account number or data
        }
        ServerBankAccount acc = new ServerBankAccount(accountNumber, null, users, banks);
        BACKEND_INSTANCES.SERVER_EVENTS.BANK_ACCOUNT_CREATED.notifyListeners(acc); // Notify listeners that a new bank account has been created
        return acc; // Return the newly created bank account
    }
    public static @Nullable ServerBankAccount createPersonal(int accountNumber, User user, long startMoneyBalance) {
        if (user == null || accountNumber <= 0) {
            return null; // Invalid user or account number
        }

        ServerBank moneyBank = ServerBank.create(MoneyItem.getItemID(), startMoneyBalance);
        if (moneyBank == null) {
            return null; // Failed to create money bank
        }
        Map<ItemID, ServerBank> banks = new HashMap<>();
        banks.put(MoneyItem.getItemID(), moneyBank); // Add money bank to the account
        ServerBankAccount acc = new ServerBankAccount(accountNumber, user, new ArrayList<>(), banks);
        BACKEND_INSTANCES.SERVER_EVENTS.BANK_ACCOUNT_CREATED.notifyListeners(acc); // Notify listeners that a new bank account has been created
        return acc; // Return the newly created bank account
    }
    public static @Nullable ServerBankAccount createFromTag(CompoundTag tag) {
        ServerBankAccount account = new ServerBankAccount();
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

        for(Map.Entry<ItemID, ServerBank> entry : this.banks.entrySet()) {
            ItemID itemID = entry.getKey();
            ServerBank bank = entry.getValue();
            bankData.put(itemID, bank.getMinimalData()); // Convert ServerBank to BankData
        }

        return new BankAccountData(accountNumber, accountName, accountIcon, personalBankOwnerData, users, bankData);
    }
    @Override
    public CompletableFuture<BankAccountData> getAccountDataAsync()
    {
        return CompletableFuture.completedFuture(getAccountData());
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

        ServerBank bank = this.banks.get(itemID);
        if (bank != null) {
            bankData.put(itemID, bank.getMinimalData()); // Convert ServerBank to BankData
            return new BankAccountData(accountNumber, accountName, accountIcon, personalBankOwnerData, users, bankData);
        }
        else {
            return null; // No bank found for the item ID
        }
    }
    @Override
    public CompletableFuture<@Nullable BankAccountData> getAccountDataAsync(ItemID itemID)
    {
        return CompletableFuture.completedFuture(getAccountData(itemID));
    }





    @Override
    public @Nullable BankData getBankData(ItemID itemID)
    {
        if (itemID == null) {
            return null; // Invalid item ID
        }
        ServerBank bank = banks.get(itemID);
        if (bank != null) {
            return bank.getMinimalData(); // Get minimal data for the bank with the given item ID
        }
        return null; // No bank found for the item ID
    }
    @Override
    public CompletableFuture<@Nullable BankData> getBankDataAsync(ItemID itemID)
    {
        return CompletableFuture.completedFuture(getBankData(itemID));
    }





    @Override
    public List<BankData> getBankData()
    {
        return banks.values().stream()
                .map(ServerBank::getMinimalData)
                .toList(); // Get minimal data for all banks in the account
    }
    @Override
    public CompletableFuture<List<BankData>> getBankDataAsync()
    {
        return CompletableFuture.completedFuture(getBankData());
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
    public CompletableFuture<@Nullable BankUserData> getUserDataAsync(UUID userUUID) {
        return CompletableFuture.completedFuture(getUserData(userUUID));
    }




    @Override
    public List<BankUserData> getUserData() {
        return users.values().stream()
                .map(BankUser::toBankUserData)
                .toList(); // Get data for all users in the account
    }
    @Override
    public CompletableFuture<List<BankUserData>> getUserDataAsync() {
        return CompletableFuture.completedFuture(getUserData());
    }




    @Override
    public @Nullable UserData getPersonalBankOwnerData() {
        if (personalBankOwner != null) {
            return personalBankOwner.getUserData(); // Convert User to UserData
        }
        return null; // No personal bank owner
    }
    @Override
    public CompletableFuture<@Nullable UserData> getPersonalBankOwnerDataAsync() {
        return CompletableFuture.completedFuture(getPersonalBankOwnerData());
    }





    @Override
    public int getAccountNumber() {
        return accountNumber;
    }
    @Override
    public int getAccountNumberAsync() {
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
    public void setAccountNameAsync(String accountName) {
        setAccountName(accountName);
    }



    @Override
    public String getAccountName() {
        return accountName; // Get the name of the bank account
    }
    @Override
    public CompletableFuture<String> getAccountNameAsync() {
        return CompletableFuture.completedFuture(accountName);
    }



    @Override
    public void setAccountIcon(@Nullable ItemID accountIcon) {
        this.accountIcon = accountIcon; // Set the icon for the bank account
    }
    @Override
    public void setAccountIconAsync(@Nullable ItemID accountIcon) {
        this.accountIcon = accountIcon; // Set the icon for the bank account
    }




    @Override
    public @Nullable ItemID getAccountIcon() {
        return accountIcon; // Get the icon of the bank account
    }
    @Override
    public CompletableFuture<@Nullable ItemID> getAccountIconAsync() {
        return CompletableFuture.completedFuture(accountIcon); // Get the icon of the bank account
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
    public CompletableFuture<Integer> getPermissionAsync(UUID userUUID) {
        return CompletableFuture.completedFuture(getPermission(userUUID));
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
    }
    @Override
    public CompletableFuture<Boolean> hasPermissionAsync(UUID userUUID, int permission)
    {
        return CompletableFuture.completedFuture(hasPermission(userUUID, permission));
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
    public void setPermissionAsync(UUID userUUID, int permission)
    {
        setPermission(userUUID, permission);
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
    public void addUserAsync(User user, int permission)
    {
        addUser(user, permission);
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
    public void setUsersAsync(Map<User, Integer> userList)
    {
        setUsers(userList);
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
    public void removeUserAsync(UUID userUUID) {
        removeUser(userUUID);
    }




    @Override
    public boolean hasAnyUser() {
        return (!users.isEmpty() || personalBankOwner != null); // Check if there are any users
    }
    @Override
    public CompletableFuture<Boolean> hasAnyUserAsync() {
        return CompletableFuture.completedFuture(hasAnyUser());
    }




    @Override
    public boolean hasUser(UUID userUUID) {
        return userUUID != null && (users.containsKey(userUUID) ||
                (personalBankOwner != null && personalBankOwner.getUUID().equals(userUUID))); // Check if user exists
    }
    @Override
    public CompletableFuture<Boolean> hasUserAsync(UUID userUUID) {
        return CompletableFuture.completedFuture(hasAnyUser());
    }




    @Override
    public @Nullable User getPersonalBankOwner() {
        return personalBankOwner; // Get the personalBankOwnerData of the bank account
    }
    @Override
    public CompletableFuture<@Nullable User> getPersonalBankOwnerAsync() {
        return CompletableFuture.completedFuture(personalBankOwner); // Get the personalBankOwnerData of the bank account
    }






    @Override
    public @Nullable ServerBank createBank(ItemID itemID, long startBalance)
    {
        if (itemID == null) {
            return null; // Invalid item ID
        }
        if (banks.containsKey(itemID)) {
            return banks.get(itemID); // Return existing bank if it already exists
        }
        ServerBank bank = ServerBank.create(itemID, startBalance); // Create a new bank with 0 balance
        if (bank != null) {
            banks.put(itemID, bank); // Add new bank to the account
            return bank;
        }
        return null; // Failed to create bank
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBank> createBankAsync(ItemID itemID, long startBalance)
    {
        if (itemID == null) {
            return CompletableFuture.completedFuture(null); // Invalid item ID
        }
        if (banks.containsKey(itemID)) {
            return CompletableFuture.completedFuture(banks.get(itemID)); // Return existing bank if it already exists
        }
        ServerBank bank = ServerBank.create(itemID, startBalance); // Create a new bank with 0 balance
        if (bank != null) {
            banks.put(itemID, bank); // Add new bank to the account
            return CompletableFuture.completedFuture(bank);
        }
        return CompletableFuture.completedFuture(null); // Failed to create bank
    }





    @Override
    public void removeBank(ItemID itemID) {
        if (itemID == null) {
            return; // Invalid item ID
        }
        banks.remove(itemID); // Remove bank by item ID
    }
    @Override
    public void removeBankAsync(ItemID itemID) {
        removeBank(itemID);
    }



    @Override
    public List<ItemID> removeEmptyBanks()
    {
        List<ItemID> emptyBanks = new ArrayList<>();
        for (Map.Entry<ItemID, ServerBank> entry : banks.entrySet()) {
            ServerBank bank = entry.getValue();
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
    public CompletableFuture<List<ItemID>> removeEmptyBanksAsync()
    {
        return CompletableFuture.completedFuture(removeEmptyBanks());
    }



    @Override
    public void removeAllBanks() {
        banks.clear(); // Clear all banks in the account
    }
    @Override
    public void removeAllBanksAsync() {
        banks.clear(); // Clear all banks in the account
    }



    @Override
    public boolean hasAnyBank() {
        return !banks.isEmpty(); // Check if there are any banks
    }
    @Override
    public CompletableFuture<Boolean> hasAnyBankAsync() {
        return CompletableFuture.completedFuture(hasAnyBank());
    }




    @Override
    public boolean hasBank(ItemID itemID) {
        return itemID != null && banks.containsKey(itemID); // Check if bank exists for the item ID
    }
    @Override
    public CompletableFuture<Boolean> hasBankAsync(ItemID itemID) {
        return CompletableFuture.completedFuture(hasAnyBank());
    }




    @Override
    public @Nullable ServerBank getBank(ItemID itemID) {
        if (itemID == null) {
            return null; // Invalid item ID
        }
        return banks.get(itemID); // Get bank by item ID
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBank> getBankAsync(ItemID itemID) {
        if (itemID == null) {
            return CompletableFuture.completedFuture(null); // Invalid item ID
        }
        return CompletableFuture.completedFuture(banks.get(itemID)); // Get bank by item ID
    }




    @Override
    public @Nullable ServerBank getOrCreateBank(ItemID itemID)
    {
        if (itemID == null) {
            return null; // Invalid item ID
        }
        ServerBank bank = banks.get(itemID);
        if (bank == null) {
            bank = ServerBank.create(itemID, 0); // Create a new bank with 0 balance if it doesn't exist
            if (bank != null) {
                banks.put(itemID, bank); // Add new bank to the account
            }
        }
        return bank; // Return the existing or newly created bank
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBank> getOrCreateBankAsync(ItemID itemID)
    {
        if (itemID == null) {
            return CompletableFuture.completedFuture(null); // Invalid item ID
        }
        ServerBank bank = banks.get(itemID);
        if (bank == null) {
            bank = ServerBank.create(itemID, 0); // Create a new bank with 0 balance if it doesn't exist
            if (bank != null) {
                banks.put(itemID, bank); // Add new bank to the account
            }
        }
        return CompletableFuture.completedFuture(bank); // Return the existing or newly created bank
    }





    @Override
    public Map<ItemID, IServerBank> getAllBanks() {
        return new HashMap<>(banks); // Return a copy of all banks in the account
    }
    @Override
    public CompletableFuture<Map<ItemID, IAsyncBank>> getAllBanksAsync() {
        Map<ItemID, IAsyncBank> bankMap = new HashMap<>(banks);
        return CompletableFuture.completedFuture(bankMap);
    }

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
        for (Map.Entry<ItemID, ServerBank> entry : banks.entrySet()) {
            ServerBank bank = entry.getValue();
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
        ISyncServerBankManager bankManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        this.accountNumber = tag.getInt("accountNumber");
        if(tag.contains("accountName")) {
            this.accountName = tag.getString("accountName");
        } else {
            this.accountName = ""; // Default to empty if not set
        }

        if(tag.contains("personalBankOwnerDataUUID")) {
            UUID personalBankOwnerDataUUID = tag.getUUID("personalBankOwnerDataUUID");
            this.personalBankOwner = bankManager.getUserByUUID(personalBankOwnerDataUUID);
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
            User user = bankManager.getUserByUUID(userUUID);
            if (user != null) {
                users.put(user.getUUID(), new BankUser(user, permissions));
            }
        }

        ListTag banksTag = tag.getList("banks", 10);
        for (int i = 0; i < banksTag.size(); i++) {
            CompoundTag bankTag = banksTag.getCompound(i);
            ServerBank bank = ServerBank.createFromTag(bankTag);
            if (bank != null) {
                banks.put(bank.getItemID(), bank);
            }
        }
        return true;
    }

    @Override
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
        for (Map.Entry<ItemID, ServerBank> entry : banks.entrySet()) {
            banksJson.add(entry.getValue().toJson());
        }
        jsonObject.add("banks", banksJson);
        return jsonObject;
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
        return CompletableFuture.completedFuture(toJsonString());
    }

    @Override
    public String toString() {
        return toJsonString();
    }
}
