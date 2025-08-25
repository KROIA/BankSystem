package net.kroia.banksystem.networking.packet.client_sender.update.entity;

import net.kroia.banksystem.entity.custom.BankDownloadBlockEntity;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

public class UpdateBankDownloadBlockEntityPacket extends BankSystemNetworkPacket {

    BlockPos pos;
    List<BankDownloadBlockEntity.WithdrawOrder> withdrawOrders;
    int accountNr;

    public UpdateBankDownloadBlockEntityPacket(BlockPos pos, List<BankDownloadBlockEntity.WithdrawOrder> withdrawOrders, int accountNr) {
        this.pos = pos;
        this.withdrawOrders = withdrawOrders;
        this.accountNr = accountNr;
    }

    public UpdateBankDownloadBlockEntityPacket(FriendlyByteBuf buf) {
        super(buf);
    }

    public static void sendPacket(BlockPos pos, List<BankDownloadBlockEntity.WithdrawOrder> withdrawOrders, int accountNr) {
        new UpdateBankDownloadBlockEntityPacket(pos, withdrawOrders, accountNr).sendToServer();
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeInt(withdrawOrders.size());
        for (BankDownloadBlockEntity.WithdrawOrder order : withdrawOrders) {
            order.encode(buf);
        }
        buf.writeInt(accountNr);
    }

    @Override
    public void decode(FriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        int size = buf.readInt();
        withdrawOrders = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            BankDownloadBlockEntity.WithdrawOrder order = BankDownloadBlockEntity.WithdrawOrder.decode(buf);
            if(order != null)
                withdrawOrders.add(order);
        }
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
    public List<BankDownloadBlockEntity.WithdrawOrder> getWithdrawOrders() {
        return withdrawOrders;
    }
    public int getAccountNr() {
        return accountNr;
    }
}
