package net.kroia.banksystem.banking;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.api.IBank;
import net.kroia.banksystem.api.IServerBankManager;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankManagerData;
import net.kroia.banksystem.banking.clientdata.ItemInfoData;
import net.kroia.banksystem.banking.clientdata.UserData;
import net.kroia.banksystem.compat.OldBankDataLoader;
import net.kroia.banksystem.item.custom.money.MoneyItem;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.JsonUtilities;
import net.kroia.modutilities.ServerSaveable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class ServerBankManager implements ServerSaveable, IServerBankManager {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        ServerBankManager.BACKEND_INSTANCES = backend;
        BankAccount.setBackend(backend);
    }


    /**
     * Using the player UUID as key
     */
    private final Map<UUID, User> userMap = new HashMap<>();

    /**
     * Using the ItemID for which the scale factor belongs to as key.
     */
    private final Map<ItemID, Integer> itemFractionScaleFactor = new HashMap<>();

    /**
     * Using the account number as key.
     */
    private final Map<Integer, BankAccount> bankAccounts = new HashMap<>();

    private int nextAccountNumber = 1; // Start with account number 1


    public ServerBankManager()
    {

    }
    public static ServerBankManager createFromTag(CompoundTag tag)
    {
        ServerBankManager manager = new ServerBankManager();
        if(!manager.load(tag)) {
            return null; // Invalid data
        }
        return manager;
    }


    public BankManagerData getBankManagerData()
    {
        return new BankManagerData(
                getBankManagerUserMapData(),
                getBankManagerItemFractionScaleFactorData(),
                getBankManagerBankAccountsData(),
                getAllowedItems(),
                getBlacklistedItems(),
                getNotRemovableItems()
        );
    }
    public BankManagerData.UserMapData getBankManagerUserMapData()
    {
        Map<UUID, UserData> userDataMap = new HashMap<>();
        for (Map.Entry<UUID, User> entry : userMap.entrySet()) {
            userDataMap.put(entry.getKey(), entry.getValue().getUserData());
        }
        return new BankManagerData.UserMapData(userDataMap);
    }
    public BankManagerData.ItemFractionScaleFactorData getBankManagerItemFractionScaleFactorData()
    {
        Map<ItemID, Integer> itemFractionScaleFactorMap = new HashMap<>(itemFractionScaleFactor);
        return new BankManagerData.ItemFractionScaleFactorData(itemFractionScaleFactorMap);
    }
    public BankManagerData.BankAccountsData getBankManagerBankAccountsData()
    {
        Map<Integer, BankAccountData> bankAccountDataMap = new HashMap<>();
        for (Map.Entry<Integer, BankAccount> entry : bankAccounts.entrySet()) {
            bankAccountDataMap.put(entry.getKey(), entry.getValue().getAccountData());
        }
        return new BankManagerData.BankAccountsData(bankAccountDataMap);
    }

    public List<ItemID> getAllowedItems()
    {
        return BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ALLOWED_ITEM_IDS.get().stream()
                .map(item -> item.itemID)
                .toList();
    }
    public List<ItemID> getBlacklistedItems()
    {
        return BACKEND_INSTANCES.SERVER_SETTINGS.BANK.BLACKLIST_ITEM_IDS.get();
    }
    public List<ItemID> getNotRemovableItems()
    {
        return BACKEND_INSTANCES.SERVER_SETTINGS.BANK.NOT_REMOVABLE_ITEM_IDS.get();
    }
    public ItemInfoData getItemInfoData(ItemID itemID)
    {
        double totalSupply = 0;
        double totalLocked = 0;
        List<BankAccountData> bankAccounts = new java.util.ArrayList<>();
        int itemFractionScaleFactor = getItemFractionScaleFactor(itemID);

        for (Map.Entry<Integer, BankAccount> entry : this.bankAccounts.entrySet()) {
            BankAccount account = entry.getValue();
            IBank bank = account.getBank(itemID);
            if(bank == null)
                continue;
            totalSupply += bank.getRealTotalBalance();
            totalLocked += bank.getRealLockedBalance();
            bankAccounts.add(account.getAccountData(itemID));
        }
        return new ItemInfoData(itemID, totalSupply, totalLocked, bankAccounts, itemFractionScaleFactor);
    }



    public BankAccount getBankAccount(int accountNumber)
    {
        return bankAccounts.get(accountNumber);
    }

    /**
     * Gets all bank accounts in which the user with the given UUID is a member.
     * @param userUUID
     * @return
     */
    public List<BankAccount> getBankAccounts(UUID userUUID)
    {
        List<BankAccount> accounts = new ArrayList<>();
        for(Map.Entry<Integer, BankAccount> entry : bankAccounts.entrySet()) {
            BankAccount account = entry.getValue();
            if(account.hasUser(userUUID)) {
                accounts.add(account); // Add the account if the user is a member
            }
        }
        return accounts; // Return all accounts the user is a member of
    }
    public List<BankAccount> getBankAccounts(ItemID itemID)
    {
        List<BankAccount> accounts = new ArrayList<>();
        for(Map.Entry<Integer, BankAccount> entry : bankAccounts.entrySet()) {
            BankAccount account = entry.getValue();
            if(account.hasBank(itemID)) {
                accounts.add(account); // Add the account if it has the bank for the given itemID
            }
        }
        return accounts; // Return all accounts that have a bank for the given itemID
    }

    @Override
    public BankAccount getPersonalBankAccount(UUID userUUID)
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
    public BankAccount getPersonalBankAccount(User user)
    {
        if(user == null) {
            warn("Cannot get personal bank account for null user.");
            return null;
        }
        return getPersonalBankAccount(user.getUUID());
    }
    public BankAccount getPersonalBankAccount(String userName)
    {
        User user = getUserByName(userName);
        return getPersonalBankAccount(user.getUUID());
    }
    public @Nullable BankAccount getOrCreatePersonalBankAccount(UUID userUUID)
    {
        BankAccount account = getPersonalBankAccount(userUUID);
        if(account != null)
            return account;
        account = createPersonalBankAccount(userUUID);
        return account;
    }
    public BankAccount getOrCreatePersonalBankAccount(@NotNull User user)
    {
        return getOrCreatePersonalBankAccount(user.getUUID());
    }
    public @Nullable BankAccount getOrCreatePersonalBankAccount(@NotNull String userName)
    {
        User user = getUserByName(userName);
        if(user == null)
            return null;
        return getOrCreatePersonalBankAccount(user.getUUID());
    }




    public @Nullable IBank getPersonalBank(UUID owner, ItemID itemID)
    {
        BankAccount account = getPersonalBankAccount(owner);
        if(account == null)
            return null;
        return account.getBank(itemID);
    }
    public @Nullable IBank getPersonalBank(String ownerName, ItemID itemID)
    {
        User owner = getUserByName(ownerName);
        BankAccount account = getPersonalBankAccount(owner.getUUID());
        if(account == null)
            return null;
        return account.getBank(itemID);
    }
    public @Nullable IBank getPersonalBank(User owner, ItemID itemID)
    {
        BankAccount account = getPersonalBankAccount(owner.getUUID());
        if(account == null)
            return null;
        return account.getBank(itemID);
    }
    public @Nullable IBank getOrCreatePersonalBank(UUID owner, ItemID itemID)
    {
        BankAccount account = getOrCreatePersonalBankAccount(owner);
        if(account == null)
            return null;
        IBank bank = account.getBank(itemID);
        if(bank != null)
            return bank;
        return account.createBank(itemID, 0);
    }
    public @Nullable IBank getOrCreatePersonalBank(String ownerName, ItemID itemID)
    {
        User owner = getUserByName(ownerName);
        return getOrCreatePersonalBank(owner.getUUID(), itemID);
    }
    public @Nullable IBank getOrCreatePersonalBank(User owner, ItemID itemID)
    {
        return getOrCreatePersonalBank(owner.getUUID(), itemID);
    }

    public boolean deleteBankAccount(int accountNumber)
    {
        if(bankAccounts.containsKey(accountNumber)) {
            BankAccount account = bankAccounts.get(accountNumber);
            if(account.getPersonalBankOwner() != null){
                error("Cannot delete personal bank account with number: " + accountNumber + ".");
                return false; // Cannot delete personal bank accounts
            }
            bankAccounts.remove(accountNumber);
            info("Deleted bank account with number: " + accountNumber);
            return true;
        } else {
            warn("No bank account found with number: " + accountNumber);
        }
        return false;
    }


    public void addUser(@NotNull ServerPlayer player)
    {
        UUID userUUID = player.getUUID();
        String userName = player.getName().getString();
        if(userMap.containsKey(userUUID)) {
            warn("User with UUID " + userUUID + " already exists. Not adding again.");
            userMap.get(userUUID);
            return;
        }
        User user = new User(userUUID, userName, true);
        userMap.put(userUUID, user);
        info("Added new user: " + userName + " with UUID: " + userUUID);
    }
    public void addUser(@NotNull User user)
    {
        if(user == null) {
            warn("Cannot add null user.");
            return;
        }
        UUID userUUID = user.getUUID();
        if(userMap.containsKey(userUUID)) {
            warn("User with UUID " + userUUID + " already exists. Not adding again.");
            return;
        }
        userMap.put(userUUID, user);
        info("Added new user: " + user.getName() + " with UUID: " + userUUID);
    }
    public boolean removeUser(UUID userUUID)
    {
        if(userMap.containsKey(userUUID)) {
            userMap.remove(userUUID);
            for(Map.Entry<Integer, BankAccount> entry : bankAccounts.entrySet()) {
                BankAccount account = entry.getValue();
                if(account.hasUser(userUUID)) {
                    account.removeUser(userUUID);
                    if(!account.hasAnyUser()) {
                        bankAccounts.remove(entry.getKey());
                        info("Removed empty bank account with number: " + entry.getKey());
                    }
                }
            }
            info("Removed user with UUID: " + userUUID);
            return true;
        } else {
            warn("No user found with UUID: " + userUUID);
        }
        return false;
    }
    public boolean userExists(UUID userUUID)
    {
        return userMap.containsKey(userUUID);
    }
    public User getUserByUUID(UUID userUUID) {
        return userMap.get(userUUID);
    }
    public User getUserByName(String name)
    {
        String lowerCaseName = name.toLowerCase();
        for (User user : userMap.values()) {
            if(user.getName().toLowerCase().equals(lowerCaseName)) {
                return user;
            }
        }
        return null;
    }



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
    public BankAccount createPersonalBankAccount(UUID user)
    {
        User creator = userMap.get(user);
        if(creator == null) {
            warn("No user found with UUID: " + user);
            return null;
        }
        if(userHasPersonalBankAccount(user)) {
            warn("User with UUID: " + user + " already has a personal bank account.");
            return null;
        }

        BankAccount existingAccount = getPersonalBankAccount(user);
        if(existingAccount != null) {
            warn("User with UUID: " + user + " already has a personal bank account with number: " + existingAccount.getAccountNumber());
            return null;
        }

        int accountNumber = generateNewAccountNumber();
        float startBalance = BACKEND_INSTANCES.SERVER_SETTINGS.PLAYER.STARTING_BALANCE.get();
        BankAccount account = BankAccount.createPersonal(accountNumber, creator, startBalance);
        if(account == null) {
            warn("Failed to create personal bank account for user with UUID: " + user);
            return null;
        }
        bankAccounts.put(accountNumber, account);
        return account;
    }

    public BankAccount createBankAccount(String accountName)
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
        account.setAccountIcon(ItemID.of(Items.CHEST.getDefaultInstance()));
        bankAccounts.put(accountNumber, account);
        info("Created new bank account with number: " + accountNumber + " and name: " + accountName);
        return account;
    }




    public int getItemFractionScaleFactor(ItemID itemID)
    {
        if(itemID == null)
            return 1;
        Integer scaleFactor = itemFractionScaleFactor.get(itemID);
        if(scaleFactor == null || scaleFactor <= 0) {
            return 1;
        }
        return scaleFactor;
    }
    public boolean isItemIDAllowed(ItemID itemID)
    {
        return itemFractionScaleFactor.containsKey(itemID) && itemFractionScaleFactor.get(itemID) > 0;
    }
    public boolean allowItemID(ItemID itemID, int itemFractionScaleFactor)
    {
        if(itemID == null)
            return false;
        if(isItemIDBlacklisted(itemID))
        {
            warn("It is not allowed to add the itemID: " + itemID + " because it is blacklisted.");
            return false;
        }
        if(MoneyItem.isMoney(itemID))
            itemFractionScaleFactor = MoneyItem.ITEM_FRACTION_SCALE_FACTOR;
        else if(itemFractionScaleFactor != 1 && itemFractionScaleFactor != 10 && itemFractionScaleFactor != 100)
            itemFractionScaleFactor = 1;
        this.itemFractionScaleFactor.put(itemID, itemFractionScaleFactor);

        List<BankSystemModSettings.Bank.ItemIDAndScaleFactor> allowed = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ALLOWED_ITEM_IDS.get();
        for(var allowedItem : allowed) {
            if(allowedItem.itemID.equals(itemID)) {

                return true;
            }
        }

        BankSystemModSettings.Bank.ItemIDAndScaleFactor itemIDAndScaleFactor = new BankSystemModSettings.Bank.ItemIDAndScaleFactor(itemID, itemFractionScaleFactor);
        allowed.add(itemIDAndScaleFactor);
        BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ALLOWED_ITEM_IDS.set(allowed);
        return true;
    }
    public boolean disallowItemID(ItemID itemID)
    {
        if(itemID == null)
            return false;
        if(isItemIDNotRemovable(itemID))
        {
            warn("It is not allowed to remove the itemID: " + itemID);
            return false;
        }
        List<BankSystemModSettings.Bank.ItemIDAndScaleFactor> allowed = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ALLOWED_ITEM_IDS.get();
        for(var allowedItem : allowed) {
            if(allowedItem.itemID.equals(itemID)) {
                allowed.remove(allowedItem);
                BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ALLOWED_ITEM_IDS.set(allowed);
                for(Map.Entry<Integer, BankAccount> entry : bankAccounts.entrySet()) {
                    BankAccount account = entry.getValue();
                    if(account.hasBank(itemID)) {
                        account.removeBank(itemID);
                        info("Removed item bank for itemID: " + itemID + " from account number: " + entry.getKey());
                    }
                }
                // Remove all factors
                itemFractionScaleFactor.keySet().removeIf(existingItemID -> existingItemID.equals(itemID));
                return true;
            }
        }
        return false;
    }
    public boolean isItemIDNotRemovable(ItemID itemID)
    {
        List<ItemID> notRemovable = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.NOT_REMOVABLE_ITEM_IDS.get();
        return notRemovable.contains(itemID);
    }
    public boolean isItemIDBlacklisted(ItemID itemID)
    {
        List<ItemID> blackList = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.BLACKLIST_ITEM_IDS.get();
        return blackList.contains(itemID);
    }

    @Override
    public List<ItemID> getAllowedItemIDs()
    {
        var ids = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ALLOWED_ITEM_IDS.get();
        List<ItemID> allowedItemIDs = new ArrayList<>(ids.size());
        for (var el : ids) {
            allowedItemIDs.add(el.itemID);
        }
        return allowedItemIDs;
    }

    @Override
    public List<ItemID> getBlacklistedItemIDs()
    {
        return new ArrayList<>(BACKEND_INSTANCES.SERVER_SETTINGS.BANK.BLACKLIST_ITEM_IDS.get());
    }
    @Override
    public List<ItemID> getNotRemovableItemIDs()
    {
        return new ArrayList<>(BACKEND_INSTANCES.SERVER_SETTINGS.BANK.NOT_REMOVABLE_ITEM_IDS.get());
    }



    @Override
    public double getRealMoneyCirculation()
    {
        double total = 0;
        for (Map.Entry<Integer, BankAccount> entry : bankAccounts.entrySet()) {
            BankAccount account = entry.getValue();
            IBank moneyBank = account.getBank(MoneyItem.getItemID());
            if(moneyBank != null) {
                total += moneyBank.getRealTotalBalance();
            }
        }
        return total;
    }


    @Override
    public double getRealLockedMoneyCirculation()
    {
        double total = 0;
        for (Map.Entry<Integer, BankAccount> entry : bankAccounts.entrySet()) {
            BankAccount account = entry.getValue();
            IBank moneyBank = account.getBank(MoneyItem.getItemID());
            if(moneyBank != null) {
                total += moneyBank.getRealLockedBalance();
            }
        }
        return total;
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
    public String getCirculationDataJsonString()
    {
        JsonElement circulationData = getCirculationDataJson();
        return JsonUtilities.toPrettyString(circulationData);
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

    @Override
    public boolean save(CompoundTag tag) {
        tag.putInt("version", 1); // Versioning for future changes
        tag.putInt("nextAccountNumber", nextAccountNumber);

        ListTag userList = new ListTag();
        for (Map.Entry<UUID, User> entry : userMap.entrySet()) {
            CompoundTag userTag = new CompoundTag();
            entry.getValue().save(userTag);
            userList.add(userTag);
        }
        tag.put("users", userList);

        // Save item cent scale factors
        ListTag itemScaleFactors = new ListTag();
        for (Map.Entry<ItemID, Integer> entry : itemFractionScaleFactor.entrySet()) {
            CompoundTag pairTag = new CompoundTag();
            CompoundTag itemTag = new CompoundTag();
            entry.getKey().save(itemTag);
            pairTag.put("itemID", itemTag);
            pairTag.putInt("scaleFactor", entry.getValue());
            itemScaleFactors.add(pairTag);
        }
        tag.put("itemCentScaleFactors", itemScaleFactors);

        ListTag accountsList = new ListTag();
        for (Map.Entry<Integer, BankAccount> entry : bankAccounts.entrySet()) {
            CompoundTag accountTag = new CompoundTag();
            entry.getValue().save(accountTag);
            accountsList.add(accountTag);
        }
        tag.put("bankAccounts", accountsList);



        return true;
    }

    @Override
    public boolean load(CompoundTag tag) {
        if(!tag.contains("version"))
        {
            return compatibility_load_old_users(tag);
        }
        if(tag.getInt("version") > 1) {
            error("Unsupported version of bank data: " + tag.getInt("version"));
            return false;
        }

        nextAccountNumber = tag.getInt("nextAccountNumber");

        // Load item cent scale factors
        if(tag.contains("itemCentScaleFactors")) {
            ListTag itemScaleFactors = tag.getList("itemCentScaleFactors", 10);
            itemFractionScaleFactor.clear();
            for (int i = 0; i < itemScaleFactors.size(); i++) {
                CompoundTag pairTag = itemScaleFactors.getCompound(i);
                if(!pairTag.contains("itemID") || !pairTag.contains("scaleFactor")) {
                    warn("Invalid item scale factor tag: " + pairTag);
                    continue; // Skip invalid entries
                }
                ItemID itemID = ItemID.createFromTag(pairTag.getCompound("itemID"));
                int scaleFactor = pairTag.getInt("scaleFactor");
                itemFractionScaleFactor.put(itemID, scaleFactor);
            }
        }

        // Check if all allowed items have a scale factor
        List<BankSystemModSettings.Bank.ItemIDAndScaleFactor> allowedItems = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ALLOWED_ITEM_IDS.get();
        for (var el : allowedItems) {
            if(itemFractionScaleFactor.containsKey(el.itemID))
                continue;
            itemFractionScaleFactor.put(el.itemID, el.itemFractionScaleFactor);
        }

        // Load users
        if(tag.contains("users")) {
            ListTag userList = tag.getList("users", 10);
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
        if(tag.contains("bankAccounts")) {
            ListTag accountsList = tag.getList("bankAccounts", 10);
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


    public JsonElement toJson()
    {
        JsonObject jsonObject = new JsonObject();

        JsonArray usersJson = new JsonArray();
        for (User user : userMap.values()) {
            usersJson.add(user.toJson());
        }
        jsonObject.add("users", usersJson);

        JsonArray itemScaleFactorsJson = new JsonArray();
        for (Map.Entry<ItemID, Integer> entry : itemFractionScaleFactor.entrySet()) {
            JsonObject itemScaleFactorJson = new JsonObject();
            itemScaleFactorJson.add("itemID", entry.getKey().toJson());
            itemScaleFactorJson.addProperty("scaleFactor", entry.getValue());
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
    public String toJsonString()
    {
        return JsonUtilities.toPrettyString(toJson());
    }
    @Override
    public String toString() {
        return toJsonString();
    }


/*
    private final Map<UUID, BankUserOld> userMap = new HashMap<>();
    private final Map<ItemID, Integer> itemFractionScaleFactor = new HashMap<>();

    @Override
    public MinimalBankUserData getMinimalBankUserData(UUID userUUID)
    {
        BankUserOld user = userMap.get(userUUID);
        if(user == null)
            return null;
        return user.getMinimalData();
    }

    @Override
    public BankData getMinimalBankData(UUID userUUID, ItemID itemID)
    {
        BankUserOld user = userMap.get(userUUID);
        if(user == null)
            return null;
        IBank bank = user.getBank(itemID);
        if(bank == null)
            return null;
        return bank.getMinimalData();
    }

    @Override
    public ItemInfoData getItemInfoData(ItemID itemID)
    {
        return new ItemInfoData(this, itemID);
    }

    @Override
    public MinimalBankManagerData getMinimalData()
    {
        return new MinimalBankManagerData(this);
    }

    @Override
    public IBankUserOld createUser(UUID userUUID, String userName, ArrayList<ItemID> itemIDs, boolean createMoneyBank, float startMoney)
    {
        BankUserOld user = userMap.get(userUUID);
        if(user != null)
            return user;
        user = new BankUserOld(userUUID, userName);
        for(ItemID itemID : itemIDs)
            user.createItemBank(itemID, 0, true);
        if(createMoneyBank)
            user.createMoneyBank(startMoney);
        ServerPlayerUtilities.printToClientConsole(userUUID, BankSystemTextMessages.getBankCreatedMessage(userName, MoneyItem.getName())+"\n"+
                BankSystemTextMessages.getMoneyBankAccessHelpMessage());
        userMap.put(userUUID, user);
        return user;
    }

    @Override
    public IBankUserOld createUser(ServerPlayer player, ArrayList<ItemID> itemIDs, boolean createMoneyBank, float startMoney)
    {
        String userName = player.getName().getString();
        UUID uuid = player.getUUID();
        return createUser(uuid, userName, itemIDs, createMoneyBank, startMoney);
    }


    @Override
    public @Nullable IBankUserOld getUser(UUID userUUID)
    {
        return userMap.get(userUUID);
    }
    @Override
    public @Nullable IBankUserOld getUser(String userName)
    {
        for (Map.Entry<UUID, BankUserOld> entry : userMap.entrySet()) {
            if(entry.getValue().getPlayerName().equals(userName))
                return entry.getValue();
        }
        return null;
    }

    @Override
    public Map<UUID, IBankUserOld> getUser()
    {
        return new HashMap<>(userMap);
    }

    @Override
    public void clear()
    {
        HashMap<UUID, CloseItemBankEventData.PlayerData> lostItems = new HashMap<>();
        HashMap<ItemID, Boolean> allRemovedItemIDs = new HashMap<>();
        for(BankUserOld user : userMap.values())
        {
            HashMap<ItemID, Long> itemAmounts = new HashMap<>();
            user.getAllBanks().forEach((itemID, bank) -> {
                itemAmounts.put(itemID, bank.getTotalBalance());
                allRemovedItemIDs.put(itemID, true);
            });
            for(var itemID : itemAmounts.keySet())
            {
                user.removeBank(itemID);
            }
            CloseItemBankEventData.PlayerData playerData = new CloseItemBankEventData.PlayerData(user.getPlayerUUID(), itemAmounts);
            lostItems.put(user.getPlayerUUID(), playerData);
        }
        userMap.clear();

        CloseItemBankEventData event = new CloseItemBankEventData(lostItems, new ArrayList<>(allRemovedItemIDs.keySet()));
        BACKEND_INSTANCES.SERVER_EVENTS.CLOSE_ITEM_BANK_EVENT.notifyListeners(event);
    }

    @Override
    public boolean closeBankAccount(UUID userUUID, ItemID itemID)
    {
        BankUserOld user = userMap.get(userUUID);
        IBank bank = user.getBank(itemID);
        if(bank == null) {
            return false;
        }
        HashMap<ItemID, Long> itemAmounts = new HashMap<>();
        itemAmounts.put(itemID, bank.getTotalBalance());
        HashMap<UUID, CloseItemBankEventData.PlayerData> lostItems = new HashMap<>();
        lostItems.put(user.getPlayerUUID(), new CloseItemBankEventData.PlayerData(user.getPlayerUUID(), itemAmounts));
        if(user.removeBank(itemID))
        {
            ArrayList<ItemID> itemIDs= new ArrayList<>();
            itemIDs.add(itemID);
            CloseItemBankEventData event = new CloseItemBankEventData(lostItems, itemIDs);
            BACKEND_INSTANCES.SERVER_EVENTS.CLOSE_ITEM_BANK_EVENT.notifyListeners(event);
            return true;
        }
        return false;
    }

    @Override
    public void closeBankAccount(ItemID itemID)
    {
        HashMap<UUID, CloseItemBankEventData.PlayerData> lostItems = new HashMap<>();
        for(BankUserOld user : userMap.values())
        {
            IBank bank = user.getBank(itemID);
            if(bank == null)
                continue;
            HashMap<ItemID, Long> itemAmounts = new HashMap<>();
            itemAmounts.put(itemID, bank.getTotalBalance());
            user.removeBank(itemID);
            lostItems.put(user.getPlayerUUID(), new CloseItemBankEventData.PlayerData(user.getPlayerUUID(), itemAmounts));
        }
        ArrayList<ItemID> itemIDs= new ArrayList<>();
        itemIDs.add(itemID);
        CloseItemBankEventData event = new CloseItemBankEventData(lostItems, itemIDs);
        BACKEND_INSTANCES.SERVER_EVENTS.CLOSE_ITEM_BANK_EVENT.notifyListeners(event);

    }

    @Override
    public void closeBankAccount(String itemIDStr)
    {
        HashMap<UUID, CloseItemBankEventData.PlayerData> lostItems = new HashMap<>();
        ArrayList<ItemID> itemIDs= new ArrayList<>();
        for(BankUserOld user : userMap.values())
        {
            HashMap<ItemID, IBank> bankMap = user.getAllBanks();
            ArrayList<ItemID> itemIDsToRemove = new ArrayList<>();
            for(Map.Entry<ItemID, IBank> entry : bankMap.entrySet())
            {
                if(!entry.getKey().getName().equals(itemIDStr))
                    continue;
                IBank bank = entry.getValue();
                if(bank == null)
                    continue;
                ItemID itemID = entry.getKey();
                HashMap<ItemID, Long> itemAmounts = new HashMap<>();
                itemAmounts.put(itemID, bank.getTotalBalance());
                itemIDsToRemove.add(itemID);
                lostItems.put(user.getPlayerUUID(), new CloseItemBankEventData.PlayerData(user.getPlayerUUID(), itemAmounts));
                itemIDs.add(itemID);
            }
            for (ItemID itemID : itemIDsToRemove) {
                user.removeBank(itemID);
            }
        }
        if(!lostItems.isEmpty() || itemIDs.isEmpty())
        {
            CloseItemBankEventData event = new CloseItemBankEventData(lostItems, itemIDs);
            BACKEND_INSTANCES.SERVER_EVENTS.CLOSE_ITEM_BANK_EVENT.notifyListeners(event);
        }
    }

    @Override
    public boolean removeUser(UUID userUUID)
    {
        BankUserOld user = userMap.get(userUUID);
        if(user == null)
            return false;
        HashMap<ItemID, Long> itemAmounts = new HashMap<>();
        for (Map.Entry<ItemID, IBank> entry : user.getAllBanks().entrySet()) {
            itemAmounts.put(entry.getKey(), entry.getValue().getTotalBalance());
        }
        HashMap<UUID, CloseItemBankEventData.PlayerData> lostItems = new HashMap<>();
        lostItems.put(user.getPlayerUUID(), new CloseItemBankEventData.PlayerData(user.getPlayerUUID(), itemAmounts));
        userMap.remove(userUUID);
        if(!itemAmounts.isEmpty() || !lostItems.isEmpty()) {
            CloseItemBankEventData event = new CloseItemBankEventData(lostItems, new ArrayList<>(itemAmounts.keySet()));
            BACKEND_INSTANCES.SERVER_EVENTS.CLOSE_ITEM_BANK_EVENT.notifyListeners(event);
        }
        return true;
    }

    @Override
    public Map<UUID, String> getUserNameMap()
    {
        Map<UUID, String> map = new HashMap<>();
        for (Map.Entry<UUID, BankUserOld> entry : userMap.entrySet()) {
            map.put(entry.getKey(), entry.getValue().getPlayerName());
        }
        return map;
    }


    @Override
    public List<UUID> getUserUUIDList()
    {
        List<UUID> userUUIDs = new ArrayList<>();
        for (Map.Entry<UUID, BankUserOld> entry : userMap.entrySet()) {
            userUUIDs.add(entry.getKey());
        }
        return userUUIDs;
    }

    @Override
    public @Nullable IBank getMoneyBank(UUID userUUID)
    {
        BankUserOld user = userMap.get(userUUID);
        if(user == null)
            return null;
        return user.getMoneyBank();
    }
    @Override
    public @Nullable Bank getMoneyBank(String userName)
    {
        for (Map.Entry<UUID, BankUserOld> entry : userMap.entrySet()) {
            if(entry.getValue().getPlayerName().equals(userName))
                return entry.getValue().getMoneyBank();
        }
        return null;
    }
    @Override
    public @Nullable IBank getBank(UUID userUUID, ItemID itemID)
    {
        BankUserOld user = userMap.get(userUUID);
        if(user == null)
            return null;
        return user.getBank(itemID);
    }
    @Override
    public @Nullable IBank getBank(String userName, ItemID itemID)
    {
        for (Map.Entry<UUID, BankUserOld> entry : userMap.entrySet()) {
            if(entry.getValue().getPlayerName().equals(userName))
                return entry.getValue().getBank(itemID);
        }
        return null;
    }


    @Override
    public long getMoneyCirculation()
    {
        long total = 0;
        for (Map.Entry<UUID, BankUserOld> entry : userMap.entrySet()) {
            total += entry.getValue().getTotalMoneyBalance();
        }
        return total;
    }

    @Override
    public long getLockedMoneyCirculation()
    {
        long total = 0;
        for (Map.Entry<UUID, BankUserOld> entry : userMap.entrySet()) {
            total += entry.getValue().getMoneyBalance();
        }
        return total;
    }

    @Override
    public long getItemCirculation(ItemID itemID)
    {
        long total = 0;
        for (Map.Entry<UUID, BankUserOld> entry : userMap.entrySet()) {
            IBank bank = entry.getValue().getBank(itemID);
            if(bank != null)
                total += bank.getTotalBalance();
        }
        return total;
    }

    @Override
    public long getLockedItemCirculation(ItemID itemID)
    {
        long total = 0;
        for (Map.Entry<UUID, BankUserOld> entry : userMap.entrySet()) {
            IBank bank = entry.getValue().getBank(itemID);
            if(bank != null)
                total += bank.getLockedBalance();
        }
        return total;
    }

    public JsonElement getCirculationDataJson()
    {
        class Data
        {
            public ItemID itemID;
            public long lockedBalance = 0;
            public long freeBalance = 0;
        }
        Map<ItemID, Data> sums = new HashMap<>();
        for (Map.Entry<UUID, BankUserOld> entry : userMap.entrySet()) {
            HashMap<ItemID, IBank> bankMap = entry.getValue().getAllBanks();
            for(Map.Entry<ItemID, IBank> bankEntry : bankMap.entrySet())
            {
                ItemID itemID = bankEntry.getKey();
                IBank bank = bankEntry.getValue();
                if(bank == null)
                    continue;

                Data data = sums.get(itemID);
                if(data == null) {
                    data = new Data();
                    data.itemID = itemID;
                    sums.put(itemID, data);
                }

                data.lockedBalance += bank.getLockedBalance();
                data.freeBalance += bank.getBalance();

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
    public String getCirculationDataJsonString()
    {
        JsonElement circulationData = getCirculationDataJson();
        return JsonUtilities.toPrettyString(circulationData);
    }


    @Override
    public int getItemFractionScaleFactor(ItemID itemID)
    {
        if(itemID == null)
            return 1;
        Integer scaleFactor = itemFractionScaleFactor.get(itemID);
        if(scaleFactor == null || scaleFactor <= 0) {
            return 1;
        }
        return scaleFactor;
    }

    @Override
    public List<ItemID> getAllowedItemIDs()
    {
        var ids = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ALLOWED_ITEM_IDS.get();
        List<ItemID> allowedItemIDs = new ArrayList<>(ids.size());
        for (var el : ids) {
            allowedItemIDs.add(el.itemID);
        }
        return allowedItemIDs;
    }

    @Override
    public List<ItemID> getBlacklistedItemIDs()
    {
        return new ArrayList<>(BACKEND_INSTANCES.SERVER_SETTINGS.BANK.BLACKLIST_ITEM_IDS.get());
    }
    @Override
    public List<ItemID> getNotRemovableItemIDs()
    {
        return new ArrayList<>(BACKEND_INSTANCES.SERVER_SETTINGS.BANK.NOT_REMOVABLE_ITEM_IDS.get());
    }

    @Override
    public boolean isItemIDAllowed(ItemID itemID)
    {
        var allowed = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ALLOWED_ITEM_IDS.get();
        for(var allowedItem : allowed) {
            if(allowedItem.itemID.equals(itemID))
                return true;
        }
        return false;
    }
    @Override
    public boolean isItemIDBlacklisted(ItemID itemID)
    {
        List<ItemID> blackList = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.BLACKLIST_ITEM_IDS.get();
        return blackList.contains(itemID);
    }
    @Override
    public boolean isItemIDNotRemovable(ItemID itemID)
    {
        List<ItemID> notRemovable = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.NOT_REMOVABLE_ITEM_IDS.get();
        return notRemovable.contains(itemID);
    }
    @Override
    public boolean allowItemID(ItemID itemID, int itemFractionScaleFactor)
    {
        if(itemID == null)
            return false;
        if(isItemIDBlacklisted(itemID))
        {
            info("It is not allowed to add the itemID: " + itemID + " because it is blacklisted.");
            return false;
        }
        List<BankSystemModSettings.Bank.ItemIDAndScaleFactor> allowed = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ALLOWED_ITEM_IDS.get();
        boolean isAlreadyAllowed = false;
        for(var allowedItem : allowed) {
            if(allowedItem.itemID.equals(itemID)) {
                isAlreadyAllowed = true;
                break;
            }
        }
        if(itemFractionScaleFactor != 1 && itemFractionScaleFactor != 10 && itemFractionScaleFactor != 100)
            itemFractionScaleFactor = 1;

        if(!isAlreadyAllowed) {
            BankSystemModSettings.Bank.ItemIDAndScaleFactor itemIDAndScaleFactor = new BankSystemModSettings.Bank.ItemIDAndScaleFactor(itemID, itemFractionScaleFactor);
            allowed.add(itemIDAndScaleFactor);
            // Remove all factors
            if(MoneyItem.isMoney(itemID))
                this.itemFractionScaleFactor.put(itemID, MoneyBank.getItemFractionScaleFactorStatic());
            else {
                this.itemFractionScaleFactor.put(itemID, itemFractionScaleFactor);
            }
            BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ALLOWED_ITEM_IDS.set(allowed);
        }
        return true;
    }
    @Override
    public boolean disallowItemID(ItemID itemID)
    {
        if(itemID == null)
            return false;
        if(isItemIDNotRemovable(itemID))
        {
            info("It is not allowed to remove the itemID: " + itemID);
            return false;
        }
        List<BankSystemModSettings.Bank.ItemIDAndScaleFactor> allowed = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ALLOWED_ITEM_IDS.get();
        for(var allowedItem : allowed) {
            if(allowedItem.itemID.equals(itemID)) {
                allowed.remove(allowedItem);
                BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ALLOWED_ITEM_IDS.set(allowed);
                closeBankAccount(itemID);
                // Remove all factors
                itemFractionScaleFactor.keySet().removeIf(existingItemID -> existingItemID.equals(itemID));
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean save(CompoundTag tag) {
        ListTag bankElements = new ListTag();
        for (Map.Entry<UUID, BankUserOld> entry : userMap.entrySet()) {
            CompoundTag bankTag = new CompoundTag();
            entry.getValue().save(bankTag);
            bankElements.add(bankTag);
        }
        tag.put("users", bankElements);

        // Save item cent scale factors
        ListTag itemScaleFactors = new ListTag();
        for (Map.Entry<ItemID, Integer> entry : itemFractionScaleFactor.entrySet()) {
            CompoundTag pairTag = new CompoundTag();
            CompoundTag itemTag = new CompoundTag();
            entry.getKey().save(itemTag);
            pairTag.put("itemID", itemTag);
            pairTag.putInt("scaleFactor", entry.getValue());
            itemScaleFactors.add(pairTag);
        }
        tag.put("itemCentScaleFactors", itemScaleFactors);
        return true;
    }

    @Override
    public boolean load(CompoundTag tag) {
        boolean success = true;

        // Load item cent scale factors
        if(tag.contains("itemCentScaleFactors")) {
            ListTag itemScaleFactors = tag.getList("itemCentScaleFactors", 10);
            itemFractionScaleFactor.clear();
            for (int i = 0; i < itemScaleFactors.size(); i++) {
                CompoundTag pairTag = itemScaleFactors.getCompound(i);
                ItemID itemID = new ItemID(pairTag.getCompound("itemID"));
                int scaleFactor = pairTag.getInt("scaleFactor");
                itemFractionScaleFactor.put(itemID, scaleFactor);
            }
        }
        // Check if all allowed items have a scale factor
        List<BankSystemModSettings.Bank.ItemIDAndScaleFactor> allowedItems = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ALLOWED_ITEM_IDS.get();
        for (var el : allowedItems) {
            if(itemFractionScaleFactor.containsKey(el.itemID))
                continue;
            itemFractionScaleFactor.put(el.itemID, el.itemFractionScaleFactor);
        }


        if(tag.contains("users"))
        {
            compatibility_load_old_users(tag);
        }



        return success;
    }
*/
    private boolean compatibility_load_old_users(CompoundTag tag)
    {
        OldBankDataLoader oldBankDataLoader = new OldBankDataLoader(this);
        return oldBankDataLoader.load(tag);
    }


    private void info(String msg)
    {
        BACKEND_INSTANCES.LOGGER.info("[ServerBankManager] " + msg);
    }
    private void error(String msg)
    {
        BACKEND_INSTANCES.LOGGER.error("[ServerBankManager] " + msg);
    }
    private void error(String msg, Throwable e)
    {
        BACKEND_INSTANCES.LOGGER.error("[ServerBankManager] " + msg, e);
    }
    private void warn(String msg)
    {
        BACKEND_INSTANCES.LOGGER.warn("[ServerBankManager] " + msg);
    }
    private void debug(String msg)
    {
        BACKEND_INSTANCES.LOGGER.debug("[ServerBankManager] " + msg);
    }



}
