package net.kroia.banksystem.networking.packet.server_sender.update;

import net.kroia.banksystem.banking.ClientBankManager;
import net.kroia.banksystem.networking.ModMessages;
import net.kroia.modutilities.networking.NetworkPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;

public class SyncPotentialBankItemIDsPacket extends NetworkPacket {
    private ArrayList<String> potentialBankItemIDs;


    public static void sendPacket(ServerPlayer receiver, ArrayList<String> potentialBankItemIDs)
    {
        SyncPotentialBankItemIDsPacket packet = new SyncPotentialBankItemIDsPacket(potentialBankItemIDs);
        ModMessages.sendToPlayer(packet, receiver);
    }
    public SyncPotentialBankItemIDsPacket(ArrayList<String> potentialBankItemIDs)
    {
        this.potentialBankItemIDs = potentialBankItemIDs;
    }
    public SyncPotentialBankItemIDsPacket(FriendlyByteBuf buf)
    {
        this.fromBytes(buf);
    }

    public ArrayList<String> getPotentialBankItemIDs()
    {
        return potentialBankItemIDs;
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeInt(potentialBankItemIDs.size());
        for(String itemID : potentialBankItemIDs)
        {
            buf.writeUtf(itemID);
        }
    }

    public void fromBytes(FriendlyByteBuf buf)
    {
        int size = buf.readInt();
        potentialBankItemIDs = new ArrayList<>(size);
        for(int i = 0; i < size; i++)
        {
            potentialBankItemIDs.add(buf.readUtf());
        }
    }

    protected void handleOnClient()
    {
        ClientBankManager.handlePacket(this);
    }
}
