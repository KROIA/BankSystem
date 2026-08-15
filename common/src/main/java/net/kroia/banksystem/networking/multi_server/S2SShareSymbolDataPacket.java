package net.kroia.banksystem.networking.multi_server;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.ShareSymbolStore;
import net.kroia.banksystem.networking.general.S2CShareSymbolDataPacket;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.modutilities.networking.multi_server.ForwardPacketContext;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Task #54 (v2.1.0) — master→slave S2S packet carrying one chunk of a symbol PNG.
 * <p>
 * Slave reassembles chunks, verifies SHA-256, writes to the slave's
 * {@link ShareSymbolStore} mirror via {@link ShareSymbolStore#mirrorWrite}, then
 * fans out {@link S2CShareSymbolDataPacket} chunks to all locally-connected players.
 */
public class S2SShareSymbolDataPacket extends BankSystemNetworkPacket {

    public static final Type<S2SShareSymbolDataPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "s2s_share_symbol_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2SShareSymbolDataPacket> STREAM_CODEC =
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
                        return new S2SShareSymbolDataPacket(sha256, totalSize, chunkIndex, chunkCount, chunkBytes);
                    });

    // Slave-side reassembly state (server thread — no concurrent access expected)
    private static final Map<String, byte[]> pendingBuffers = new HashMap<>();
    private static final Map<String, Set<Integer>> receivedChunkSets = new HashMap<>();

    private final byte[] sha256;
    private final int totalSize;
    private final int chunkIndex;
    private final int chunkCount;
    private final byte[] chunkBytes;

    public S2SShareSymbolDataPacket(byte[] sha256, int totalSize, int chunkIndex, int chunkCount, byte[] chunkBytes) {
        this.sha256 = sha256;
        this.totalSize = totalSize;
        this.chunkIndex = chunkIndex;
        this.chunkCount = chunkCount;
        this.chunkBytes = chunkBytes;
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    @Override
    protected void handleOnSlave(ForwardPacketContext context) {
        if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.SHARE_SYMBOL_STORE == null) return;

        String hex = toHex(sha256);
        byte[] buf = pendingBuffers.computeIfAbsent(hex, k -> new byte[totalSize]);
        int offset = chunkIndex * 65536;
        System.arraycopy(chunkBytes, 0, buf, offset, chunkBytes.length);
        receivedChunkSets.computeIfAbsent(hex, k -> new HashSet<>()).add(chunkIndex);

        if (receivedChunkSets.get(hex).size() == chunkCount) {
            byte[] assembled = buf;
            pendingBuffers.remove(hex);
            receivedChunkSets.remove(hex);
            finalizeOnSlave(hex, assembled);
        }
    }

    private void finalizeOnSlave(String hex, byte[] bytes) {
        if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.SHARE_SYMBOL_STORE == null) return;
        ShareSymbolStore store = BACKEND_INSTANCES.SHARE_SYMBOL_STORE;
        // Identify which entry owns this hash
        String id = null;
        int ordinal = 0;
        for (ShareSymbolStore.SymbolEntry e : store.getEntries()) {
            if (Arrays.equals(e.sha256(), sha256)) {
                id = e.id();
                ordinal = e.ordinal();
                break;
            }
        }
        if (id == null) {
            debug("[S2SShareSymbolDataPacket] no manifest entry for hash " + hex.substring(0, 8) + "...");
            return;
        }
        String err = store.mirrorWrite(id, ordinal, sha256, bytes);
        if (err != null) {
            warn("[S2SShareSymbolDataPacket] mirrorWrite failed for " + id + ": " + err);
            return;
        }
        // Fan-out chunks to locally-connected clients
        MinecraftServer server = net.kroia.modutilities.UtilitiesPlatform.getServer();
        if (server != null && BACKEND_INSTANCES.NETWORKING != null) {
            int chunkCountFanout = (bytes.length + 65536 - 1) / 65536;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                for (int ci = 0; ci < chunkCountFanout; ci++) {
                    int start = ci * 65536;
                    int end = Math.min(start + 65536, bytes.length);
                    byte[] chunk = Arrays.copyOfRange(bytes, start, end);
                    S2CShareSymbolDataPacket.sendChunkTo(player, sha256, bytes.length, ci, chunkCountFanout, chunk);
                }
            }
        }
    }

    /** Master-side: broadcast all chunks of a symbol PNG to every connected slave. */
    public static void broadcastChunksToSlaves(byte[] sha256, byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        int chunkCount = (bytes.length + 65536 - 1) / 65536;
        for (int ci = 0; ci < chunkCount; ci++) {
            int start = ci * 65536;
            int end = Math.min(start + 65536, bytes.length);
            byte[] chunk = Arrays.copyOfRange(bytes, start, end);
            new S2SShareSymbolDataPacket(sha256, bytes.length, ci, chunkCount, chunk).broadcastToSlaves();
        }
    }

    /** Master-side: send all chunks of a symbol PNG to a specific slave (used by PULL_SYMBOL_BYTES). */
    public static void sendChunksToSlave(String slaveID, byte[] sha256, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || slaveID == null || slaveID.isEmpty()) return;
        int chunkCount = (bytes.length + 65536 - 1) / 65536;
        for (int ci = 0; ci < chunkCount; ci++) {
            int start = ci * 65536;
            int end = Math.min(start + 65536, bytes.length);
            byte[] chunk = Arrays.copyOfRange(bytes, start, end);
            new S2SShareSymbolDataPacket(sha256, bytes.length, ci, chunkCount, chunk).sendToSlave(slaveID);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
