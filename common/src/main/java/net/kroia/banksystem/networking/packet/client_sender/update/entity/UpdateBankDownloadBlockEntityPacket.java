package net.kroia.banksystem.networking.packet.client_sender.update.entity;

import net.kroia.banksystem.entity.custom.BankDownloadBlockEntity;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.NetworkPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

public class UpdateBankDownloadBlockEntityPacket extends NetworkPacket {

    BlockPos pos;
    boolean isOwned;
    ItemID itemID;
    int targetAmount;
    public UpdateBankDownloadBlockEntityPacket(BlockPos pos, boolean isOwned, ItemID itemID, int targetAmount) {
        this.pos = pos;
        this.isOwned = isOwned;
        this.itemID = itemID;
        this.targetAmount = targetAmount;
    }

    public UpdateBankDownloadBlockEntityPacket(FriendlyByteBuf buf) {
        super(buf);
    }

    public static void sendPacket(BlockPos pos, boolean isOwned, ItemID itemID, int targetAmount) {
        BankSystemNetworking.sendToServer(new UpdateBankDownloadBlockEntityPacket(pos, isOwned, itemID, targetAmount));
    }

    @Override
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeBoolean(isOwned);
        buf.writeItem(itemID.getStack());
        buf.writeInt(targetAmount);
    }

    @Override
    public void fromBytes(FriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        isOwned = buf.readBoolean();
        itemID = new ItemID(buf.readItem());
        targetAmount = buf.readInt();
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
    public ItemID getItemID() {
        return itemID;
    }
    public int getTargetAmount() {
        return targetAmount;
    }
}
