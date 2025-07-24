package net.kroia.banksystem.banking.eventdata;


import net.kroia.banksystem.util.ItemID;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

public class CloseItemBankEventData {
    public static class PlayerData
    {
        public final UUID playerUUID;
        public final HashMap<ItemID, Long> itemAmounts;
        public PlayerData(UUID playerUUID, HashMap<ItemID, Long> itemAmounts)
        {
            this.playerUUID = playerUUID;
            this.itemAmounts = itemAmounts;
        }
    }

    // A list of item amount for each player who lost items because the bank account was closed
    private final HashMap<UUID, PlayerData> lostItems;
    private final ArrayList<ItemID> allRemovedItemIDs;

    public CloseItemBankEventData(HashMap<UUID, PlayerData> lostItems, ArrayList<ItemID> allRemovedItemIDs) {
        this.lostItems = lostItems;
        this.allRemovedItemIDs = allRemovedItemIDs;
    }

    public HashMap<UUID, PlayerData> getLostItems() {
        return lostItems;
    }
    public ArrayList<ItemID> getAllRemovedItemIDs() {
        return allRemovedItemIDs;
    }
}

