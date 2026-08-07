package net.kroia.banksystem.networking.general;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.ShareVisuals;
import net.kroia.banksystem.client.cache.CompanyInfoCache;
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

    // v2.0.9 two-layer: bgSymbolId/bgTint/fgSymbolId/fgTint replace iconPresetId/tint.
    public record Entry(int companyId,
                        String bgSymbolId, int bgTint,
                        String fgSymbolId, int fgTint,
                        String displayName,
                        String description, long totalSharesIssued, long maxSupply,
                        String internalName, String companyDescription,
                        int bankAccountNr, List<String> founderNames,
                        int holderCount) {
        public static Entry of(int companyId, ShareVisuals v, long issued, long max) {
            return of(companyId, v, issued, max, "", "", 0, List.of(), 0);
        }
        /** Task #51 fix (v2.0.8) — extended with internal company metadata. */
        public static Entry of(int companyId, ShareVisuals v, long issued, long max,
                               String internalName, String companyDescription,
                               int bankAccountNr, List<String> founderNames) {
            return of(companyId, v, issued, max, internalName, companyDescription,
                    bankAccountNr, founderNames, 0);
        }
        /** Task #52 (v2.0.8) — extended with holderCount. */
        public static Entry of(int companyId, ShareVisuals v, long issued, long max,
                               String internalName, String companyDescription,
                               int bankAccountNr, List<String> founderNames,
                               int holderCount) {
            String bgSym = v != null ? v.getBgLayer().symbolId() : "";
            int bgTint = v != null ? v.getBgLayer().tint() : 0xFFFFFFFF;
            String fgSym = v != null ? v.getFgLayer().symbolId() : "";
            int fgTint = v != null ? v.getFgLayer().tint() : 0xFFFFFFFF;
            String dn = v != null ? v.getDisplayName() : "";
            String desc = v != null ? v.getDescription() : "";
            return new Entry(companyId, bgSym, bgTint, fgSym, fgTint, dn, desc, issued, max,
                    internalName == null ? "" : internalName,
                    companyDescription == null ? "" : companyDescription,
                    bankAccountNr,
                    founderNames == null ? List.of() : List.copyOf(founderNames),
                    holderCount);
        }
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CCompanyVisualBulkPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeVarInt(p.entries.size());
                        for (Entry e : p.entries) {
                            buf.writeVarInt(e.companyId());
                            buf.writeUtf(e.bgSymbolId() == null ? "" : e.bgSymbolId());
                            buf.writeInt(e.bgTint());
                            buf.writeUtf(e.fgSymbolId() == null ? "" : e.fgSymbolId());
                            buf.writeInt(e.fgTint());
                            buf.writeUtf(e.displayName() == null ? "" : e.displayName());
                            buf.writeUtf(e.description() == null ? "" : e.description());
                            buf.writeVarLong(e.totalSharesIssued());
                            buf.writeVarLong(e.maxSupply());
                            buf.writeUtf(e.internalName() == null ? "" : e.internalName());
                            buf.writeUtf(e.companyDescription() == null ? "" : e.companyDescription());
                            buf.writeVarInt(e.bankAccountNr());
                            buf.writeVarInt(e.founderNames().size());
                            for (String fn : e.founderNames()) buf.writeUtf(fn);
                            buf.writeVarInt(e.holderCount());
                        }
                    },
                    buf -> {
                        int n = buf.readVarInt();
                        List<Entry> out = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            int cid = buf.readVarInt();
                            String bgSym = buf.readUtf();
                            int bgTint = buf.readInt();
                            String fgSym = buf.readUtf();
                            int fgTint = buf.readInt();
                            String dn = buf.readUtf();
                            String desc = buf.readUtf();
                            long issued = buf.readVarLong();
                            long max = buf.readVarLong();
                            String internalName = buf.readUtf();
                            String companyDesc = buf.readUtf();
                            int accNr = buf.readVarInt();
                            int fn = buf.readVarInt();
                            List<String> founders = new ArrayList<>(fn);
                            for (int j = 0; j < fn; j++) founders.add(buf.readUtf());
                            int holderCount = buf.readVarInt();
                            out.add(new Entry(cid, bgSym, bgTint, fgSym, fgTint, dn, desc, issued, max,
                                    internalName, companyDesc, accNr, founders, holderCount));
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
            ShareVisualCache.put(e.companyId(),
                    new ShareVisuals(
                            new ShareVisuals.ShareLayer(e.bgSymbolId(), e.bgTint()),
                            new ShareVisuals.ShareLayer(e.fgSymbolId(), e.fgTint()),
                            e.displayName(), e.description()),
                    e.totalSharesIssued(), e.maxSupply());
            // Task #51 fix — mirror the internal Company metadata into CompanyInfoCache so
            // tooltips and CompanyManagementScreen render the canonical Company.name at login
            // even when ShareVisuals.displayName is blank.
            if (e.internalName() != null && !e.internalName().isEmpty()) {
                CompanyInfoCache.put(new CompanyInfoCache.Snapshot(
                        e.companyId(), e.internalName(), e.companyDescription(),
                        e.maxSupply(), e.totalSharesIssued(),
                        e.bankAccountNr(), e.founderNames(), e.holderCount()));
            }
        }
    }
}
