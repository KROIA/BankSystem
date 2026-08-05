package net.kroia.banksystem.networking.general;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.ShareVisuals;
import net.kroia.banksystem.client.cache.ShareVisualCache;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Task #46 (v2.0.8) — S2C broadcast when a Company's share visuals change on the master.
 * Applied to the client-side {@link ShareVisualCache}; every rendered stamped_share
 * tooltip and every open share-visual GUI refreshes on the next frame.
 *
 * <p>Emission points (deferred to Task #46b editor and Task #47 stamper):
 * <ul>
 *   <li>Owner edits visuals via ShareVisualEditorScreen → broadcast to all clients.</li>
 *   <li>Stamper stamps / redeems shares → broadcast supply update (separate packet).</li>
 * </ul>
 */
public class S2CCompanyVisualUpdatePacket extends BankSystemNetworkPacket {

    public static final Type<S2CCompanyVisualUpdatePacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "s2c_company_visual_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CCompanyVisualUpdatePacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeVarInt(p.companyId);
                        buf.writeUtf(p.iconPresetId);
                        buf.writeInt(p.tint);
                        buf.writeUtf(p.displayName);
                        buf.writeUtf(p.description);
                        buf.writeVarLong(p.totalSharesIssued);
                        buf.writeVarLong(p.maxSupply);
                    },
                    buf -> new S2CCompanyVisualUpdatePacket(
                            buf.readVarInt(),
                            buf.readUtf(),
                            buf.readInt(),
                            buf.readUtf(),
                            buf.readUtf(),
                            buf.readVarLong(),
                            buf.readVarLong()));

    private final int companyId;
    private final String iconPresetId;
    private final int tint;
    private final String displayName;
    private final String description;
    private final long totalSharesIssued;
    private final long maxSupply;

    public S2CCompanyVisualUpdatePacket(int companyId, String iconPresetId, int tint,
                                        String displayName, String description,
                                        long totalSharesIssued, long maxSupply) {
        this.companyId = companyId;
        this.iconPresetId = iconPresetId == null ? "" : iconPresetId;
        this.tint = tint;
        this.displayName = displayName == null ? "" : displayName;
        this.description = description == null ? "" : description;
        this.totalSharesIssued = totalSharesIssued;
        this.maxSupply = maxSupply;
    }

    public S2CCompanyVisualUpdatePacket(int companyId, ShareVisuals visuals,
                                        long totalSharesIssued, long maxSupply) {
        this(companyId,
                visuals != null ? visuals.getIconPresetId() : "",
                visuals != null ? visuals.getTint() : 0xFFFFFFFF,
                visuals != null ? visuals.getDisplayName() : "",
                visuals != null ? visuals.getDescription() : "",
                totalSharesIssued, maxSupply);
    }

    /**
     * Master-side broadcast helper — sends the packet to every currently-connected client.
     * Writers (visual editor save, stamper stamp/redeem) call this after the master mutation.
     */
    public static void broadcast(net.minecraft.server.MinecraftServer server, int companyId,
                                 ShareVisuals visuals, long totalSharesIssued, long maxSupply) {
        if (server == null || BACKEND_INSTANCES == null || BACKEND_INSTANCES.NETWORKING == null) return;
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        S2CCompanyVisualUpdatePacket packet = new S2CCompanyVisualUpdatePacket(
                companyId, visuals, totalSharesIssued, maxSupply);
        for (ServerPlayer p : players) {
            packet.sendToClient(p);
        }
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    @Override
    public void handleOnClient(NetworkManager.PacketContext context) {
        ShareVisualCache.put(companyId,
                new ShareVisuals(iconPresetId, tint, displayName, description),
                totalSharesIssued, maxSupply);
    }
}
