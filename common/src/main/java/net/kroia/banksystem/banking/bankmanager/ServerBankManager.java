package net.kroia.banksystem.banking.bankmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.architectury.event.events.common.TickEvent;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.api.bank.IAsyncBank;
import net.kroia.banksystem.api.bank.IServerBank;
import net.kroia.banksystem.api.bank.ISyncServerBank;
import net.kroia.banksystem.api.bankaccount.IAsyncBankAccount;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankManagerData;
import net.kroia.banksystem.banking.clientdata.ItemInfoData;
import net.kroia.banksystem.banking.clientdata.UserData;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.modutilities.JsonUtilities;
import net.kroia.modutilities.persistence.ServerSaveableChunked;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class ServerBankManager implements ServerSaveableChunked, IServerBankManager {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
        ServerBankAccount.setBackend(backend);
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
    private final Map<Integer, ServerBankAccount> bankAccounts = new HashMap<>();

    private final Set<String> trustedSlaveServers = new HashSet<>();

    private int nextAccountNumber = 1; // Start with account number 1
    private int tickCounter = 0;


    public ServerBankManager() {
        getBlacklistedItems();
        getAllowedItems();
        getNotRemovableItems();

        setupDefaultItems();


        TickEvent.SERVER_POST.register(this::update);
    }

    public void update(MinecraftServer server)
    {
        tickCounter++;
        if(tickCounter < 20)
            return; // Only process bank updates once per second to save some performance
        tickCounter = 0;

        for(ServerBankAccount account : bankAccounts.values())
            account.update(server);
    }


    @Override
    public void subscribeBankChanges(int accountNr, Consumer<BankAccountData> callback)
    {
        ServerBankAccount account = bankAccounts.get(accountNr);
        if(account != null)
        {
            account.subscribeBankChanges(callback);
        }
    }
    @Override
    public void unsubscribeBankChanges(int accountNr, Consumer<BankAccountData> callback)
    {
        ServerBankAccount account = bankAccounts.get(accountNr);
        if(account != null)
        {
            account.unsubscribeBankChanges(callback);
        }
    }

    @Override
    public BankManagerData getBankManagerData() {
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
    public BankManagerData.UserMapData getBankManagerUserMapData() {
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
    public BankManagerData.BankAccountsData getBankManagerBankAccountsData() {
        Map<Integer, BankAccountData> bankAccountDataMap = new HashMap<>();
        for (Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            bankAccountDataMap.put(entry.getKey(), entry.getValue().getAccountData());
        }
        return new BankManagerData.BankAccountsData(bankAccountDataMap);
    }

    @Override
    public CompletableFuture<BankManagerData.BankAccountsData> getBankManagerBankAccountsDataAsync() {
        return CompletableFuture.completedFuture(getBankManagerBankAccountsData());
    }

    @Override
    public boolean setBanksystemAdminMode(UUID playerUUID, boolean isAdmin) {
        User user = userMap.get(playerUUID);
        if (user == null)
            return false;
        user.setBanksystemAdmin(isAdmin);
        return true;
    }

    @Override
    public CompletableFuture<Boolean> setBanksystemAdminModeAsync(UUID playerUUID, boolean isAdmin) {
        return CompletableFuture.completedFuture(setBanksystemAdminMode(playerUUID, isAdmin));
    }

    @Override
    public boolean isBanksystemAdmin(UUID playerUUID) {
        User user = userMap.get(playerUUID);
        if (user == null)
            return false;
        return user.isBanksystemAdmin();
    }
    @Override
    public CompletableFuture<Boolean> isBanksystemAdminAsync(UUID playerUUID) {
        User user = userMap.get(playerUUID);
        if (user == null)
            return CompletableFuture.completedFuture(false);
        return CompletableFuture.completedFuture(user.isBanksystemAdmin());
    }



    @Override
    public boolean isSlaveServerTrusted(String slaveID)
    {
        return trustedSlaveServers.contains(slaveID);
    }
    @Override
    public CompletableFuture<Boolean> isSlaveServerTrustedAsync(String slaveID)
    {
        return CompletableFuture.completedFuture(isSlaveServerTrusted(slaveID));
    }


    @Override
    public Set<String> getTrustedSlaveServers()
    {
        return trustedSlaveServers;
    }



    @Override
    public void trustSlaveServer(String slaveID)
    {
        trustedSlaveServers.add(slaveID);
    }
    @Override
    public void untrustSlaveServer(String slaveID)
    {
        trustedSlaveServers.remove(slaveID);
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
    public List<ItemID> getBlacklistedItems() {
        List<ItemID> ids = ItemIDManager.registerItemStackServerSide_direct(BACKEND_INSTANCES.SERVER_SETTINGS.BANK.INITIAL_BLACKLIST_ITEMS);
        return ids;
    }

    @Override
    public CompletableFuture<List<ItemID>> getBlacklistedItemsAsync() {
        return CompletableFuture.completedFuture(getBlacklistedItems());
    }

    @Override
    public List<ItemID> getNotRemovableItems() {
        List<ItemID> ids = ItemIDManager.registerItemStackServerSide_direct(BACKEND_INSTANCES.SERVER_SETTINGS.BANK.INITIAL_BLACKLIST_ITEMS);
        return ids;
    }

    @Override
    public CompletableFuture<List<ItemID>> getNotRemovableItemsAsync() {
        return CompletableFuture.completedFuture(getNotRemovableItems());
    }

    @Override
    public ItemInfoData getItemInfoData(@NotNull ItemID itemID) {
        double totalSupply = 0;
        double totalLocked = 0;
        List<BankAccountData> bankAccounts = new java.util.ArrayList<>();

        for (Map.Entry<Integer, ServerBankAccount> entry : this.bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            ISyncServerBank bank = account.getBank(itemID);
            if (bank == null)
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
    public void addUser(@NotNull ServerPlayer player) {
        addUser(new User(player.getUUID(), player.getName().getString(), true));
    }

    @Override
    public void addUserAsync(@NotNull ServerPlayer player) {
        addUser(player);
    }

    @Override
    public void addUser(@NotNull UUID playerUUID, @NotNull String playerName) {
        addUser(new User(playerUUID, playerName, true));
    }

    @Override
    public void addUserAsync(@NotNull UUID playerUUID, @NotNull String playerName) {
        addUser(playerUUID, playerName);
    }

    @Override
    public void addUser(@NotNull User user) {
        UUID userUUID = user.getUUID();
        if (userMap.containsKey(userUUID)) {
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
    public boolean removeUser(UUID userUUID) {
        if (userMap.containsKey(userUUID)) {
            User user = userMap.remove(userUUID);
            for (Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
                ServerBankAccount account = entry.getValue();
                if (account.hasUser(userUUID)) {
                    account.removeUser(userUUID);
                    if (!account.hasAnyUser()) {
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
    public boolean userExists(UUID userUUID) {
        return userMap.containsKey(userUUID);
    }

    @Override
    public boolean updateUserName(UUID playerUUID, String playerName)
    {
        User user = userMap.get(playerUUID);
        if (user == null)
            return false;
        if(user.getName().equals(playerName))
            return false;
        User newUser = User.createWithChangedName(user,  playerName);
        userMap.put(playerUUID, newUser);
        return true;
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
    public @Nullable User getUserByName(String name) {
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
    public boolean bankAccountExists(int accountNumber) {
        return bankAccounts.containsKey(accountNumber);
    }

    @Override
    public CompletableFuture<Boolean> bankAccountExistsAsync(int accountNumber) {
        return CompletableFuture.completedFuture(bankAccounts.containsKey(accountNumber));
    }


    @Override
    public boolean bankAccountHasBank(int accountNumber, ItemID itemID) {
        ServerBankAccount account = bankAccounts.get(accountNumber);
        if (account == null)
            return false;
        return account.hasBank(itemID);
    }
    @Override
    public CompletableFuture<Boolean> bankAccountHasBankAsync(int accountNumber, ItemID itemID) {
        return CompletableFuture.completedFuture(bankAccountHasBank(accountNumber, itemID));
    }




    @Override
    public @Nullable BankAccountData getBankAccountData(int accountNumber)
    {
        ServerBankAccount account = bankAccounts.get(accountNumber);
        if (account == null)
            return null;
        return account.getAccountData();
    }
    @Override
    public CompletableFuture<@Nullable BankAccountData> getBankAccountDataAsync(int accountNumber)
    {
        return CompletableFuture.completedFuture(getBankAccountData(accountNumber));
    }






    @Override
    public @Nullable ServerBankAccount createPersonalBankAccount(UUID user) {
        return createPersonalBankAccount_internal(user);
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBankAccount> createPersonalBankAccountAsync(UUID user) {
        return CompletableFuture.completedFuture(createPersonalBankAccount_internal(user));
    }




    @Override
    public int createPersonalBankAccountGetAccountNr(UUID user) {
        @Nullable ISyncServerBankAccount account = createPersonalBankAccount(user);
        if (account == null) {
            return ServerBankAccount.INVALID_ACCOUNT_NUMBER;
        }
        return account.getAccountNumber();
    }
    @Override
    public CompletableFuture<Integer> createPersonalBankAccountGetAccountNrAsync(UUID user)
    {
        return CompletableFuture.completedFuture(createPersonalBankAccountGetAccountNr(user));
    }




    @Override
    public int createPersonalBankAccountGetAccountNr(String userName)
    {
        User user = getUserByName(userName);
        if(user == null)
            return ServerBankAccount.INVALID_ACCOUNT_NUMBER;
        else
            return createPersonalBankAccountGetAccountNr(user.getUUID());
    }
    @Override
    public CompletableFuture<Integer> createPersonalBankAccountGetAccountNrAsync(String userName)
    {
        return CompletableFuture.completedFuture(createPersonalBankAccountGetAccountNr(userName));
    }






    @Override
    public int getPersonalBankAccountNr(UUID user) {
        ServerBankAccount account = getPersonalBankAccount_internal(user);
        if(account != null)
            return account.getAccountNumber();
        return ServerBankAccount.INVALID_ACCOUNT_NUMBER;
    }
    @Override
    public CompletableFuture<Integer> getPersonalBankAccountNrAsync(UUID user)
    {
        return CompletableFuture.completedFuture(getPersonalBankAccountNr(user));
    }
    @Override
    public int getPersonalBankAccountNr(String userName)
    {
        ServerBankAccount account = getPersonalBankAccount_internal(userName);
        if(account != null)
            return account.getAccountNumber();
        return ServerBankAccount.INVALID_ACCOUNT_NUMBER;
    }
    @Override
    public CompletableFuture<Integer> getPersonalBankAccountNrAsync(String userName)
    {
        return CompletableFuture.completedFuture(getPersonalBankAccountNr(userName));
    }





    @Override
    public @Nullable ServerBankAccount createBankAccount(String accountName)
    {
        if(accountName == null || accountName.isEmpty()) {
            accountName = "Unnamed Account";
        }
        int accountNumber = generateNewAccountNumber();
        ServerBankAccount account = ServerBankAccount.create(accountNumber);
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
    public int createBankAccountGetAccountNr(String accountName)
    {
        @Nullable ISyncServerBankAccount account = createBankAccount(accountName);
        if(account == null) {
            return ServerBankAccount.INVALID_ACCOUNT_NUMBER;
        }
        return account.getAccountNumber();
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBankAccount> createBankAccountAsync(String accountName) {
        if(accountName == null || accountName.isEmpty()) {
            accountName = "Unnamed Account";
        }
        int accountNumber = generateNewAccountNumber();
        ServerBankAccount account = ServerBankAccount.create(accountNumber);
        if(account == null)
        {
            warn("Failed to create bank account with number: " + accountNumber);
            CompletableFuture.completedFuture(null);
        }
        account.setAccountName(accountName);
        account.setAccountIcon(ItemIDManager.registerItemStackServerSide_direct(Items.CHEST.getDefaultInstance()));
        bankAccounts.put(accountNumber, account);
        info("Created new bank account with number: " + accountNumber + " and name: " + accountName);
        return CompletableFuture.completedFuture(account);
    }
    @Override
    public CompletableFuture<Integer> createBankAccountGetAccountNrAsync(String accountName)
    {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        CompletableFuture<@Nullable IAsyncBankAccount>  futureAccount = createBankAccountAsync(accountName);
        futureAccount.thenAccept(account -> {
            if(account == null) {
                future.complete(ServerBankAccount.INVALID_ACCOUNT_NUMBER);
            }
            else
                future.complete(account.getAccountNumberAsync());
        });
        return future;
    }

    @Override
    public @Nullable IServerBankAccount getBankAccount(int accountNumber)
    {
        return bankAccounts.get(accountNumber);
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBankAccount> getBankAccountAsync(int accountNumber) {
        return CompletableFuture.completedFuture(bankAccounts.get(accountNumber));
    }



    @Override
    public List<IServerBankAccount> getBankAccounts(UUID userUUID)
    {
        List<IServerBankAccount> accounts = new ArrayList<>();
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            if(account.hasUser(userUUID)) {
                accounts.add(account); // Add the account if the user is a member
            }
        }
        return accounts; // Return all accounts the user is a member of
    }
    @Override
    public CompletableFuture<List<IAsyncBankAccount>> getBankAccountsAsync(UUID userUUID) {
        List<IAsyncBankAccount> accounts = new ArrayList<>();
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            if(account.hasUser(userUUID)) {
                accounts.add(account); // Add the account if the user is a member
            }
        }
        return CompletableFuture.completedFuture(accounts); // Return all accounts the user is a member of
    }





    @Override
    public @Nullable IServerBankAccount getBankAccountByName(String accountName)
    {
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            if(account.getAccountName().equals(accountName)) {
                return account;
            }
        }
        return null;
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBankAccount> getBankAccountByNameAsync(String accountName)
    {
        return CompletableFuture.completedFuture(getBankAccountByName(accountName));
    }
    @Override
    public CompletableFuture<Integer> getBankAccountNrByNameAsync(String accountName)
    {
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            if(account.getAccountName().equals(accountName)) {
                return CompletableFuture.completedFuture(entry.getKey());
            }
        }
        return CompletableFuture.completedFuture(ServerBankAccount.INVALID_ACCOUNT_NUMBER);
    }



    @Override
    public List<Integer> getBankAccountNumbers(UUID userUUID)
    {
        List<Integer> accounts = new ArrayList<>();
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            if(account.hasUser(userUUID)) {
                accounts.add(account.getAccountNumber()); // Add the account if the user is a member
            }
        }
        return accounts; // Return all accounts the user is a member of
    }
    @Override
    public CompletableFuture<List<Integer>> getBankAccountNumbersAsync(UUID userUUID)
    {
        return CompletableFuture.completedFuture(getBankAccountNumbers(userUUID));
    }





    @Override
    public List<Integer> getBankAccountNumbers(ItemID itemID)
    {
        List<Integer> accounts = new ArrayList<>();
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            if(account.hasBank(itemID)) {
                accounts.add(account.getAccountNumber()); // Add the account if the user is a member
            }
        }
        return accounts; // Return all accounts the user is a member of
    }
    @Override
    public CompletableFuture<List<Integer>> getBankAccountNumbersAsync(ItemID itemID)
    {
        return CompletableFuture.completedFuture(getBankAccountNumbers(itemID));
    }




    @Override
    public List<BankAccountData> getBankAccountsData(UUID userUUID)
    {
        List<BankAccountData> accounts = new ArrayList<>();
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            if(account.hasUser(userUUID)) {
                accounts.add(account.getAccountData()); // Add the account if the user is a member
            }
        }
        return accounts; // Return all accounts the user is a member of
    }
    @Override
    public CompletableFuture<List<BankAccountData>> getBankAccountsDataAsync(UUID userUUID)
    {
        return CompletableFuture.completedFuture(getBankAccountsData(userUUID)); // Return all accounts the user is a member of
    }




    @Override
    public List<IServerBankAccount> getBankAccounts(ItemID itemID)
    {
        List<IServerBankAccount> accounts = new ArrayList<>();
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            if(account.hasBank(itemID)) {
                accounts.add(account); // Add the account if it has the bank for the given itemID
            }
        }
        return accounts; // Return all accounts that have a bank for the given itemID
    }
    @Override
    public CompletableFuture<List<IAsyncBankAccount>> getBankAccountsAsync(ItemID itemID) {
        List<IAsyncBankAccount> accounts = new ArrayList<>();
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            if(account.hasBank(itemID)) {
                accounts.add(account); // Add the account if it has the bank for the given itemID
            }
        }
        return CompletableFuture.completedFuture(accounts); // Return all accounts that have a bank for the given itemID
    }





    @Override
    public List<BankAccountData> getBankAccountsData(ItemID itemID)
    {
        List<BankAccountData> accounts = new ArrayList<>();
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            if(account.hasBank(itemID)) {
                accounts.add(account.getAccountData()); // Add the account if it has the bank for the given itemID
            }
        }
        return accounts; // Return all accounts that have a bank for the given itemID
    }
    @Override
    public CompletableFuture<List<BankAccountData>> getBankAccountsDataAsync(ItemID itemID)
    {
        return CompletableFuture.completedFuture(getBankAccountsData(itemID)); // Return all accounts that have a bank for the given itemID
    }





    @Override
    public @Nullable IServerBankAccount getPersonalBankAccount(UUID userUUID)
    {
        return getPersonalBankAccount_internal(userUUID);
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBankAccount> getPersonalBankAccountAsync(UUID userUUID) {
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            User creator = account.getPersonalBankOwner();
            if(creator != null && creator.getUUID().equals(userUUID)) {
                return CompletableFuture.completedFuture(account); // Found the personal bank account
            }
        }
        return CompletableFuture.completedFuture(null); // No personal bank account found for this user
    }




    @Override
    public @Nullable BankAccountData getPersonalBankAccountData(UUID userUUID)
    {
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            User creator = account.getPersonalBankOwner();
            if(creator != null && creator.getUUID().equals(userUUID)) {
                return account.getAccountData(); // Found the personal bank account
            }
        }
        return null;
    }
    @Override
    public CompletableFuture<@Nullable BankAccountData> getPersonalBankAccountDataAsync(UUID userUUID)
    {
        return CompletableFuture.completedFuture(getPersonalBankAccountData(userUUID));
    }




    @Override
    public @Nullable IServerBankAccount getPersonalBankAccount(String userName)
    {
        return getPersonalBankAccount_internal(userName);
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBankAccount> getPersonalBankAccountAsync(String userName) {
        return CompletableFuture.completedFuture(getPersonalBankAccount_internal(userName));
    }





    @Override
    public @Nullable BankAccountData getPersonalBankAccountData(String userName)
    {
        ISyncServerBankAccount personalAccount = getPersonalBankAccount(userName);
        if(personalAccount == null) {
            return null;
        }
        return personalAccount.getAccountData();
    }
    @Override
    public CompletableFuture<@Nullable BankAccountData> getPersonalBankAccountDataAsync(String userName)
    {
        User user = getUserByName(userName);
        if(user == null) {
            return CompletableFuture.completedFuture(null);
        }
        else
        {
            @Nullable ISyncServerBankAccount  account = getPersonalBankAccount(user.getUUID());
            if(account == null) {
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.completedFuture(account.getAccountData());
        }
    }




    @Override
    public @Nullable IServerBankAccount getOrCreatePersonalBankAccount(UUID userUUID)
    {
        return getOrCreatePersonalBankAccount_internal(userUUID);
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBankAccount> getOrCreatePersonalBankAccountAsync(UUID userUUID) {
        CompletableFuture<@Nullable IAsyncBankAccount> account = getPersonalBankAccountAsync(userUUID);
        if(account != null) {
            return account;
        }
        else {
            return createPersonalBankAccountAsync(userUUID);
        }
    }




    @Override
    public @Nullable IServerBankAccount getOrCreatePersonalBankAccount(@NotNull String userName)
    {
        User user = getUserByName(userName);
        if(user == null)
            return null;
        else
            return getOrCreatePersonalBankAccount(user.getUUID());
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBankAccount> getOrCreatePersonalBankAccountAsync(@NotNull String userName) {
        User user = getUserByName(userName);
        if(user == null)
            return CompletableFuture.completedFuture(null);
        else
            return getOrCreatePersonalBankAccountAsync(user.getUUID());
    }
    @Override
    public boolean userHasPersonalBankAccount(UUID userUUID)
    {
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
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
            ServerBankAccount account = bankAccounts.get(accountNumber);
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
    public boolean personalBankExists(UUID owner, ItemID itemID)
    {
        ISyncServerBank bank = getPersonalBank(owner, itemID);
        return bank != null;
    }
    @Override
    public CompletableFuture<Boolean> personalBankExistsAsync(UUID owner, ItemID itemID)
    {
        return CompletableFuture.completedFuture(personalBankExists(owner, itemID));
    }



    @Override
    public boolean personalBankExists(String ownerName, ItemID itemID)
    {
        ISyncServerBank bank = getPersonalBank(ownerName, itemID);
        return bank != null;
    }
    @Override
    public CompletableFuture<Boolean> personalBankExistsAsync(String ownerName, ItemID itemID)
    {
        return CompletableFuture.completedFuture(personalBankExists(ownerName, itemID));
    }






    @Override
    public @Nullable IServerBank getPersonalBank(UUID owner, ItemID itemID)
    {
        ISyncServerBankAccount account = getPersonalBankAccount(owner);
        if(account == null)
            return null;
        else
            return account.getBank(itemID);
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBank> getPersonalBankAsync(UUID owner, ItemID itemID) {
        return CompletableFuture.completedFuture(getPersonalBank(owner, itemID));
    }







    @Override
    public @Nullable IServerBank getPersonalBank(String ownerName, ItemID itemID)
    {
        User owner = getUserByName(ownerName);
        if(owner == null)
        {
            return null;
        }
        ISyncServerBankAccount account = getPersonalBankAccount(owner.getUUID());
        if(account == null)
            return null;
        else
            return account.getBank(itemID);
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBank> getPersonalBankAsync(String ownerName, ItemID itemID) {
        return CompletableFuture.completedFuture(getPersonalBank(ownerName, itemID));
    }







    @Override
    public @Nullable IServerBank getOrCreatePersonalBank(UUID owner, ItemID itemID)
    {
        ISyncServerBankAccount account = getOrCreatePersonalBankAccount(owner);
        if(account == null) {
            return null;
        }
        IServerBank bank = account.getBank(itemID);
        if(bank != null)
            return bank;
        else
            return account.createBank(itemID, ServerBankAccount.INVALID_ACCOUNT_NUMBER);
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBank> getOrCreatePersonalBankAsync(UUID owner, ItemID itemID) {
        return CompletableFuture.completedFuture(getOrCreatePersonalBank(owner, itemID));
    }










    @Override
    public @Nullable IServerBank getOrCreatePersonalBank(String ownerName, ItemID itemID)
    {
        User owner = getUserByName(ownerName);
        if(owner == null)
            return null;
        else
            return getOrCreatePersonalBank(owner.getUUID(), itemID);
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBank> getOrCreatePersonalBankAsync(String ownerName, ItemID itemID) {
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

        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
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
    public int getItemFractionScaleFactor()
    {
        return BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
    }
    @Override
    public CompletableFuture<Integer> getItemFractionScaleFactorAsync()
    {
        return CompletableFuture.completedFuture(getItemFractionScaleFactor());
    }




    @Override
    public double getRealMoneyCirculation()
    {
        double total = 0;
        ItemID moneyItemID = MoneyItem.getItemID();
        for (Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            ISyncServerBank moneyBank = account.getBank(moneyItemID);
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
        for (Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            ISyncServerBank moneyBank = account.getBank(moneyItemID);
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
        for (Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            ISyncServerBank bank = account.getBank(itemID);
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
        for (Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            ISyncServerBank bank = account.getBank(itemID);
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
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet())
        {
            ServerBankAccount account = entry.getValue();
            for(Map.Entry<ItemID, IServerBank> bankEntry : account.getAllBanks().entrySet())
            {
                ItemID itemID = bankEntry.getKey();
                ISyncServerBank bank = bankEntry.getValue();
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
    public long convertToRawAmount(double realAmount)
    {
        return BankManager.convertToRawAmountStatic(realAmount);
    }
    @Override
    public double convertToRealAmount(long rawAmount)
    {
        return BankManager.convertToRealAmountStatic(rawAmount);
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
        for (ServerBankAccount account : bankAccounts.values()) {
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
        }
        updateUserName(playerUUID, playerName);
        ServerBankAccount account = getPersonalBankAccount_internal(playerUUID);
        if(account == null)
        {
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
        for (Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            CompoundTag accountTag = new CompoundTag();
            entry.getValue().save(accountTag);
            accountsList.add(accountTag);
        }
        listTags.put("bankAccounts", accountsList);


        ListTag trustedSlaves = new ListTag();
        for(String slaveID : trustedSlaveServers)
        {
            trustedSlaves.add(StringTag.valueOf(slaveID));
        }
        listTags.put("trustedSlaves", trustedSlaves);



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
                ServerBankAccount account = ServerBankAccount.createFromTag(accountTag);
                if(account != null) {
                    bankAccounts.put(account.getAccountNumber(), account);
                } else {
                    warn("Failed to load bank account from tag: " + accountTag);
                }
            }
        }

        if(listTags.containsKey("trustedSlaves")) {
            ListTag trustedSlaves = listTags.get("trustedSlaves");
            trustedSlaveServers.clear();
            for (net.minecraft.nbt.Tag trustedSlave : trustedSlaves) {
                String slaveID = trustedSlave.getAsString();
                trustedSlaveServers.add(slaveID);
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
    public boolean load_compatibilityMode_setBankAccounts(Map<Integer, ServerBankAccount> bankAccounts)
    {
        this.bankAccounts.clear();
        this.bankAccounts.putAll(bankAccounts);
        return true;
    }




    public @Nullable ServerBankAccount getPersonalBankAccount_internal(UUID userUUID)
    {
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            User creator = account.getPersonalBankOwner();
            if(creator != null && creator.getUUID().equals(userUUID)) {
                return account; // Found the personal bank account
            }
        }
        return null; // No personal bank account found for this user
    }
    public @Nullable ServerBankAccount getPersonalBankAccount_internal(String userName)
    {
        for(Map.Entry<Integer, ServerBankAccount> entry : bankAccounts.entrySet()) {
            ServerBankAccount account = entry.getValue();
            User creator = account.getPersonalBankOwner();
            if(creator != null && creator.getName().equals(userName)) {
                return account; // Found the personal bank account
            }
        }
        return null; // No personal bank account found for this user
    }
    public @Nullable ServerBankAccount getOrCreatePersonalBankAccount_internal(UUID userUUID)
    {
        ServerBankAccount account = getPersonalBankAccount_internal(userUUID);
        if(account != null) {
            return account;
        }
        else {
            return createPersonalBankAccount_internal(userUUID);
        }
    }

    public @Nullable ServerBankAccount createPersonalBankAccount_internal(UUID user)
    {
        User creator = userMap.get(user);
        if(creator == null) {
            warn("No user found with UUID: " + user);
            return null;
        }

        ServerBankAccount existingAccount = getPersonalBankAccount_internal(user);
        if(existingAccount != null) {
            //warn("User with UUID: " + user + " already has a personal bank account with number: " + existingAccount.getAccountNumber());
            return existingAccount;
        }

        int accountNumber = generateNewAccountNumber();
        long startBalance = BACKEND_INSTANCES.SERVER_SETTINGS.PLAYER.STARTING_BALANCE.get();
        ServerBankAccount account = ServerBankAccount.createPersonal(accountNumber, creator, startBalance);
        if(account == null) {
            warn("Failed to create personal bank account for user with UUID: " + user);
            return null;
        }
        bankAccounts.put(accountNumber, account);
        return account;
    }


    @Override
    public String toString() {
        return toJsonString();
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
