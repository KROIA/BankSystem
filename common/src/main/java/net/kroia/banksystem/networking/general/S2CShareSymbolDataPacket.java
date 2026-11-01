package net.kroia.banksystem.networking.general;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.client.company.ClientSymbolRegistry;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * Task #54 (v2.1.0) — S2C packet carrying one chunk of a symbol PNG.
 * Reassembly and SHA-256 verification happen in {@link ClientSymbolRegistry}.
 */
public class S2CShareSymbolDataPacket extends BankSystemNetworkPacket {

    public static final Type<S2CShareSymbolDataPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "s2c_share_symbol_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CShareSymbolDataPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBytes(p.sha256); // 32 bytes
                        buf.writeVarInt(p.totalSize);
                        buf.writeVarInt(p.chunkIndex);
                        buf.writeVarInt(p.chunkCount);
                        buf.writeVarInt(p.chunkBytes.length);
                        buf.writeBytes(p.chunkBytes);
                    },
                    buf -> {
                        byte[] sha256 = new byte[32];
                        buf.readBytes(sha256);
                        int totalSize = buf.readVarInt();
                        int chunkIndex = buf.readVarInt();
                        int chunkCount = buf.readVarInt();
                        int len = buf.readVarInt();
                        byte[] chunkBytes = new byte[len];
                        buf.readBytes(chunkBytes);
                        return new S2CShareSymbolDataPacket(sha256, totalSize, chunkIndex, chunkCount, chunkBytes);
                    });

    private final byte[] sha256;
    private final int totalSize;
    private final int chunkIndex;
    private final int chunkCount;
    private final byte[] chunkBytes;

    public S2CShareSymbolDataPacket(byte[] sha256, int totalSize, int chunkIndex, int chunkCount, byte[] chunkBytes) {
        this.sha256 = sha256;
        this.totalSize = totalSize;
        this.chunkIndex = chunkIndex;
        this.chunkCount = chunkCount;
        this.chunkBytes = chunkBytes;
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    @Override
    public void handleOnClient(NetworkManager.PacketContext context) {
        ClientSymbolRegistry.handleDataChunk(sha256, totalSize, chunkIndex, chunkCount, chunkBytes);
    }

    /** Server-side helper: send one chunk of a symbol PNG to a player. */
    public static void sendChunkTo(ServerPlayer player, byte[] sha256, int totalSize,
                                   int chunkIndex, int chunkCount, byte[] chunkBytes) {
        new S2CShareSymbolDataPacket(sha256, totalSize, chunkIndex, chunkCount, chunkBytes)
                .sendToClient(player);
    }
}
