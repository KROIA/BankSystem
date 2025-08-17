package net.kroia.banksystem.networking.packet.client_sender.update.entity;

import net.kroia.banksystem.entity.custom.BankDownloadBlockEntity;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

public class UpdateBankDownloadBlockEntityPacket extends BankSystemNetworkPacket {

    BlockPos pos;
    boolean isOwned;
    @Nullable ItemID itemID;
    int targetAmount;
    int accountNr;

    public UpdateBankDownloadBlockEntityPacket(BlockPos pos, boolean isOwned, ItemID itemID, int targetAmount, int accountNr) {
        this.pos = pos;
        this.isOwned = isOwned;
        this.itemID = itemID;
        this.targetAmount = targetAmount;
        this.accountNr = accountNr;
    }

    public UpdateBankDownloadBlockEntityPacket(FriendlyByteBuf buf) {
        super(buf);
    }

    public static void sendPacket(BlockPos pos, boolean isOwned, ItemID itemID, int targetAmount, int accountNr) {
        new UpdateBankDownloadBlockEntityPacket(pos, isOwned, itemID, targetAmount, accountNr).sendToServer();
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeBoolean(isOwned);
        buf.writeBoolean(itemID != null);
        if(itemID != null)
            buf.writeItem(itemID.getStack());
        buf.writeInt(targetAmount);
        buf.writeInt(accountNr);
    }

    @Override
    public void decode(FriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        isOwned = buf.readBoolean();
        itemID = null;
        if(buf.readBoolean())
        {
            itemID = new ItemID(buf.readItem());
        }

        targetAmount = buf.readInt();
        accountNr = buf.readInt();
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
    public @Nullable ItemID getItemID() {
        return itemID;
    }
    public int getTargetAmount() {
        return targetAmount;
    }
    public int getAccountNr() {
        return accountNr;
    }
}
