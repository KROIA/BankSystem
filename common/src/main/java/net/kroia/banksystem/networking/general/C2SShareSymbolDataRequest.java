package net.kroia.banksystem.networking.general;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.ShareSymbolStore;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Task #54 (v2.1.0) — C2S: client requests up to 8 symbol PNGs by SHA-256 hash.
 * Server locates each symbol in the {@link ShareSymbolStore}, chunks the bytes, and
 * replies with {@link S2CShareSymbolDataPacket} frames.
 * <p>
 * Rate-limit: one request per player per 2 seconds. Slave servers serve directly from
 * their mirror; no master routing needed ({@link #needsRoutingToMaster()} returns {@code false}).
 */
public class C2SShareSymbolDataRequest extends BankSystemNetworkPacket {

    public static final Type<C2SShareSymbolDataRequest> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "c2s_share_symbol_data_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SShareSymbolDataRequest> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeVarInt(p.hashes.size());
                        for (byte[] h : p.hashes) buf.writeBytes(h); // 32 bytes each
                    },
                    buf -> {
                        int count = Math.min(buf.readVarInt(), 8);
                        List<byte[]> hashes = new ArrayList<>(count);
                        for (int i = 0; i < count; i++) {
                            byte[] h = new byte[32];
                            buf.readBytes(h);
                            hashes.add(h);
                        }
                        return new C2SShareSymbolDataRequest(hashes);
                    });

    private static final int CHUNK_SIZE = 65536;
    /** Rate-limit map: player UUID → last-request timestamp (ms). */
    private static final Map<UUID, Long> lastRequestTime = new ConcurrentHashMap<>();

    private final List<byte[]> hashes;

    public C2SShareSymbolDataRequest(List<byte[]> hashes) {
        this.hashes = hashes;
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    // Slave mirrors symbol bytes; no master routing needed.
    @Override
    protected boolean needsRoutingToMaster() { return false; }

    @Override
    protected void handleOnServer(ServerPlayer player) {
        if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.SHARE_SYMBOL_STORE == null) return;

        UUID uuid = player.getUUID();
        long now = System.currentTimeMillis();
        Long last = lastRequestTime.get(uuid);
        if (last != null && (now - last) < 2000L) {
            debug("[C2SShareSymbolDataRequest] rate-limited player " + uuid);
            return;
        }
        lastRequestTime.put(uuid, now);

        ShareSymbolStore store = BACKEND_INSTANCES.SHARE_SYMBOL_STORE;
        for (byte[] requestedHash : hashes) {
            for (ShareSymbolStore.SymbolEntry entry : store.getEntries()) {
                if (Arrays.equals(entry.sha256(), requestedHash)) {
                    byte[] bytes = store.getSymbolBytes(entry.id());
                    if (bytes == null) break;
                    int chunkCount = (bytes.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
                    for (int ci = 0; ci < chunkCount; ci++) {
                        int start = ci * CHUNK_SIZE;
                        int end = Math.min(start + CHUNK_SIZE, bytes.length);
                        byte[] chunk = Arrays.copyOfRange(bytes, start, end);
                        S2CShareSymbolDataPacket.sendChunkTo(player, entry.sha256(), bytes.length, ci, chunkCount, chunk);
                    }
                    break;
                }
            }
        }
    }

    /** Client-side: request PNG bytes for the given SHA-256 hashes (max 8). */
    public static void send(List<byte[]> hashes) {
        if (hashes == null || hashes.isEmpty()) return;
        if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.NETWORKING == null) return;
        new C2SShareSymbolDataRequest(hashes).sendToServer();
    }
}
