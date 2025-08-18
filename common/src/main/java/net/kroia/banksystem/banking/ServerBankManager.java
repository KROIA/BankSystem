package net.kroia.banksystem.banking;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.api.IBank;
import net.kroia.banksystem.api.IBankAccount;
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


    @Override
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
    public BankManagerData.ItemFractionScaleFactorData getBankManagerItemFractionScaleFactorData()
    {
        Map<ItemID, Integer> itemFractionScaleFactorMap = new HashMap<>(itemFractionScaleFactor);
        return new BankManagerData.ItemFractionScaleFactorData(itemFractionScaleFactorMap);
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
    public List<ItemID> getAllowedItems()
    {
        return BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ALLOWED_ITEM_IDS.get().stream()
                .map(item -> item.itemID)
                .toList();
    }
    @Override
    public List<ItemID> getBlacklistedItems()
    {
        return BACKEND_INSTANCES.SERVER_SETTINGS.BANK.BLACKLIST_ITEM_IDS.get();
    }
    @Override
    public List<ItemID> getNotRemovableItems()
    {
        return BACKEND_INSTANCES.SERVER_SETTINGS.BANK.NOT_REMOVABLE_ITEM_IDS.get();
    }
    @Override
    public ItemInfoData getItemInfoData(@NotNull ItemID itemID)
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




    @Override
    public void addUser(@NotNull ServerPlayer player)
    {
        addUser(new User(player.getUUID(), player.getName().getString(), true));
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
        }
        return false;
    }
    @Override
    public boolean userExists(UUID userUUID)
    {
        return userMap.containsKey(userUUID);
    }
    @Override
    public @Nullable User getUserByUUID(UUID userUUID) {
        return userMap.get(userUUID);
    }
    @Override
    public @Nullable User getUserByName(String name)
    {
        String lowerCaseName = name.toLowerCase();
        for (User user : userMap.values()) {
            if(user.getName().toLowerCase().equals(lowerCaseName)) {
                return user;
            }
        }
        return null;
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
        float startBalance = BACKEND_INSTANCES.SERVER_SETTINGS.PLAYER.STARTING_BALANCE.get();
        BankAccount account = BankAccount.createPersonal(accountNumber, creator, startBalance);
        if(account == null) {
            warn("Failed to create personal bank account for user with UUID: " + user);
            return null;
        }
        bankAccounts.put(accountNumber, account);
        return account;
    }
    @Override
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
    @Override
    public @Nullable IBankAccount getBankAccount(int accountNumber)
    {
        return bankAccounts.get(accountNumber);
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
    public @Nullable IBankAccount getPersonalBankAccount(String userName)
    {
        User user = getUserByName(userName);
        if(user == null)
            return null; // User not found
        return getPersonalBankAccount(user.getUUID());
    }
    @Override
    public @Nullable IBankAccount getOrCreatePersonalBankAccount(UUID userUUID)
    {
        IBankAccount account = getPersonalBankAccount(userUUID);
        if(account != null)
            return account;
        account = createPersonalBankAccount(userUUID);
        return account;
    }
    @Override
    public @Nullable IBankAccount getOrCreatePersonalBankAccount(@NotNull String userName)
    {
        User user = getUserByName(userName);
        if(user == null)
            return null;
        return getOrCreatePersonalBankAccount(user.getUUID());
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
    public @Nullable IBank getPersonalBank(UUID owner, ItemID itemID)
    {
        IBankAccount account = getPersonalBankAccount(owner);
        if(account == null)
            return null;
        return account.getBank(itemID);
    }
    @Override
    public @Nullable IBank getPersonalBank(String ownerName, ItemID itemID)
    {
        User owner = getUserByName(ownerName);
        if(owner == null)
            return null; // User not found
        IBankAccount account = getPersonalBankAccount(owner.getUUID());
        if(account == null)
            return null;
        return account.getBank(itemID);
    }
    @Override
    public @Nullable IBank getOrCreatePersonalBank(UUID owner, ItemID itemID)
    {
        IBankAccount account = getOrCreatePersonalBankAccount(owner);
        if(account == null)
            return null;
        IBank bank = account.getBank(itemID);
        if(bank != null)
            return bank;
        return account.createBank(itemID, 0);
    }
    @Override
    public @Nullable IBank getOrCreatePersonalBank(String ownerName, ItemID itemID)
    {
        User owner = getUserByName(ownerName);
        if(owner == null)
            return null; // User not found
        return getOrCreatePersonalBank(owner.getUUID(), itemID);
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
    public boolean isItemIDAllowed(ItemID itemID)
    {
        return itemFractionScaleFactor.containsKey(itemID) && itemFractionScaleFactor.get(itemID) > 0;
    }
    @Override
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
    @Override
    public boolean isItemIDNotRemovable(ItemID itemID)
    {
        List<ItemID> notRemovable = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.NOT_REMOVABLE_ITEM_IDS.get();
        return notRemovable.contains(itemID);
    }
    @Override
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
