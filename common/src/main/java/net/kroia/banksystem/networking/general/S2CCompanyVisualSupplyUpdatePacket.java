package net.kroia.banksystem.networking.general;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
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
 * Task #46 (v2.0.8) — lightweight S2C broadcast of a Company's {@code totalSharesIssued}
 * counter change, without touching visuals or maxSupply. Writers live in Task #47
 * (share stamper) — this packet is registered now so the wire contract is stable.
 */
public class S2CCompanyVisualSupplyUpdatePacket extends BankSystemNetworkPacket {

    public static final Type<S2CCompanyVisualSupplyUpdatePacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "s2c_company_supply_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CCompanyVisualSupplyUpdatePacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,  p -> p.companyId,
                    ByteBufCodecs.VAR_LONG, p -> p.totalSharesIssued,
                    S2CCompanyVisualSupplyUpdatePacket::new);

    private final int companyId;
    private final long totalSharesIssued;

    public S2CCompanyVisualSupplyUpdatePacket(int companyId, long totalSharesIssued) {
        this.companyId = companyId;
        this.totalSharesIssued = totalSharesIssued;
    }

    /**
     * Master-side broadcast helper — sends the packet to every currently-connected client.
     */
    public static void broadcast(net.minecraft.server.MinecraftServer server, int companyId,
                                 long totalSharesIssued) {
        if (server == null || BACKEND_INSTANCES == null || BACKEND_INSTANCES.NETWORKING == null) return;
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        S2CCompanyVisualSupplyUpdatePacket packet = new S2CCompanyVisualSupplyUpdatePacket(companyId, totalSharesIssued);
        for (ServerPlayer p : players) {
            packet.sendToClient(p);
        }
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    @Override
    public void handleOnClient(NetworkManager.PacketContext context) {
        ShareVisualCache.updateSupply(companyId, totalSharesIssued);
    }
}
