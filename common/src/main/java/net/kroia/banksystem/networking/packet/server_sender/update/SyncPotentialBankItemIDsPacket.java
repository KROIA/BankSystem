package net.kroia.banksystem.networking.packet.server_sender.update;

import net.kroia.banksystem.banking.ClientBankManager;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.NetworkPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;

public class SyncPotentialBankItemIDsPacket extends NetworkPacket {
    private ArrayList<ItemID> potentialBankItemIDs;


    public static void sendPacket(ServerPlayer receiver, ArrayList<ItemID> potentialBankItemIDs)
    {
        SyncPotentialBankItemIDsPacket packet = new SyncPotentialBankItemIDsPacket(potentialBankItemIDs);
        BankSystemNetworking.sendToClient(receiver, packet);
    }
    public SyncPotentialBankItemIDsPacket(ArrayList<ItemID> potentialBankItemIDs)
    {
        this.potentialBankItemIDs = potentialBankItemIDs;
    }
    public SyncPotentialBankItemIDsPacket(FriendlyByteBuf buf)
    {
        this.fromBytes(buf);
    }

    public ArrayList<ItemID> getPotentialBankItemIDs()
    {
        return potentialBankItemIDs;
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeInt(potentialBankItemIDs.size());
        for(ItemID itemID : potentialBankItemIDs)
        {
            buf.writeItem(itemID.getStack());
        }
    }

    public void fromBytes(FriendlyByteBuf buf)
    {
        int size = buf.readInt();
        potentialBankItemIDs = new ArrayList<>(size);
        for(int i = 0; i < size; i++)
        {
            potentialBankItemIDs.add(new ItemID(buf.readItem()));
        }
    }

    protected void handleOnClient()
    {
        ClientBankManager.handlePacket(this);
    }
}
