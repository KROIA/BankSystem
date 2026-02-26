package net.kroia.banksystem.compat;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.api.IBank;
import net.kroia.banksystem.api.IBankAccount;
import net.kroia.banksystem.banking.BankAccount;
import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.item.custom.money.MoneyItem;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ItemUtilities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.*;

public class OldBankDataLoader {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    private static class AccountData
    {
        public static class BankData
        {
            ItemID itemID;
            long balance = 0;
            long lockedBalance = 0;

            public static BankData loadFromTag(CompoundTag tag)
            {
                BankData data = new BankData();

                // Check if "itemID" tag is a string or a compound
                if (!tag.contains("itemID", Tag.TAG_COMPOUND)) {
                    ItemStack itemStack = ItemUtilities.createItemStackFromId(tag.getString("itemID"));
                    data.itemID = ItemID.getFromItemStack(itemStack);
                }else if (tag.get("itemID") instanceof CompoundTag itemTag) {
                    data.itemID = ItemID.createFromTag(itemTag);
                } else {
                    BACKEND_INSTANCES.LOGGER.error("Invalid itemID format in bank data: "+ Objects.requireNonNull(tag.get("itemID")));
                    return null; // Invalid itemID format
                }
                data.balance = tag.getLong("balance");
                data.lockedBalance = tag.getLong("lockedBalance");

                if(!tag.contains("useCents")) {
                    assert data.itemID != null;
                    if (MoneyItem.isMoney(data.itemID)) {
                        data.balance *= 100; // Convert to cents if useCents is true
                        data.lockedBalance *= 100; // Convert to cents if useCents is true
                    }
                }
                return data;
            }
        }

        public UUID playerUUID;
        public String playerName;
        public boolean enableNotifications;
        public final List<BankData> banks = new ArrayList<>();

        public static AccountData loadFromTag(CompoundTag tag)
        {
            AccountData accountData = new AccountData();
            accountData.playerUUID = tag.getUUID("userUUID");
            accountData.playerName = tag.getString("userName");
            accountData.enableNotifications = tag.getBoolean("enableBankNotifications");

            ListTag bankList = tag.getList("bankMap", Tag.TAG_COMPOUND);
            for (int i = 0; i < bankList.size(); i++) {
                CompoundTag bankTag = bankList.getCompound(i);
                BankData bankData = BankData.loadFromTag(bankTag);
                if (bankData != null) {
                    accountData.banks.add(bankData);
                }
            }
            return accountData;
        }
    }

    private final ServerBankManager manager;
    public OldBankDataLoader(ServerBankManager serverBankManager)
    {
        this.manager = serverBankManager;
    }


    public boolean load(CompoundTag tag)
    {
        if(!tag.contains("version"))
        {
            return load_vLessThan_1_5_0(tag);
        }
        int version = tag.getInt("version");
        if(version == 1)
        {
            return load_vLessThan_1_5_0_ALPHA_3(tag);
        }
        return false;
    }

    public boolean load_vLessThan_1_5_0_ALPHA_3(CompoundTag tag)
    {
        Map<ItemID, Integer> itemFractionScaleFactor = new HashMap<>();
        Map<UUID, User> userMap = new HashMap<>();
        Map<Integer, BankAccount> bankAccounts = new HashMap<>();
        int nextAccountNumber = tag.getInt("nextAccountNumber");
        manager.load_compatibilityMode_setNextAccountNumber(nextAccountNumber);

        // Load item cent scale factors
        if(tag.contains("itemCentScaleFactors")) {
            ListTag itemScaleFactors = tag.getList("itemCentScaleFactors", 10);
            for (int i = 0; i < itemScaleFactors.size(); i++) {
                CompoundTag pairTag = itemScaleFactors.getCompound(i);
                if(!pairTag.contains("itemID") || !pairTag.contains("scaleFactor")) {
                    BACKEND_INSTANCES.LOGGER.warn("Invalid item scale factor tag: " + pairTag);
                    continue; // Skip invalid entries
                }
                ItemID itemID = ItemID.createFromTag(pairTag.getCompound("itemID"));
                int scaleFactor = pairTag.getInt("scaleFactor");
                itemFractionScaleFactor.put(itemID, scaleFactor);
            }
        }
        else {
            // Check if all allowed items have a scale factor
            List<BankSystemModSettings.Bank.ItemStackAndScaleFactor> allowedItems = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.INITIAL_ALLOWED_ITEMS;
            for (var el : allowedItems) {
                ItemID id = ItemID.getFromItemStack(el.stack);
                if(itemFractionScaleFactor.containsKey(id))
                    continue;
                itemFractionScaleFactor.put(id, el.itemFractionScaleFactor);
            }
        }
        manager.load_compatibilityMode_setItemFractionScaleFactors(itemFractionScaleFactor);



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
                    BACKEND_INSTANCES.LOGGER.warn("Failed to load user from tag: " + userTag);
                }
            }
        }
        manager.load_compatibilityMode_setUsers(userMap);

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
                    BACKEND_INSTANCES.LOGGER.warn("Failed to load bank account from tag: " + accountTag);
                }
            }
        }
        return manager.load_compatibilityMode_setBankAccounts(bankAccounts);
    }

    public boolean load_vLessThan_1_5_0(CompoundTag tag)
    {
        List<AccountData> accountDataList = new ArrayList<>();
        boolean success = true;
        ListTag bankElements = tag.getList("users", Tag.TAG_COMPOUND);
        for (int i = 0; i < bankElements.size(); i++) {
            CompoundTag bankTag = bankElements.getCompound(i);
            AccountData accountData = AccountData.loadFromTag(bankTag);
            if(accountData == null)
            {
                BACKEND_INSTANCES.LOGGER.error("Failed to load bank data for user at index " + i + ". Skipping this entry.");
                success = false;
                continue;
            }
            accountDataList.add(accountData);
        }

        // Allow the items that occured in the old bank data to be loaded into the new system
        Map<ItemID, Integer> items = new HashMap<>();
        for(AccountData accountData : accountDataList)
        {
            for(AccountData.BankData bankData : accountData.banks)
            {
                if(bankData.itemID != null)
                {
                    items.put(bankData.itemID, 1);
                }
            }
        }

        var alsoAdd = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.INITIAL_ALLOWED_ITEMS;

        for(var item : alsoAdd)
        {
            ItemID id = ItemID.getFromItemStack(item.stack);
            if(!items.containsKey(id)) {
                items.put(id, item.itemFractionScaleFactor); // Add the item with a default scale factor of 1
            }
        }


        // Load the items into the new system
        for (Map.Entry<ItemID, Integer> entry : items.entrySet()) {
            ItemID itemID = entry.getKey();
            int itemFractionScaleFactor = entry.getValue();
            manager.allowItemID(itemID, itemFractionScaleFactor);
        }


        // Load users
        for (AccountData accountData : accountDataList) {
            User user = new User(
                    accountData.playerUUID,
                    accountData.playerName,
                    accountData.enableNotifications
            );
            manager.addUser(user);
        }


        // Create personal banks for each user
        ItemID icon = ItemID.of(Items.CHEST.getDefaultInstance());
        for (AccountData accountData : accountDataList) {
            IBankAccount account = manager.createPersonalBankAccount(accountData.playerUUID);
            if (account == null) {
                BACKEND_INSTANCES.LOGGER.error("Failed to create personal bank account for user: " + accountData.playerName);
                success = false;
                continue;
            }

            account.setAccountIcon(icon);

            // Load bank data into the personal bank account
            for (AccountData.BankData bankData : accountData.banks) {
                if (bankData.itemID != null) {
                    long balance = bankData.balance;
                    long lockedBalance = bankData.lockedBalance;

                    IBank bank = account.createBank(bankData.itemID, 0);
                    if(bank != null) {
                        bank.setBalance(balance + lockedBalance);
                        if (lockedBalance > 0) {
                            bank.lockAmount(lockedBalance);
                        }
                    }
                } else {
                    BACKEND_INSTANCES.LOGGER.error("Invalid itemID for user: " + accountData.playerName);
                    success = false;
                }
            }
        }
        return success;
    }
}
