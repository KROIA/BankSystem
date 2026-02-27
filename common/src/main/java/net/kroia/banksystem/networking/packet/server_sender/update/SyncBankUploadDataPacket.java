package net.kroia.banksystem.networking.packet.server_sender.update;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.entity.custom.BankUploadBlockEntity;
import net.kroia.banksystem.screen.custom.BankUploadScreen;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class SyncBankUploadDataPacket extends BankSystemNetworkPacket {

    public static final Type<SyncBankUploadDataPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "sync_bank_upload_data_packet"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncBankUploadDataPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, p -> p.isOwned,
            ByteBufCodecs.BOOL, p -> p.dropIfNotBankable,
            ByteBufCodecs.INT, p -> p.bankAccountNumber,
            SyncBankUploadDataPacket::new
    );

    boolean isOwned;
    boolean dropIfNotBankable;
    int bankAccountNumber;
    public SyncBankUploadDataPacket(boolean isOwned, boolean dropIfNotBankable, int bankAccountNumber) {
        this.isOwned = isOwned;
        this.dropIfNotBankable = dropIfNotBankable;
        this.bankAccountNumber = bankAccountNumber;
    }

    /*public SyncBankUploadDataPacket(RegistryFriendlyByteBuf buf) {
        super(buf);
    }*/

    public static void sendPacket(ServerPlayer receiver, BankUploadBlockEntity blockEntity) {
        UUID playerOwner = blockEntity.getPlayerOwner();
        boolean dropIfNotBankable = blockEntity.doesDropIfNotBankable();
        boolean isOwned = playerOwner != null && playerOwner.equals(receiver.getUUID());
        int bankAccountNumber = blockEntity.getBankAccountNumber();
        new SyncBankUploadDataPacket(isOwned, dropIfNotBankable, bankAccountNumber).sendToClient(receiver);
    }

/*
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
    }*/


    @Override
    protected void handleClient(NetworkManager.PacketContext context) {
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

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
