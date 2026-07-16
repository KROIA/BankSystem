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
    }

    @Override
    public void handleOnClient(NetworkManager.PacketContext context) {
        BACKEND_INSTANCES.CLIENT_SETTINGS.loadFrom(clientSettings);
    }
}
