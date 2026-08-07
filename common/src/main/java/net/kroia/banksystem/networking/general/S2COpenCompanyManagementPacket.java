package net.kroia.banksystem.networking.general;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * Task #51 (v2.0.8) — S2C packet instructing the client to open the
 * CompanyManagementScreen for a given company. Sent by the server-side
 * {@code /company manage} handler after resolving + MANAGE-gating.
 */
public class S2COpenCompanyManagementPacket extends BankSystemNetworkPacket {

    public static final Type<S2COpenCompanyManagementPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "s2c_open_company_management"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2COpenCompanyManagementPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,     p -> p.companyId,
                    ByteBufCodecs.STRING_UTF8, p -> p.companyName,
                    S2COpenCompanyManagementPacket::new);

    private final int companyId;
    private final String companyName;

    public S2COpenCompanyManagementPacket(int companyId, String companyName) {
        this.companyId = companyId;
        this.companyName = companyName == null ? "" : companyName;
    }

    public static void send(ServerPlayer player, int companyId, String companyName) {
        new S2COpenCompanyManagementPacket(companyId, companyName).sendToClient(player);
    }

    @Override
    protected void handleOnClient(NetworkManager.PacketContext context) {
        net.minecraft.client.Minecraft.getInstance().execute(() ->
                net.kroia.banksystem.client.company.CompanyManagementScreenLauncher.open(companyId, companyName));
    }

    @Override public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
