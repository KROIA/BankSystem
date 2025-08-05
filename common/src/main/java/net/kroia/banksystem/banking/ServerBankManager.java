package net.kroia.banksystem.banking;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.IBank;
import net.kroia.banksystem.api.IBankUser;
import net.kroia.banksystem.api.IServerBankManager;
import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.banksystem.banking.clientdata.ItemInfoData;
import net.kroia.banksystem.banking.clientdata.MinimalBankData;
import net.kroia.banksystem.banking.clientdata.MinimalBankManagerData;
import net.kroia.banksystem.banking.clientdata.MinimalBankUserData;
import net.kroia.banksystem.banking.eventdata.CloseItemBankEventData;
import net.kroia.banksystem.item.custom.money.MoneyItem;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.PlayerUtilities;
import net.kroia.modutilities.ServerSaveable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class ServerBankManager implements ServerSaveable, IServerBankManager {

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    private final Map<UUID, BankUser> userMap = new HashMap<>();

    public static void setBackend(BankSystemModBackend.Instances backend) {
        ServerBankManager.BACKEND_INSTANCES = backend;
    }


    @Override
    public MinimalBankUserData getMinimalBankUserData(UUID userUUID)
    {
        BankUser user = userMap.get(userUUID);
        if(user == null)
            return null;
        return user.getMinimalData();
    }

    @Override
    public MinimalBankData getMinimalBankData(UUID userUUID, ItemID itemID)
    {
        BankUser user = userMap.get(userUUID);
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
    public IBankUser createUser(UUID userUUID, String userName, ArrayList<ItemID> itemIDs, boolean createMoneyBank, long startMoney)
    {
        BankUser user = userMap.get(userUUID);
        if(user != null)
            return user;
        user = new BankUser(userUUID, userName);
        for(ItemID itemID : itemIDs)
            user.createItemBank(itemID, 0, true);
        if(createMoneyBank)
            user.createMoneyBank(startMoney);
        PlayerUtilities.printToClientConsole(userUUID, BankSystemTextMessages.getBankCreatedMessage(userName, MoneyItem.getName())+"\n"+
                BankSystemTextMessages.getMoneyBankAccessHelpMessage());
        userMap.put(userUUID, user);
        return user;
    }

    @Override
    public IBankUser createUser(ServerPlayer player, ArrayList<ItemID> itemIDs, boolean createMoneyBank, long startMoney)
    {
        String userName = player.getName().getString();
        UUID uuid = player.getUUID();
        return createUser(uuid, userName, itemIDs, createMoneyBank, startMoney);
    }


    @Override
    public @Nullable IBankUser getUser(UUID userUUID)
    {
        return userMap.get(userUUID);
    }
    @Override
    public @Nullable IBankUser getUser(String userName)
    {
        for (Map.Entry<UUID, BankUser> entry : userMap.entrySet()) {
            if(entry.getValue().getPlayerName().equals(userName))
                return entry.getValue();
        }
        return null;
    }

    @Override
    public Map<UUID, IBankUser> getUser()
    {
        return new HashMap<>(userMap);
    }

    @Override
    public void clear()
    {
        HashMap<UUID, CloseItemBankEventData.PlayerData> lostItems = new HashMap<>();
        HashMap<ItemID, Boolean> allRemovedItemIDs = new HashMap<>();
        for(BankUser user : userMap.values())
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
        BankUser user = userMap.get(userUUID);
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
        for(BankUser user : userMap.values())
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
        for(BankUser user : userMap.values())
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
        BankUser user = userMap.get(userUUID);
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
        for (Map.Entry<UUID, BankUser> entry : userMap.entrySet()) {
            map.put(entry.getKey(), entry.getValue().getPlayerName());
        }
        return map;
    }


    @Override
    public List<UUID> getUserUUIDList()
    {
        List<UUID> userUUIDs = new ArrayList<>();
        for (Map.Entry<UUID, BankUser> entry : userMap.entrySet()) {
            userUUIDs.add(entry.getKey());
        }
        return userUUIDs;
    }

    @Override
    public @Nullable IBank getMoneyBank(UUID userUUID)
    {
        BankUser user = userMap.get(userUUID);
        if(user == null)
            return null;
        return user.getMoneyBank();
    }
    @Override
    public @Nullable Bank getMoneyBank(String userName)
    {
        for (Map.Entry<UUID, BankUser> entry : userMap.entrySet()) {
            if(entry.getValue().getPlayerName().equals(userName))
                return entry.getValue().getMoneyBank();
        }
        return null;
    }
    @Override
    public @Nullable IBank getBank(UUID userUUID, ItemID itemID)
    {
        BankUser user = userMap.get(userUUID);
        if(user == null)
            return null;
        return user.getBank(itemID);
    }
    @Override
    public @Nullable IBank getBank(String userName, ItemID itemID)
    {
        for (Map.Entry<UUID, BankUser> entry : userMap.entrySet()) {
            if(entry.getValue().getPlayerName().equals(userName))
                return entry.getValue().getBank(itemID);
        }
        return null;
    }


    @Override
    public long getMoneyCirculation()
    {
        long total = 0;
        for (Map.Entry<UUID, BankUser> entry : userMap.entrySet()) {
            total += entry.getValue().getTotalMoneyBalance();
        }
        return total;
    }

    @Override
    public long getLockedMoneyCirculation()
    {
        long total = 0;
        for (Map.Entry<UUID, BankUser> entry : userMap.entrySet()) {
            total += entry.getValue().getMoneyBalance();
        }
        return total;
    }

    @Override
    public long getItemCirculation(ItemID itemID)
    {
        long total = 0;
        for (Map.Entry<UUID, BankUser> entry : userMap.entrySet()) {
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
        for (Map.Entry<UUID, BankUser> entry : userMap.entrySet()) {
            IBank bank = entry.getValue().getBank(itemID);
            if(bank != null)
                total += bank.getLockedBalance();
        }
        return total;
    }

    @Override
    public ArrayList<ItemID> getAllowedItemIDs()
    {
        ArrayList<ItemID> allowedItemIDs = new ArrayList<>();
        ArrayList<String> allowed = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ALLOWED_ITEM_IDS.get();
        for(String itemIDstr : allowed)
        {
            ItemID itemID = new ItemID(itemIDstr);
            if(itemID.isValid())
                allowedItemIDs.add(itemID);
        }
        return allowedItemIDs;
    }

    @Override
    public ArrayList<ItemID> getBlacklistedItemIDs()
    {
        ArrayList<ItemID> notAllowedItemIDs = new ArrayList<>();
        ArrayList<String> notAllowed = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.BLACKLIST_ITEM_IDS.get();
        for(String itemIDstr : notAllowed)
        {
            ItemID itemID = new ItemID(itemIDstr);
            if(itemID.isValid())
                notAllowedItemIDs.add(itemID);
        }
        return notAllowedItemIDs;
    }
    @Override
    public ArrayList<ItemID> getNotRemovableItemIDs()
    {
        ArrayList<ItemID> notRemovableItemIDs = new ArrayList<>();
        ArrayList<String> notRemovable = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.NOT_REMOVABLE_ITEM_IDS.get();
        for(String itemIDstr : notRemovable)
        {
            ItemID itemID = new ItemID(itemIDstr);
            if(itemID.isValid())
                notRemovableItemIDs.add(itemID);
        }
        return notRemovableItemIDs;
    }

    @Override
    public boolean isItemIDAllowed(ItemID itemID)
    {
        ArrayList<String> allowed = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ALLOWED_ITEM_IDS.get();
        return allowed.contains(itemID.getName());
    }
    @Override
    public boolean isItemIDBlacklisted(ItemID itemID)
    {
        ArrayList<String> blackList = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.BLACKLIST_ITEM_IDS.get();
        return blackList.contains(itemID.getName());
    }
    @Override
    public boolean isItemIDNotRemovable(ItemID itemID)
    {
        ArrayList<String> notRemovable = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.NOT_REMOVABLE_ITEM_IDS.get();
        return notRemovable.contains(itemID.getName());
    }
    @Override
    public boolean allowItemID(ItemID itemID)
    {
        if(itemID == null)
            return false;
        if(isItemIDBlacklisted(itemID))
        {
            info("It is not allowed to add the itemID: " + itemID + " because it is blacklisted.");
            return false;
        }
        ArrayList<String> allowed = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ALLOWED_ITEM_IDS.get();
        String itemIdStr = itemID.getName();
        if(!allowed.contains(itemIdStr)) {
            allowed.add(itemIdStr);
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
        ArrayList<String> allowed = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ALLOWED_ITEM_IDS.get();
        allowed.remove(itemID.getName());
        BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ALLOWED_ITEM_IDS.set(allowed);
        //closeBankAccount(itemID);

        // Remove banks by item ID string to make sure banks for special items like Enchanted Books, potions, etc.
        // are closed for all variants of the item.
        closeBankAccount(itemID.getName());
        return true;
    }

    @Override
    public boolean save(CompoundTag tag) {
        ListTag bankElements = new ListTag();
        for (Map.Entry<UUID, BankUser> entry : userMap.entrySet()) {
            CompoundTag bankTag = new CompoundTag();
            entry.getValue().save(bankTag);
            bankElements.add(bankTag);
        }
        tag.put("users", bankElements);
        return true;
    }

    @Override
    public boolean load(CompoundTag tag) {
        boolean success = true;

        ListTag bankElements = tag.getList("users", 10);
        userMap.clear();
        for (int i = 0; i < bankElements.size(); i++) {
            CompoundTag bankTag = bankElements.getCompound(i);
            BankUser user = BankUser.loadFromTag(bankTag);
            if(user == null)
            {
                success = false;
                continue;
            }
            userMap.put(user.getPlayerUUID(), user);
        }
        return success;
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
