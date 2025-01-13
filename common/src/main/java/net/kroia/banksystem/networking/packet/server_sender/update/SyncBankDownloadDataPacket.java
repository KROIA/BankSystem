package net.kroia.banksystem.networking.packet.server_sender.update;

import net.kroia.banksystem.entity.custom.BankDownloadBlockEntity;
import net.kroia.banksystem.entity.custom.BankUploadBlockEntity;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.screen.custom.BankDownloadScreen;
import net.kroia.banksystem.screen.custom.BankUploadScreen;
import net.kroia.modutilities.networking.NetworkPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class SyncBankDownloadDataPacket extends NetworkPacket {

    boolean isOwned;
    String itemID;
    int targetAmount;
    int maxTargetAmount;
    public SyncBankDownloadDataPacket(boolean isOwned, String itemID, int targetAmount, int maxTargetAmount) {
        this.isOwned = isOwned;
        this.itemID = itemID;
        this.targetAmount = targetAmount;
        this.maxTargetAmount = maxTargetAmount;
    }

    public SyncBankDownloadDataPacket(FriendlyByteBuf buf) {
        super(buf);
    }

    public static void sendPacket(ServerPlayer receiver, BankDownloadBlockEntity blockEntity) {
        UUID playerOwner = blockEntity.getPlayerOwner();
        String itemID = blockEntity.getItemID();
        int targetAmount = blockEntity.getTargetAmount();
        int maxTargetAmount = blockEntity.getMaxTargetAmount();
        boolean isOwned = playerOwner != null && playerOwner.equals(receiver.getUUID());
        BankSystemNetworking.sendToClient(receiver, new SyncBankDownloadDataPacket(isOwned, itemID, targetAmount, maxTargetAmount));
    }


    @Override
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeBoolean(isOwned);
        if(itemID == null)
            itemID = "";
        buf.writeUtf(itemID);
        buf.writeInt(targetAmount);
        buf.writeInt(maxTargetAmount);
    }

    @Override
    public void fromBytes(FriendlyByteBuf buf) {
        isOwned = buf.readBoolean();
        itemID = buf.readUtf();
        if(itemID.isEmpty())
            itemID = null;
        targetAmount = buf.readInt();
        maxTargetAmount = buf.readInt();
    }

    protected void handleOnClient() {
        BankDownloadScreen.handlePacket(this);
    }

    public boolean isOwned() {
        return isOwned;
    }
    public String getItemID() {
        return itemID;
    }
    public int getTargetAmount() {
        return targetAmount;
    }
    public int getMaxTargetAmount() {
        return maxTargetAmount;
    }
}
