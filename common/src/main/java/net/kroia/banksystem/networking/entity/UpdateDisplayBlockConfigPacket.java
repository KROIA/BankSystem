package net.kroia.banksystem.networking.entity;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.minecraft.entity.custom.BankSystemDisplayBlockEntity;
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

public class UpdateDisplayBlockConfigPacket extends BankSystemNetworkPacket {

    public static final Type<UpdateDisplayBlockConfigPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "update_display_block_config_packet"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateDisplayBlockConfigPacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, p -> p.pos,
            ByteBufCodecs.STRING_UTF8, p -> p.displayType,
            ByteBufCodecs.INT, p -> p.accountNumber,
            UpdateDisplayBlockConfigPacket::new
    );

    private final BlockPos pos;
    private final String displayType;
    private final int accountNumber;

    public UpdateDisplayBlockConfigPacket(BlockPos pos, String displayType, int accountNumber) {
        super();
        this.pos = pos;
        this.displayType = displayType;
        this.accountNumber = accountNumber;
    }

    @Override
    protected boolean needsRoutingToMaster() { return false; }

    public static void sendToServer(BlockPos pos, String displayType, int accountNumber) {
        new UpdateDisplayBlockConfigPacket(pos, displayType, accountNumber).sendToServer();
    }

    @Override
    protected void handleOnServer(NetworkManager.PacketContext context) {
        ServerPlayer player = (ServerPlayer) context.getPlayer();
        if (player.distanceToSqr(this.pos.getX() + 0.5, this.pos.getY() + 0.5, this.pos.getZ() + 0.5) > BankSystemMod.MAX_INTERACT_DISTANCE_SQR)
            return;
        BlockEntity blockEntity = player.level().getBlockEntity(this.pos);
        if (blockEntity instanceof BankSystemDisplayBlockEntity displayEntity) {
            BankSystemDisplayBlockEntity.DisplayType type = BankSystemDisplayBlockEntity.DisplayType.fromId(this.displayType);
            if (type != BankSystemDisplayBlockEntity.DisplayType.NONE && this.accountNumber > 0) {
                displayEntity.setConfig(type, this.accountNumber);
            }
        }
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
