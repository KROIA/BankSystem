package net.kroia.banksystem.banking.bankaccount;

import java.util.Objects;
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
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.modutilities.JsonUtilities;
import net.kroia.modutilities.persistence.ServerSaveable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

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
    private boolean hasChanges = false;

    private final List<Consumer<BankAccountData>> changeListeners = new ArrayList<>();

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



    public void update(MinecraftServer server)
    {
        if(hasChanges()) {
            if(!changeListeners.isEmpty()) {
                BankAccountData changes = getAccountData();
                for (Consumer<BankAccountData> currentListener : changeListeners) {
                    currentListener.accept(changes);
                }
            }
            clearChangeFlag();
        }
    }





    @Override
    public boolean hasChanges()
    {
        if(hasChanges)
            return true;
        for(Map.Entry<ItemID, ServerBank> entry : banks.entrySet()) {
            if(entry.getValue().hasChanges()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void clearChangeFlag()
    {
        hasChanges = false;
        for(Map.Entry<ItemID, ServerBank> entry : banks.entrySet()) {
            entry.getValue().clearChangeFlag();
        }
    }

    /**
     * Snapshots this account's client-visible state for streaming to consumers (ATM screen,
     * BankTerminalScreen, block-entity displays, ...). Emits alias-resolved ItemIDs only
     * (Task #13 Fix B): the raw {@code banks} map may hold entries keyed by an aliased ID
     * under a legacy corrupt-alias state, and streaming those raw keys hides balances from
     * consumers that look them up by canonical ID (concrete symptom: ATM shows "no money"
     * while the bank terminal shows the balance). Duplicate resolutions (two source keys
     * collapsing to the same canonical) sum both free and locked balances — never drop
     * funds under a corrupt state.
     *
     * @return snapshot of account data with alias-resolved bank keys and deduplicated balances
     */
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

        // Task #13 Fix B: emit alias-resolved keys, summing balances if two source shorts
        // collapse to the same canonical (rare, only observable under Bug-A corrupt state).
        // The emitted BankData record itself carries the resolved (canonical) ItemID so its
        // own itemID field matches the map key.
        for(Map.Entry<ItemID, ServerBank> entry : this.banks.entrySet()) {
            ItemID canonical = ItemIDManager.resolveAlias(entry.getKey());
            ServerBank bank = entry.getValue();
            long balance = bank.getBalance();
            long lockedBalance = bank.getLockedBalance();
            BankData existing = bankData.get(canonical);
            if (existing != null) {
                // Sum both source contributions — clamp against overflow the same way
                // ServerBank#absorb does; a Long.MAX_VALUE clamp is the least surprising
                // fallback for the pathological case of two near-max source balances.
                balance = addClampedForAggregation(existing.balance(), balance);
                lockedBalance = addClampedForAggregation(existing.lockedBalance(), lockedBalance);
            }
            bankData.put(canonical, new BankData(canonical, balance, lockedBalance));
        }

        return new BankAccountData(accountNumber, accountName, accountIcon, personalBankOwnerData, users, bankData);
    }

    /**
     * Overflow-safe {@code long} addition used by {@link #getAccountData()} when summing
     * two alias-keyed balances into the same canonical entry.
     * Matches {@code ServerBank#addClamped}'s clamp-at-{@link Long#MAX_VALUE} semantics —
     * consistent with how bank absorption already handles the same theoretical overflow.
     *
     * @param a first addend (non-negative in practice)
     * @param b second addend (non-negative in practice)
     * @return {@code a + b}, or {@link Long#MAX_VALUE} if the sum would overflow
     */
    private static long addClampedForAggregation(long a, long b) {
        long sum = a + b;
        // If both are non-negative but the sum went negative → overflow.
        if (((a ^ sum) & (b ^ sum)) < 0)
            return Long.MAX_VALUE;
        return sum;
    }
    @Override
    public CompletableFuture<BankAccountData> getAccountDataAsync()
    {
        return CompletableFuture.completedFuture(getAccountData());
    }


    /*private BankAccountData getChangedAccountData()
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
            ServerBank bank = entry.getValue();
            if(bank.hasChanges()) {
                ItemID itemID = entry.getKey();
                bankData.put(itemID, bank.getMinimalData()); // Convert ServerBank to BankData
            }
        }

        return new BankAccountData(accountNumber, accountName, accountIcon, personalBankOwnerData, users, bankData);
    }*/

    @Override
    public void subscribeBankChanges(Consumer<BankAccountData> callback)
    {
        if(callback == null)
            return;
        for(Consumer<BankAccountData> currentListener : changeListeners)
        {
            if(currentListener == callback)
                return; // Already subscribed
        }
        changeListeners.add(callback);
    }
    @Override
    public void unsubscribeBankChanges(Consumer<BankAccountData> callback)
    {
        changeListeners.remove(callback);
    }


    /**
     * Only contains the bank data for the given item ID.
     * @param itemID The item ID of the bank to get data for.
     * @return BankAccountData containing only the bank data for the given item ID, or null if the item ID is invalid.
     */
    @Override
    public @Nullable BankAccountData getAccountData(ItemID itemID)
    {
        if (itemID == null || !itemID.isValid()) {
            return null; // Invalid item ID
        }
        // Alias safety net: merged (aliased) ItemIDs resolve to their canonical entry,
        // so callers still holding an old ID reach the consolidated bank. O(1) map lookup.
        itemID = ItemIDManager.resolveAlias(itemID);
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
        if (itemID == null || !itemID.isValid()) {
            return null; // Invalid item ID
        }
        itemID = ItemIDManager.resolveAlias(itemID); // alias safety net (see getAccountData)
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
        if (accountName == null) {
            accountName = "";
        }
        hasChanges |= !this.accountName.equals(accountName);
        this.accountName = accountName;
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
        hasChanges |= !java.util.Objects.equals(this.accountIcon, accountIcon);
        this.accountIcon = accountIcon;
    }
    @Override
    public void setAccountIconAsync(@Nullable ItemID accountIcon) {
        setAccountIcon(accountIcon);
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
        hasChanges = true;
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
            existingUser.setPermission(permission);
            hasChanges = true;
            return;
        }
        users.put(user.getUUID(), new BankUser(user, permission)); // Add new user
        hasChanges = true;
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
                hasChanges = true;
            }
        }
    }
    @Override
    public void setUsersAsync(Map<User, Integer> userList)
    {
        setUsers(userList);
    }


    /**
     * Result of {@link #enforceManageInvariant(Map, boolean)}.
     */
    public enum ManageInvariantOutcome {
        /** The proposed user set already satisfies the invariant; no mutation performed. */
        OK,
        /** No user held MANAGE; one retained user was auto-granted MANAGE (map mutated). */
        PROMOTED,
        /** The proposed set is empty; applying it would orphan the account — caller must refuse. */
        REFUSED_ORPHAN
    }

    /**
     * Enforces the "at least one MANAGE holder" invariant for a proposed complete user set
     * (full-REPLACE semantics), for NON-personal accounts only.
     * <p>
     * Personal accounts ({@code hasOwner == true}) are always {@link ManageInvariantOutcome#OK}:
     * the personal bank owner implicitly holds MANAGE and can never be removed, so no promotion
     * is needed regardless of the proposed set.
     * <p>
     * For non-personal accounts:
     * <ul>
     *   <li>If {@code proposed} is empty → {@link ManageInvariantOutcome#REFUSED_ORPHAN}
     *       (the caller must skip the user-set mutation so the account is not orphaned).</li>
     *   <li>If any entry already holds MANAGE → {@link ManageInvariantOutcome#OK}.</li>
     *   <li>Otherwise → {@link ManageInvariantOutcome#PROMOTED}: the MANAGE bit is added to the
     *       deterministically-chosen retained user's mask (the map is mutated in place). The pick
     *       is the retained user with the MOST set permission bits ({@link Integer#bitCount}),
     *       tie-broken by lowest UUID.</li>
     * </ul>
     *
     * @param proposed the complete proposed users→permission-mask map; mutated only on PROMOTED
     * @param hasOwner  true if the account has a personal bank owner
     * @return the outcome of the invariant check
     */
    public static ManageInvariantOutcome enforceManageInvariant(Map<User, Integer> proposed, boolean hasOwner) {
        if (hasOwner) {
            return ManageInvariantOutcome.OK; // Owner implicitly holds MANAGE and cannot be removed.
        }
        if (proposed == null || proposed.isEmpty()) {
            return ManageInvariantOutcome.REFUSED_ORPHAN;
        }
        for (Integer mask : proposed.values()) {
            if (mask != null && BankPermission.hasPermission(mask, BankPermission.MANAGE.getValue())) {
                return ManageInvariantOutcome.OK;
            }
        }
        // No retained user holds MANAGE — hand off to a deterministically chosen user.
        Map.Entry<User, Integer> pick = null;
        for (Map.Entry<User, Integer> entry : proposed.entrySet()) {
            if (pick == null) {
                pick = entry;
                continue;
            }
            int entryBits = Integer.bitCount(entry.getValue() == null ? 0 : entry.getValue());
            int pickBits = Integer.bitCount(pick.getValue() == null ? 0 : pick.getValue());
            if (entryBits > pickBits) {
                pick = entry;
            } else if (entryBits == pickBits
                    && entry.getKey().getUUID().compareTo(pick.getKey().getUUID()) < 0) {
                pick = entry;
            }
        }
        int promotedMask = (pick.getValue() == null ? 0 : pick.getValue()) | BankPermission.MANAGE.getValue();
        proposed.put(pick.getKey(), promotedMask); // Key already present → value update, not structural.
        return ManageInvariantOutcome.PROMOTED;
    }




    @Override
    public void removeUser(UUID userUUID) {
        if (userUUID == null) {
            return;
        }
        // Prevent removing the personal bank owner from their own account
        if (personalBankOwner != null && personalBankOwner.getUUID().equals(userUUID)) {
            return;
        }
        hasChanges |= users.remove(userUUID) != null; // Remove user by UUID
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
        return CompletableFuture.completedFuture(hasUser(userUUID));
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
        if (itemID == null || !itemID.isValid()) {
            return null; // Invalid item ID
        }
        itemID = ItemIDManager.resolveAlias(itemID); // alias safety net (see getAccountData)
        if (banks.containsKey(itemID)) {
            return banks.get(itemID); // Return existing bank if it already exists
        }
        ServerBank bank = ServerBank.create(itemID, startBalance); // Create a new bank with 0 balance
        if (bank != null) {
            banks.put(itemID, bank); // Add new bank to the account
            hasChanges = true;
            return bank;
        }
        return null; // Failed to create bank
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBank> createBankAsync(ItemID itemID, long startBalance)
    {
        if (itemID == null || !itemID.isValid()) {
            return CompletableFuture.completedFuture(null); // Invalid item ID
        }
        itemID = ItemIDManager.resolveAlias(itemID); // alias safety net (see getAccountData)
        if (banks.containsKey(itemID)) {
            return CompletableFuture.completedFuture(banks.get(itemID)); // Return existing bank if it already exists
        }
        ServerBank bank = ServerBank.create(itemID, startBalance); // Create a new bank with 0 balance
        if (bank != null) {
            banks.put(itemID, bank); // Add new bank to the account
            hasChanges = true;
            return CompletableFuture.completedFuture(bank);
        }
        return CompletableFuture.completedFuture(null); // Failed to create bank
    }





    @Override
    public void removeBank(ItemID itemID) {
        if (itemID == null || !itemID.isValid()) {
            return; // Invalid item ID
        }
        itemID = ItemIDManager.resolveAlias(itemID); // alias safety net (see getAccountData)
        hasChanges |= banks.remove(itemID) != null; // Remove bank by item ID
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
            if (bank.getTotalBalance() <= 0 && !BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().isItemIDNotRemovable(entry.getKey())) {
                emptyBanks.add(entry.getKey());
            }
        }
        for (ItemID itemID : emptyBanks) {
            hasChanges |= banks.remove(itemID) != null; // Remove empty banks from the account
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
        hasChanges |= !banks.isEmpty();
        banks.clear(); // Clear all banks in the account
    }
    @Override
    public void removeAllBanksAsync() {
        removeAllBanks();
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
        // Alias safety net: merged IDs resolve to their canonical bank (see getAccountData).
        return itemID != null && itemID.isValid() && banks.containsKey(ItemIDManager.resolveAlias(itemID));
    }
    @Override
    public CompletableFuture<Boolean> hasBankAsync(ItemID itemID) {
        return CompletableFuture.completedFuture(hasBank(itemID));
    }




    @Override
    public @Nullable ServerBank getBank(ItemID itemID) {
        if (itemID == null || !itemID.isValid()) {
            return null; // Invalid item ID
        }
        // Alias safety net: merged IDs resolve to their canonical bank (see getAccountData).
        return banks.get(ItemIDManager.resolveAlias(itemID)); // Get bank by item ID
    }
    @Override
    public CompletableFuture<@Nullable IAsyncBank> getBankAsync(ItemID itemID) {
        if (itemID == null) {
            return CompletableFuture.completedFuture(null); // Invalid item ID
        }
        // Alias safety net: merged IDs resolve to their canonical bank (see getAccountData).
        return CompletableFuture.completedFuture(banks.get(ItemIDManager.resolveAlias(itemID))); // Get bank by item ID
    }




    @Override
    public @Nullable ServerBank getOrCreateBank(ItemID itemID)
    {
        if (itemID == null || !itemID.isValid()) {
            return null; // Invalid item ID
        }
        itemID = ItemIDManager.resolveAlias(itemID); // alias safety net (see getAccountData)
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
        itemID = ItemIDManager.resolveAlias(itemID); // alias safety net (see getAccountData)
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
            // Canonicalize a possibly aliased icon ID (cosmetic — it would alias-resolve
            // for rendering anyway, but persisted data should reference the canonical ID).
            this.accountIcon = ItemIDManager.resolveAlias(ItemID.createFromTag(iconTag)); // Load account icon if set
        } else {
            this.accountIcon = null; // No account icon set
        }



        ListTag usersTag = tag.getList("users", Tag.TAG_COMPOUND);
        for (int i = 0; i < usersTag.size(); i++) {
            CompoundTag userTag = usersTag.getCompound(i);
            UUID userUUID = userTag.getUUID("userUUID");
            int permissions = userTag.getInt("permissions");
            User user = bankManager.getUserByUUID(userUUID);
            if (user != null) {
                users.put(user.getUUID(), new BankUser(user, permissions));
            }
        }

        ListTag banksTag = tag.getList("banks", Tag.TAG_COMPOUND);
        for (int i = 0; i < banksTag.size(); i++) {
            CompoundTag bankTag = banksTag.getCompound(i);
            ServerBank bank = ServerBank.createFromTag(bankTag);
            if (bank != null) {
                // ServerBank.load() canonicalizes aliased ItemIDs, so two saved banks may
                // resolve to the same key after an ItemID merge (confirmed volatile-
                // component merge, possibly from an earlier session before consolidation
                // existed). Merge their balances instead of silently dropping one — this
                // heals fragmented holdings in pre-fix worlds on the next load.
                ServerBank existing = banks.get(bank.getItemID());
                if (existing != null) {
                    existing.absorb(bank);
                } else {
                    banks.put(bank.getItemID(), bank);
                }
            }
        }
        return true;
    }

    /**
     * Consolidates this account's banks after an ItemID alias merge: every bank keyed by
     * one of the given alias IDs is merged into the bank of its canonical ID — created by
     * re-keying the alias bank in place when no canonical bank exists yet (deliberately
     * NOT via {@link ServerBank#create}, whose allowed-item check must never be able to
     * drop an existing balance). Both the free and the locked balance are preserved: the
     * sum over each merge group is identical before and after (verified, mismatches are
     * logged as errors).
     * <p>
     * Called by {@code ServerBankManager.consolidateMergedItemIDs} on the master server
     * after {@code ItemIDManager.renormalizeAndMerge()} created new aliases.
     *
     * @param aliasToCanonical alias → canonical pairs from the ItemID merge pass
     * @return the number of banks that were merged away or re-keyed
     */
    public int consolidateMergedItemIDs(Map<ItemID, ItemID> aliasToCanonical) {
        int changed = 0;
        for (Map.Entry<ItemID, ItemID> entry : aliasToCanonical.entrySet()) {
            ServerBank aliasBank = banks.remove(entry.getKey());
            if (aliasBank == null)
                continue;
            ItemID canonical = entry.getValue();
            ServerBank canonicalBank = banks.get(canonical);
            if (canonicalBank == null) {
                // No bank under the canonical ID yet: re-key the alias bank in place.
                aliasBank.rekeyForAliasMerge_internal(canonical);
                banks.put(canonical, aliasBank);
            } else {
                // Invariant check: total over the pair must be unchanged by the merge.
                long expectedTotal = canonicalBank.getTotalBalance() + aliasBank.getTotalBalance();
                canonicalBank.absorb(aliasBank);
                if (canonicalBank.getTotalBalance() != expectedTotal) {
                    BACKEND_INSTANCES.LOGGER.error("[ServerBankAccount] Balance mismatch while consolidating "
                            + entry.getKey() + " -> " + canonical + " on account " + accountNumber
                            + " (overflow clamp): expected total " + expectedTotal
                            + ", got " + canonicalBank.getTotalBalance());
                }
            }
            changed++;
            hasChanges = true;
        }
        // Cosmetic: an aliased account icon keeps rendering through alias resolution, but
        // rewrite it so newly persisted data references the canonical ID.
        if (accountIcon != null) {
            ItemID canonicalIcon = aliasToCanonical.get(accountIcon);
            if (canonicalIcon != null) {
                accountIcon = canonicalIcon;
                hasChanges = true;
            }
        }
        return changed;
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
