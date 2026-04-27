package net.kroia.banksystem.networking.entity;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.minecraft.entity.custom.BankUploadBlockEntity;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;

public class UpdateBankUploadBlockEntityPacket extends BankSystemNetworkPacket {

    public static final Type<UpdateBankUploadBlockEntityPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "update_bank_upload_block_entity_packet"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateBankUploadBlockEntityPacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, p -> p.pos,
            ByteBufCodecs.BOOL, p -> p.isOwned,
            ByteBufCodecs.BOOL, p -> p.dropIfNotBankable,
            ByteBufCodecs.INT, p -> p.bankAccountNumber,
            UpdateBankUploadBlockEntityPacket::new
    );

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
    @Override
    protected boolean needsRoutingToMaster() {
        return false;
    }

    public static void sendPacket(BlockPos pos, boolean isOwned, boolean dropIfNotBankable, int bankAccountNumber) {
        new UpdateBankUploadBlockEntityPacket(pos, isOwned, dropIfNotBankable, bankAccountNumber).sendToServer();
    }

    @Override
    protected void handleOnServer(NetworkManager.PacketContext context) {
        BlockEntity blockEntity = context.getPlayer().level().getBlockEntity(pos);
        if (blockEntity instanceof BankUploadBlockEntity be) {
            be.handlePacket((ServerPlayer) context.getPlayer(),this);
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

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
