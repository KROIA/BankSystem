package net.kroia.banksystem.networking.packet.client_sender.update.entity;

import net.kroia.banksystem.entity.custom.BankUploadBlockEntity;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.modutilities.networking.NetworkPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.UUID;

public class UpdateBankUploadBlockEntityPacket extends NetworkPacket {

    BlockPos pos;
    boolean isOwned;
    boolean dropIfNotBankable;
    public UpdateBankUploadBlockEntityPacket(BlockPos pos, boolean isOwned, boolean dropIfNotBankable) {
        this.pos = pos;
        this.isOwned = isOwned;
        this.dropIfNotBankable = dropIfNotBankable;
    }

    public UpdateBankUploadBlockEntityPacket(FriendlyByteBuf buf) {
        super(buf);
    }

    public static void sendPacket(BlockPos pos, boolean isOwned, boolean dropIfNotBankable) {
        BankSystemNetworking.sendToServer(new UpdateBankUploadBlockEntityPacket(pos, isOwned, dropIfNotBankable));
    }

    @Override
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeBoolean(isOwned);
        buf.writeBoolean(dropIfNotBankable);
    }

    @Override
    public void fromBytes(FriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        isOwned = buf.readBoolean();
        dropIfNotBankable = buf.readBoolean();
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
}
