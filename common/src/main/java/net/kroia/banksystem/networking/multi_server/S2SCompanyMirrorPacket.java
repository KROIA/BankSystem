package net.kroia.banksystem.networking.multi_server;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.company.SlaveCompanyMirror;
import net.kroia.banksystem.banking.company.ShareVisuals;
import net.kroia.banksystem.client.cache.ShareVisualCache;
import net.kroia.banksystem.networking.general.S2CCompanyVisualBulkPacket;
import net.kroia.banksystem.networking.general.S2CCompanyVisualSupplyUpdatePacket;
import net.kroia.banksystem.networking.general.S2CCompanyVisualUpdatePacket;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.modutilities.networking.multi_server.ForwardPacketContext;
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
 * Task #54 (v2.0.8) — master→slave S2S packet carrying company visual+info
 * mutations. Discriminator {@code op}:
 * <ul>
 *   <li>{@code OP_UPSERT} — one full {@link S2CCompanyVisualBulkPacket.Entry};
 *       slave upserts the mirror and forwards a {@link S2CCompanyVisualUpdatePacket}
 *       to every locally-connected player.</li>
 *   <li>{@code OP_SUPPLY} — companyId + totalSharesIssued; slave patches the
 *       mirror's supply (leaves visuals alone) and forwards
 *       {@link S2CCompanyVisualSupplyUpdatePacket} to locals.</li>
 *   <li>{@code OP_REMOVE} — companyId; slave drops the mirror row.
 *       (Locals learn on next join / bulk sync; a dedicated dissolve S2C
 *       could be added later.)</li>
 * </ul>
 *
 * <p>Registered via {@code registerS2S} — the slave handler runs on
 * {@link #handleOnSlave(ForwardPacketContext)}.
 */
public class S2SCompanyMirrorPacket extends BankSystemNetworkPacket {

    public static final Type<S2SCompanyMirrorPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "s2s_company_mirror"));

    public static final byte OP_UPSERT = 0;
    public static final byte OP_SUPPLY = 1;
    public static final byte OP_REMOVE = 2;

    public static final StreamCodec<RegistryFriendlyByteBuf, S2SCompanyMirrorPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeByte(p.op);
                        buf.writeVarInt(p.companyId);
                        if (p.op == OP_UPSERT) {
                            S2CCompanyVisualBulkPacket.Entry e = p.entry;
                            buf.writeUtf(e.bgSymbolId() == null ? "" : e.bgSymbolId());
                            buf.writeInt(e.bgTint());
                            buf.writeUtf(e.fgSymbolId() == null ? "" : e.fgSymbolId());
                            buf.writeInt(e.fgTint());
                            buf.writeInt(e.baseTint());
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
                        } else if (p.op == OP_SUPPLY) {
                            buf.writeVarLong(p.totalSharesIssued);
                        }
                    },
                    buf -> {
                        byte op = buf.readByte();
                        int cid = buf.readVarInt();
                        if (op == OP_UPSERT) {
                            String bgSym = buf.readUtf();
                            int bgTint = buf.readInt();
                            String fgSym = buf.readUtf();
                            int fgTint = buf.readInt();
                            int baseTint = buf.readInt();
                            String dn = buf.readUtf();
                            String desc = buf.readUtf();
                            long issued = buf.readVarLong();
                            long max = buf.readVarLong();
                            String iname = buf.readUtf();
                            String cdesc = buf.readUtf();
                            int accNr = buf.readVarInt();
                            int fn = buf.readVarInt();
                            List<String> founders = new ArrayList<>(fn);
                            for (int j = 0; j < fn; j++) founders.add(buf.readUtf());
                            int hc = buf.readVarInt();
                            S2CCompanyVisualBulkPacket.Entry entry = new S2CCompanyVisualBulkPacket.Entry(
                                    cid, bgSym, bgTint, fgSym, fgTint, baseTint, dn, desc, issued, max, iname, cdesc, accNr, founders, hc);
                            return new S2SCompanyMirrorPacket(op, cid, entry, 0L);
                        } else if (op == OP_SUPPLY) {
                            long issued = buf.readVarLong();
                            return new S2SCompanyMirrorPacket(op, cid, null, issued);
                        }
                        return new S2SCompanyMirrorPacket(op, cid, null, 0L);
                    });

    private final byte op;
    private final int companyId;
    private final S2CCompanyVisualBulkPacket.Entry entry;
    private final long totalSharesIssued;

    public S2SCompanyMirrorPacket(byte op, int companyId, S2CCompanyVisualBulkPacket.Entry entry, long totalSharesIssued) {
        this.op = op;
        this.companyId = companyId;
        this.entry = entry;
        this.totalSharesIssued = totalSharesIssued;
    }

    public static S2SCompanyMirrorPacket upsert(S2CCompanyVisualBulkPacket.Entry entry) {
        return new S2SCompanyMirrorPacket(OP_UPSERT, entry.companyId(), entry, entry.totalSharesIssued());
    }
    public static S2SCompanyMirrorPacket supply(int companyId, long totalSharesIssued) {
        return new S2SCompanyMirrorPacket(OP_SUPPLY, companyId, null, totalSharesIssued);
    }
    public static S2SCompanyMirrorPacket remove(int companyId) {
        return new S2SCompanyMirrorPacket(OP_REMOVE, companyId, null, 0L);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    @Override
    protected void handleOnSlave(ForwardPacketContext context) {
        MinecraftServer server = net.kroia.modutilities.UtilitiesPlatform.getServer();
        if (op == OP_UPSERT && entry != null) {
            SlaveCompanyMirror.put(entry);
            if (server != null) {
                S2CCompanyVisualUpdatePacket fwd = new S2CCompanyVisualUpdatePacket(
                        entry.companyId(),
                        new ShareVisuals(
                                new ShareVisuals.ShareLayer(entry.bgSymbolId(), entry.bgTint()),
                                new ShareVisuals.ShareLayer(entry.fgSymbolId(), entry.fgTint()),
                                entry.baseTint(), entry.displayName(), entry.description()),
                        entry.totalSharesIssued(), entry.maxSupply());
                // Task #51 (v2.0.8, spec §1.4) — also forward the company-level
                // description so slave-side clients' Overview tabs stay fresh.
                net.kroia.banksystem.networking.general.S2CCompanyDescriptionUpdatePacket descFwd =
                        new net.kroia.banksystem.networking.general.S2CCompanyDescriptionUpdatePacket(
                                entry.companyId(), entry.companyDescription());
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.NETWORKING != null) {
                        BACKEND_INSTANCES.NETWORKING.sendToClient(p, fwd);
                        BACKEND_INSTANCES.NETWORKING.sendToClient(p, descFwd);
                    }
                }
            }
        } else if (op == OP_SUPPLY) {
            SlaveCompanyMirror.updateSupply(companyId, totalSharesIssued);
            if (server != null) {
                S2CCompanyVisualSupplyUpdatePacket fwd =
                        new S2CCompanyVisualSupplyUpdatePacket(companyId, totalSharesIssued);
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.NETWORKING != null) {
                        BACKEND_INSTANCES.NETWORKING.sendToClient(p, fwd);
                    }
                }
            }
        } else if (op == OP_REMOVE) {
            SlaveCompanyMirror.remove(companyId);
        }
    }
}
