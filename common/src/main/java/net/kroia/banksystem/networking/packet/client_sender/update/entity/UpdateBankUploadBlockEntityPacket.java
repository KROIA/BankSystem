package net.kroia.banksystem.networking.packet.client_sender.update.entity;

import dev.architectury.networking.simple.MessageType;
import net.kroia.banksystem.entity.custom.BankUploadBlockEntity;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.modutilities.networking.NetworkPacketC2S;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.UUID;

public class UpdateBankUploadBlockEntityPacket extends NetworkPacketC2S {

    BlockPos pos;
    boolean isOwned;
    boolean dropIfNotBankable;

    @Override
    public MessageType getType() {
        return BankSystemNetworking.UPDATE_BANK_UPLOAD_BLOCK_ENTITY;
    }
    public UpdateBankUploadBlockEntityPacket(BlockPos pos, boolean isOwned, boolean dropIfNotBankable) {
        this.pos = pos;
        this.isOwned = isOwned;
        this.dropIfNotBankable = dropIfNotBankable;
    }

    public UpdateBankUploadBlockEntityPacket(RegistryFriendlyByteBuf buf) {
        super(buf);
    }

    public static void sendPacket(BlockPos pos, boolean isOwned, boolean dropIfNotBankable) {
        new UpdateBankUploadBlockEntityPacket(pos, isOwned, dropIfNotBankable).sendToServer();
    }

    @Override
    public void toBytes(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeBoolean(isOwned);
        buf.writeBoolean(dropIfNotBankable);
    }

    @Override
    public void fromBytes(RegistryFriendlyByteBuf buf) {
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
