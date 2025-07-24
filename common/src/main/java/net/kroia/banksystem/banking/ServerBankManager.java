package net.kroia.banksystem.banking;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.banksystem.banking.eventdata.CloseItemBankEventData;
import net.kroia.banksystem.item.custom.money.MoneyItem;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.PlayerUtilities;
import net.kroia.modutilities.ServerSaveable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ServerBankManager implements ServerSaveable {

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    private Map<UUID, BankUser> userMap = new HashMap<>();

    public static void setBackend(BankSystemModBackend.Instances backend) {
        ServerBankManager.BACKEND_INSTANCES = backend;
    }
    public BankUser createUser(UUID userUUID, String userName, ArrayList<ItemID> itemIDs, boolean createMoneyBank, long startMoney)
    {
        BankUser user = userMap.get(userUUID);
        if(user != null)
            return user;
        user = new BankUser(userUUID, userName);
        for(ItemID itemID : itemIDs)
            user.createItemBank(itemID, 0);
        if(createMoneyBank)
            user.createMoneyBank(startMoney);
        PlayerUtilities.printToClientConsole(userUUID, BankSystemTextMessages.getBankCreatedMessage(userName, MoneyItem.getName())+"\n"+
                BankSystemTextMessages.getMoneyBankAccessHelpMessage());
        userMap.put(userUUID, user);
        return user;
    }
    public BankUser createUser(ServerPlayer player, ArrayList<ItemID> itemIDs, boolean createMoneyBank, long startMoney)
    {
        String userName = player.getName().getString();
        BankUser user = userMap.get(player.getUUID());
        if(user != null)
            return user;
        user = new BankUser(player.getUUID(), userName);
        for(ItemID itemID : itemIDs)
            user.createItemBank(itemID, 0);
        if(createMoneyBank)
            user.createMoneyBank(startMoney);
        PlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getBankCreatedMessage(userName, MoneyItem.getName())+"\n"+
                BankSystemTextMessages.getMoneyBankAccessHelpMessage());
        userMap.put(player.getUUID(), user);
        return user;
    }


    public BankUser getUser(UUID userUUID)
    {
        return userMap.get(userUUID);
    }
    public BankUser getUser(String userName)
    {
        for (Map.Entry<UUID, BankUser> entry : userMap.entrySet()) {
            if(entry.getValue().getPlayerName().equals(userName))
                return entry.getValue();
        }
        return null;
    }
    public Map<UUID, BankUser> getUser()
    {
        return userMap;
    }
    public void clear()
    {
        HashMap<UUID, CloseItemBankEventData.PlayerData> lostItems = new HashMap<>();
        HashMap<ItemID, Boolean> allRemovedItemIDs = new HashMap<>();
        for(BankUser user : userMap.values())
        {
            HashMap<ItemID, Long> itemAmounts = new HashMap<>();
            user.getBankMap().forEach((itemID, bank) -> {
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
    public boolean closeBankAccount(UUID playerUUID, ItemID itemID)
    {
        BankUser user = userMap.get(playerUUID);
        Bank bank = user.getBank(itemID);
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
    public void closeBankAccount(ItemID itemID)
    {
        HashMap<UUID, CloseItemBankEventData.PlayerData> lostItems = new HashMap<>();
        for(BankUser user : userMap.values())
        {
            Bank bank = user.getBank(itemID);
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

    public void closeBankAccount(String itemIDStr)
    {
        HashMap<UUID, CloseItemBankEventData.PlayerData> lostItems = new HashMap<>();
        ArrayList<ItemID> itemIDs= new ArrayList<>();
        for(BankUser user : userMap.values())
        {
            HashMap<ItemID, Bank> bankMap = user.getBankMap();
            ArrayList<ItemID> itemIDsToRemove = new ArrayList<>();
            for(Map.Entry<ItemID, Bank> entry : bankMap.entrySet())
            {
                if(!entry.getKey().getName().equals(itemIDStr))
                    continue;
                Bank bank = entry.getValue();
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
        CloseItemBankEventData event = new CloseItemBankEventData(lostItems, itemIDs);
        BACKEND_INSTANCES.SERVER_EVENTS.CLOSE_ITEM_BANK_EVENT.notifyListeners(event);
    }

    public boolean removeUser(UUID userUUID)
    {
        BankUser user = userMap.get(userUUID);
        if(user == null)
            return false;
        HashMap<ItemID, Long> itemAmounts = new HashMap<>();
        for (Map.Entry<ItemID, Bank> entry : user.getBankMap().entrySet()) {
            itemAmounts.put(entry.getKey(), entry.getValue().getTotalBalance());
        }
        HashMap<UUID, CloseItemBankEventData.PlayerData> lostItems = new HashMap<>();
        lostItems.put(user.getPlayerUUID(), new CloseItemBankEventData.PlayerData(user.getPlayerUUID(), itemAmounts));
        userMap.remove(userUUID);
        CloseItemBankEventData event = new CloseItemBankEventData(lostItems, new ArrayList<>(itemAmounts.keySet()));
        BACKEND_INSTANCES.SERVER_EVENTS.CLOSE_ITEM_BANK_EVENT.notifyListeners(event);
        return true;
    }

    public HashMap<UUID, String> getPlayerNameMap()
    {
        HashMap<UUID, String> map = new HashMap<>();
        for (Map.Entry<UUID, BankUser> entry : userMap.entrySet()) {
            map.put(entry.getKey(), entry.getValue().getPlayerName());
        }
        return map;
    }

    public Bank getMoneyBank(UUID userUUID)
    {
        BankUser user = userMap.get(userUUID);
        if(user == null)
            return null;
        return user.getMoneyBank();
    }
    public Bank getMoneyBank(String userName)
    {
        for (Map.Entry<UUID, BankUser> entry : userMap.entrySet()) {
            if(entry.getValue().getPlayerName().equals(userName))
                return entry.getValue().getMoneyBank();
        }
        return null;
    }
    public Bank getBank(UUID userUUID, ItemID itemID)
    {
        BankUser user = userMap.get(userUUID);
        if(user == null)
            return null;
        return user.getBank(itemID);
    }
    public Bank getMoneyBank(String userName, ItemID itemID)
    {
        for (Map.Entry<UUID, BankUser> entry : userMap.entrySet()) {
            if(entry.getValue().getPlayerName().equals(userName))
                return entry.getValue().getBank(itemID);
        }
        return null;
    }


    public long getMoneyCirculation()
    {
        long total = 0;
        for (Map.Entry<UUID, BankUser> entry : userMap.entrySet()) {
            total += entry.getValue().getTotalMoneyBalance();
        }
        return total;
    }

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

    public boolean isItemIDAllowed(ItemID itemID)
    {
        ArrayList<String> allowed = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ALLOWED_ITEM_IDS.get();
        return allowed.contains(itemID.getName());
    }
    public boolean isItemIDBlacklisted(ItemID itemID)
    {
        ArrayList<String> blackList = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.BLACKLIST_ITEM_IDS.get();
        return blackList.contains(itemID.getName());
    }
    public boolean isItemIDNotRemovable(ItemID itemID)
    {
        ArrayList<String> notRemovable = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.NOT_REMOVABLE_ITEM_IDS.get();
        return notRemovable.contains(itemID.getName());
    }
    public boolean allowItemID(ItemID itemID)
    {
        if(itemID == null)
            return false;
        if(isItemIDBlacklisted(itemID))
        {
            BACKEND_INSTANCES.LOGGER.info("It is not allowed to add the itemID: " + itemID + " because it is blacklisted.");
            return false;
        }
        ArrayList<String> allowed = BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ALLOWED_ITEM_IDS.get();
        allowed.add(itemID.getName());
        BACKEND_INSTANCES.SERVER_SETTINGS.BANK.ALLOWED_ITEM_IDS.set(allowed);
        return true;
    }
    public boolean disallowItemID(ItemID itemID)
    {
        if(itemID == null)
            return false;
        if(isItemIDNotRemovable(itemID))
        {
            BACKEND_INSTANCES.LOGGER.info("It is not allowed to remove the itemID: " + itemID);
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



    /*
    public static void setPotientialBankItemIDs(ArrayList<ItemID> potentialBankItemIDs)
    {
        ServerBankManager.potentialBankItemIDs = potentialBankItemIDs;

        ArrayList<ItemID> blackList = BACKEND_INSTANCESSettings.Bank.getItemBlacklist();
        for(ItemID itemID : blackList)
        {
            potentialBankItemIDs.remove(itemID);
        }

        ArrayList<ItemID> notRemovable = BACKEND_INSTANCESSettings.Bank.getNotRemovableItemIDs();
        for(ItemID itemID : notRemovable)
        {
            if(!potentialBankItemIDs.contains(itemID))
                potentialBankItemIDs.add(itemID);
        }
    }*/

    /*
    public static ArrayList<ItemID> getPotentialBankItemIDs()
    {
        ArrayList<ItemID> items = new ArrayList<>();
        for(ItemStack item : ItemUtilities.getAllItems())
        {
            items.add(new ItemID(item));
        }

        setPotientialBankItemIDs(items);
        return potentialBankItemIDs;
    }*/

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
}
