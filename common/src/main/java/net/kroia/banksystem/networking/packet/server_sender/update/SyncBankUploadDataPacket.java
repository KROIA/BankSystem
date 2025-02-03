package net.kroia.banksystem.networking.packet.server_sender.update;

import dev.architectury.networking.simple.MessageType;
import net.kroia.banksystem.entity.custom.BankUploadBlockEntity;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.screen.custom.BankUploadScreen;
import net.kroia.modutilities.networking.NetworkPacketS2C;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class SyncBankUploadDataPacket extends NetworkPacketS2C {

    boolean isOwned;
    boolean dropIfNotBankable;

    @Override
    public MessageType getType() {
        return BankSystemNetworking.SYNC_BANK_UPLOAD_DATA;
    }
    public SyncBankUploadDataPacket(boolean isOwned, boolean dropIfNotBankable) {
        this.isOwned = isOwned;
        this.dropIfNotBankable = dropIfNotBankable;
    }

    public SyncBankUploadDataPacket(RegistryFriendlyByteBuf buf) {
        super(buf);
    }

    public static void sendPacket(ServerPlayer receiver, BankUploadBlockEntity blockEntity) {
        UUID playerOwner = blockEntity.getPlayerOwner();
        boolean dropIfNotBankable = blockEntity.doesDropIfNotBankable();
        boolean isOwned = playerOwner != null && playerOwner.equals(receiver.getUUID());
        new SyncBankUploadDataPacket(isOwned, dropIfNotBankable).sendTo(receiver);
    }


    @Override
    public void toBytes(RegistryFriendlyByteBuf buf) {
        buf.writeBoolean(isOwned);
        buf.writeBoolean(dropIfNotBankable);
    }

    @Override
    public void fromBytes(RegistryFriendlyByteBuf buf) {
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
