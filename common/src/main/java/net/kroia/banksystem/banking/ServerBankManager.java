package net.kroia.banksystem.banking;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemSettings;
import net.kroia.banksystem.banking.bank.Bank;
import net.kroia.banksystem.banking.events.ServerBankCloseItemBankEvent;
import net.kroia.banksystem.banking.events.ServerBankEvent;
import net.kroia.modutilities.PlayerUtilities;
import net.kroia.modutilities.ServerSaveable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public class ServerBankManager implements ServerSaveable {

    private static Map<UUID, BankUser> userMap = new HashMap<>();
    private static Map<String, Boolean> allowedItemIDs = BankSystemSettings.Bank.ALLOWED_ITEM_IDS;
    private static ArrayList<String> potentialBankItemIDs = new ArrayList<>();
    private static ArrayList<Consumer<ServerBankEvent>> eventListeners = new ArrayList<>();
    public static BankUser createUser(UUID userUUID, String userName, ArrayList<String> itemIDs, boolean createMoneyBank, long startMoney)
    {
        BankUser user = userMap.get(userUUID);
        if(user != null)
            return user;
        user = new BankUser(userUUID, userName);
        for(String itemID : itemIDs)
            user.createItemBank(itemID, 0);
        if(createMoneyBank)
            user.createMoneyBank(startMoney);
        PlayerUtilities.printToClientConsole(userUUID, "A bank account has been created for you.\n" +
                "You can access your account using the Bank Terminal block\nor the /bank command.");
        userMap.put(userUUID, user);
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
    public static void clear()
    {
        HashMap<UUID, ServerBankCloseItemBankEvent.PlayerData> lostItems = new HashMap<>();
        HashMap<String, Boolean> allRemovedItemIDs = new HashMap<>();
        for(BankUser user : userMap.values())
        {
            HashMap<String, Long> itemAmounts = new HashMap<>();
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
    public static boolean closeBankAccount(UUID playerUUID, String itemID)
    {
        BankUser user = userMap.get(playerUUID);
        Bank bank = user.getBank(itemID);
        if(bank == null) {
            return false;
        }
        HashMap<String, Long> itemAmounts = new HashMap<>();
        itemAmounts.put(itemID, bank.getTotalBalance());
        HashMap<UUID, ServerBankCloseItemBankEvent.PlayerData> lostItems = new HashMap<>();
        lostItems.put(user.getPlayerUUID(), new ServerBankCloseItemBankEvent.PlayerData(user.getPlayerUUID(), itemAmounts));
        if(user.removeBank(itemID))
        {
            ArrayList<String> itemIDs= new ArrayList<>();
            itemIDs.add(itemID);
            ServerBankCloseItemBankEvent event = new ServerBankCloseItemBankEvent(lostItems, itemIDs);
            ServerBankManager.fireEvent(event);
            return true;
        }
        return false;
    }
    public static void closeBankAccount(String itemID)
    {
        HashMap<UUID, ServerBankCloseItemBankEvent.PlayerData> lostItems = new HashMap<>();
        for(BankUser user : userMap.values())
        {
            Bank bank = user.getBank(itemID);
            if(bank == null)
                continue;
            HashMap<String, Long> itemAmounts = new HashMap<>();
            itemAmounts.put(itemID, bank.getTotalBalance());
            user.removeBank(itemID);
            lostItems.put(user.getPlayerUUID(), new ServerBankCloseItemBankEvent.PlayerData(user.getPlayerUUID(), itemAmounts));
        }
        ArrayList<String> itemIDs= new ArrayList<>();
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
    public static Bank getBank(UUID userUUID, String itemID)
    {
        BankUser user = userMap.get(userUUID);
        if(user == null)
            return null;
        return user.getBank(itemID);
    }
    public static Bank getMoneyBank(String userName, String itemID)
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

    public static boolean isItemIDAllowed(String itemID)
    {
        return allowedItemIDs.containsKey(itemID);
    }
    public static boolean allowItemID(String itemID)
    {
        if(itemID == null)
            return false;
        ArrayList<String> blackList = BankSystemSettings.Bank.POTENTIAL_ITEM_BLACKLIST;
        if(blackList.contains(itemID))
        {
            BankSystemMod.LOGGER.info("It is not allowed to add the itemID: " + itemID);
            return false;
        }
        allowedItemIDs.put(itemID, true);
        return true;
    }
    public static void disallowItemID(String itemID)
    {
        if(itemID == null)
            return;
        ArrayList<String> notRemovable = BankSystemSettings.Bank.NOT_REMOVABLE_ITEM_IDS;
        if(notRemovable.contains(itemID))
        {
            BankSystemMod.LOGGER.info("It is not allowed to remove the itemID: " + itemID);
            return;
        }
        allowedItemIDs.remove(itemID);
        closeBankAccount(itemID);
    }
    public static ArrayList<String> getAllowedItemIDs()
    {
        return new ArrayList<>(allowedItemIDs.keySet());
    }


    public static void setPotientialBankItemIDs(ArrayList<String> potentialBankItemIDs)
    {
        ServerBankManager.potentialBankItemIDs = potentialBankItemIDs;

        ArrayList<String> blackList = BankSystemSettings.Bank.POTENTIAL_ITEM_BLACKLIST;
        for(String itemID : blackList)
        {
            potentialBankItemIDs.remove(itemID);
        }

        ArrayList<String> notRemovable = BankSystemSettings.Bank.NOT_REMOVABLE_ITEM_IDS;
        for(String itemID : notRemovable)
        {
            if(!potentialBankItemIDs.contains(itemID))
                potentialBankItemIDs.add(itemID);
        }
    }
    public static ArrayList<String> getPotentialBankItemIDs()
    {
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
        for (Map.Entry<String, Boolean> entry : allowedItemIDs.entrySet()) {
            CompoundTag allowedItemTag = new CompoundTag();
            allowedItemTag.putString("itemID", entry.getKey());
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
            String itemID = allowedItemTag.getString("itemID");
            allowedItemIDs.put(itemID, true);
        }
        return success;
    }


    /*public static void handlePacket(ServerPlayer sender, RequestBankDataPacket packet)
    {
        SyncBankDataPacket.sendPacket(sender);
    }*/
}
