package net.kroia.banksystem.networking.entity;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.minecraft.entity.custom.BankDownloadBlockEntity;
import net.kroia.banksystem.screen.custom.BankDownloadScreen;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class SyncBankDownloadDataPacket extends BankSystemNetworkPacket {

    public static final Type<SyncBankDownloadDataPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "sync_bank_download_data_packet"));

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

    @Override
    protected boolean needsRoutingToMaster() {
        return false;
    }


    public static void sendPacket(ServerPlayer receiver, BankDownloadBlockEntity blockEntity) {
        List<BankDownloadBlockEntity.WithdrawOrder> withdrawOrders = blockEntity.getWithdrawOrders();
        int blockInventorySlotCount = blockEntity.getBlockInventorySlotCount();
        int accountNr = blockEntity.getBankAccountNumber();
        new SyncBankDownloadDataPacket(withdrawOrders, blockInventorySlotCount, accountNr).sendToClient(receiver);
    }


    @Override
    protected void handleOnClient(NetworkManager.PacketContext context) {
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

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
