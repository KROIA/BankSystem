package net.kroia.banksystem.networking.general;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.banksystem.util.ClientSettings;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * S2C packet sent once when a player joins, carrying the {@link ClientSettings}
 * snapshot (currently: whether this server is the MASTER server).
 * <p>
 * The client stores the received snapshot in
 * {@code BankSystemModBackend.Instances.CLIENT_SETTINGS}, where GUI code queries it
 * via {@code BankSystemGuiScreen.isMasterServer()} — e.g. to gate the master-only
 * "Mod Settings" button in the {@code BankSystemSettingScreen}. This is UI gating
 * only; the server independently enforces admin + master status in
 * {@code ModSettingsRequest} regardless of what the client believes.
 */
public class PlayerJoinSyncPacket extends BankSystemNetworkPacket {

    public static final Type<PlayerJoinSyncPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "player_join_sync_packet"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerJoinSyncPacket> STREAM_CODEC = StreamCodec.composite(
            ClientSettings.STREAM_CODEC, p -> p.clientSettings,
            PlayerJoinSyncPacket::new
    );

    private final ClientSettings clientSettings;

    public PlayerJoinSyncPacket(ClientSettings clientSettings) {
        this.clientSettings = clientSettings;
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Builds the snapshot for THIS server (the server the player is connected to)
     * and sends it to the joining player. A slave server sends
     * {@code isMasterServer = false}; a master or a regular single server (its own
     * master) sends {@code true}.
     *
     * @param player the player that just joined this server
     */
    public static void send(ServerPlayer player) {
        ClientSettings settings = new ClientSettings(!BACKEND_INSTANCES.isSlaveServer);
        PlayerJoinSyncPacket packet = new PlayerJoinSyncPacket(settings);
        packet.sendToClient(player);

        // Task #46 (v2.1.0) — piggyback the initial bulk sync of Company share visuals on
        // the login handshake. Master-only: on slaves the CompanyManager singleton is null
        // (all Company state lives on master) and slaves receive their visuals via S2C
        // updates forwarded from master through the standard broadcast path.
        net.kroia.banksystem.banking.company.CompanyManager mgr =
                net.kroia.banksystem.banking.company.CompanyManager.get();
        if (mgr != null) {
            java.util.List<net.kroia.banksystem.networking.general.S2CCompanyVisualBulkPacket.Entry> entries =
                    new java.util.ArrayList<>();
            net.kroia.banksystem.api.bankmanager.IServerBankManager bm =
                    BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
            for (net.kroia.banksystem.banking.company.Company c : mgr.getAll()) {
                java.util.List<String> founderNames = new java.util.ArrayList<>();
                for (java.util.UUID uuid : c.getFounders()) {
                    net.kroia.banksystem.banking.User u = bm != null ? bm.getUserByUUID(uuid) : null;
                    founderNames.add(u != null ? u.getName() : uuid.toString());
                }
                // Task #52 fix (v2.1.0) — count holders by iterating the ItemID registry and
                // reverse-mapping each ItemID to a companyId via StampedShareItem. The forward
                // template-match path (ofCompany → getItemID) is fragile against extra default
                // components on the persisted stack; iterating the registry sidesteps that.
                int holderCount = 0;
                if (bm != null) {
                    java.util.Set<Integer> holders = new java.util.HashSet<>();
                    for (java.util.Map.Entry<net.kroia.banksystem.util.ItemID, net.minecraft.world.item.ItemStack> e
                            : net.kroia.banksystem.util.ItemIDManager.getItemIDMap().entrySet()) {
                        Integer cid = net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem
                                .getCompanyIdForItemID(e.getKey());
                        if (cid == null || cid != c.getCompanyId()) continue;
                        holders.addAll(bm.listAccountsHolding(e.getKey()));
                    }
                    holderCount = holders.size();
                }
                entries.add(net.kroia.banksystem.networking.general.S2CCompanyVisualBulkPacket.Entry.of(
                        c.getCompanyId(), c.getShareVisuals(),
                        c.getTotalSharesIssued(), c.getMaxSupply(),
                        c.getName(),
                        c.getDescription() == null ? "" : c.getDescription(),
                        c.getBankAccountNr(),
                        founderNames,
                        holderCount));
            }
            if (!entries.isEmpty()) {
                net.kroia.banksystem.networking.general.S2CCompanyVisualBulkPacket.sendTo(player, entries);
            }
        } else if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.isSlaveServer) {
            // Task #54 (v2.1.0) — slave-side branch: master's CompanyManager is null here
            // (all Company state lives on master). Serve the join-time bulk sync from the
            // slave-side mirror populated by the master→slave push (see SlaveCompanyMirror).
            // If the mirror is empty (fresh boot, master unreachable, or the master→slave
            // bulk request is still in flight) we send nothing — the tooltip's per-id
            // ARRS self-heal path continues to cover the miss.
            java.util.List<net.kroia.banksystem.networking.general.S2CCompanyVisualBulkPacket.Entry> mirrored =
                    net.kroia.banksystem.banking.company.SlaveCompanyMirror.snapshot();
            if (!mirrored.isEmpty()) {
                net.kroia.banksystem.networking.general.S2CCompanyVisualBulkPacket.sendTo(player, mirrored);
            }
        }
        // Task #54 (v2.1.0) — send share symbol manifest to joining player.
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.SHARE_SYMBOL_STORE != null) {
            net.kroia.banksystem.networking.general.S2CShareSymbolManifestPacket.sendTo(
                    player, BACKEND_INSTANCES.SHARE_SYMBOL_STORE);
        }
    }

    @Override
    public void handleOnClient(NetworkManager.PacketContext context) {
        BACKEND_INSTANCES.CLIENT_SETTINGS.loadFrom(clientSettings);
    }
}
