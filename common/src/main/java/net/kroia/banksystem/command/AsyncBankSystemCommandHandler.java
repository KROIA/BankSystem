package net.kroia.banksystem.command;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankaccount.IAsyncBankAccount;
import net.kroia.banksystem.api.bankmanager.IAsyncBankManager;
import net.kroia.banksystem.api.command.IAsyncBankSystemCommandHandler;
import net.kroia.banksystem.api.command.IServerBankSystemCommandHandler;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.bankaccount.AsyncBankAccount;
import net.kroia.banksystem.banking.bankaccount.ServerBankAccount;
import net.kroia.banksystem.networking.ui.SyncOpenGUIPacket;
import net.kroia.banksystem.networking.multi_server.ServerInfoRequest;
import net.kroia.banksystem.networking.multi_server.ServerNetworkInfoRequest;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.MultiServerUtils;
import net.kroia.banksystem.util.async_function_forwarding.AsyncForwardingRequest;
import net.kroia.banksystem.util.async_function_forwarding.AsyncFunctionDataCodecs;
import net.kroia.banksystem.util.async_function_forwarding.AsyncFunctionInputData;
import net.kroia.banksystem.util.async_function_forwarding.AsyncFunctionOutputData;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.kroia.modutilities.UtilitiesPlatform;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.kroia.modutilities.networking.client_server.arrs.AsynchronousRequestResponseSystem;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class AsyncBankSystemCommandHandler implements IAsyncBankSystemCommandHandler {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
        ServerBankAccount.setBackend(backend);
    }



    /**
     * Enumeration to specify each function that can be forwarded
     */
    public enum FunctionType
    {
        //Banksystem_manage,
        //Banksystem_testScreen,
        Banksystem_setBankSystemAdminMode,
        Banksystem_setBankSystemAdminMode_user,
        Banksystem_allowItem,
        Banksystem_disallowItem,

        Money,
        Money_add,
        Money_add_user,
        Money_set,
        Money_set_user,
        Money_remove,
        Money_remove_user,
        Money_send_user,
        Money_circulation,



        Bank_enableNotifications,
        Bank_disableNotifications,
        Bank_create,
        Bank_show_user

    }

    /**
     * Map of codec pairs for each function
     * If a codec is set to null, that means the argument is not available.
     *      -> inputParamsCodec  == null: The function does not take any parameters
     *      -> outputParamsCodec == null: The function does not return any value
     */
    private static AsyncFunctionDataCodecs codecPacket(@Nullable StreamCodec<RegistryFriendlyByteBuf, ?> inputParamsCodec)
    {
        return new AsyncFunctionDataCodecs(BankIdentifyAndDataPacket.streamCodec(inputParamsCodec), ByteBufCodecs.BOOL.cast());
    }
    private static AsyncFunctionDataCodecs codecPacket(@Nullable StreamCodec<RegistryFriendlyByteBuf, ?> inputParamsCodec, @Nullable StreamCodec<RegistryFriendlyByteBuf, ?> outputParamsCodec)
    {
        return new AsyncFunctionDataCodecs(BankIdentifyAndDataPacket.streamCodec(inputParamsCodec), outputParamsCodec);
    }
    public static final Map<FunctionType, AsyncFunctionDataCodecs> codecs = new HashMap<>(){{
        //put(FunctionType.GetAccountDataAsync_1,             codecPacket(null, BankAccountData.STREAM_CODEC));
        //put(FunctionType.Banksystem_manage,                 codecPacket(null));
        //put(FunctionType.Banksystem_testScreen,             codecPacket(null));
        put(FunctionType.Banksystem_setBankSystemAdminMode,         codecPacket(ByteBufCodecs.BOOL.cast()));
        put(FunctionType.Banksystem_setBankSystemAdminMode_user,    codecPacket(ParamGroup_String_Bool.STREAM_CODEC));
        put(FunctionType.Banksystem_allowItem,                      codecPacket(ItemID.STREAM_CODEC));
        put(FunctionType.Banksystem_disallowItem,                   codecPacket(ItemID.STREAM_CODEC));

        put(FunctionType.Money,                                     codecPacket(null));
        put(FunctionType.Money_add,                                 codecPacket(ByteBufCodecs.FLOAT.cast()));
        put(FunctionType.Money_add_user,                            codecPacket(ParamGroup_String_Float.STREAM_CODEC));
        put(FunctionType.Money_set,                                 codecPacket(ByteBufCodecs.FLOAT.cast()));
        put(FunctionType.Money_set_user,                            codecPacket(ParamGroup_String_Float.STREAM_CODEC));
        put(FunctionType.Money_remove,                              codecPacket(ByteBufCodecs.FLOAT.cast()));
        put(FunctionType.Money_remove_user,                         codecPacket(ParamGroup_String_Float.STREAM_CODEC));
        put(FunctionType.Money_send_user,                           codecPacket(ParamGroup_String_Float.STREAM_CODEC));
        put(FunctionType.Money_circulation,                         codecPacket(null));

        put(FunctionType.Bank_enableNotifications,                  codecPacket(null));
        put(FunctionType.Bank_disableNotifications,                 codecPacket(null));
        put(FunctionType.Bank_create,                               codecPacket(ByteBufCodecs.STRING_UTF8.cast(), ByteBufCodecs.INT.cast()));
        put(FunctionType.Bank_show_user,                            codecPacket(ByteBufCodecs.STRING_UTF8.cast()));

    }};

    /**
     * Specialized InputData class, acting as data container for function input arguments
     */
    public static class InputData extends AsyncFunctionInputData<FunctionType> {
        public InputData(FunctionType function, byte[] encodedParams) {
            super(function, codecs.get(function).inputParamsCodec, encodedParams);
        }
        public InputData(FunctionType function) {
            super(function, codecs.get(function).inputParamsCodec);
        }
        public static <T> InputData of(FunctionType functionType, UUID commandExecutorPlayer, T result)
        {
            BankIdentifyAndDataPacket<T> packet = BankIdentifyAndDataPacket.of(commandExecutorPlayer, result);
            return (InputData) AsyncFunctionInputData.of(codecs.get(functionType).inputParamsCodec, functionType, packet, InputData::new);
        }
        public static InputData of(FunctionType functionType, UUID commandExecutorPlayer)
        {
            BankIdentifyAndDataPacket packet = BankIdentifyAndDataPacket.of(commandExecutorPlayer, null);
            return (InputData) AsyncFunctionInputData.of(codecs.get(functionType).inputParamsCodec, functionType, packet, InputData::new);
        }
    }

    /**
     * Specialized OutputData class, acting as data container for function return values
     */
    public static class OutputData extends AsyncFunctionOutputData<FunctionType> {

        public OutputData(FunctionType function, byte[] encodedResult) {
            super(function, codecs.get(function).outputParamsCodec, encodedResult);
        }
        public OutputData(FunctionType function) {
            super(function, codecs.get(function).outputParamsCodec);
        }
        public static <T> OutputData of(FunctionType functionType, T result)
        {
            return (OutputData) AsyncFunctionOutputData.of(codecs.get(functionType).outputParamsCodec, functionType, result, OutputData::new);
        }
        public static OutputData of(FunctionType functionType)
        {
            return (OutputData) AsyncFunctionOutputData.of(functionType, OutputData::new);
        }
    }

    /**
     * Specialized Request class to transport the data packets to the master
     */
    public static class Request extends AsyncForwardingRequest<FunctionType, InputData, OutputData>
    {
        public static final Request instance = (Request) AsynchronousRequestResponseSystem.register(new Request());
        public Request() {
            super(InputData::new, OutputData::new, FunctionType.class);
        }
        @Override
        public String getRequestTypeID() {
            return Request.class.getName();
        }

        @Override
        public CompletableFuture<OutputData> sendRequestToServer(InputData input)
        {
            if(AsyncForwardingRequest.DEBUG_ENABLE_LOGS)
                info("Sending request to server for command: "+input.function.toString());
            return super.sendRequestToServer(input);
        }

        /**
         * Gets called by the Request handler on the master side
         * @param input the input data provided by the function call
         * @param playerSender the player. If null, no player has sent the request (server only request)
         * @return the response data future for to send back to the requestor
         */
        @Override
        public CompletableFuture<OutputData> handleOnMasterServer(InputData input, String slaveID, @Nullable UUID playerSender) {
            BankIdentifyAndDataPacket inputData = input.decodeParams();
            UUID executorPlayer = inputData.commandExecutorPlayer;
            if(executorPlayer == null)
            {
                error("Commands can only be called by a player! Command: "+input.function.toString());
                return CompletableFuture.completedFuture(OutputData.of(input.function, false));
            }
            String playerName = tryGetPlayerName(executorPlayer);
            String playerInfo = " from player: " + playerName;
            if(playerSender != null) {
                playerName = tryGetPlayerName(playerSender);
                warn("The player '"+playerName+"' try's to call the command: '"+input.function.toString()+"' directly from the client side, which is not allowed!");
                return CompletableFuture.completedFuture(OutputData.of(input.function, false));
            }

            if(AsyncForwardingRequest.DEBUG_ENABLE_LOGS)
                info("Received request to handle on master server for command: "+input.function.toString() + playerInfo);


            IServerBankSystemCommandHandler commandHandler = BACKEND_INSTANCES.COMMAND_HANDLER.getSync();
            if(commandHandler == null) {
                if(BACKEND_INSTANCES.isSlaveServer)
                {
                    throw new RuntimeException("[AsyncBankSystemCommandHandler]: This server is configured to be a slave server but the slave seems not to be connected to its master.\n" +
                            "This server instance has no IServerBankManager!");
                }
                throw new RuntimeException("Server bank manager not found");
            }

            switch (input.function) {
                case FunctionType.Banksystem_setBankSystemAdminMode ->			commandHandler.banksystem_setBankSystemAdminMode(executorPlayer, (boolean)inputData.extra);
                case FunctionType.Banksystem_setBankSystemAdminMode_user ->		{
                    ParamGroup_String_Bool data = (ParamGroup_String_Bool)inputData.extra;
                    commandHandler.banksystem_setBankSystemAdminMode_user(executorPlayer, data.string, data.boolValue);
                }
                case FunctionType.Banksystem_allowItem ->                   commandHandler.banksystem_allowItem(executorPlayer, (ItemID)inputData.extra);
                case FunctionType.Banksystem_disallowItem ->                commandHandler.banksystem_disallowItem(executorPlayer, (ItemID)inputData.extra);
                case FunctionType.Money_add ->                              commandHandler.money_add(executorPlayer, (float)inputData.extra);
                case FunctionType.Money_add_user ->  {
                    ParamGroup_String_Float data = (ParamGroup_String_Float)inputData.extra;
                    commandHandler.money_add_user(executorPlayer, data.string, data.floatValue);
                }
                case FunctionType.Money ->				                    commandHandler.money(executorPlayer);
                case FunctionType.Money_set ->				                commandHandler.money_set(executorPlayer, (float)inputData.extra);
                case FunctionType.Money_set_user ->				            {
                    ParamGroup_String_Float data = (ParamGroup_String_Float)inputData.extra;
                    commandHandler.money_set_user(executorPlayer, data.string, data.floatValue);
                }
                case FunctionType.Money_remove ->				            commandHandler.money_remove(executorPlayer, (float)inputData.extra);
                case FunctionType.Money_remove_user ->				        {
                    ParamGroup_String_Float data = (ParamGroup_String_Float)inputData.extra;
                    commandHandler.money_remove_user(executorPlayer, data.string, data.floatValue);
                }
                case FunctionType.Money_send_user ->				        {
                    ParamGroup_String_Float data = (ParamGroup_String_Float)inputData.extra;
                    commandHandler.money_send_user(executorPlayer, data.string, data.floatValue);
                }
                case FunctionType.Money_circulation ->				        commandHandler.money_circulation(executorPlayer);
                case FunctionType.Bank_enableNotifications ->				commandHandler.bank_enableNotifications(executorPlayer);
                case FunctionType.Bank_disableNotifications ->				commandHandler.bank_disableNotifications(executorPlayer);
                case FunctionType.Bank_create ->{
                    int accountNr = commandHandler.bank_create(executorPlayer, (String)inputData.extra);
                    return CompletableFuture.completedFuture(OutputData.of(input.function, accountNr));
                }
                case FunctionType.Bank_show_user ->                         commandHandler.bank_show_user(executorPlayer, (String)inputData.extra);
            }

            return CompletableFuture.completedFuture(OutputData.of(input.function, true));
        }
        @Override
        protected boolean isAllowedToCallByClient(InputData input)
        {
            return false;
        }
    }

    /**
     * Makes sure that the instance exists from the beginning on and not only on the first usage
     */
    public static void setupNetworkPacket()
    {
        Request instance = Request.instance;
    }

    //private static Request forwardingRequest()
    //{
    //    return Request.instance;
    //}
    private CompletableFuture<OutputData> sendRequest(InputData input)
    {
        CompletableFuture<OutputData> future = new CompletableFuture<>();
        CompletableFuture<OutputData> tmpFuture;
        tmpFuture = Request.instance.sendRequestToMaster(input);

        tmpFuture.thenAccept(outputData ->{
            if(AsyncForwardingRequest.DEBUG_ENABLE_LOGS)
                info("Response received for request: "+ input.function.toString());
            future.complete(outputData);
        });

        return future;
    }



    // ================================================================================================================
    //
    //
    //       Custom Objects to hold multiple parameters, passed by a function call
    //       These objects are used to bundle the arguments from a function that uses multiple arguments
    //
    //
    // ================================================================================================================

    public record BankIdentifyAndDataPacket<T>(UUID commandExecutorPlayer, T extra) {

        public static <T> BankIdentifyAndDataPacket<T> of(UUID commandExecutorPlayer, T extra)
        {
            return new BankIdentifyAndDataPacket<>(commandExecutorPlayer, extra);
        }

        // Encode: write accountNr, itemID, then the extra using the provided codec
        public static <T> void encode(
                RegistryFriendlyByteBuf buf,
                BankIdentifyAndDataPacket<T> params,
                StreamCodec<RegistryFriendlyByteBuf, T> extraCodec
        ) {
            buf.writeUUID(params.commandExecutorPlayer);
            ExtraCodecUtils.nullable(extraCodec).encode(buf, params.extra);
        }

        // Decode: read accountNr, itemID, then the extra using the provided codec
        public static <T> BankIdentifyAndDataPacket<T> decode(
                RegistryFriendlyByteBuf buf,
                StreamCodec<RegistryFriendlyByteBuf, T> extraCodec
        ) {
            UUID commandExecutorPlayer = buf.readUUID();
            T extra = ExtraCodecUtils.nullable(extraCodec).decode(buf);
            return new BankIdentifyAndDataPacket<>(commandExecutorPlayer, extra);
        }

        // Factory to build a StreamCodec for a specific extra type
        public static <T> StreamCodec<RegistryFriendlyByteBuf, BankIdentifyAndDataPacket<T>> streamCodec(
                StreamCodec<RegistryFriendlyByteBuf, T> extraCodec
        ) {
            return StreamCodec.of(
                    (buf, params) -> encode(buf, params, extraCodec),
                    buf -> decode(buf, extraCodec)
            );
        }
    }

    private record ParamGroup_UUID_int(UUID uuid, int integer)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, ParamGroup_UUID_int> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, p -> p.uuid,
                ByteBufCodecs.INT, p -> p.integer,
                ParamGroup_UUID_int::new
        );
    }
    private record ParamGroup_User_int(User user, int integer)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, ParamGroup_User_int> STREAM_CODEC = StreamCodec.composite(
                User.STREAM_CODEC, p -> p.user,
                ByteBufCodecs.INT, p -> p.integer,
                ParamGroup_User_int::new
        );
    }
    private record ParamGroup_ItemID_long(ItemID itemID, long longValue)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, ParamGroup_ItemID_long> STREAM_CODEC = StreamCodec.composite(
                ItemID.STREAM_CODEC, p -> p.itemID,
                ByteBufCodecs.VAR_LONG, p -> p.longValue,
                ParamGroup_ItemID_long::new
        );
    }
    private record ParamGroup_String_Float(String string, float floatValue)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, ParamGroup_String_Float> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, p -> p.string,
                ByteBufCodecs.FLOAT, p -> p.floatValue,
                ParamGroup_String_Float::new
        );
    }
    private record ParamGroup_String_Bool(String string, boolean boolValue)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, ParamGroup_String_Bool> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, p -> p.string,
                ByteBufCodecs.BOOL, p -> p.boolValue,
                ParamGroup_String_Bool::new
        );
    }

    // ================================================================================================================
    //
    //
    //       Main Interface implementation below
    //
    //
    // ================================================================================================================

    @Override
    public CompletableFuture<Boolean> banksystem_manage_async(@NotNull UUID executor) {
        if(!MultiServerUtils.checkConnectionToMaster(executor))
            return CompletableFuture.completedFuture(false);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().isBanksystemAdminAsync(executor).thenAcceptAsync(result -> {
            ServerPlayer player = ServerPlayerUtilities.getOnlinePlayer(executor);
            if(result)
            {
                // Open screen for settings GUI
                SyncOpenGUIPacket.send_openBankSystemManageScreen(player);
                future.complete(true);
            }
            else
            {
                ServerPlayerUtilities.printToClientConsole(player,"This command is only for BankSystem admins!");
                future.complete(false);
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<Boolean> banksystem_testScreen_async(@NotNull UUID executor) {
        ServerPlayer player = ServerPlayerUtilities.getOnlinePlayer(executor);
        if(player != null) {
            SyncOpenGUIPacket.send_openTestScreen(player);
            return CompletableFuture.completedFuture(true);
        }
        return CompletableFuture.completedFuture(false);
    }

    @Override
    public CompletableFuture<Boolean> banksystem_setBankSystemAdminMode_async(@NotNull UUID executor, boolean isAdmin) {
        if(!MultiServerUtils.checkConnectionToMaster(executor))
            return CompletableFuture.completedFuture(false);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(InputData.of(FunctionType.Banksystem_setBankSystemAdminMode, executor, isAdmin));
        handleResponse(outputDataFuture, executor);
        CompletableFuture<Boolean>  future = new CompletableFuture<>();
        outputDataFuture.thenAccept(outputData -> {
            future.complete(outputData.decodeResult());
        });
        return future;
    }

    @Override
    public CompletableFuture<Boolean> banksystem_setBankSystemAdminMode_user_async(@NotNull UUID executor, String userName, boolean isAdmin) {
        if(!MultiServerUtils.checkConnectionToMaster(executor))
            return CompletableFuture.completedFuture(false);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(InputData.of(FunctionType.Banksystem_setBankSystemAdminMode_user, executor, new ParamGroup_String_Bool(userName, isAdmin)));
        handleResponse(outputDataFuture, executor);
        CompletableFuture<Boolean>  future = new CompletableFuture<>();
        outputDataFuture.thenAccept(outputData -> {
            future.complete(outputData.decodeResult());
        });
        return future;
    }

    @Override
    public CompletableFuture<Boolean> banksystem_allowItem_async(@NotNull UUID executor, ItemID itemID) {
        if(!MultiServerUtils.checkConnectionToMaster(executor))
            return CompletableFuture.completedFuture(false);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(InputData.of(FunctionType.Banksystem_allowItem, executor, itemID));
        handleResponse(outputDataFuture, executor);
        CompletableFuture<Boolean>  future = new CompletableFuture<>();
        outputDataFuture.thenAccept(outputData -> {
            future.complete(outputData.decodeResult());
        });
        return future;
    }

    @Override
    public CompletableFuture<Boolean> banksystem_disallowItem_async(@NotNull UUID executor, ItemID itemID) {
        if(!MultiServerUtils.checkConnectionToMaster(executor))
            return CompletableFuture.completedFuture(false);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(InputData.of(FunctionType.Banksystem_disallowItem, executor, itemID));
        handleResponse(outputDataFuture, executor);
        CompletableFuture<Boolean>  future = new CompletableFuture<>();
        outputDataFuture.thenAccept(outputData -> {
            future.complete(outputData.decodeResult());
        });
        return future;
    }
    @Override
    public CompletableFuture<Boolean> banksystem_serverInfo_async(@NotNull UUID executor)
    {
        MinecraftServer server = UtilitiesPlatform.getServer();
        if(server == null)
            return CompletableFuture.completedFuture(false);

        ServerPlayerUtilities.printToClientConsole(executor, ServerInfoRequest.createInfo(server).toString());
        return CompletableFuture.completedFuture(true);
    }
    @Override
    public CompletableFuture<Boolean> banksystem_serverNetworkInfo_async(@NotNull UUID executor)
    {
        MinecraftServer server = UtilitiesPlatform.getServer();
        if(server == null)
            return CompletableFuture.completedFuture(false);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        ServerNetworkInfoRequest.sendRequest().thenAccept(serverNetworkInfo -> {
            StringBuilder builder = new StringBuilder();
            builder.append("§8============================================\n");
            List<ServerInfoRequest.ServerInfo> servers = serverNetworkInfo.servers();
            for(int i = 0; i < servers.size(); i++)
            {
                builder.append(servers.get(i));
                if(i < servers.size()-1)
                    builder.append("\n");
            }
            builder.append("\n§8============================================");
            ServerPlayerUtilities.printToClientConsole(executor, builder.toString());
            future.complete(true);
        });
        return future;
    }


    @Override
    public CompletableFuture<Boolean> money_async(@NotNull UUID executor)
    {
        if(!MultiServerUtils.checkConnectionToMaster(executor))
            return CompletableFuture.completedFuture(false);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(InputData.of(FunctionType.Money, executor));
        handleResponse(outputDataFuture, executor);
        CompletableFuture<Boolean>  future = new CompletableFuture<>();
        outputDataFuture.thenAccept(outputData -> {
            future.complete(outputData.decodeResult());
        });
        return future;
    }

    @Override
    public CompletableFuture<Boolean> money_add_async(UUID executor, float amount) {
        if(!MultiServerUtils.checkConnectionToMaster(executor))
            return CompletableFuture.completedFuture(false);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(InputData.of(FunctionType.Money_add, executor, amount));
        handleResponse(outputDataFuture, executor);
        CompletableFuture<Boolean>  future = new CompletableFuture<>();
        outputDataFuture.thenAccept(outputData -> {
            future.complete(outputData.decodeResult());
        });
        return future;
    }

    @Override
    public CompletableFuture<Boolean> money_add_user_async(UUID executor, String userName, float amount) {
        if(!MultiServerUtils.checkConnectionToMaster(executor))
            return CompletableFuture.completedFuture(false);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(InputData.of(FunctionType.Money_add_user, executor, new ParamGroup_String_Float(userName, amount)));
        handleResponse(outputDataFuture, executor);
        CompletableFuture<Boolean>  future = new CompletableFuture<>();
        outputDataFuture.thenAccept(outputData -> {
            future.complete(outputData.decodeResult());
        });
        return future;
    }

    @Override
    public CompletableFuture<Boolean> money_set_async(@NotNull UUID executor, float amount) {
        if(!MultiServerUtils.checkConnectionToMaster(executor))
            return CompletableFuture.completedFuture(false);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(InputData.of(FunctionType.Money_set, executor, amount));
        handleResponse(outputDataFuture, executor);
        CompletableFuture<Boolean>  future = new CompletableFuture<>();
        outputDataFuture.thenAccept(outputData -> {
            future.complete(outputData.decodeResult());
        });
        return future;
    }

    @Override
    public CompletableFuture<Boolean> money_set_user_async(@NotNull UUID executor, String userName, float amount) {
        if(!MultiServerUtils.checkConnectionToMaster(executor))
            return CompletableFuture.completedFuture(false);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(InputData.of(FunctionType.Money_set_user, executor, new ParamGroup_String_Float(userName, amount)));
        handleResponse(outputDataFuture, executor);
        CompletableFuture<Boolean>  future = new CompletableFuture<>();
        outputDataFuture.thenAccept(outputData -> {
            future.complete(outputData.decodeResult());
        });
        return future;
    }

    @Override
    public CompletableFuture<Boolean> money_remove_async(@NotNull UUID executor, float amount) {
        if(!MultiServerUtils.checkConnectionToMaster(executor))
            return CompletableFuture.completedFuture(false);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(InputData.of(FunctionType.Money_remove, executor, amount));
        handleResponse(outputDataFuture, executor);
        CompletableFuture<Boolean>  future = new CompletableFuture<>();
        outputDataFuture.thenAccept(outputData -> {
            future.complete(outputData.decodeResult());
        });
        return future;
    }

    @Override
    public CompletableFuture<Boolean> money_remove_user_async(@NotNull UUID executor, String userName, float amount) {
        if(!MultiServerUtils.checkConnectionToMaster(executor))
            return CompletableFuture.completedFuture(false);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(InputData.of(FunctionType.Money_remove_user, executor, new ParamGroup_String_Float(userName, amount)));
        handleResponse(outputDataFuture, executor);
        CompletableFuture<Boolean>  future = new CompletableFuture<>();
        outputDataFuture.thenAccept(outputData -> {
            future.complete(outputData.decodeResult());
        });
        return future;
    }

    @Override
    public CompletableFuture<Boolean> money_send_user_async(@NotNull UUID executor, String toUserName, float amount) {
        if(!MultiServerUtils.checkConnectionToMaster(executor))
            return CompletableFuture.completedFuture(false);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(InputData.of(FunctionType.Money_send_user, executor, new ParamGroup_String_Float(toUserName, amount)));
        handleResponse(outputDataFuture, executor);
        CompletableFuture<Boolean>  future = new CompletableFuture<>();
        outputDataFuture.thenAccept(outputData -> {
            future.complete(outputData.decodeResult());
        });
        return future;
    }

    @Override
    public CompletableFuture<Boolean> money_circulation_async(@NotNull UUID executor) {
        if(!MultiServerUtils.checkConnectionToMaster(executor))
            return CompletableFuture.completedFuture(false);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(InputData.of(FunctionType.Money_circulation, executor));
        handleResponse(outputDataFuture, executor);
        CompletableFuture<Boolean>  future = new CompletableFuture<>();
        outputDataFuture.thenAccept(outputData -> {
            future.complete(outputData.decodeResult());
        });
        return future;
    }

    @Override
    public CompletableFuture<Boolean> bank_enableNotifications_async(@NotNull UUID executor) {
        if(!MultiServerUtils.checkConnectionToMaster(executor))
            return CompletableFuture.completedFuture(false);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(InputData.of(FunctionType.Bank_enableNotifications, executor));
        handleResponse(outputDataFuture, executor);
        CompletableFuture<Boolean>  future = new CompletableFuture<>();
        outputDataFuture.thenAccept(outputData -> {
            future.complete(outputData.decodeResult());
        });
        return future;
    }

    @Override
    public CompletableFuture<Boolean> bank_disableNotifications_async(@NotNull UUID executor) {
        if(!MultiServerUtils.checkConnectionToMaster(executor))
            return CompletableFuture.completedFuture(false);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(InputData.of(FunctionType.Bank_disableNotifications, executor));
        handleResponse(outputDataFuture, executor);
        CompletableFuture<Boolean>  future = new CompletableFuture<>();
        outputDataFuture.thenAccept(outputData -> {
            future.complete(outputData.decodeResult());
        });
        return future;
    }



    @Override
    public CompletableFuture<Boolean> bank_manage_async(@NotNull UUID executor)
    {
        if(!MultiServerUtils.checkConnectionToMaster(executor))
            return CompletableFuture.completedFuture(false);
        CompletableFuture<IAsyncBankAccount> account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().getOrCreatePersonalBankAccountAsync(executor);
        CompletableFuture<Boolean> isAdmin = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync().isBanksystemAdminAsync(executor);
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        account.thenAccept(result -> {
            if(result == null)
            {
                //ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getUserNotFoundMessage(player.getName().getString()));
                future.complete(false);
                return;
            }
            isAdmin.thenAccept(bool -> {
                ServerPlayer player = ServerPlayerUtilities.getOnlinePlayer(executor);
                SyncOpenGUIPacket.send_openBankAccountScreen(player, player.getUUID(), result.getAccountNumberAsync(), bool);
                future.complete(true);
            });
        });
        return future;
    }
    @Override
    public CompletableFuture<Boolean> bank_manage_account_async(@NotNull UUID executor, String accountName)
    {
        if(!MultiServerUtils.checkConnectionToMaster(executor))
            return CompletableFuture.completedFuture(false);
        IAsyncBankManager manager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync();
        CompletableFuture<IAsyncBankAccount> account = manager.getBankAccountByNameAsync(accountName);
        CompletableFuture<Boolean> isAdmin = manager.isBanksystemAdminAsync(executor);
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        account.thenAccept(result -> {

            if(result == null)
            {
                future.complete(false);
                return;
            }
            CompletableFuture<Boolean> hasPermission = result.hasUserAsync(executor);
            hasPermission.thenAccept(hasPermissionResult -> {
                isAdmin.thenAccept(isAdminResult -> {

                    if(!hasPermissionResult && !isAdminResult) {
                        future.complete(false);
                        return;
                    }
                    ServerPlayer player = ServerPlayerUtilities.getOnlinePlayer(executor);
                    SyncOpenGUIPacket.send_openBankAccountScreen(player, player.getUUID(), result.getAccountNumberAsync(), isAdminResult);
                    future.complete(true);
                });
            });
        });
        return future;
    }


    @Override
    public CompletableFuture<Boolean> bank_manage_account_async(@NotNull UUID executor, int accountNr)
    {
        if(!MultiServerUtils.checkConnectionToMaster(executor))
            return CompletableFuture.completedFuture(false);
        IAsyncBankManager manager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync();
        CompletableFuture<IAsyncBankAccount> account = manager.getBankAccountAsync(accountNr);
        CompletableFuture<Boolean> isAdmin = manager.isBanksystemAdminAsync(executor);
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        account.thenAccept(result -> {

            if(result == null)
            {
                future.complete(false);
                return;
            }
            CompletableFuture<Boolean> hasPermission = result.hasUserAsync(executor);
            hasPermission.thenAccept(hasPermissionResult -> {
                isAdmin.thenAccept(isAdminResult -> {

                    if(!hasPermissionResult && !isAdminResult) {
                        future.complete(false);
                        return;
                    }
                    ServerPlayer player = ServerPlayerUtilities.getOnlinePlayer(executor);
                    SyncOpenGUIPacket.send_openBankAccountScreen(player, player.getUUID(), result.getAccountNumberAsync(), isAdminResult);
                    future.complete(true);
                });
            });
        });
        return future;
    }

    @Override
    public CompletableFuture<IAsyncBankAccount> bank_create_async(@NotNull UUID executor, String accountName)
    {
        if(!MultiServerUtils.checkConnectionToMaster(executor))
            return CompletableFuture.completedFuture(null);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(InputData.of(FunctionType.Bank_create, executor, accountName));
        handleResponse(outputDataFuture, executor);
        CompletableFuture<IAsyncBankAccount> future = new CompletableFuture<>();
        outputDataFuture.thenAccept(outputData -> {
            int accountNr = outputData.decodeResult();
            IAsyncBankAccount account = AsyncBankAccount.createSlaveServerBank(accountNr);
            future.complete(account);
        });
        return future;
    }


    @Override
    public CompletableFuture<Boolean> bank_show_user_async(@NotNull UUID executor, String userName)
    {
        if(!MultiServerUtils.checkConnectionToMaster(executor))
            return CompletableFuture.completedFuture(false);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(InputData.of(FunctionType.Bank_show_user, executor, userName));
        handleResponse(outputDataFuture, executor);
        CompletableFuture<Boolean>  future = new CompletableFuture<>();
        outputDataFuture.thenAccept(outputData -> {
            future.complete(outputData.decodeResult());
        });
        return future;
    }





    private static void handleResponse(CompletableFuture<OutputData> response, UUID executor)
    {
        response.whenComplete((result, throwable) -> {
            boolean success = throwable == null && (Boolean)result.decodeResult();
            String playerName = Request.tryGetPlayerName(executor);
            String text = "Async command execution result for command "+result.function.name()+" from player: "+playerName+" Result: "+(success?"Success":"Failure");
            if(throwable!=null)
                error(text, throwable);
            else
                info(text);

            if(!success)
            {
                ServerPlayerUtilities.printToClientConsole(executor, text);
            }
        });
    }

    private static void info(String msg)
    {
        BACKEND_INSTANCES.LOGGER.info("[AsyncBankSystemCommandHandler] " + msg);
    }
    private static void error(String msg)
    {
        BACKEND_INSTANCES.LOGGER.error("[AsyncBankSystemCommandHandler] " + msg);
    }
    private static void error(String msg, Throwable e)
    {
        BACKEND_INSTANCES.LOGGER.error("[AsyncBankSystemCommandHandler] " + msg, e);
    }
    private static void warn(String msg)
    {
        BACKEND_INSTANCES.LOGGER.warn("[AsyncBankSystemCommandHandler] " + msg);
    }
    private static void debug(String msg)
    {
        BACKEND_INSTANCES.LOGGER.debug("[AsyncBankSystemCommandHandler] " + msg);
    }
}
