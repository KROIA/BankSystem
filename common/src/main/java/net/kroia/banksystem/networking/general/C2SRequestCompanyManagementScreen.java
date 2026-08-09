package net.kroia.banksystem.networking.general;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.company.Company;
import net.kroia.banksystem.banking.company.CompanyManager;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * Task #51 (v2.0.8) — C2S: client requests the server to open
 * {@link net.kroia.banksystem.screen.custom.CompanyManagementScreen} for a stamped
 * share right-click.
 *
 * <p>The server resolves rights server-side and responds with
 * {@link S2COpenCompanyManagementPacket} carrying pre-resolved {@code isFounder} /
 * {@code canManage} flags. This avoids the client-side ARRS round-trip that fails
 * on dedicated servers before the ARRS channel is established.
 */
public class C2SRequestCompanyManagementScreen extends BankSystemNetworkPacket {

    public static final Type<C2SRequestCompanyManagementScreen> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "c2s_request_company_management"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SRequestCompanyManagementScreen> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, p -> p.companyId,
                    C2SRequestCompanyManagementScreen::new);

    private final int companyId;

    public C2SRequestCompanyManagementScreen(int companyId) {
        this.companyId = companyId;
    }

    /** Send from client to request the server to open the management screen. */
    public static void send(int companyId) {
        new C2SRequestCompanyManagementScreen(companyId).sendToServer();
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    // No master routing — the server the client is connected to handles this locally.
    @Override protected boolean needsRoutingToMaster() { return false; }

    @Override
    protected void handleOnServer(ServerPlayer player) {
        if (BACKEND_INSTANCES == null) return;
        CompanyManager cm = CompanyManager.get();
        if (cm == null) {
            // Slave without company data — fall back to opening the screen with no rights.
            S2COpenCompanyManagementPacket.send(player, companyId, "");
            return;
        }
        Company company = cm.getById(companyId);
        if (company == null) return; // company not found — don't open screen

        String name      = company.getName();
        boolean founder  = company.isFounder(player.getUUID());
        boolean manage   = founder; // founders always have manage
        if (!manage && BACKEND_INSTANCES.SERVER_BANK_MANAGER != null) {
            net.kroia.banksystem.api.bankaccount.IServerBankAccount account =
                    BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync() != null
                            ? BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync()
                                    .getBankAccount(company.getBankAccountNr())
                            : null;
            if (account != null) {
                manage = account.hasPermission(player.getUUID(), BankPermission.MANAGE);
            }
        }
        S2COpenCompanyManagementPacket.sendWithRights(player, companyId, name, founder, manage);
    }
}
