package net.kroia.banksystem.networking.packet.server_sender.update;

import net.kroia.banksystem.entity.custom.BankUploadBlockEntity;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.screen.custom.BankUploadScreen;
import net.kroia.modutilities.networking.NetworkPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class SyncBankUploadDataPacket extends NetworkPacket {

    boolean isOwned;
    boolean dropIfNotBankable;
    public SyncBankUploadDataPacket(boolean isOwned, boolean dropIfNotBankable) {
        this.isOwned = isOwned;
        this.dropIfNotBankable = dropIfNotBankable;
    }

    public SyncBankUploadDataPacket(FriendlyByteBuf buf) {
        super(buf);
    }

    public static void sendPacket(ServerPlayer receiver, BankUploadBlockEntity blockEntity) {
        UUID playerOwner = blockEntity.getPlayerOwner();
        boolean dropIfNotBankable = blockEntity.doesDropIfNotBankable();
        boolean isOwned = playerOwner != null && playerOwner.equals(receiver.getUUID());
        BankSystemNetworking.sendToClient(receiver, new SyncBankUploadDataPacket(isOwned, dropIfNotBankable));
    }


    @Override
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeBoolean(isOwned);
        buf.writeBoolean(dropIfNotBankable);
    }

    @Override
    public void fromBytes(FriendlyByteBuf buf) {
        isOwned = buf.readBoolean();
        dropIfNotBankable = buf.readBoolean();
    }

    protected void handleOnClient() {
        BankUploadScreen.handlePacket(this);
    }

    public boolean isOwned() {
        return isOwned;
    }
    public boolean doesDropIfNotBankable() {
        return dropIfNotBankable;
    }
}
