package net.kroia.banksystem.networking.packet.client_sender.request;

import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.networking.ModMessages;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDataPacket;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.PlayerUtilities;
import net.kroia.modutilities.networking.NetworkPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class RequestAllowNewBankItemIDPacket extends NetworkPacket {
    private String itemID;

    public static void sendRequest(String itemID)
    {
        RequestAllowNewBankItemIDPacket packet = new RequestAllowNewBankItemIDPacket(itemID);
        ModMessages.sendToServer(packet);
    }

    public RequestAllowNewBankItemIDPacket(String itemID) {
        this.itemID = itemID;
    }
    public RequestAllowNewBankItemIDPacket(FriendlyByteBuf buf)
    {
        this.fromBytes(buf);
    }
    @Override
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeUtf(itemID);
    }

    @Override
    public void fromBytes(FriendlyByteBuf buf)
    {
        this.itemID = buf.readUtf();
    }

    public String getItemID() {
        return itemID;
    }

    @Override
    protected void handleOnServer(ServerPlayer sender)
    {
        String normalized = ItemUtilities.getNormalizedItemID(itemID);
        if(normalized != null)
        {
            if(ServerBankManager.isItemIDAllowed(normalized))
            {
                PlayerUtilities.printToClientConsole(sender, "Item ID is already allowed: " + normalized + " for banking");
                return;
            }
            if(ServerBankManager.allowItemID(normalized))
            {
                PlayerUtilities.printToClientConsole(sender, "Item ID allowed: " + normalized + " for banking now");
                SyncBankDataPacket.sendPacket(sender);
            }
            else
            {
                PlayerUtilities.printToClientConsole(sender, "Failed to allow item ID: " + normalized + " for banking");
            }
        }
        else
        {
            PlayerUtilities.printToClientConsole(sender, "Invalid item ID: " + itemID);
        }
    }
}
