package net.kroia.banksystem.networking.packet.client_sender.update.entity;

import net.kroia.banksystem.entity.custom.BankDownloadBlockEntity;
import net.kroia.banksystem.entity.custom.BankUploadBlockEntity;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.modutilities.networking.NetworkPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

public class UpdateBankDownloadBlockEntityPacket extends NetworkPacket {

    BlockPos pos;
    boolean isOwned;
    String itemID;
    int targetAmount;
    public UpdateBankDownloadBlockEntityPacket(BlockPos pos, boolean isOwned, String itemID, int targetAmount) {
        this.pos = pos;
        this.isOwned = isOwned;
        this.itemID = itemID;
        this.targetAmount = targetAmount;
        if(itemID == null)
            itemID = "";
    }

    public UpdateBankDownloadBlockEntityPacket(FriendlyByteBuf buf) {
        super(buf);
    }

    public static void sendPacket(BlockPos pos, boolean isOwned, String itemID, int targetAmount) {
        BankSystemNetworking.sendToServer(new UpdateBankDownloadBlockEntityPacket(pos, isOwned, itemID, targetAmount));
    }

    @Override
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeBoolean(isOwned);
        if(itemID == null)
            itemID = "";
        buf.writeUtf(itemID);
        buf.writeInt(targetAmount);
    }

    @Override
    public void fromBytes(FriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        isOwned = buf.readBoolean();
        itemID = buf.readUtf();
        targetAmount = buf.readInt();
        if(itemID.isEmpty())
            itemID = null;
    }

    @Override
    protected void handleOnServer(ServerPlayer sender) {
        BlockEntity blockEntity = sender.level().getBlockEntity(pos);
        if (blockEntity instanceof BankDownloadBlockEntity be) {
            be.handlePacket(sender,this);
        }
    }

    public BlockPos getPos() {
        return pos;
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
}
