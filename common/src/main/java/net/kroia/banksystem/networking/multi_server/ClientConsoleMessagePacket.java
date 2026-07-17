package net.kroia.banksystem.networking.multi_server;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.kroia.modutilities.UtilitiesPlatform;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.kroia.modutilities.networking.multi_server.ForwardPacketContext;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class ClientConsoleMessagePacket extends BankSystemNetworkPacket {
    public static final Type<ClientConsoleMessagePacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "client_console_message_packet"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientConsoleMessagePacket> STREAM_CODEC = StreamCodec.composite(
            ExtraCodecUtils.nullable(UUIDUtil.STREAM_CODEC), p -> p.receivingPlayer,
            ByteBufCodecs.STRING_UTF8, p -> p.message,
            ClientConsoleMessagePacket::new
    );
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }


    public static void sendMessageFromMaster(UUID targetPlayer, String message)
    {
        ClientConsoleMessagePacket packet = new ClientConsoleMessagePacket(targetPlayer, message);
        packet.broadcastToSlaves();
        packet.handle(); // Print to players that are on this server
    }
    public static void sendMessageFromMaster(String message)
    {
        sendMessageFromMaster(null, message);
    }

    /**
     * Sends a chat message from master to a specific slave, broadcast to all players
     * currently online on that slave. Use when the master identifies the slave that
     * originated a rejected/failing request but does not know which player on that
     * slave initiated it (e.g. because ARRS does not propagate the player UUID from
     * slave to master for server-initiated requests). All players on that slave will
     * see the message — acceptable tradeoff for a rejection notification pending a
     * proper UUID propagation.
     */
    public static void sendMessageFromMasterToSlave(String slaveID, String message)
    {
        ClientConsoleMessagePacket packet = new ClientConsoleMessagePacket(null, message);
        packet.sendToSlave(slaveID);
    }


    private final @Nullable UUID receivingPlayer;
    private final String message;


    public ClientConsoleMessagePacket(@Nullable UUID receivingPlayer, String message) {
        this.receivingPlayer = receivingPlayer;
        this.message = message;

    }

    @Override
    protected void handleOnSlave(ForwardPacketContext context)
    {
        handle();
    }

    private void handle()
    {
        MinecraftServer server = UtilitiesPlatform.getServer();
        if (server == null)
            return;
        if(receivingPlayer == null)
        {
            ServerPlayerUtilities.printToClientConsole(message);
        }
        else
        {
            ServerPlayerUtilities.printToClientConsole(receivingPlayer, message);
        }
    }
}
