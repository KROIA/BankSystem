package net.kroia.banksystem.util;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Client-side settings snapshot synced from the server at player join
 * (see {@code net.kroia.banksystem.networking.general.PlayerJoinSyncPacket}).
 * <p>
 * The server fills this object when the player joins and sends it once per join;
 * the client stores it in {@code BankSystemModBackend.Instances.CLIENT_SETTINGS}
 * where GUI code can query it (e.g. via {@code BankSystemGuiScreen.isMasterServer()}).
 */
public class ClientSettings {
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientSettings> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, p -> p.isMasterServer,
            ClientSettings::new
    );

    /**
     * True when the server the player is connected to is the MASTER server of a
     * master/slave multi-server setup (or a regular single server, which acts as
     * its own master). Slave servers send {@code false}.
     * <p>
     * Used client-side to gate master-only UI such as the "Mod Settings" button in
     * the {@code BankSystemSettingScreen}: only the master loads and owns
     * {@code settings.json}, so editing settings from a slave is not possible.
     */
    private boolean isMasterServer = false;

    public ClientSettings() {
    }

    public ClientSettings(boolean isMasterServer) {
        this.isMasterServer = isMasterServer;
    }

    /**
     * Copies all server-synced values from the received settings object into this
     * (client-held) instance. Called from the {@code PlayerJoinSyncPacket} handler.
     *
     * @param settings the settings object decoded from the join-sync packet
     */
    public void loadFrom(ClientSettings settings) {
        this.isMasterServer = settings.isMasterServer;
    }

    /**
     * @return true if the server this client is connected to is the master server
     *         (see {@link #isMasterServer})
     */
    public boolean isMasterServer() {
        return isMasterServer;
    }

    /**
     * Server-side setter used when building the join-sync payload; also used by the
     * client to reset the flag to its safe default when leaving a server.
     *
     * @param isMasterServer whether this server is the master server
     */
    public void setMasterServer(boolean isMasterServer) {
        this.isMasterServer = isMasterServer;
    }
}
