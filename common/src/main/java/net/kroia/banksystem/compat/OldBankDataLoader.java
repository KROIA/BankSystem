package net.kroia.banksystem.compat;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.IBank;
import net.kroia.banksystem.banking.BankAccount;
import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.item.custom.money.MoneyItem;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
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
                    data.itemID = new ItemID(tag.getString("itemID"));
                }else if (tag.get("itemID") instanceof CompoundTag itemTag) {
                    data.itemID = ItemID.createFromTag(itemTag);
                } else {
                    BACKEND_INSTANCES.LOGGER.error("Invalid itemID format in bank data: "+ Objects.requireNonNull(tag.get("itemID")));
                    return null; // Invalid itemID format
                }
                data.balance = tag.getLong("balance");
                data.lockedBalance = tag.getLong("lockedBalance");

                if(!tag.contains("useCents") && MoneyItem.isMoney(data.itemID))
                {
                    data.balance *= 100; // Convert to cents if useCents is true
                    data.lockedBalance *= 100; // Convert to cents if useCents is true
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
            BankAccount account = manager.createPersonalBankAccount(accountData.playerUUID);
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
                    bank.setBalance(balance);
                    if(lockedBalance > 0) {
                        bank.lockAmount(lockedBalance);
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
