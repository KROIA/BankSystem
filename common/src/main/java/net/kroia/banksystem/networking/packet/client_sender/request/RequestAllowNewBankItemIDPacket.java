package net.kroia.banksystem.networking.packet.client_sender.request;

import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDataPacket;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.PlayerUtilities;
import net.kroia.modutilities.networking.NetworkPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class RequestAllowNewBankItemIDPacket extends NetworkPacket {
    private ItemID itemID;

    public static void sendRequest(ItemID itemID)
    {
        RequestAllowNewBankItemIDPacket packet = new RequestAllowNewBankItemIDPacket(itemID);
        BankSystemNetworking.sendToServer(packet);
    }

    public RequestAllowNewBankItemIDPacket(ItemID itemID) {
        this.itemID = itemID;
    }
    public RequestAllowNewBankItemIDPacket(FriendlyByteBuf buf)
    {
        this.fromBytes(buf);
    }
    @Override
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeItem(itemID.getStack());
    }

    @Override
    public void fromBytes(FriendlyByteBuf buf)
    {
        this.itemID = new ItemID(buf.readItem());
    }

    public ItemID getItemID() {
        return itemID;
    }

    @Override
    protected void handleOnServer(ServerPlayer sender)
    {
        //String normalized = ItemUtilities.getNormalizedItemID(itemID);
        if(itemID != null)
        {
            if(ServerBankManager.isItemIDAllowed(itemID))
            {
                PlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getItemAlreadyAllowedMessage(itemID.getName()));
                return;
            }
            if(ServerBankManager.allowItemID(itemID))
            {
                PlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getItemNowAllowedMessage(itemID.getName()));
                SyncBankDataPacket.sendPacket(sender);
            }
            else
            {
                PlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getItemNowAllowedFailedMessage(itemID.getName()));
            }
        }
        else
        {
            PlayerUtilities.printToClientConsole(sender, BankSystemTextMessages.getInvalidItemIDMessage("null"));
        }
    }


}
