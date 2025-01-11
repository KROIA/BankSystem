package net.kroia.banksystem.networking.packet.client_sender.request;

import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDataPacket;
import net.kroia.banksystem.util.BankSystemTextMessages;
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
        BankSystemNetworking.sendToServer(packet);
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
                PlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getItemAlreadyAllowedMessage(normalized));
                return;
            }
            if(ServerBankManager.allowItemID(normalized))
            {
                PlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getItemNowAllowedMessage(normalized));
                SyncBankDataPacket.sendPacket(sender);
            }
            else
            {
                PlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getItemNowAllowedFailedMessage(normalized));
            }
        }
        else
        {
            PlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getInvalidItemIDMessage(itemID));
        }
    }


}
