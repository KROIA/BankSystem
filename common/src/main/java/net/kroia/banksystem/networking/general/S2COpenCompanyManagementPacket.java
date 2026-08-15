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
 * Task #51 (v2.1.0) — S2C packet instructing the client to open the
 * CompanyManagementScreen for a given company.
 *
 * <p>Carries pre-resolved {@code isFounder} / {@code canManage} flags so the screen
 * can display the correct tab set immediately without an extra ARRS round-trip.
 * Both the {@code /company manage} command handler and the share right-click C2S
 * ({@link C2SRequestCompanyManagementScreen}) use this packet.
 */
public class S2COpenCompanyManagementPacket extends BankSystemNetworkPacket {

    public static final Type<S2COpenCompanyManagementPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "s2c_open_company_management"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2COpenCompanyManagementPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,     p -> p.companyId,
                    ByteBufCodecs.STRING_UTF8, p -> p.companyName,
                    ByteBufCodecs.BOOL,        p -> p.isFounder,
                    ByteBufCodecs.BOOL,        p -> p.canManage,
                    S2COpenCompanyManagementPacket::new);

    private final int companyId;
    private final String companyName;
    private final boolean isFounder;
    private final boolean canManage;

    public S2COpenCompanyManagementPacket(int companyId, String companyName,
                                          boolean isFounder, boolean canManage) {
        this.companyId  = companyId;
        this.companyName = companyName == null ? "" : companyName;
        this.isFounder  = isFounder;
        this.canManage  = canManage;
    }

    /** Convenience overload for callers that don't yet have rights resolved (defaults to no rights). */
    public static void send(ServerPlayer player, int companyId, String companyName) {
        new S2COpenCompanyManagementPacket(companyId, companyName, false, false).sendToClient(player);
    }

    public static void sendWithRights(ServerPlayer player, int companyId, String companyName,
                                      boolean isFounder, boolean canManage) {
        new S2COpenCompanyManagementPacket(companyId, companyName, isFounder, canManage).sendToClient(player);
    }

    @Override
    protected void handleOnClient(NetworkManager.PacketContext context) {
        net.kroia.banksystem.util.BankSystemClientHooks.openCompanyManagementScreen(
                companyId, companyName, isFounder, canManage);
    }

    @Override public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
