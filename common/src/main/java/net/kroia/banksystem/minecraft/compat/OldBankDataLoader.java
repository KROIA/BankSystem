package net.kroia.banksystem.minecraft.compat;

import net.kroia.modutilities.NbtCompat;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.api.bank.ISyncServerBank;
import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import net.kroia.banksystem.banking.bankmanager.ServerBankManager;
import net.kroia.banksystem.banking.User;
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
                if (!NbtCompat.contains(tag, "itemID", Tag.TAG_COMPOUND)) {
                    ItemStack itemStack = ItemUtilities.createItemStackFromId(NbtCompat.getString(tag, "itemID"));
                    data.itemID = ItemID.getFromItemStack(itemStack);
                }else if (tag.get("itemID") instanceof CompoundTag itemTag) {
                    data.itemID = ItemID.createFromTag(itemTag);
                } else {
                    BACKEND_INSTANCES.LOGGER.error("Invalid itemID format in bank data: "+ Objects.requireNonNull(tag.get("itemID")));
                    return null; // Invalid itemID format
                }

                // Convert to cent system
                data.balance = NbtCompat.getLong(tag, "balance") * BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
                data.lockedBalance = NbtCompat.getLong(tag, "lockedBalance") * BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
                return data;
            }
        }

        public UUID playerUUID;
        public String playerName;
        public final List<BankData> banks = new ArrayList<>();

        public static AccountData loadFromTag(CompoundTag tag)
        {
            AccountData accountData = new AccountData();
            accountData.playerUUID = NbtCompat.getUUID(tag, "userUUID");
            accountData.playerName = NbtCompat.getString(tag, "userName");
            ListTag bankList = NbtCompat.getList(tag, "bankMap", Tag.TAG_COMPOUND);
            for (int i = 0; i < bankList.size(); i++) {
                CompoundTag bankTag = NbtCompat.getCompound(bankList, i);
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
        int version = NbtCompat.getInt(tag, "version");
        if(version == 1)
        {
            return load_vLessThan_1_5_0_ALPHA_3(tag);
        }
        return false;
    }

    public boolean load_vLessThan_1_5_0_ALPHA_3(CompoundTag tag)
    {
        Map<UUID, User> userMap = new HashMap<>();
        Map<Integer, ServerBankAccount> bankAccounts = new HashMap<>();
        int nextAccountNumber = NbtCompat.getInt(tag, "nextAccountNumber");
        manager.load_compatibilityMode_setNextAccountNumber(nextAccountNumber);

        Set<ItemID> usedItems = new HashSet<>();
        // Load item cent scale factors
        if(tag.contains("itemCentScaleFactors")) {
            ListTag itemScaleFactors = NbtCompat.getList(tag, "itemCentScaleFactors", 10);
            for (int i = 0; i < itemScaleFactors.size(); i++) {
                CompoundTag pairTag = NbtCompat.getCompound(itemScaleFactors, i);
                if(!pairTag.contains("itemID") || !pairTag.contains("scaleFactor")) {
                    BACKEND_INSTANCES.LOGGER.warn("Invalid item scale factor tag: " + pairTag);
                    continue; // Skip invalid entries
                }
                ItemID itemID = ItemID.createFromTag(NbtCompat.getCompound(pairTag, "itemID"));
               // int scaleFactor = pairTag.getInt("scaleFactor");
                usedItems.add(itemID);
            }
        }
        else {
            // Check if all allowed items have a scale factor
            List<ItemStack> allowedItems = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.INITIAL_ALLOWED_ITEMS;
            for (var el : allowedItems) {
                ItemID id = ItemID.getFromItemStack(el);
                if(usedItems.contains(id))
                    continue;
                usedItems.add(id);
            }
        }
        //manager.load_compatibilityMode_setItemFractionScaleFactors(itemFractionScaleFactor);



        // Load users
        if(tag.contains("users")) {
            ListTag userList = NbtCompat.getList(tag, "users", 10);
            userMap.clear();
            for (int i = 0; i < userList.size(); i++) {
                CompoundTag userTag = NbtCompat.getCompound(userList, i);
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
            ListTag accountsList = NbtCompat.getList(tag, "bankAccounts", 10);
            bankAccounts.clear();
            for (int i = 0; i < accountsList.size(); i++) {
                CompoundTag accountTag = NbtCompat.getCompound(accountsList, i);
                ServerBankAccount account = ServerBankAccount.createFromTag(accountTag);
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
        ListTag bankElements = NbtCompat.getList(tag, "users", Tag.TAG_COMPOUND);
        for (int i = 0; i < bankElements.size(); i++) {
            CompoundTag bankTag = NbtCompat.getCompound(bankElements, i);
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
        Set<ItemID> items = new HashSet<>();
        for(AccountData accountData : accountDataList)
        {
            for(AccountData.BankData bankData : accountData.banks)
            {
                if(bankData.itemID != null)
                {
                    items.add(bankData.itemID);
                }
            }
        }

        var alsoAdd = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.INITIAL_ALLOWED_ITEMS;

        for(var item : alsoAdd)
        {
            ItemID id = ItemID.getFromItemStack(item);
            items.add(id); // Add the item with a default scale factor of 1
        }


        // Load the items into the new system
        for (ItemID itemID : items) {
            manager.allowItemID(itemID);
        }


        // Load users
        for (AccountData accountData : accountDataList) {
            User user = new User(
                    accountData.playerUUID,
                    accountData.playerName
            );
            manager.addUser(user);
        }


        // Create personal banks for each user
        ItemID icon = ItemID.of(Items.CHEST.getDefaultInstance());
        for (AccountData accountData : accountDataList) {
            ISyncServerBankAccount account = manager.createPersonalBankAccount(accountData.playerUUID);
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

                    ISyncServerBank bank = account.createBank(bankData.itemID, 0);
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
