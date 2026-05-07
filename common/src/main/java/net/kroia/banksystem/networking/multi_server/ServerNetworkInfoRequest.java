package net.kroia.banksystem.networking.multi_server;

import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.modutilities.UtilitiesPlatform;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.kroia.modutilities.networking.multi_server.MultiServerManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class ServerNetworkInfoRequest extends BankSystemGenericRequest<ServerNetworkInfoRequest.InputData, ServerNetworkInfoRequest.OutputData> {
    public record InputData(boolean dummy)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, InputData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, p->p.dummy,
                InputData::new
        );
    }


    public record OutputData(List<ServerInfoRequest.ServerInfo> servers)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, OutputData> STREAM_CODEC = StreamCodec.composite(
                ExtraCodecUtils.listStreamCodec(ServerInfoRequest.ServerInfo.STREAM_CODEC), p->p.servers,
                OutputData::new
        );
    }

    public static CompletableFuture<OutputData> sendRequest()
    {
        if(MultiServerManager.isRunning() && MultiServerManager.isSlave())
            return BankSystemNetworking.SERVER_NETWORK_INFO_REQUEST.sendRequestToMaster(new InputData(false));
        else
            return createResponse();
    }

    private static CompletableFuture<OutputData> createResponse()
    {
        if(!MultiServerManager.isRunning())
            return CompletableFuture.completedFuture(new OutputData(List.of(ServerInfoRequest.createInfo(UtilitiesPlatform.getServer()))));

        List<String> slaves = MultiServerManager.getConnectedSlaveIDs();
        if(slaves.isEmpty())
            return CompletableFuture.completedFuture(new OutputData(List.of(ServerInfoRequest.createInfo(UtilitiesPlatform.getServer()))));

        CompletableFuture<OutputData> future = new CompletableFuture<>();
        List<CompletableFuture<ServerInfoRequest.ServerInfo>> slavesFutures = new ArrayList<>();

        for(String slave : slaves)
        {
            // Wrap each slave future so a single failure doesn't cancel everything
            slavesFutures.add(ServerInfoRequest.sendRequest(slave).exceptionally(ex ->
                    new ServerInfoRequest.ServerInfo(false, false, "unknown", slave, "", 0, List.of(), "Error: " + ex.getMessage())
            ));
        }

        Set<String> trustedSlaves = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().getTrustedSlaveServers();
        CompletableFuture.allOf(slavesFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> slavesFutures.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.toList())
                )
                .whenComplete((results, ex) ->
                {
                    if (ex != null || results == null) {
                        // Fallback: return just the master info
                        future.complete(new OutputData(List.of(ServerInfoRequest.createInfo(UtilitiesPlatform.getServer()))));
                    } else {
                        for (ServerInfoRequest.ServerInfo info : results)
                            info.isTrusted = trustedSlaves.contains(info.slaveID);
                        results.addFirst(ServerInfoRequest.createInfo(UtilitiesPlatform.getServer()));
                        future.complete(new OutputData(results));
                    }
                });

        return future;
    }

    @Override
    public String getRequestTypeID() {
        return ServerNetworkInfoRequest.class.getName();
    }


    @Override
    public CompletableFuture<OutputData> handleOnMasterServer(InputData input, String slaveID, @Nullable UUID playerSender) {
        return createResponse();
    }
    //@Override
    //public CompletableFuture<OutputData> handleOnSlaveServer(InputData input, @Nullable UUID playerSender) {
    //
    //}

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, InputData input) {
        InputData.STREAM_CODEC.encode(buf, input);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, OutputData output) {
        OutputData.STREAM_CODEC.encode(buf, output);
    }

    @Override
    public InputData decodeInput(RegistryFriendlyByteBuf buf) {
        return InputData.STREAM_CODEC.decode(buf);
    }

    @Override
    public OutputData decodeOutput(RegistryFriendlyByteBuf buf) {
        return OutputData.STREAM_CODEC.decode(buf);
    }
}
