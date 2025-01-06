package net.kroia.banksystem.networking.packet.client_sender.request;

import dev.architectury.networking.simple.MessageType;
import net.kroia.banksystem.banking.ServerBankManager;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDataPacket;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.PlayerUtilities;
import net.kroia.modutilities.networking.NetworkPacketC2S;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class RequestAllowNewBankItemIDPacket extends NetworkPacketC2S {
    private String itemID;

    @Override
    public MessageType getType() {
        return BankSystemNetworking.REQUEST_ALLOW_NEW_BANK_ITEM_ID;
    }
    public static void sendRequest(String itemID)
    {
        RequestAllowNewBankItemIDPacket packet = new RequestAllowNewBankItemIDPacket(itemID);
        packet.sendToServer();
    }

    public RequestAllowNewBankItemIDPacket(String itemID) {
        this.itemID = itemID;
    }
    public RequestAllowNewBankItemIDPacket(RegistryFriendlyByteBuf buf)
    {
        this.fromBytes(buf);
    }
    @Override
    public void toBytes(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(itemID);
    }

    @Override
    public void fromBytes(RegistryFriendlyByteBuf buf)
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
