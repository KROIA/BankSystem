package net.kroia.banksystem.networking.general;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.client.cache.CompanyInfoCache;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Task #51 (v2.1.0) — S2C broadcast when {@code Company.description} changes on the
 * master (spec §1.4). Closes the propagation gap: {@link S2CCompanyVisualUpdatePacket}
 * only carries {@code ShareVisuals} (icon/tint/displayName/visual description) + supply,
 * NOT the company-level description shown on the Overview tab. Client handler patches
 * {@link CompanyInfoCache} in place so every open CompanyManagementScreen re-renders the
 * fresh text on the next frame.
 *
 * <p>Slave path: the master's {@code handleDescription} also upserts the
 * {@link net.kroia.banksystem.networking.multi_server.S2SCompanyMirrorPacket} entry
 * (carries {@code companyDescription}); the slave forwards this packet to its own
 * connected clients from that handler.
 */
public class S2CCompanyDescriptionUpdatePacket extends BankSystemNetworkPacket {

    public static final Type<S2CCompanyDescriptionUpdatePacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "s2c_company_description_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CCompanyDescriptionUpdatePacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeVarInt(p.companyId);
                        buf.writeUtf(p.description);
                    },
                    buf -> new S2CCompanyDescriptionUpdatePacket(buf.readVarInt(), buf.readUtf()));

    private final int companyId;
    private final String description;

    public S2CCompanyDescriptionUpdatePacket(int companyId, String description) {
        this.companyId = companyId;
        this.description = description == null ? "" : description;
    }

    /** Master/slave-side helper — pushes the new description to every connected client. */
    public static void broadcast(MinecraftServer server, int companyId, String description) {
        if (server == null || BACKEND_INSTANCES == null || BACKEND_INSTANCES.NETWORKING == null) return;
        S2CCompanyDescriptionUpdatePacket packet =
                new S2CCompanyDescriptionUpdatePacket(companyId, description);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            packet.sendToClient(p);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    @Override
    public void handleOnClient(NetworkManager.PacketContext context) {
        CompanyInfoCache.updateDescription(companyId, description);
    }
}
