package net.kroia.banksystem.banking;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.banksystem.banking.events.ServerBankCloseItemBankEvent;
import net.kroia.banksystem.banking.events.ServerBankEvent;
import net.kroia.banksystem.item.custom.money.MoneyItem;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.PlayerUtilities;
import net.kroia.modutilities.ServerSaveable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public class ServerBankManager implements ServerSaveable {

    private static Map<UUID, BankUser> userMap = new HashMap<>();
    private static Map<ItemID, Boolean> allowedItemIDs = BankSystemModSettings.Bank.getAllowedItemIDs();
    private static ArrayList<ItemID> potentialBankItemIDs = new ArrayList<>();
    private static ArrayList<Consumer<ServerBankEvent>> eventListeners = new ArrayList<>();
    public static BankUser createUser(UUID userUUID, String userName, ArrayList<ItemID> itemIDs, boolean createMoneyBank, long startMoney)
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
    public static BankUser createUser(ServerPlayer player, ArrayList<ItemID> itemIDs, boolean createMoneyBank, long startMoney)
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

    public static BankUser getUser(UUID userUUID)
    {
        return userMap.get(userUUID);
    }
    public static BankUser getUser(String userName)
    {
        for (Map.Entry<UUID, BankUser> entry : userMap.entrySet()) {
            if(entry.getValue().getPlayerName().equals(userName))
                return entry.getValue();
        }
        return null;
    }
    public static Map<UUID, BankUser> getUser()
    {
        return userMap;
    }
    public static void clear()
    {
        HashMap<UUID, ServerBankCloseItemBankEvent.PlayerData> lostItems = new HashMap<>();
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
            ServerBankCloseItemBankEvent.PlayerData playerData = new ServerBankCloseItemBankEvent.PlayerData(user.getPlayerUUID(), itemAmounts);
            lostItems.put(user.getPlayerUUID(), playerData);
        }
        userMap.clear();

        ServerBankCloseItemBankEvent event = new ServerBankCloseItemBankEvent(lostItems, new ArrayList<>(allRemovedItemIDs.keySet()));
        ServerBankManager.fireEvent(event);
    }
    public static boolean closeBankAccount(UUID playerUUID, ItemID itemID)
    {
        BankUser user = userMap.get(playerUUID);
        Bank bank = user.getBank(itemID);
        if(bank == null) {
            return false;
        }
        HashMap<ItemID, Long> itemAmounts = new HashMap<>();
        itemAmounts.put(itemID, bank.getTotalBalance());
        HashMap<UUID, ServerBankCloseItemBankEvent.PlayerData> lostItems = new HashMap<>();
        lostItems.put(user.getPlayerUUID(), new ServerBankCloseItemBankEvent.PlayerData(user.getPlayerUUID(), itemAmounts));
        if(user.removeBank(itemID))
        {
            ArrayList<ItemID> itemIDs= new ArrayList<>();
            itemIDs.add(itemID);
            ServerBankCloseItemBankEvent event = new ServerBankCloseItemBankEvent(lostItems, itemIDs);
            ServerBankManager.fireEvent(event);
            return true;
        }
        return false;
    }
    public static void closeBankAccount(ItemID itemID)
    {
        HashMap<UUID, ServerBankCloseItemBankEvent.PlayerData> lostItems = new HashMap<>();
        for(BankUser user : userMap.values())
        {
            Bank bank = user.getBank(itemID);
            if(bank == null)
                continue;
            HashMap<ItemID, Long> itemAmounts = new HashMap<>();
            itemAmounts.put(itemID, bank.getTotalBalance());
            user.removeBank(itemID);
            lostItems.put(user.getPlayerUUID(), new ServerBankCloseItemBankEvent.PlayerData(user.getPlayerUUID(), itemAmounts));
        }
        ArrayList<ItemID> itemIDs= new ArrayList<>();
        itemIDs.add(itemID);
        ServerBankCloseItemBankEvent event = new ServerBankCloseItemBankEvent(lostItems, itemIDs);
        ServerBankManager.fireEvent(event);
    }

    public static void addEventListener(Consumer<ServerBankEvent> listener)
    {
        eventListeners.add(listener);
    }
    public static void removeEventListener(Consumer<ServerBankEvent> listener)
    {
        eventListeners.remove(listener);
    }
    public static void removeAllEventListeners()
    {
        eventListeners.clear();
    }
    public static void fireEvent(ServerBankEvent event)
    {
        for(Consumer<ServerBankEvent> listener : eventListeners)
        {
            listener.accept(event);
        }
    }

    public static HashMap<UUID, String> getPlayerNameMap()
    {
        HashMap<UUID, String> map = new HashMap<>();
        for (Map.Entry<UUID, BankUser> entry : userMap.entrySet()) {
            map.put(entry.getKey(), entry.getValue().getPlayerName());
        }
        return map;
    }

    public static Bank getMoneyBank(UUID userUUID)
    {
        BankUser user = userMap.get(userUUID);
        if(user == null)
            return null;
        return user.getMoneyBank();
    }
    public static Bank getMoneyBank(String userName)
    {
        for (Map.Entry<UUID, BankUser> entry : userMap.entrySet()) {
            if(entry.getValue().getPlayerName().equals(userName))
                return entry.getValue().getMoneyBank();
        }
        return null;
    }
    public static Bank getBank(UUID userUUID, ItemID itemID)
    {
        BankUser user = userMap.get(userUUID);
        if(user == null)
            return null;
        return user.getBank(itemID);
    }
    public static Bank getMoneyBank(String userName, ItemID itemID)
    {
        for (Map.Entry<UUID, BankUser> entry : userMap.entrySet()) {
            if(entry.getValue().getPlayerName().equals(userName))
                return entry.getValue().getBank(itemID);
        }
        return null;
    }


    public static long getMoneyCirculation()
    {
        long total = 0;
        for (Map.Entry<UUID, BankUser> entry : userMap.entrySet()) {
            total += entry.getValue().getTotalMoneyBalance();
        }
        return total;
    }

    public static boolean isItemIDAllowed(ItemID itemID)
    {
        return allowedItemIDs.containsKey(itemID);
    }
    public static boolean allowItemID(ItemID itemID)
    {
        if(itemID == null)
            return false;
        ArrayList<ItemID> blackList = BankSystemModSettings.Bank.getPotentialItemBlacklist();
        if(blackList.contains(itemID))
        {
            BankSystemMod.LOGGER.info("It is not allowed to add the itemID: " + itemID);
            return false;
        }
        allowedItemIDs.put(itemID, true);
        return true;
    }
    public static void disallowItemID(ItemID itemID)
    {
        if(itemID == null)
            return;
        ArrayList<ItemID> notRemovable = BankSystemModSettings.Bank.getNotRemovableItemIDs();
        if(notRemovable.contains(itemID))
        {
            BankSystemMod.LOGGER.info("It is not allowed to remove the itemID: " + itemID);
            return;
        }
        allowedItemIDs.remove(itemID);
        closeBankAccount(itemID);
    }
    public static ArrayList<ItemID> getAllowedItemIDs()
    {
        return new ArrayList<>(allowedItemIDs.keySet());
    }


    public static void setPotientialBankItemIDs(ArrayList<ItemID> potentialBankItemIDs)
    {
        ServerBankManager.potentialBankItemIDs = potentialBankItemIDs;

        ArrayList<ItemID> blackList = BankSystemModSettings.Bank.getPotentialItemBlacklist();
        for(ItemID itemID : blackList)
        {
            potentialBankItemIDs.remove(itemID);
        }

        ArrayList<ItemID> notRemovable = BankSystemModSettings.Bank.getNotRemovableItemIDs();
        for(ItemID itemID : notRemovable)
        {
            if(!potentialBankItemIDs.contains(itemID))
                potentialBankItemIDs.add(itemID);
        }
    }
    public static ArrayList<ItemID> getPotentialBankItemIDs()
    {
        //BankSystemNetworking.setupServerReceiverPackets();
        ArrayList<ItemID> items = new ArrayList<>();
        /*for(ItemStack item : ItemUtilities.getAllItems())
        {
            items.add(new ItemID(item));
        }*/
        /*for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
            for(ItemStack item : tab.getDisplayItems())
            {
                items.add(new ItemID(item));
            }
        }*/
        for(ItemStack item : ItemUtilities.getAllItems())
        {
            items.add(new ItemID(item));
        }

        setPotientialBankItemIDs(items);
        return potentialBankItemIDs;
    }

    public static boolean saveToTag(CompoundTag tag)
    {
        ServerBankManager tmp = new ServerBankManager();
        return tmp.save(tag);
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

        ListTag allowedItemIDsTag = new ListTag();
        for (Map.Entry<ItemID, Boolean> entry : allowedItemIDs.entrySet()) {
            CompoundTag allowedItemTag = new CompoundTag();
            CompoundTag itemTag = new CompoundTag();
            entry.getKey().save(itemTag);
            allowedItemTag.put("itemID", itemTag);
            allowedItemIDsTag.add(allowedItemTag);
        }
        tag.put("allowedItemIDs", allowedItemIDsTag);
        return true;
    }

    public static boolean loadFromTag(CompoundTag tag)
    {
        ServerBankManager tmp = new ServerBankManager();
        return tmp.load(tag);
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

        ListTag allowedItemIDsTag = tag.getList("allowedItemIDs", 10);
        allowedItemIDs.clear();
        for (int i = 0; i < allowedItemIDsTag.size(); i++) {
            CompoundTag allowedItemTag = allowedItemIDsTag.getCompound(i);
            ItemID itemID;
            if(!allowedItemTag.getString("itemID").isEmpty())
            {
                itemID = new ItemID(allowedItemTag.getString("itemID"));
            }
            else
            {
                CompoundTag itemTag = allowedItemTag.getCompound("itemID");
                itemID = new ItemID(itemTag);
            }

            allowedItemIDs.put(itemID, true);
        }
        return success;
    }
}
