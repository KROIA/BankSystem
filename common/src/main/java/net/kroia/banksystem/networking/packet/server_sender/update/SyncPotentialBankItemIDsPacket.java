package net.kroia.banksystem.networking.packet.server_sender.update;

import dev.architectury.networking.simple.MessageType;
import net.kroia.banksystem.banking.ClientBankManager;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.modutilities.networking.NetworkPacketS2C;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;

public class SyncPotentialBankItemIDsPacket extends NetworkPacketS2C {
    private ArrayList<String> potentialBankItemIDs;

    @Override
    public MessageType getType() {
        return BankSystemNetworking.SYNC_POTENTIAL_BANK_ITEM_IDS;
    }


    public static void sendPacket(ServerPlayer receiver, ArrayList<String> potentialBankItemIDs)
    {
        SyncPotentialBankItemIDsPacket packet = new SyncPotentialBankItemIDsPacket(potentialBankItemIDs);
        packet.sendTo(receiver);
    }
    public SyncPotentialBankItemIDsPacket(ArrayList<String> potentialBankItemIDs)
    {
        this.potentialBankItemIDs = potentialBankItemIDs;
    }
    public SyncPotentialBankItemIDsPacket(RegistryFriendlyByteBuf buf)
    {
        super(buf);
    }

    public ArrayList<String> getPotentialBankItemIDs()
    {
        return potentialBankItemIDs;
    }

    @Override
    public void toBytes(RegistryFriendlyByteBuf buf) {
        buf.writeInt(potentialBankItemIDs.size());
        for(String itemID : potentialBankItemIDs)
        {
            buf.writeUtf(itemID);
        }
    }

    @Override
    public void fromBytes(RegistryFriendlyByteBuf buf)
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
