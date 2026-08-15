package net.kroia.banksystem.networking.multi_server;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.ShareSymbolStore;
import net.kroia.banksystem.networking.general.S2CShareSymbolManifestPacket;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.modutilities.networking.multi_server.ForwardPacketContext;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Task #54 (v2.1.0) — master→slave S2S packet mirroring the share symbol manifest.
 * Slave applies the manifest via {@link ShareSymbolStore#mirrorApplyManifest} and
 * fans out {@link S2CShareSymbolManifestPacket} to all locally-connected players.
 */
public class S2SShareSymbolManifestPacket extends BankSystemNetworkPacket {

    public static final Type<S2SShareSymbolManifestPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "s2s_share_symbol_manifest"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2SShareSymbolManifestPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeVarInt(p.revision);
                        buf.writeVarInt(p.entries.size());
                        for (ShareSymbolStore.SymbolEntry e : p.entries) {
                            buf.writeUtf(e.id());
                            buf.writeVarInt(e.ordinal());
                            buf.writeBytes(e.sha256()); // always 32 bytes
                            buf.writeVarInt(e.size());
                        }
                    },
                    buf -> {
                        int revision = buf.readVarInt();
                        int count = buf.readVarInt();
                        List<ShareSymbolStore.SymbolEntry> entries = new ArrayList<>(count);
                        for (int i = 0; i < count; i++) {
                            String id = buf.readUtf();
                            int ordinal = buf.readVarInt();
                            byte[] sha256 = new byte[32];
                            buf.readBytes(sha256);
                            int size = buf.readVarInt();
                            entries.add(new ShareSymbolStore.SymbolEntry(id, ordinal, sha256, size));
                        }
                        return new S2SShareSymbolManifestPacket(revision, entries);
                    });

    private final int revision;
    private final List<ShareSymbolStore.SymbolEntry> entries;

    public S2SShareSymbolManifestPacket(int revision, List<ShareSymbolStore.SymbolEntry> entries) {
        this.revision = revision;
        this.entries = entries;
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    @Override
    protected void handleOnSlave(ForwardPacketContext context) {
        if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.SHARE_SYMBOL_STORE == null) return;
        BACKEND_INSTANCES.SHARE_SYMBOL_STORE.mirrorApplyManifest(revision, entries);
        // Fan-out manifest to locally-connected clients so they can request missing bytes.
        MinecraftServer server = net.kroia.modutilities.UtilitiesPlatform.getServer();
        if (server != null) {
            S2CShareSymbolManifestPacket.broadcastToAll(server, BACKEND_INSTANCES.SHARE_SYMBOL_STORE);
        }
    }

    /** Broadcast the current manifest to all connected slave servers (call from master on revision bump). */
    public static void broadcastToSlaves(ShareSymbolStore store) {
        if (store == null) return;
        new S2SShareSymbolManifestPacket(store.getRevision(), store.getEntries()).broadcastToSlaves();
    }
}
