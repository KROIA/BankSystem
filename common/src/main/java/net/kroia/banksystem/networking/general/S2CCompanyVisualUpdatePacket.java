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
 * Task #46 (v2.1.0) — S2C broadcast when a Company's share visuals change on the master.
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

    // v2.1.0 two-layer wire format.
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CCompanyVisualUpdatePacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeVarInt(p.companyId);
                        buf.writeUtf(p.bgSymbolId);
                        buf.writeInt(p.bgTint);
                        buf.writeUtf(p.fgSymbolId);
                        buf.writeInt(p.fgTint);
                        buf.writeInt(p.baseTint);
                        buf.writeUtf(p.displayName);
                        buf.writeUtf(p.description);
                        buf.writeVarLong(p.totalSharesIssued);
                        buf.writeVarLong(p.maxSupply);
                    },
                    buf -> new S2CCompanyVisualUpdatePacket(
                            buf.readVarInt(),
                            buf.readUtf(), buf.readInt(),
                            buf.readUtf(), buf.readInt(),
                            buf.readInt(),
                            buf.readUtf(), buf.readUtf(),
                            buf.readVarLong(), buf.readVarLong()));

    private final int companyId;
    private final String bgSymbolId;
    private final int bgTint;
    private final String fgSymbolId;
    private final int fgTint;
    private final int baseTint;
    private final String displayName;
    private final String description;
    private final long totalSharesIssued;
    private final long maxSupply;

    public S2CCompanyVisualUpdatePacket(int companyId,
                                        String bgSymbolId, int bgTint,
                                        String fgSymbolId, int fgTint,
                                        int baseTint,
                                        String displayName, String description,
                                        long totalSharesIssued, long maxSupply) {
        this.companyId = companyId;
        this.bgSymbolId = bgSymbolId == null ? "" : bgSymbolId;
        this.bgTint = bgTint;
        this.fgSymbolId = fgSymbolId == null ? "" : fgSymbolId;
        this.fgTint = fgTint;
        this.baseTint = baseTint;
        this.displayName = displayName == null ? "" : displayName;
        this.description = description == null ? "" : description;
        this.totalSharesIssued = totalSharesIssued;
        this.maxSupply = maxSupply;
    }

    public S2CCompanyVisualUpdatePacket(int companyId, ShareVisuals visuals,
                                        long totalSharesIssued, long maxSupply) {
        this(companyId,
                visuals != null ? visuals.getBgLayer().symbolId() : "",
                visuals != null ? visuals.getBgLayer().tint() : 0xFFFFFFFF,
                visuals != null ? visuals.getFgLayer().symbolId() : "",
                visuals != null ? visuals.getFgLayer().tint() : 0xFFFFFFFF,
                visuals != null ? visuals.getBaseTint() : 0xFFFFFFFF,
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
        // Task #54 (v2.1.0) — also fanout to slave servers so their
        // SlaveCompanyMirror stays in sync with master for join-time bulks.
        broadcastMirrorEntryToSlaves(server, companyId, visuals, totalSharesIssued, maxSupply);
    }

    /** Task #54 helper — build an S2CCompanyVisualBulkPacket.Entry from live master state
     *  (name + description + founders + holderCount) and push it to every connected slave
     *  via {@link net.kroia.banksystem.networking.multi_server.S2SCompanyMirrorPacket}. */
    private static void broadcastMirrorEntryToSlaves(net.minecraft.server.MinecraftServer server, int companyId,
                                                     ShareVisuals visuals, long totalSharesIssued, long maxSupply) {
        if (!net.kroia.modutilities.networking.multi_server.MultiServerManager.isRunning()
                || !net.kroia.modutilities.networking.multi_server.MultiServerManager.isMaster()) return;
        net.kroia.banksystem.banking.company.CompanyManager cm =
                net.kroia.banksystem.banking.company.CompanyManager.get();
        if (cm == null) return;
        net.kroia.banksystem.banking.company.Company c = cm.getById(companyId);
        String internalName = c != null ? c.getName() : "";
        String companyDesc = c != null && c.getDescription() != null ? c.getDescription() : "";
        int accNr = c != null ? c.getBankAccountNr() : 0;
        java.util.List<String> founderNames = new java.util.ArrayList<>();
        if (c != null && BACKEND_INSTANCES != null && BACKEND_INSTANCES.SERVER_BANK_MANAGER != null) {
            net.kroia.banksystem.api.bankmanager.IServerBankManager bm =
                    BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
            for (java.util.UUID uuid : c.getFounders()) {
                net.kroia.banksystem.banking.User u = bm != null ? bm.getUserByUUID(uuid) : null;
                founderNames.add(u != null ? u.getName() : uuid.toString());
            }
        }
        S2CCompanyVisualBulkPacket.Entry entry = S2CCompanyVisualBulkPacket.Entry.of(
                companyId, visuals, totalSharesIssued, maxSupply,
                internalName, companyDesc, accNr, founderNames, 0);
        net.kroia.banksystem.networking.multi_server.S2SCompanyMirrorPacket pkt =
                net.kroia.banksystem.networking.multi_server.S2SCompanyMirrorPacket.upsert(entry);
        net.kroia.modutilities.networking.multi_server.MultiServerManager.broadcastToSlaves(pkt);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    @Override
    public void handleOnClient(NetworkManager.PacketContext context) {
        ShareVisualCache.put(companyId,
                new ShareVisuals(
                        new ShareVisuals.ShareLayer(bgSymbolId, bgTint),
                        new ShareVisuals.ShareLayer(fgSymbolId, fgTint),
                        baseTint, displayName, description),
                totalSharesIssued, maxSupply);
    }
}
