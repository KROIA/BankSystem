package net.kroia.banksystem.networking.general;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.ShareSymbolStore;
import net.kroia.banksystem.client.company.ClientSymbolRegistry;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Task #54 (v2.1.0) — S2C packet carrying the share symbol manifest
 * (revision + entry list). Sent on player join and on every revision bump.
 * Client handler delegates to {@link ClientSymbolRegistry#handleManifest}.
 */
public class S2CShareSymbolManifestPacket extends BankSystemNetworkPacket {

    public static final Type<S2CShareSymbolManifestPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "s2c_share_symbol_manifest"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CShareSymbolManifestPacket> STREAM_CODEC =
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
                        return new S2CShareSymbolManifestPacket(revision, entries);
                    });

    private final int revision;
    private final List<ShareSymbolStore.SymbolEntry> entries;

    public S2CShareSymbolManifestPacket(int revision, List<ShareSymbolStore.SymbolEntry> entries) {
        this.revision = revision;
        this.entries = entries;
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    @Override
    public void handleOnClient(NetworkManager.PacketContext context) {
        ClientSymbolRegistry.handleManifest(revision, entries);
    }

    /** Send the current store manifest to a single joining player. */
    public static void sendTo(ServerPlayer player, ShareSymbolStore store) {
        if (store == null || BACKEND_INSTANCES == null) return;
        new S2CShareSymbolManifestPacket(store.getRevision(), store.getEntries()).sendToClient(player);
    }

    /** Broadcast the current store manifest to all connected players. */
    public static void broadcastToAll(MinecraftServer server, ShareSymbolStore store) {
        if (server == null || store == null || BACKEND_INSTANCES == null || BACKEND_INSTANCES.NETWORKING == null) return;
        S2CShareSymbolManifestPacket pkt = new S2CShareSymbolManifestPacket(store.getRevision(), store.getEntries());
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            pkt.sendToClient(p);
        }
    }
}
