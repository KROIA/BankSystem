package net.kroia.banksystem.networking.packet.server_sender.update;

import dev.architectury.networking.simple.MessageType;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.entity.custom.BankDownloadBlockEntity;
import net.kroia.banksystem.screen.custom.BankDownloadScreen;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

public class SyncBankDownloadDataPacket extends BankSystemNetworkPacket {

    public static final Type<SyncBankDownloadDataPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "SyncBankDownloadDataPacket"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncBankDownloadDataPacket> STREAM_CODEC = StreamCodec.composite(
            ExtraCodecUtils.listStreamCodec(BankDownloadBlockEntity.WithdrawOrder.STREAM_CODEC), p -> p.withdrawOrders,
            ByteBufCodecs.INT, p -> p.blockInventorySlotCount,
            ByteBufCodecs.INT, p -> p.accountNr,
            SyncBankDownloadDataPacket::new
    );


    List<BankDownloadBlockEntity.WithdrawOrder> withdrawOrders;
    int blockInventorySlotCount;
    int accountNr;

    public SyncBankDownloadDataPacket(List<BankDownloadBlockEntity.WithdrawOrder> withdrawOrders, int blockInventorySlotCount, int accountNr) {
        super();
        this.withdrawOrders = withdrawOrders;
        this.blockInventorySlotCount = blockInventorySlotCount;
        this.accountNr = accountNr;
    }

    /*public SyncBankDownloadDataPacket(RegistryFriendlyByteBuf buf) {
        super(buf);
    }*/

    public static void sendPacket(ServerPlayer receiver, BankDownloadBlockEntity blockEntity) {
        List<BankDownloadBlockEntity.WithdrawOrder> withdrawOrders = blockEntity.getWithdrawOrders();
        int blockInventorySlotCount = blockEntity.getBlockInventorySlotCount();
        int accountNr = blockEntity.getBankAccountNumber();
        new SyncBankDownloadDataPacket(withdrawOrders, blockInventorySlotCount, accountNr).sendToClient(receiver);
    }

    //public void handle()


    /*@Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(withdrawOrders.size());
        for (BankDownloadBlockEntity.WithdrawOrder order : withdrawOrders) {
            order.encode(buf);
        }
        buf.writeInt(blockInventorySlotCount);
        buf.writeInt(accountNr);
    }

    @Override
    public void decode(FriendlyByteBuf buf) {
        int size = buf.readInt();
        withdrawOrders = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            BankDownloadBlockEntity.WithdrawOrder order = BankDownloadBlockEntity.WithdrawOrder.decode(buf);
            if(order != null)
                withdrawOrders.add(order);
        }
        blockInventorySlotCount = buf.readInt();
        accountNr = buf.readInt();
    }*/

    protected void handleOnClient() {
        BankDownloadScreen.handlePacket(this);
    }


    public List<BankDownloadBlockEntity.WithdrawOrder> getWithdrawOrders() {
        return withdrawOrders;
    }
    public int getBlockInventorySlotCount() {
        return blockInventorySlotCount;
    }
    public int getAccountNr() {
        return accountNr;
    }
}
