package net.kroia.banksystem.banking.clientdata;

import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.INetworkPayloadEncoder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents minimal data for the server bank manager.
 * This class is used to transfer user bank data from the server to the client.
 */
public class MinimalBankManagerData implements INetworkPayloadEncoder {
    public final List<UUID> bankUsers;
    public final List<ItemID> allowedItems;
    public final List<ItemID> blacklistedItems;
    public final List<ItemID> notRemovableItems;

    public MinimalBankManagerData(ServerBankManager manager) {
        this.bankUsers = manager.getUserUUIDList();
        this.allowedItems = manager.getAllowedItemIDs();
        this.blacklistedItems = manager.getBlacklistedItemIDs();
        this.notRemovableItems = manager.getNotRemovableItemIDs();
    }

    public MinimalBankManagerData(List<UUID> bankUsers,
                                  List<ItemID> allowableItems,
                                  List<ItemID> blacklistedItems,
                                  List<ItemID> notRemovableItems) {
        this.bankUsers = bankUsers;
        this.allowedItems = allowableItems;
        this.blacklistedItems = blacklistedItems;
        this.notRemovableItems = notRemovableItems;
    }


    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(bankUsers, FriendlyByteBuf::writeUUID);
        buf.writeCollection(allowedItems, (b, itemID) -> b.writeItem(itemID.getStack()));
        buf.writeCollection(blacklistedItems, (b, itemID) -> b.writeItem(itemID.getStack()));
        buf.writeCollection(notRemovableItems, (b, itemID) -> b.writeItem(itemID.getStack()));
    }

    public static MinimalBankManagerData decode(FriendlyByteBuf buf) {
        List<UUID> bankUsers = buf.readList(FriendlyByteBuf::readUUID);
        List<ItemID> allowableItems = buf.readList(b -> new ItemID(b.readItem()));
        List<ItemID> blacklistedItems = buf.readList(b -> new ItemID(b.readItem()));
        List<ItemID> notRemovableItems = buf.readList(b -> new ItemID(b.readItem()));
        return new MinimalBankManagerData(bankUsers, allowableItems, blacklistedItems, notRemovableItems);
    }


    public ArrayList<ItemStack> createAllowedItemStacks() {
        ArrayList<ItemStack> stacks = new ArrayList<>();
        for (ItemID itemID : allowedItems) {
            stacks.add(itemID.getStack());
        }
        return stacks;
    }
    public ArrayList<ItemStack> createBlacklistedItemStacks() {
        ArrayList<ItemStack> stacks = new ArrayList<>();
        for (ItemID itemID : blacklistedItems) {
            stacks.add(itemID.getStack());
        }
        return stacks;
    }
    public ArrayList<ItemStack> createNotRemovableItemStacks() {
        ArrayList<ItemStack> stacks = new ArrayList<>();
        for (ItemID itemID : notRemovableItems) {
            stacks.add(itemID.getStack());
        }
        return stacks;
    }

}
