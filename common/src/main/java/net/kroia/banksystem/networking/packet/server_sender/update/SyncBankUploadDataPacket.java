package net.kroia.banksystem.networking.packet.server_sender.update;

import dev.architectury.networking.simple.MessageType;
import net.kroia.banksystem.entity.custom.BankUploadBlockEntity;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.banksystem.screen.custom.BankUploadScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class SyncBankUploadDataPacket extends BankSystemNetworkPacket {

    boolean isOwned;
    boolean dropIfNotBankable;
    int bankAccountNumber;
    public SyncBankUploadDataPacket(boolean isOwned, boolean dropIfNotBankable, int bankAccountNumber) {
        this.isOwned = isOwned;
        this.dropIfNotBankable = dropIfNotBankable;
        this.bankAccountNumber = bankAccountNumber;
    }

    public SyncBankUploadDataPacket(RegistryFriendlyByteBuf buf) {
        super(buf);
    }

    public static void sendPacket(ServerPlayer receiver, BankUploadBlockEntity blockEntity) {
        UUID playerOwner = blockEntity.getPlayerOwner();
        boolean dropIfNotBankable = blockEntity.doesDropIfNotBankable();
        boolean isOwned = playerOwner != null && playerOwner.equals(receiver.getUUID());
        int bankAccountNumber = blockEntity.getBankAccountNumber();
        new SyncBankUploadDataPacket(isOwned, dropIfNotBankable, bankAccountNumber).sendToClient(receiver);
    }


    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(isOwned);
        buf.writeBoolean(dropIfNotBankable);
        buf.writeInt(bankAccountNumber);

    }

    @Override
    public void decode(FriendlyByteBuf buf) {
        isOwned = buf.readBoolean();
        dropIfNotBankable = buf.readBoolean();
        bankAccountNumber = buf.readInt();
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
    public int getBankAccountNumber() {
        return bankAccountNumber;
    }
}
