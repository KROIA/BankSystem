package net.kroia.banksystem.banking;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.IBank;
import net.kroia.banksystem.api.IBankAccount;
import net.kroia.banksystem.api.IServerBankManager;
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

public class ServerBankManager implements ServerSaveableChunked, IServerBankManager {
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
    //private final Map<ItemID, Integer> itemFractionScaleFactor = new HashMap<>();



    /**
     * List of all items that are allowed to be stored inside a bank account
     */
    private final Set<ItemID> allowedItemIDs = new HashSet<>();


    /**
     * Using the account number as key.
     */
    private final Map<Integer, BankAccount> bankAccounts = new HashMap<>();

    private int nextAccountNumber = 1; // Start with account number 1


    public ServerBankManager()
    {
        getBlacklistedItems();
        getAllowedItems();
        getNotRemovableItems();
        
        setupDefaultItems();
    }


    @Override
    public CompletableFuture<BankManagerData> getBankManagerData()
    {
        CompletableFuture<BankManagerData> future = new CompletableFuture<>();
        future.complete(getBankManagerData_direct());
        return future;
    }
    public BankManagerData getBankManagerData_direct()
    {
        return new BankManagerData(
                getBankManagerUserMapData_direct(),
                //getBankManagerItemFractionScaleFactorData(),
                getBankManagerBankAccountsData_direct(),
                getAllowedItems_direct(),
                getBlacklistedItems_direct(),
                getNotRemovableItems_direct()
        );
    }

    @Override
    public CompletableFuture<BankManagerData.UserMapData> getBankManagerUserMapData()
    {
        CompletableFuture<BankManagerData.UserMapData> future = new CompletableFuture<>();
        future.complete(getBankManagerUserMapData_direct());
        return future;
    }
    public BankManagerData.UserMapData getBankManagerUserMapData_direct()
    {
        Map<UUID, UserData> userDataMap = new HashMap<>();
        for (Map.Entry<UUID, User> entry : userMap.entrySet()) {
            userDataMap.put(entry.getKey(), entry.getValue().getUserData());
        }
        return new BankManagerData.UserMapData(userDataMap);
    }

    /*@Override
    public BankManagerData.ItemFractionScaleFactorData getBankManagerItemFractionScaleFactorData()
    {
        Map<ItemID, Integer> itemFractionScaleFactorMap = new HashMap<>(itemFractionScaleFactor);
        return new BankManagerData.ItemFractionScaleFactorData(itemFractionScaleFactorMap);
    }*/

    @Override
    public CompletableFuture<BankManagerData.BankAccountsData> getBankManagerBankAccountsData()
    {
        CompletableFuture<BankManagerData.BankAccountsData> future = new CompletableFuture<>();
        future.complete(getBankManagerBankAccountsData_direct());
        return future;
    }
    public BankManagerData.BankAccountsData getBankManagerBankAccountsData_direct()
    {
        Map<Integer, BankAccountData> bankAccountDataMap = new HashMap<>();
        for (Map.Entry<Integer, BankAccount> entry : bankAccounts.entrySet()) {
            bankAccountDataMap.put(entry.getKey(), entry.getValue().getAccountData());
        }
        return new BankManagerData.BankAccountsData(bankAccountDataMap);
    }


    @Override
    public CompletableFuture<List<ItemID>> getAllowedItems() {
        CompletableFuture<List<ItemID>> future = new CompletableFuture<>();
        future.complete(getAllowedItems_direct());
        return future;
    }
    public List<ItemID> getAllowedItems_direct() {
        return allowedItemIDs.stream().toList();
    }
    
    
    @Override
    public CompletableFuture<List<ItemID>> getBlacklistedItems()
    {
        CompletableFuture<List<ItemID>> future = new CompletableFuture<>();
        future.complete(getBlacklistedItems_direct());
        return future;
    }
    public List<ItemID> getBlacklistedItems_direct()
    {
        List<ItemID> ids = new ArrayList<>();
        for(ItemStack stack : BACKEND_INSTANCES.SERVER_SETTINGS.BANK.INITIAL_BLACKLIST_ITEMS)
        {
            ItemID id = ItemIDManager.registerItemStack_direct(stack);
            ids.add(id);
        }
        return ids;
    }
    
    
    @Override
    public CompletableFuture<List<ItemID>> getNotRemovableItems()
    {
        CompletableFuture<List<ItemID>> future = new CompletableFuture<>();
        future.complete(getNotRemovableItems_direct());
        return future;
    }
    public List<ItemID> getNotRemovableItems_direct()
    {
        List<ItemID> ids = new ArrayList<>();
        for(ItemStack stack : BACKEND_INSTANCES.SERVER_SETTINGS.BANK.INITIAL_BLACKLIST_ITEMS)
        {
            ItemID id = ItemIDManager.registerItemStack_direct(stack);
            ids.add(id);
        }
        return ids;
    }
    
    
    @Override
    public CompletableFuture<ItemInfoData> getItemInfoData(@NotNull ItemID itemID)
    {
        CompletableFuture<ItemInfoData> future = new CompletableFuture<>();
        future.complete(getItemInfoData_direct(itemID));
        return future;
    }
    public ItemInfoData getItemInfoData_direct(@NotNull ItemID itemID)
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
    public void addUser(@NotNull ServerPlayer player)
    {
        addUser(new User(player.getUUID(), player.getName().getString(), true));
    }
    @Override
    public void addUser(@NotNull UUID playerUUID, @NotNull String playerName)
    {
        addUser(new User(playerUUID, playerName, true));
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
    public CompletableFuture<Boolean> removeUser(UUID userUUID)
    {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        future.complete(removeUser_direct(userUUID));
        return future;
    }

    public boolean removeUser_direct(UUID userUUID)
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
    public CompletableFuture<Boolean> userExists(UUID userUUID)
    {
        CompletableFuture<Boolean>  future = new CompletableFuture<>();
        future.complete(userExists_direct(userUUID));
        return future;
    }
    public boolean userExists_direct(UUID userUUID)
    {
        return userMap.containsKey(userUUID);
    }

    @Override
    public CompletableFuture<@Nullable User> getUserByUUID(UUID userUUID) {
        CompletableFuture<User>  future = new CompletableFuture<>();
        future.complete(getUserByUUID_direct(userUUID));
        return future;
    }
    public @Nullable User getUserByUUID_direct(UUID userUUID) {
        return userMap.get(userUUID);
    }


    @Override
    public CompletableFuture<@Nullable User> getUserByName(String name)
    {
        CompletableFuture<User>  future = new CompletableFuture<>();
        future.complete(getUserByName_direct(name));
        return future;
    }
    public @Nullable User getUserByName_direct(String name)
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
    public CompletableFuture<@Nullable IBankAccount> createPersonalBankAccount(UUID user)
    {
        CompletableFuture<@Nullable IBankAccount> future = new CompletableFuture<>();
        future.complete(createPersonalBankAccount_direct(user));
        return future;
    }
    public @Nullable IBankAccount createPersonalBankAccount_direct(UUID user)
    {
        User creator = userMap.get(user);
        if(creator == null) {
            warn("No user found with UUID: " + user);
             return null;
        }
        if(userHasPersonalBankAccount_direct(user)) {
            warn("User with UUID: " + user + " already has a personal bank account.");
            return getPersonalBankAccount_direct(user); // Return existing account if it exists
        }

        IBankAccount existingAccount = getPersonalBankAccount_direct(user);
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
    public CompletableFuture<IBankAccount> createBankAccount(String accountName)
    {
        CompletableFuture<IBankAccount> future = new CompletableFuture<>();
        future.complete(createBankAccount_direct(accountName));
        return future;
    }
    public IBankAccount createBankAccount_direct(String accountName)
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
        account.setAccountIcon(ItemIDManager.registerItemStack_direct(Items.CHEST.getDefaultInstance()));
        bankAccounts.put(accountNumber, account);
        info("Created new bank account with number: " + accountNumber + " and name: " + accountName);
        return account;
    }


    @Override
    public CompletableFuture<@Nullable IBankAccount> getBankAccount(int accountNumber)
    {
        CompletableFuture<@Nullable IBankAccount> future = new CompletableFuture<>();
        future.complete(getBankAccount_direct(accountNumber));
        return future;
    }
    public @Nullable IBankAccount getBankAccount_direct(int accountNumber)
    {
        return bankAccounts.get(accountNumber);
    }


    @Override
    public CompletableFuture<List<IBankAccount>>getBankAccounts(UUID userUUID)
    {
        CompletableFuture<List<IBankAccount>> future = new CompletableFuture<>();
        future.complete(getBankAccounts_direct(userUUID));
        return future; // Return all accounts the user is a member of
    }
    public List<IBankAccount> getBankAccounts_direct(UUID userUUID)
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
    public CompletableFuture<List<IBankAccount>> getBankAccounts(ItemID itemID)
    {
        CompletableFuture<List<IBankAccount>> future = new CompletableFuture<>();
        future.complete(getBankAccounts_direct(itemID));
        return future; // Return all accounts that have a bank for the given itemID
    }
    public List<IBankAccount> getBankAccounts_direct(ItemID itemID)
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
    public CompletableFuture<@Nullable IBankAccount> getPersonalBankAccount(UUID userUUID)
    {
        CompletableFuture<@Nullable IBankAccount> future = new CompletableFuture<>();
        future.complete(getPersonalBankAccount_direct(userUUID));
        return future; // No personal bank account found for this user
    }
    public @Nullable IBankAccount getPersonalBankAccount_direct(UUID userUUID)
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
    public CompletableFuture<@Nullable IBankAccount> getPersonalBankAccount(String userName)
    {
        CompletableFuture<@Nullable IBankAccount> future = new CompletableFuture<>();
        future.complete(getPersonalBankAccount_direct(userName));
        return future;
    }
    public @Nullable IBankAccount getPersonalBankAccount_direct(String userName)
    {
        User user = getUserByName_direct(userName);
        if(user == null) {
            return null;
        }
        else
        {
            return getPersonalBankAccount_direct(user.getUUID());
        }
    }


    @Override
    public CompletableFuture<@Nullable IBankAccount> getOrCreatePersonalBankAccount(UUID userUUID)
    {
        CompletableFuture<@Nullable IBankAccount> future = new CompletableFuture<>();
        future.complete(getOrCreatePersonalBankAccount_direct(userUUID));
        return future;
    }
    public @Nullable IBankAccount getOrCreatePersonalBankAccount_direct(UUID userUUID)
    {
        IBankAccount account = getPersonalBankAccount_direct(userUUID);
        if(account != null) {
            return account;
        }
        else {
            return createPersonalBankAccount_direct(userUUID);
        }
    }


    @Override
    public CompletableFuture<@Nullable IBankAccount> getOrCreatePersonalBankAccount(@NotNull String userName)
    {
        CompletableFuture<@Nullable IBankAccount> future = new CompletableFuture<>();
        future.complete(getOrCreatePersonalBankAccount_direct(userName));
        return future;
    }
    public @Nullable IBankAccount getOrCreatePersonalBankAccount_direct(@NotNull String userName)
    {
        User user = getUserByName_direct(userName);
        if(user == null)
            return null;
        else
            return getOrCreatePersonalBankAccount_direct(user.getUUID());
    }


    @Override
    public CompletableFuture<Boolean> userHasPersonalBankAccount(UUID userUUID)
    {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        future.complete(userHasPersonalBankAccount_direct(userUUID));
        return future;
    }
    public boolean userHasPersonalBankAccount_direct(UUID userUUID)
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
    public CompletableFuture<Boolean> deleteBankAccount(int accountNumber)
    {
        CompletableFuture<Boolean>  future = new CompletableFuture<>();
        future.complete(deleteBankAccount_direct(accountNumber));
        return future;
    }
    public boolean deleteBankAccount_direct(int accountNumber)
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
    public CompletableFuture<@Nullable IBank> getPersonalBank(UUID owner, ItemID itemID)
    {
        CompletableFuture<@Nullable IBank> future = new CompletableFuture<>();
        future.complete(getPersonalBank_direct(owner, itemID));
        return future;
    }
    public @Nullable IBank getPersonalBank_direct(UUID owner, ItemID itemID)
    {
        IBankAccount account = getPersonalBankAccount_direct(owner);
        if(account == null)
            return null;
        else
            return account.getBank(itemID);
    }




    @Override
    public CompletableFuture<@Nullable IBank> getPersonalBank(String ownerName, ItemID itemID)
    {
        CompletableFuture<@Nullable IBank> future = new CompletableFuture<>();
        future.complete(getPersonalBank_direct(ownerName, itemID));
        return future;
    }
    public @Nullable IBank getPersonalBank_direct(String ownerName, ItemID itemID)
    {
        User owner = getUserByName_direct(ownerName);
        if(owner == null)
        {
            return null;
        }
        IBankAccount account = getPersonalBankAccount_direct(owner.getUUID());
        if(account == null)
            return null;
        else
            return account.getBank(itemID);
    }





    @Override
    public CompletableFuture<@Nullable IBank> getOrCreatePersonalBank(UUID owner, ItemID itemID)
    {
        CompletableFuture<@Nullable IBank> future = new CompletableFuture<>();
        future.complete(getOrCreatePersonalBank_direct(owner, itemID));
        return future;
    }
    public @Nullable IBank getOrCreatePersonalBank_direct(UUID owner, ItemID itemID)
    {
        IBankAccount account = getOrCreatePersonalBankAccount_direct(owner);
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
    public CompletableFuture<@Nullable IBank> getOrCreatePersonalBank(String ownerName, ItemID itemID)
    {
        CompletableFuture<@Nullable IBank> future = new CompletableFuture<>();
        future.complete(getOrCreatePersonalBank_direct(ownerName, itemID));
        return future;
    }
    public @Nullable IBank getOrCreatePersonalBank_direct(String ownerName, ItemID itemID)
    {
        User owner = getUserByName_direct(ownerName);
        if(owner == null)
            return null;
        else
            return getOrCreatePersonalBank_direct(owner.getUUID(), itemID);
    }











    /*@Override
    public int getItemFractionScaleFactor(ItemID itemID)
    {
        if(itemID == null)
            return 1;
        Integer scaleFactor = itemFractionScaleFactor.get(itemID);
        if(scaleFactor == null || scaleFactor <= 0) {
            return 1;
        }
        return scaleFactor;
    }*/
    @Override
    public CompletableFuture<Boolean> isItemIDAllowed(ItemID itemID)
    {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        future.complete(isItemIDAllowed_direct(itemID));
        return future;
    }
    public boolean isItemIDAllowed_direct(ItemID itemID)
    {
        return allowedItemIDs.contains(itemID);
    }




    @Override
    public CompletableFuture<Boolean> allowItemID(ItemID itemID)
    {
        CompletableFuture<Boolean>  future = new CompletableFuture<>();
        future.complete(allowItemID_direct(itemID));
        return future;
    }
    public boolean allowItemID_direct(ItemID itemID)
    {
        if(itemID == null)
            return false;
        if(isItemIDBlacklisted_direct(itemID))
        {
            warn("It is not allowed to add the itemID: " + itemID + " because it is blacklisted.");
            return false;
        }

        this.allowedItemIDs.add(itemID);
        return true;
    }





    @Override
    public CompletableFuture<Boolean> disallowItemID(ItemID itemID)
    {
        CompletableFuture<Boolean>   future = new CompletableFuture<>();
        future.complete(disallowItemID_direct(itemID));
        return future;
    }
    public boolean disallowItemID_direct(ItemID itemID)
    {
        if(itemID == null)
            return false;
        if(isItemIDNotRemovable_direct(itemID))
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
    public CompletableFuture<Boolean> isItemIDNotRemovable(ItemID itemID)
    {
        CompletableFuture<Boolean>  future = new CompletableFuture<>();
        future.complete(isItemIDNotRemovable_direct(itemID));
        return future;
    }
    public boolean isItemIDNotRemovable_direct(ItemID itemID)
    {
        List<ItemStack> notRemovable = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.INITIAL_NOT_REMOVABLE_ITEMS;
        for(ItemStack stack : notRemovable)
        {
            ItemID id = ItemIDManager.registerItemStack_direct(stack);
            if(id.equals(itemID))
            {
                return true;
            }
        }
        return false;
    }




    @Override
    public CompletableFuture<Boolean> isItemIDBlacklisted(ItemID itemID)
    {
        CompletableFuture<Boolean>  future = new CompletableFuture<>();
        future.complete(isItemIDBlacklisted_direct(itemID));
        return future;
    }
    public boolean isItemIDBlacklisted_direct(ItemID itemID)
    {
        List<ItemStack> blacklistItems = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.INITIAL_BLACKLIST_ITEMS;
        for(ItemStack stack : blacklistItems)
        {
            ItemID id = ItemIDManager.registerItemStack_direct(stack);
            if(id.equals(itemID))
                return true;
        }
        return false;
    }







    @Override
    public CompletableFuture<Double> getRealMoneyCirculation()
    {
        CompletableFuture<Double>   future = new CompletableFuture<>();
        future.complete(getRealMoneyCirculation_direct());
        return future;
    }
    public double getRealMoneyCirculation_direct()
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
    public CompletableFuture<Double> getRealLockedMoneyCirculation()
    {
        CompletableFuture<Double>    future = new CompletableFuture<>();
        future.complete(getRealLockedMoneyCirculation_direct());
        return future;
    }
    public double getRealLockedMoneyCirculation_direct()
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
    public CompletableFuture<Double> getRealItemCirculation(ItemID itemID)
    {
        CompletableFuture<Double>     future = new CompletableFuture<>();
        future.complete(getRealItemCirculation_direct(itemID));
        return future;
    }
    public double getRealItemCirculation_direct(ItemID itemID)
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
    public CompletableFuture<Double> getRealLockedItemCirculation(ItemID itemID)
    {
        CompletableFuture<Double>     future = new CompletableFuture<>();
        future.complete(getRealLockedItemCirculation_direct(itemID));
        return future;
    }
    public double getRealLockedItemCirculation_direct(ItemID itemID)
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





    public CompletableFuture<JsonElement> getCirculationDataJson()
    {
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        future.complete(getCirculationDataJson_direct());
        return future;
    }
    public JsonElement getCirculationDataJson_direct()
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
    public CompletableFuture<String> getCirculationDataJsonString()
    {
        CompletableFuture<String> future = new CompletableFuture<>();
        future.complete(getCirculationDataJsonString_direct());
        return future;
    }
    public String getCirculationDataJsonString_direct()
    {
        JsonElement circulationData = getCirculationDataJson_direct();
        return JsonUtilities.toPrettyString(circulationData);
    }



    @Override
    public void onPlayerJoin(UUID playerUUID, String playerName)
    {
        if(!userExists_direct(playerUUID)) {
            addUser(playerUUID, playerName);
            createPersonalBankAccount_direct(playerUUID);
        }
    }


    @Override
    public CompletableFuture<JsonElement> toJson()
    {
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        future.complete(toJson_direct());
        return future;
    }
    public JsonElement toJson_direct()
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
    public CompletableFuture<String> toJsonString()
    {
        CompletableFuture<String> future = new CompletableFuture<>();
        future.complete(toJsonString_direct());
        return future;
    }
    public String toJsonString_direct()
    {
        return JsonUtilities.toPrettyString(toJson_direct());
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
        for (var el : allowedItems) {
            ItemID itemID = ItemIDManager.registerItemStack_direct(el);
            allowedItemIDs.add(itemID);
        }
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
        return toJsonString_direct();
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
