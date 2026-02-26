package net.kroia.banksystem.networking.packet.client_sender.update.entity;

import dev.architectury.networking.simple.MessageType;
import net.kroia.banksystem.entity.custom.BankUploadBlockEntity;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

public class UpdateBankUploadBlockEntityPacket extends BankSystemNetworkPacket {

    BlockPos pos;
    boolean isOwned;
    boolean dropIfNotBankable;
    int bankAccountNumber;
    public UpdateBankUploadBlockEntityPacket(BlockPos pos, boolean isOwned, boolean dropIfNotBankable, int bankAccountNumber) {
        this.pos = pos;
        this.isOwned = isOwned;
        this.dropIfNotBankable = dropIfNotBankable;
        this.bankAccountNumber = bankAccountNumber;
    }

    public UpdateBankUploadBlockEntityPacket(RegistryFriendlyByteBuf buf) {
        super(buf);
    }

    public static void sendPacket(BlockPos pos, boolean isOwned, boolean dropIfNotBankable, int bankAccountNumber) {
        new UpdateBankUploadBlockEntityPacket(pos, isOwned, dropIfNotBankable, bankAccountNumber).sendToServer();
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeBoolean(isOwned);
        buf.writeBoolean(dropIfNotBankable);
        buf.writeInt(bankAccountNumber);
    }

    @Override
    public void decode(FriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        isOwned = buf.readBoolean();
        dropIfNotBankable = buf.readBoolean();
        bankAccountNumber = buf.readInt();
    }

    @Override
    protected void handleOnServer(ServerPlayer sender) {
        BlockEntity blockEntity = sender.level().getBlockEntity(pos);
        if (blockEntity instanceof BankUploadBlockEntity be) {
            be.handlePacket(sender,this);
        }
    }

    public BlockPos getPos() {
        return pos;
    }
    public boolean isOwned() {
        return isOwned;
    }
    public boolean isDropIfNotBankable() {
        return dropIfNotBankable;
    }
    public int getBankAccountNumber() {
        return bankAccountNumber;
    }
}
