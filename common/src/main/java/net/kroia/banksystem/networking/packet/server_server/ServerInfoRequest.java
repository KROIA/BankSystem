package net.kroia.banksystem.networking.packet.server_server;

import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.kroia.modutilities.UtilitiesPlatform;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.kroia.modutilities.networking.multi_server.MultiServerManager;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ServerInfoRequest extends BankSystemGenericRequest<ServerInfoRequest.InputData, ServerInfoRequest.ServerInfo> {


    public record InputData(boolean dummy)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, InputData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, p->p.dummy,
                InputData::new
        );
    }

    public record PlayerInfo(UUID uuid, String name)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, PlayerInfo> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, p->p.uuid,
                ByteBufCodecs.STRING_UTF8, p->p.name,
                PlayerInfo::new
        );
        @Override
        public @NotNull String toString()
        {
            return name;
        }
    }
    public record ServerInfo(boolean isMaster, String serverName, String slaveID, String serverIP, int serverPort, List<PlayerInfo> onlinePlayers, String customText)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, ServerInfo> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public void encode(RegistryFriendlyByteBuf buf, ServerInfo info) {
                ByteBufCodecs.BOOL.encode(buf, info.isMaster);
                ByteBufCodecs.STRING_UTF8.encode(buf, info.serverName);
                ByteBufCodecs.STRING_UTF8.encode(buf, info.slaveID);
                ByteBufCodecs.STRING_UTF8.encode(buf, info.serverIP);
                ByteBufCodecs.INT.encode(buf, info.serverPort);
                ExtraCodecUtils.listStreamCodec(PlayerInfo.STREAM_CODEC).encode(buf, info.onlinePlayers);
                ByteBufCodecs.STRING_UTF8.encode(buf, info.customText);
            }

            @Override
            public @NotNull ServerInfo decode(RegistryFriendlyByteBuf buf) {
                boolean isMaster = ByteBufCodecs.BOOL.decode(buf);
                String serverName = ByteBufCodecs.STRING_UTF8.decode(buf);
                String slaveID = ByteBufCodecs.STRING_UTF8.decode(buf);
                String serverIP = ByteBufCodecs.STRING_UTF8.decode(buf);
                int serverPort = ByteBufCodecs.INT.decode(buf);
                List<PlayerInfo> onlinePlayers = ExtraCodecUtils.listStreamCodec(PlayerInfo.STREAM_CODEC).decode(buf);
                String customText = ByteBufCodecs.STRING_UTF8.decode(buf);
                return new ServerInfo(isMaster, serverName, slaveID, serverIP, serverPort, onlinePlayers, customText);
            }
        };

        @Override
        public @NotNull String toString()
        {
            StringBuilder builder = new StringBuilder();
            builder.append("§8--------------------------------------------\n");
            if(isMaster) {
                builder.append("§6Master\n");
                builder.append("  §7Name:    §f").append(serverName).append("\n");
                builder.append("  §7IP:       §f").append(serverIP).append("\n");
                builder.append("  §7Port:    §f").append(serverPort).append("\n");
            }
            else {
                builder.append("§eSlave: §f'").append(slaveID).append("'\n");
                builder.append("  §7Name:    §f").append(serverName).append("\n");
                builder.append("  §7IP:       §f").append(serverIP).append("\n");
            }
            if(!customText.isEmpty()) {
                builder.append("  §7").append(customText);
            }
            //builder.append("§8____________________________________________");
            builder.append("  §7Online Players: ").append("\n");
            for(PlayerInfo playerInfo : onlinePlayers) {
                builder.append("  §8- §f").append(playerInfo).append("\n");
            }
            builder.append("§8--------------------------------------------");
            return builder.toString();
        }
    }

    public static ServerInfo createInfo(MinecraftServer server)
    {
        String customText = "";
        if(MultiServerManager.isInUse())
        {
            if (!MultiServerManager.isRunning()) {
                if(MultiServerManager.isMaster())
                {
                    customText += "TCP server is not running\n";
                }
                else
                {
                    customText += "Slave server is not connected to the master\n";
                }
            }
        }
        else {
            customText += "Server-Server infrastructure is not in use\n";
        }
        String serverName = server.getMotd();
        ArrayList<ServerPlayer> players = ServerPlayerUtilities.getOnlinePlayers();
        List<PlayerInfo> playerInfos = new ArrayList<>();
        for(ServerPlayer player : players)
        {
            playerInfos.add(new PlayerInfo(player.getUUID(), player.getName().getString()));
        }

        if(!MultiServerManager.isRunning())
            return new ServerInfo(false,serverName,"", "", 0, playerInfos, customText);



        if(MultiServerManager.isMaster())
        {
            return new ServerInfo(true,
                    serverName,
                    MultiServerManager.getSlaveID(),
                    MultiServerManager.getMasterIP(),
                    MultiServerManager.getMasterPort(),
                    playerInfos,
                    customText);
        }
        else
        {
            return new ServerInfo(false,
                    serverName,
                    MultiServerManager.getSlaveID(),
                    MultiServerManager.getSlaveIP(),
                    MultiServerManager.getMasterPort(),
                    playerInfos,
                    customText);
        }
    }

    public static CompletableFuture<ServerInfo> sendRequest(String slaveID)
    {
        if(MultiServerManager.isRunning() && MultiServerManager.isMaster())
            return BankSystemNetworking.SERVER_INFO_REQUEST.sendRequestToSlave(slaveID, new ServerInfoRequest.InputData(false));
        else
            return CompletableFuture.completedFuture(createInfo(UtilitiesPlatform.getServer()));
    }


    @Override
    public CompletableFuture<ServerInfo> handleOnSlaveServer(InputData input, @Nullable UUID playerSender) {
       return CompletableFuture.completedFuture(createInfo(UtilitiesPlatform.getServer()));
    }

    @Override
    public String getRequestTypeID() {
        return ServerInfoRequest.class.getName();
    }

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, InputData input) {
        InputData.STREAM_CODEC.encode(buf, input);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, ServerInfo output) {
        ServerInfo.STREAM_CODEC.encode(buf, output);
    }

    @Override
    public InputData decodeInput(RegistryFriendlyByteBuf buf) {
        return InputData.STREAM_CODEC.decode(buf);
    }

    @Override
    public ServerInfo decodeOutput(RegistryFriendlyByteBuf buf) {
        return ServerInfo.STREAM_CODEC.decode(buf);
    }
}
