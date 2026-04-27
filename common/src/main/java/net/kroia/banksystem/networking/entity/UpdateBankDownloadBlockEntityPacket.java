package net.kroia.banksystem.networking.entity;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.minecraft.entity.custom.BankDownloadBlockEntity;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class UpdateBankDownloadBlockEntityPacket extends BankSystemNetworkPacket {

    public static final Type<UpdateBankDownloadBlockEntityPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "update_bank_download_block_entity_packet"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateBankDownloadBlockEntityPacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, p -> p.pos,
            ExtraCodecUtils.listStreamCodec(BankDownloadBlockEntity.WithdrawOrder.STREAM_CODEC), p -> p.withdrawOrders,
            ByteBufCodecs.INT, p -> p.accountNr,
            UpdateBankDownloadBlockEntityPacket::new
    );

    BlockPos pos;
    List<BankDownloadBlockEntity.WithdrawOrder> withdrawOrders;
    int accountNr;

    public UpdateBankDownloadBlockEntityPacket(BlockPos pos, List<BankDownloadBlockEntity.WithdrawOrder> withdrawOrders, int accountNr) {
        this.pos = pos;
        this.withdrawOrders = withdrawOrders;
        this.accountNr = accountNr;
    }
    @Override
    protected boolean needsRoutingToMaster() {
        return false;
    }

    public static void sendPacket(BlockPos pos, List<BankDownloadBlockEntity.WithdrawOrder> withdrawOrders, int accountNr) {
        new UpdateBankDownloadBlockEntityPacket(pos, withdrawOrders, accountNr).sendToServer();
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

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
