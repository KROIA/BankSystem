package net.kroia.banksystem.networking.general;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.ShareVisuals;
import net.kroia.banksystem.client.cache.ShareVisualCache;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Task #46 (v2.0.8) — bulk sync of every registered Company's share visuals + supply,
 * sent to a fresh client at login (and to slaves attaching to master, once slave-side
 * mirror is wired). Applied to {@link ShareVisualCache}.
 *
 * <p>Producers: {@link net.kroia.banksystem.networking.general.PlayerJoinSyncPacket}
 * (or a similar login hook) — currently DEFERRED writer-side; the packet class + codec
 * are shipped now so downstream tasks and the editor screen can drop it in without
 * touching the network registration.
 */
public class S2CCompanyVisualBulkPacket extends BankSystemNetworkPacket {

    public static final Type<S2CCompanyVisualBulkPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "s2c_company_visual_bulk"));

    public record Entry(int companyId, String iconPresetId, int tint, String displayName,
                        String description, long totalSharesIssued, long maxSupply) {
        public static Entry of(int companyId, ShareVisuals v, long issued, long max) {
            String preset = v != null ? v.getIconPresetId() : "";
            int tint = v != null ? v.getTint() : 0xFFFFFFFF;
            String dn = v != null ? v.getDisplayName() : "";
            String desc = v != null ? v.getDescription() : "";
            return new Entry(companyId, preset, tint, dn, desc, issued, max);
        }
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CCompanyVisualBulkPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeVarInt(p.entries.size());
                        for (Entry e : p.entries) {
                            buf.writeVarInt(e.companyId);
                            buf.writeUtf(e.iconPresetId);
                            buf.writeInt(e.tint);
                            buf.writeUtf(e.displayName);
                            buf.writeUtf(e.description);
                            buf.writeVarLong(e.totalSharesIssued);
                            buf.writeVarLong(e.maxSupply);
                        }
                    },
                    buf -> {
                        int n = buf.readVarInt();
                        List<Entry> out = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            out.add(new Entry(
                                    buf.readVarInt(),
                                    buf.readUtf(),
                                    buf.readInt(),
                                    buf.readUtf(),
                                    buf.readUtf(),
                                    buf.readVarLong(),
                                    buf.readVarLong()));
                        }
                        return new S2CCompanyVisualBulkPacket(out);
                    });

    private final List<Entry> entries;

    public S2CCompanyVisualBulkPacket(List<Entry> entries) {
        this.entries = entries == null ? List.of() : entries;
    }

    /** Master-side helper — send the bulk snapshot to a single joining player. */
    public static void sendTo(ServerPlayer player, List<Entry> entries) {
        if (player == null || BACKEND_INSTANCES == null || BACKEND_INSTANCES.NETWORKING == null) return;
        S2CCompanyVisualBulkPacket packet = new S2CCompanyVisualBulkPacket(entries);
        packet.sendToClient(player);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    @Override
    public void handleOnClient(NetworkManager.PacketContext context) {
        for (Entry e : entries) {
            ShareVisualCache.put(e.companyId,
                    new ShareVisuals(e.iconPresetId, e.tint, e.displayName, e.description),
                    e.totalSharesIssued, e.maxSupply);
        }
    }
}
