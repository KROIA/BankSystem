package net.kroia.banksystem.banking.clientdata;

import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.INetworkPayloadEncoder;
import net.minecraft.network.FriendlyByteBuf;

import java.util.List;
import java.util.UUID;

/**
 * Represents minimal data for the server bank manager.
 * This class is used to transfer user bank data from the server to the client.
 */
public class MinimalServerBankManagerData  implements INetworkPayloadEncoder {
    public final List<UUID> bankUsers;
    public final List<ItemID> allowableItems;
    public final List<ItemID> blacklistedItems;
    public final List<ItemID> notRemovableItems;

    public MinimalServerBankManagerData(ServerBankManager manager) {
        this.bankUsers = manager.getUserUUIDList();
        this.allowableItems = manager.getAllowedItemIDs();
        this.blacklistedItems = manager.getBlacklistedItemIDs();
        this.notRemovableItems = manager.getNotRemovableItemIDs();
    }

    public MinimalServerBankManagerData(List<UUID> bankUsers,
                                        List<ItemID> allowableItems,
                                        List<ItemID> blacklistedItems,
                                        List<ItemID> notRemovableItems) {
        this.bankUsers = bankUsers;
        this.allowableItems = allowableItems;
        this.blacklistedItems = blacklistedItems;
        this.notRemovableItems = notRemovableItems;
    }


    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(bankUsers, FriendlyByteBuf::writeUUID);
        buf.writeCollection(allowableItems, (b, itemID) -> b.writeItem(itemID.getStack()));
        buf.writeCollection(blacklistedItems, (b, itemID) -> b.writeItem(itemID.getStack()));
        buf.writeCollection(notRemovableItems, (b, itemID) -> b.writeItem(itemID.getStack()));
    }

    public static MinimalServerBankManagerData decode(FriendlyByteBuf buf) {
        List<UUID> bankUsers = buf.readList(FriendlyByteBuf::readUUID);
        List<ItemID> allowableItems = buf.readList(b -> new ItemID(b.readItem()));
        List<ItemID> blacklistedItems = buf.readList(b -> new ItemID(b.readItem()));
        List<ItemID> notRemovableItems = buf.readList(b -> new ItemID(b.readItem()));
        return new MinimalServerBankManagerData(bankUsers, allowableItems, blacklistedItems, notRemovableItems);
    }
}
