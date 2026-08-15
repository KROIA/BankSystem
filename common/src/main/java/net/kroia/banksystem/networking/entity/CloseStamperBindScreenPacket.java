package net.kroia.banksystem.networking.entity;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.minecraft.entity.custom.ShareStamperBlockEntity;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;

/**
 * Task #47 (v2.1.0) — C2S packet: release the Share Stamper viewer lock when the
 * {@link net.kroia.banksystem.screen.custom.StamperBindScreen} closes. Container-menu
 * closes hit {@link ShareStamperBlockEntity#stopOpen} instead; this packet exists
 * because the bind screen is not a container menu.
 */
public class CloseStamperBindScreenPacket extends BankSystemNetworkPacket {

    public static final Type<CloseStamperBindScreenPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "close_stamper_bind_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CloseStamperBindScreenPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, p -> p.pos,
                    CloseStamperBindScreenPacket::new);

    final BlockPos pos;

    public CloseStamperBindScreenPacket(BlockPos pos) { this.pos = pos; }

    public static void send(BlockPos pos) { new CloseStamperBindScreenPacket(pos).sendToServer(); }

    @Override
    protected boolean needsRoutingToMaster() { return false; }

    @Override
    protected void handleOnServer(NetworkManager.PacketContext context) {
        ServerPlayer player = (ServerPlayer) context.getPlayer();
        BlockEntity be = player.level().getBlockEntity(pos);
        if (be instanceof ShareStamperBlockEntity stamper) stamper.releaseViewer(player.getUUID());
    }

    @Override public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
