package net.kroia.banksystem.banking;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.IAsyncServerBankManager;
import net.kroia.banksystem.api.IBank;
import net.kroia.banksystem.api.IBankAccount;
import net.kroia.banksystem.api.ISyncServerBankManager;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankManagerData;
import net.kroia.banksystem.banking.clientdata.ItemInfoData;
import net.kroia.banksystem.banking.clientdata.UserData;
import net.kroia.banksystem.item.custom.money.MoneyItem;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.modutilities.JsonUtilities;
import net.kroia.modutilities.persistence.ServerSaveableChunked;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class SyncServerBankManager implements ServerSaveableChunked, ISyncServerBankManager, IAsyncServerBankManager {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        SyncServerBankManager.BACKEND_INSTANCES = backend;
        BankAccount.setBackend(backend);
    }


    /**
     * Using the player UUID as key
     */
    private final Map<UUID, User> userMap = new HashMap<>();



    /**
     * List of all items that are allowed to be stored inside a bank account
     */
    private final Set<ItemID> allowedItemIDs = new HashSet<>();


    /**
     * Using the account number as key.
     */
    private final Map<Integer, BankAccount> bankAccounts = new HashMap<>();

    private int nextAccountNumber = 1; // Start with account number 1


    public SyncServerBankManager()
    {
        getBlacklistedItems();
        getAllowedItems();
        getNotRemovableItems();
        
        setupDefaultItems();
    }

    @Override
    public BankManagerData getBankManagerData()
    {
        return new BankManagerData(
                getBankManagerUserMapData(),
                getBankManagerBankAccountsData(),
                getAllowedItems(),
                getBlacklistedItems(),
                getNotRemovableItems()
        );
    }
    @Override
    public CompletableFuture<BankManagerData> getBankManagerDataAsync() {
        return CompletableFuture.completedFuture(getBankManagerData());
    }


    @Override
    public BankManagerData.UserMapData getBankManagerUserMapData()
    {
        Map<UUID, UserData> userDataMap = new HashMap<>();
        for (Map.Entry<UUID, User> entry : userMap.entrySet()) {
            userDataMap.put(entry.getKey(), entry.getValue().getUserData());
        }
        return new BankManagerData.UserMapData(userDataMap);
    }
    @Override
    public CompletableFuture<BankManagerData.UserMapData> getBankManagerUserMapDataAsync() {
        return CompletableFuture.completedFuture(getBankManagerUserMapData());
    }




    @Override
    public BankManagerData.BankAccountsData getBankManagerBankAccountsData()
    {
        Map<Integer, BankAccountData> bankAccountDataMap = new HashMap<>();
        for (Map.Entry<Integer, BankAccount> entry : bankAccounts.entrySet()) {
            bankAccountDataMap.put(entry.getKey(), entry.getValue().getAccountData());
        }
        return new BankManagerData.BankAccountsData(bankAccountDataMap);
    }
    @Override
    public CompletableFuture<BankManagerData.BankAccountsData> getBankManagerBankAccountsDataAsync() {
        return CompletableFuture.completedFuture(getBankManagerBankAccountsData());
    }

    @Override
    public  boolean setBanksystemAdminMode(UUID playerUUID, boolean isAdmin)
    {
        User user = userMap.get(playerUUID);
        if (user == null)
            return false;
        user.setBankModAdmin(isAdmin);
        return true;
    }
    @Override
    public CompletableFuture<Boolean> setBanksystemAdminModeAsync(UUID playerUUID, boolean isAdmin)
    {
        User user = userMap.get(playerUUID);
        if (user == null)
            return CompletableFuture.completedFuture(false);
        user.setBankModAdmin(isAdmin);
        return CompletableFuture.completedFuture(true);
    }
    @Override
    public  boolean isBanksystemAdmin(UUID playerUUID)
    {
        User user = userMap.get(playerUUID);
        if (user == null)
            return false;
        return user.isBankModAdmin();
    }
    @Override
    public  CompletableFuture<Boolean> isBanksystemAdminAsync(UUID playerUUID)
    {
        User user = userMap.get(playerUUID);
        if (user == null)
            return CompletableFuture.completedFuture(false);
        return CompletableFuture.completedFuture(user.isBankModAdmin());
    }




    @Override
    public List<ItemID> getAllowedItems() {
        return allowedItemIDs.stream().toList();
    }
    @Override
    public CompletableFuture<List<ItemID>> getAllowedItemsAsync() {
        return CompletableFuture.completedFuture(getAllowedItems());
    }



    @Override
    public List<ItemID> getBlacklistedItems()
    {
        List<ItemID> ids = ItemIDManager.registerItemStackServerSide_direct(BACKEND_INSTANCES.SERVER_SETTINGS.BANK.INITIAL_BLACKLIST_ITEMS);
        return ids;
    }
    @Override
    public CompletableFuture<List<ItemID>> getBlacklistedItemsAsync() {
        return CompletableFuture.completedFuture(getBlacklistedItems());
    }

    @Override
    public List<ItemID> getNotRemovableItems()
    {
        List<ItemID> ids = ItemIDManager.registerItemStackServerSide_direct(BACKEND_INSTANCES.SERVER_SETTINGS.BANK.INITIAL_BLACKLIST_ITEMS);
        return ids;
    }
    @Override
    public CompletableFuture<List<ItemID>> getNotRemovableItemsAsync() {
        return CompletableFuture.completedFuture(getNotRemovableItems());
    }

    @Override
    public ItemInfoData getItemInfoData(@NotNull ItemID itemID)
    {
        double totalSupply = 0;
        double totalLocked = 0;
        List<BankAccountData> bankAccounts = new java.util.ArrayList<>();

        for (Map.Entry<Integer, BankAccount> entry : this.bankAccounts.entrySet()) {
            BankAccount account = entry.getValue();
            IBank bank = account.getBank(itemID);
            if(bank == null)
                continue;
            totalSupply += bank.getRealTotalBalance();
            totalLocked += bank.getRealLockedBalance();
            bankAccounts.add(account.getAccountData(itemID));
        }
        return new ItemInfoData(itemID, totalSupply, totalLocked, bankAccounts);
    }
    @Override
    public CompletableFuture<ItemInfoData> getItemInfoDataAsync(@NotNull ItemID itemID) {
        return CompletableFuture.completedFuture(getItemInfoData(itemID));
    }

    @Override
    public void addUser(@NotNull ServerPlayer player)
    {
        addUser(new User(player.getUUID(), player.getName().getString(), true));
    }
    @Override
    public void addUserAsync(@NotNull ServerPlayer player) {
        addUser(player);
    }
    @Override
    public void addUser(@NotNull UUID playerUUID, @NotNull String playerName)
    {
        addUser(new User(playerUUID, playerName, true));
    }
    @Override
    public void addUserAsync(@NotNull UUID playerUUID, @NotNull String playerName) {
        addUser(playerUUID, playerName);
    }
    @Override
    public void addUser(@NotNull User user)
    {
        UUID userUUID = user.getUUID();
        if(userMap.containsKey(userUUID)) {
            warn("User with UUID " + userUUID + " already exists. Not adding again.");
            return;
        }
        userMap.put(userUUID, user);
        info("Added new user: " + user.getName() + " with UUID: " + userUUID);
        BACKEND_INSTANCES.SERVER_EVENTS.USER_ADDED.notifyListeners(user);
    }
    @Override
    public void addUserAsync(@NotNull User user) {
        addUser(user);
    }
    @Override
    public boolean removeUser(UUID userUUID)
    {
        if(userMap.containsKey(userUUID)) {
            User user = userMap.remove(userUUID);
            for(Map.Entry<Integer, BankAccount> entry : bankAccounts.entrySet()) {
                BankAccount account = entry.getValue();
                if(account.hasUser(userUUID)) {
                    account.removeUser(userUUID);
                    if(!account.hasAnyUser()) {
                        int accountNr = entry.getKey();
                        deleteBankAccount(accountNr); // Remove the account if it has no users left
                        info("Removed empty bank account with number: " + accountNr);
                    }
                }
            }
            BACKEND_INSTANCES.SERVER_EVENTS.USER_REMOVED.notifyListeners(user);
            info("Removed user with UUID: " + userUUID);
            return true;
        } else {
            warn("No user found with UUID: " + userUUID);
            return false;
        }
    }
    @Override
    public CompletableFuture<Boolean> removeUserAsync(UUID userUUID) {
        return CompletableFuture.completedFuture(removeUser(userUUID));
    }




    @Override
    public boolean userExists(UUID userUUID)
    {
        return userMap.containsKey(userUUID);
    }
    @Override
    public CompletableFuture<Boolean> userExistsAsync(UUID userUUID) {
        return CompletableFuture.completedFuture(userExists(userUUID));
    }

    @Override
    public @Nullable User getUserByUUID(UUID userUUID) {
        return userMap.get(userUUID);
    }
    @Override
    public CompletableFuture<@Nullable User> getUserByUUIDAsync(UUID userUUID) {
        return CompletableFuture.completedFuture(getUserByUUID(userUUID));
    }

    @Override
    public @Nullable User getUserByName(String name)
    {
        String lowerCaseName = name.toLowerCase();
        for (User user : userMap.values()) {
            if (user.getName().toLowerCase().equals(lowerCaseName)) {
                return user;
            }
        }
        return null;
    }
    @Override
    public CompletableFuture<@Nullable User> getUserByNameAsync(String name) {
        return CompletableFuture.completedFuture(getUserByName(name));
    }







    @Override
    public @Nullable IBankAccount createPersonalBankAccount(UUID user)
    {
        User creator = userMap.get(user);
        if(creator == null) {
            warn("No user found with UUID: " + user);
             return null;
        }
        if(userHasPersonalBankAccount(user)) {
            warn("User with UUID: " + user + " already has a personal bank account.");
            return getPersonalBankAccount(user); // Return existing account if it exists
        }

        IBankAccount existingAccount = getPersonalBankAccount(user);
        if(existingAccount != null) {
            warn("User with UUID: " + user + " already has a personal bank account with number: " + existingAccount.getAccountNumber());
            return null;
        }

        int accountNumber = generateNewAccountNumber();
        long startBalance = BACKEND_INSTANCES.SERVER_SETTINGS.PLAYER.STARTING_BALANCE.get();
        BankAccount account = BankAccount.createPersonal(accountNumber, creator, startBalance);
        if(account == null) {
            warn("Failed to create personal bank account for user with UUID: " + user);
            return null;
        }
        bankAccounts.put(accountNumber, account);
        return account;
    }
    @Override
    public CompletableFuture<IBankAccount> createPersonalBankAccountAsync(UUID user) {
        return CompletableFuture.completedFuture(createPersonalBankAccount(user));
    }

    @Override
    public IBankAccount createBankAccount(String accountName)
    {
        if(accountName == null || accountName.isEmpty()) {
            accountName = "Unnamed Account";
        }
        int accountNumber = generateNewAccountNumber();
        BankAccount account = BankAccount.create(accountNumber);
        if(account == null)
        {
            warn("Failed to create bank account with number: " + accountNumber);
            return null;
        }
        account.setAccountName(accountName);
        account.setAccountIcon(ItemIDManager.registerItemStackServerSide_direct(Items.CHEST.getDefaultInstance()));
        bankAccounts.put(accountNumber, account);
        info("Created new bank account with number: " + accountNumber + " and name: " + accountName);
        return account;
    }
    @Override
    public CompletableFuture<IBankAccount> createBankAccountAsync(String accountName) {
        return CompletableFuture.completedFuture(createBankAccount(accountName));
    }

    @Override
    public @Nullable IBankAccount getBankAccount(int accountNumber)
    {
        return bankAccounts.get(accountNumber);
    }
    @Override
    public CompletableFuture<@Nullable IBankAccount> getBankAccountAsync(int accountNumber) {
        return CompletableFuture.completedFuture(getBankAccount(accountNumber));
    }

    @Override
    public List<IBankAccount> getBankAccounts(UUID userUUID)
    {
        List<IBankAccount> accounts = new ArrayList<>();
        for(Map.Entry<Integer, BankAccount> entry : bankAccounts.entrySet()) {
            BankAccount account = entry.getValue();
            if(account.hasUser(userUUID)) {
                accounts.add(account); // Add the account if the user is a member
            }
        }
        return accounts; // Return all accounts the user is a member of
    }
    @Override
    public CompletableFuture<List<IBankAccount>> getBankAccountsAsync(UUID userUUID) {
        return CompletableFuture.completedFuture(getBankAccounts(userUUID));
    }

    @Override
    public List<IBankAccount> getBankAccounts(ItemID itemID)
    {
        List<IBankAccount> accounts = new ArrayList<>();
        for(Map.Entry<Integer, BankAccount> entry : bankAccounts.entrySet()) {
            BankAccount account = entry.getValue();
            if(account.hasBank(itemID)) {
                accounts.add(account); // Add the account if it has the bank for the given itemID
            }
        }
        return accounts; // Return all accounts that have a bank for the given itemID
    }
    @Override
    public CompletableFuture<List<IBankAccount>> getBankAccountsAsync(ItemID itemID) {
        return CompletableFuture.completedFuture(getBankAccounts(itemID));
    }

    @Override
    public @Nullable IBankAccount getPersonalBankAccount(UUID userUUID)
    {
        for(Map.Entry<Integer, BankAccount> entry : bankAccounts.entrySet()) {
            BankAccount account = entry.getValue();
            User creator = account.getPersonalBankOwner();
            if(creator != null && creator.getUUID().equals(userUUID)) {
                return account; // Found the personal bank account
            }
        }
        return null; // No personal bank account found for this user
    }
    @Override
    public CompletableFuture<@Nullable IBankAccount> getPersonalBankAccountAsync(UUID userUUID) {
        return CompletableFuture.completedFuture(getPersonalBankAccount(userUUID));
    }


    @Override
    public @Nullable IBankAccount getPersonalBankAccount(String userName)
    {
        User user = getUserByName(userName);
        if(user == null) {
            return null;
        }
        else
        {
            return getPersonalBankAccount(user.getUUID());
        }
    }
    @Override
    public CompletableFuture<@Nullable IBankAccount> getPersonalBankAccountAsync(String userName) {
        return CompletableFuture.completedFuture(getPersonalBankAccount(userName));
    }

    @Override
    public @Nullable IBankAccount getOrCreatePersonalBankAccount(UUID userUUID)
    {
        IBankAccount account = getPersonalBankAccount(userUUID);
        if(account != null) {
            return account;
        }
        else {
            return createPersonalBankAccount(userUUID);
        }
    }
    @Override
    public CompletableFuture<@Nullable IBankAccount> getOrCreatePersonalBankAccountAsync(UUID userUUID) {
        return CompletableFuture.completedFuture(getOrCreatePersonalBankAccount(userUUID));
    }

    @Override
    public @Nullable IBankAccount getOrCreatePersonalBankAccount(@NotNull String userName)
    {
        User user = getUserByName(userName);
        if(user == null)
            return null;
        else
            return getOrCreatePersonalBankAccount(user.getUUID());
    }
    @Override
    public CompletableFuture<@Nullable IBankAccount> getOrCreatePersonalBankAccountAsync(@NotNull String userName) {
        return CompletableFuture.completedFuture(getOrCreatePersonalBankAccount(userName));
    }
    @Override
    public boolean userHasPersonalBankAccount(UUID userUUID)
    {
        for(Map.Entry<Integer, BankAccount> entry : bankAccounts.entrySet()) {
            BankAccount account = entry.getValue();
            User creator = account.getPersonalBankOwner();
            if(creator != null && creator.getUUID().equals(userUUID)) {
                return true; // User has a personal bank account
            }
        }
        return false;
    }
    @Override
    public CompletableFuture<Boolean> userHasPersonalBankAccountAsync(UUID userUUID) {
        return CompletableFuture.completedFuture(userHasPersonalBankAccount(userUUID));
    }

    @Override
    public boolean deleteBankAccount(int accountNumber)
    {
        if(bankAccounts.containsKey(accountNumber)) {
            BankAccount account = bankAccounts.get(accountNumber);
            if(account.getPersonalBankOwner() != null){
                error("Cannot delete personal bank account with number: " + accountNumber + ".");
                return false; // Cannot delete personal bank accounts
            }
            bankAccounts.remove(accountNumber);
            BACKEND_INSTANCES.SERVER_EVENTS.BANK_ACCOUNT_DELETED.notifyListeners(account);
            info("Deleted bank account with number: " + accountNumber);
            return true;
        } else {
            warn("No bank account found with number: " + accountNumber);
        }
        return false;
    }
    @Override
    public CompletableFuture<Boolean> deleteBankAccountAsync(int accountNumber) {
        return CompletableFuture.completedFuture(deleteBankAccount(accountNumber));
    }

    @Override
    public @Nullable IBank getPersonalBank(UUID owner, ItemID itemID)
    {
        IBankAccount account = getPersonalBankAccount(owner);
        if(account == null)
            return null;
        else
            return account.getBank(itemID);
    }
    @Override
    public CompletableFuture<@Nullable IBank> getPersonalBankAsync(UUID owner, ItemID itemID) {
        return CompletableFuture.completedFuture(getPersonalBank(owner, itemID));
    }

    @Override
    public @Nullable IBank getPersonalBank(String ownerName, ItemID itemID)
    {
        User owner = getUserByName(ownerName);
        if(owner == null)
        {
            return null;
        }
        IBankAccount account = getPersonalBankAccount(owner.getUUID());
        if(account == null)
            return null;
        else
            return account.getBank(itemID);
    }
    @Override
    public CompletableFuture<@Nullable IBank> getPersonalBankAsync(String ownerName, ItemID itemID) {
        return CompletableFuture.completedFuture(getPersonalBank(ownerName, itemID));
    }

    @Override
    public @Nullable IBank getOrCreatePersonalBank(UUID owner, ItemID itemID)
    {
        IBankAccount account = getOrCreatePersonalBankAccount(owner);
        if(account == null) {
            return null;
        }
        IBank bank = account.getBank(itemID);
        if(bank != null)
            return bank;
        else
            return account.createBank(itemID, 0);
    }
    @Override
    public CompletableFuture<@Nullable IBank> getOrCreatePersonalBankAsync(UUID owner, ItemID itemID) {
        return CompletableFuture.completedFuture(getOrCreatePersonalBank(owner, itemID));
    }

    @Override
    public @Nullable IBank getOrCreatePersonalBank(String ownerName, ItemID itemID)
    {
        User owner = getUserByName(ownerName);
        if(owner == null)
            return null;
        else
            return getOrCreatePersonalBank(owner.getUUID(), itemID);
    }
    @Override
    public CompletableFuture<@Nullable IBank> getOrCreatePersonalBankAsync(String ownerName, ItemID itemID) {
        return CompletableFuture.completedFuture(getOrCreatePersonalBank(ownerName, itemID));
    }




    @Override
    public boolean isItemIDAllowed(ItemID itemID)
    {
        return allowedItemIDs.contains(itemID);
    }
    @Override
    public CompletableFuture<Boolean> isItemIDAllowedAsync(ItemID itemID) {
        return CompletableFuture.completedFuture(isItemIDAllowed(itemID));
    }

    @Override
    public boolean allowItemID(ItemID itemID)
    {
        if(itemID == null)
            return false;
        if(isItemIDBlacklisted(itemID))
        {
            warn("It is not allowed to add the itemID: " + itemID + " because it is blacklisted.");
            return false;
        }

        this.allowedItemIDs.add(itemID);
        return true;
    }
    @Override
    public CompletableFuture<Boolean> allowItemIDAsync(ItemID itemID) {
        return CompletableFuture.completedFuture(allowItemID(itemID));
    }

    @Override
    public boolean disallowItemID(ItemID itemID)
    {
        if(itemID == null)
            return false;
        if(isItemIDNotRemovable(itemID))
        {
            warn("It is not allowed to remove the itemID: " + itemID);
            return false;
        }

        for(Map.Entry<Integer, BankAccount> entry : bankAccounts.entrySet()) {
            BankAccount account = entry.getValue();
            if(account.hasBank(itemID)) {
                account.removeBank(itemID);
                info("Removed item bank for itemID: " + itemID + " from account number: " + entry.getKey());
            }
        }
        return allowedItemIDs.remove(itemID);
    }
    @Override
    public CompletableFuture<Boolean> disallowItemIDAsync(ItemID itemID) {
        return CompletableFuture.completedFuture(disallowItemID(itemID));
    }

    @Override
    public boolean isItemIDNotRemovable(ItemID itemID)
    {
        List<ItemStack> notRemovable = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.INITIAL_NOT_REMOVABLE_ITEMS;
        List<ItemID> itemIDs = ItemIDManager.registerItemStackServerSide_direct(notRemovable);
        for(ItemID id : itemIDs)
        {
            if(id.equals(itemID))
            {
                return true;
            }
        }
        return false;
    }
    @Override
    public CompletableFuture<Boolean> isItemIDNotRemovableAsync(ItemID itemID) {
        return CompletableFuture.completedFuture(isItemIDNotRemovable(itemID));
    }

    @Override
    public boolean isItemIDBlacklisted(ItemID itemID)
    {
        List<ItemStack> blacklistItems = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.INITIAL_BLACKLIST_ITEMS;
        List<ItemID> itemIDs = ItemIDManager.registerItemStackServerSide_direct(blacklistItems);
        for(ItemID id : itemIDs)
        {
            if(id.equals(itemID))
            {
                return true;
            }
        }
        return false;
    }
    @Override
    public CompletableFuture<Boolean> isItemIDBlacklistedAsync(ItemID itemID) {
        return CompletableFuture.completedFuture(isItemIDBlacklisted(itemID));
    }

    @Override
    public double getRealMoneyCirculation()
    {
        double total = 0;
        ItemID moneyItemID = MoneyItem.getItemID();
        for (Map.Entry<Integer, BankAccount> entry : bankAccounts.entrySet()) {
            BankAccount account = entry.getValue();
            IBank moneyBank = account.getBank(moneyItemID);
            if(moneyBank != null) {
                total += moneyBank.getRealTotalBalance();
            }
        }
        return total;
    }
    @Override
    public CompletableFuture<Double> getRealMoneyCirculationAsync() {
        return CompletableFuture.completedFuture(getRealMoneyCirculation());
    }

    @Override
    public double getRealLockedMoneyCirculation()
    {
        double total = 0;
        ItemID moneyItemID = MoneyItem.getItemID();
        for (Map.Entry<Integer, BankAccount> entry : bankAccounts.entrySet()) {
            BankAccount account = entry.getValue();
            IBank moneyBank = account.getBank(moneyItemID);
            if(moneyBank != null) {
                total += moneyBank.getRealLockedBalance();
            }
        }
        return total;
    }
    @Override
    public CompletableFuture<Double> getRealLockedMoneyCirculationAsync() {
        return CompletableFuture.completedFuture(getRealLockedMoneyCirculation());
    }

    @Override
    public double getRealItemCirculation(ItemID itemID)
    {
        double total = 0;
        for (Map.Entry<Integer, BankAccount> entry : bankAccounts.entrySet()) {
            BankAccount account = entry.getValue();
            IBank bank = account.getBank(itemID);
            if(bank != null)
                total += bank.getRealTotalBalance();
        }
        return total;
    }
    @Override
    public CompletableFuture<Double> getRealItemCirculationAsync(ItemID itemID) {
        return CompletableFuture.completedFuture(getRealItemCirculation(itemID));
    }

    @Override
    public double getRealLockedItemCirculation(ItemID itemID)
    {
        double total = 0;
        for (Map.Entry<Integer, BankAccount> entry : bankAccounts.entrySet()) {
            BankAccount account = entry.getValue();
            IBank bank = account.getBank(itemID);
            if(bank != null)
                total += bank.getRealLockedBalance();
        }
        return total;
    }
    @Override
    public CompletableFuture<Double> getRealLockedItemCirculationAsync(ItemID itemID) {
        return CompletableFuture.completedFuture(getRealLockedItemCirculation(itemID));
    }

    @Override
    public JsonElement getCirculationDataJson()
    {
        class Data
        {
            public ItemID itemID;
            public double lockedBalance = 0;
            public double freeBalance = 0;
        }
        Map<ItemID, Data> sums = new HashMap<>();
        for(Map.Entry<Integer, BankAccount> entry : bankAccounts.entrySet())
        {
            BankAccount account = entry.getValue();
            for(Map.Entry<ItemID, IBank> bankEntry : account.getAllBanks().entrySet())
            {
                ItemID itemID = bankEntry.getKey();
                IBank bank = bankEntry.getValue();
                if(bank == null)
                    continue;
                Data data = sums.computeIfAbsent(itemID, k -> new Data());
                data.itemID = itemID;
                data.lockedBalance += bank.getRealLockedBalance();
                data.freeBalance += bank.getRealBalance();
            }
        }

        JsonArray circulationData = new JsonArray();
        for(Map.Entry<ItemID, Data> entry : sums.entrySet())
        {
            Data data = entry.getValue();
            JsonObject itemData = new JsonObject();
            itemData.add("itemID", data.itemID.toJson());
            itemData.addProperty("lockedBalance", data.lockedBalance);
            itemData.addProperty("freeBalance", data.freeBalance);
            circulationData.add(itemData);
        }
        return circulationData;
    }
    @Override
    public CompletableFuture<JsonElement> getCirculationDataJsonAsync() {
        return CompletableFuture.completedFuture(getCirculationDataJson());
    }

    @Override
    public String getCirculationDataJsonString()
    {
        JsonElement circulationData = getCirculationDataJson();
        return JsonUtilities.toPrettyString(circulationData);
    }
    @Override
    public CompletableFuture<String> getCirculationDataJsonStringAsync() {
        return CompletableFuture.completedFuture(getCirculationDataJsonString());
    }



    @Override
    public JsonElement toJson()
    {
        JsonObject jsonObject = new JsonObject();

        JsonArray usersJson = new JsonArray();
        for (User user : userMap.values()) {
            usersJson.add(user.toJson());
        }
        jsonObject.add("users", usersJson);

        JsonArray itemScaleFactorsJson = new JsonArray();
        for (ItemID itemID : allowedItemIDs) {
            JsonObject itemScaleFactorJson = new JsonObject();
            itemScaleFactorJson.add("itemID", itemID.toJson());
            itemScaleFactorsJson.add(itemScaleFactorJson);
        }
        jsonObject.add("itemCentScaleFactors", itemScaleFactorsJson);

        JsonArray accountsJson = new JsonArray();
        for (BankAccount account : bankAccounts.values()) {
            accountsJson.add(account.toJson());
        }
        jsonObject.add("bankAccounts", accountsJson);
        return jsonObject;
    }
    @Override
    public CompletableFuture<JsonElement> toJsonAsync() {
        return CompletableFuture.completedFuture(toJson());
    }





    @Override
    public String toJsonString()
    {
        return JsonUtilities.toPrettyString(toJson());
    }
    @Override
    public CompletableFuture<String> toJsonStringAsync() {
        return CompletableFuture.completedFuture(toJsonString());
    }


    @Override
    public void onPlayerJoin(UUID playerUUID, String playerName)
    {
        if(!userExists(playerUUID)) {
            addUser(playerUUID, playerName);
            createPersonalBankAccount(playerUUID);
        }
    }
    @Override
    public void onPlayerJoinAsync(UUID playerUUID, String playerName) {
        onPlayerJoin(playerUUID, playerName);
    }



    private int generateNewAccountNumber()
    {
        int newBankNumber = nextAccountNumber;
        while(bankAccounts.containsKey(newBankNumber)) {
            newBankNumber++;
        }
        nextAccountNumber = newBankNumber+1; // Increment for the next account number
        return newBankNumber;
    }

    public void setupDefaultItems()
    {
        // Check if all allowed items have a scale factor
        List<ItemStack> allowedItems = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.INITIAL_ALLOWED_ITEMS;
        List<ItemID> itemIDs = ItemIDManager.registerItemStackServerSide_direct(allowedItems);
        allowedItemIDs.addAll(itemIDs);
    }

    @Override
    public boolean save(Map<String, ListTag> listTags) {
        CompoundTag metaData = new CompoundTag();
        metaData.putInt("version", 1); // Versioning for future changes
        metaData.putInt("nextAccountNumber", nextAccountNumber);
        ListTag metaTagList = new ListTag();
        metaTagList.add(metaData);
        listTags.put("meta", metaTagList);


        ListTag userList = new ListTag();
        for (Map.Entry<UUID, User> entry : userMap.entrySet()) {
            CompoundTag userTag = new CompoundTag();
            entry.getValue().save(userTag);
            userList.add(userTag);
        }
        listTags.put("users", userList);

        // Save item cent scale factors
        ListTag allowedItems = new ListTag();
        for (ItemID itemID : allowedItemIDs) {
            CompoundTag pairTag = new CompoundTag();
            CompoundTag itemTag = new CompoundTag();
            itemID.save(itemTag);
            pairTag.put("itemID", itemTag);
            allowedItems.add(pairTag);
        }
        listTags.put("allowedItems", allowedItems);

        ListTag accountsList = new ListTag();
        for (Map.Entry<Integer, BankAccount> entry : bankAccounts.entrySet()) {
            CompoundTag accountTag = new CompoundTag();
            entry.getValue().save(accountTag);
            accountsList.add(accountTag);
        }
        listTags.put("bankAccounts", accountsList);



        return true;
    }

    @Override
    public boolean load(Map<String, ListTag> listTags) {
        CompoundTag metaData = listTags.getOrDefault("meta", new ListTag()).getCompound(0);
        int version = metaData.getInt("version");
        nextAccountNumber = metaData.getInt("nextAccountNumber");


        // Load item cent scale factors
        if(listTags.containsKey("allowedItems")) {
            ListTag allowedItems = listTags.get("allowedItems");
            allowedItemIDs.clear();
            for (int i = 0; i < allowedItems.size(); i++) {
                CompoundTag idTag = allowedItems.getCompound(i);
                if(!idTag.contains("itemID")) {
                    continue; // Skip invalid entries
                }

                ItemID itemID = ItemID.createFromTag(idTag.getCompound("itemID"));
                allowedItemIDs.add(itemID);
            }
        }
        else {
            setupDefaultItems(); // Setup default items if no scale factors are present
        }



        // Load users
        if(listTags.containsKey("users")) {
            ListTag userList = listTags.get("users");
            userMap.clear();
            for (int i = 0; i < userList.size(); i++) {
                CompoundTag userTag = userList.getCompound(i);
                User user = User.createFromTag(userTag);
                if(user != null) {
                    userMap.put(user.getUUID(), user);
                } else {
                    warn("Failed to load user from tag: " + userTag);
                }
            }
        }

        // Load bank accounts
        if(listTags.containsKey("bankAccounts")) {
            ListTag accountsList = listTags.get("bankAccounts");
            bankAccounts.clear();
            for (int i = 0; i < accountsList.size(); i++) {
                CompoundTag accountTag = accountsList.getCompound(i);
                BankAccount account = BankAccount.createFromTag(accountTag);
                if(account != null) {
                    bankAccounts.put(account.getAccountNumber(), account);
                } else {
                    warn("Failed to load bank account from tag: " + accountTag);
                }
            }
        }
        return true;
    }

    public boolean load_compatibilityMode_setNextAccountNumber(int nextAccountNumber)
    {
        this.nextAccountNumber = nextAccountNumber;
        return true;
    }
    /*public boolean load_compatibilityMode_setItemFractionScaleFactors(Map<ItemID, Integer> itemFractionScaleFactor)
    {
        this.itemFractionScaleFactor.clear();
        this.itemFractionScaleFactor.putAll(itemFractionScaleFactor);
        return true;
    }*/
    public boolean load_compatibilityMode_setUsers(Map<UUID, User> userMap)
    {
        this.userMap.clear();
        this.userMap.putAll(userMap);
        return true;
    }
    public boolean load_compatibilityMode_setBankAccounts(Map<Integer, BankAccount> bankAccounts)
    {
        this.bankAccounts.clear();
        this.bankAccounts.putAll(bankAccounts);
        return true;
    }




    @Override
    public String toString() {
        return toJsonString();
    }

    private void info(String msg)
    {
        BACKEND_INSTANCES.LOGGER.info("[SyncServerBankManager] " + msg);
    }
    private void error(String msg)
    {
        BACKEND_INSTANCES.LOGGER.error("[SyncServerBankManager] " + msg);
    }
    private void error(String msg, Throwable e)
    {
        BACKEND_INSTANCES.LOGGER.error("[SyncServerBankManager] " + msg, e);
    }
    private void warn(String msg)
    {
        BACKEND_INSTANCES.LOGGER.warn("[SyncServerBankManager] " + msg);
    }
    private void debug(String msg)
    {
        BACKEND_INSTANCES.LOGGER.debug("[SyncServerBankManager] " + msg);
    }
}
