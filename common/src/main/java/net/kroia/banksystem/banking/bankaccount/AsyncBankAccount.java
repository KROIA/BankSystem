package net.kroia.banksystem.banking.bankaccount;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bank.IAsyncBank;
import net.kroia.banksystem.api.bankaccount.IAsyncBankAccount;
import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.bank.AsyncBank;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.banking.clientdata.BankUserData;
import net.kroia.banksystem.banking.clientdata.UserData;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.MultiServerUtils;
import net.kroia.banksystem.util.async_function_forwarding.AsyncForwardingRequest;
import net.kroia.banksystem.util.async_function_forwarding.AsyncFunctionDataCodecs;
import net.kroia.banksystem.util.async_function_forwarding.AsyncFunctionInputData;
import net.kroia.banksystem.util.async_function_forwarding.AsyncFunctionOutputData;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.kroia.modutilities.networking.client_server.arrs.AsynchronousRequestResponseSystem;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class AsyncBankAccount implements IAsyncBankAccount {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
        AsyncBank.setBackend(backend);
    }


    private final boolean isClientSide;
    private final int accountNr;

    private AsyncBankAccount(int accountNr, boolean clientSide) {
        this.accountNr = accountNr;
        this.isClientSide = clientSide;
    }

    public static AsyncBankAccount createClientBank(int accountNr)
    {
        return new AsyncBankAccount(accountNr, true);
    }
    public static AsyncBankAccount createSlaveServerBank(int accountNr)
    {
        return new AsyncBankAccount(accountNr, false);
    }
    public static AsyncBankAccount createBankAccount(int accountNr, boolean clientSide)
    {
        return new AsyncBankAccount(accountNr, clientSide);
    }
    private AsyncBank createBank(ItemID itemID)
    {
        return AsyncBank.createBank(accountNr, itemID, isClientSide);
    }

    /**
     * Enumeration to specify each function that can be forwarded
     */
    public enum FunctionType
    {
        GetAccountDataAsync_1,
        GetAccountDataAsync_2,
        GetBankDataAsync_1,
        GetBankDataAsync_2,
        GetUserDataAsync_1,
        GetUserDataAsync_2,
        GetPersonalBankOwnerDataAsync,
        //GetAccountNumberAsync,
        SetAccountNameAsync,
        GetAccountNameAsync,
        SetAccountIconAsync,
        GetAccountIconAsync,
        GetPermissionAsync,
        HasPermissionAsync,
        SetPermissionAsync,
        AddUserAsync,
        SetUsersAsync,
        RemoveUserAsync,
        HasAnyUserAsync,
        HasUserAsync,
        GetPersonalBankOwnerAsync,
        CreateBankAsync,
        RemoveBankAsync,
        RemoveEmptyBanksAsync,
        RemoveAllBanksAsync,
        HasAnyBankAsync,
        HasBankAsync,
        //GetBankAsync,
        //GetOrCreateBankAsync,
        //GetAllBanksAsync,
        ToJsonAsync,
        ToJsonStringAsync,
    }

    /**
     * Map of codec pairs for each function
     * If a codec is set to null, that means the argument is not available.
     *      -> inputParamsCodec  == null: The function does not take any parameters
     *      -> outputParamsCodec == null: The function does not return any value
     */
    private static AsyncFunctionDataCodecs codecPacket(@Nullable StreamCodec<RegistryFriendlyByteBuf, ?> inputParamsCodec, @Nullable StreamCodec<RegistryFriendlyByteBuf, ?> outputParamsCodec)
    {
        return new AsyncFunctionDataCodecs(BankIdentifyAndDataPacket.streamCodec(inputParamsCodec), outputParamsCodec);
    }
    public static final Map<FunctionType, AsyncFunctionDataCodecs> codecs = new HashMap<>(){{
        put(FunctionType.GetAccountDataAsync_1,             codecPacket(null, BankAccountData.STREAM_CODEC));
        put(FunctionType.GetAccountDataAsync_2,             codecPacket(ItemID.STREAM_CODEC, BankAccountData.STREAM_CODEC));
        put(FunctionType.GetBankDataAsync_1,                codecPacket(ItemID.STREAM_CODEC, BankData.STREAM_CODEC));
        put(FunctionType.GetBankDataAsync_2,                codecPacket(null, ExtraCodecUtils.listStreamCodec(BankData.STREAM_CODEC)));
        put(FunctionType.GetUserDataAsync_1,                codecPacket(UUIDUtil.STREAM_CODEC.cast(), BankUserData.STREAM_CODEC));
        put(FunctionType.GetUserDataAsync_2,                codecPacket(null, ExtraCodecUtils.listStreamCodec(BankUserData.STREAM_CODEC)));
        put(FunctionType.GetPersonalBankOwnerDataAsync,     codecPacket(null, UserData.STREAM_CODEC));
        //put(FunctionType.GetAccountNumberAsync,             codecPacket(null), null));
        put(FunctionType.SetAccountNameAsync,               codecPacket(ByteBufCodecs.STRING_UTF8.cast(), null));
        put(FunctionType.GetAccountNameAsync,               codecPacket(null, ByteBufCodecs.STRING_UTF8.cast()));
        put(FunctionType.SetAccountIconAsync,               codecPacket(ItemID.STREAM_CODEC, null));
        put(FunctionType.GetAccountIconAsync,               codecPacket(null, ItemID.STREAM_CODEC));
        put(FunctionType.GetPermissionAsync,                codecPacket(UUIDUtil.STREAM_CODEC.cast(), ByteBufCodecs.INT.cast()));
        put(FunctionType.HasPermissionAsync,                codecPacket(ParamGroup_UUID_int.STREAM_CODEC, ByteBufCodecs.BOOL.cast()));
        put(FunctionType.SetPermissionAsync,                codecPacket(ParamGroup_UUID_int.STREAM_CODEC, null));
        put(FunctionType.AddUserAsync,                      codecPacket(ParamGroup_User_int.STREAM_CODEC, null));
        put(FunctionType.SetUsersAsync,                     codecPacket(ExtraCodecUtils.mapStreamCodec(User.STREAM_CODEC, ByteBufCodecs.INT, HashMap<User,Integer>::new), null));
        put(FunctionType.RemoveUserAsync,                   codecPacket(UUIDUtil.STREAM_CODEC.cast(), null));
        put(FunctionType.HasAnyUserAsync,                   codecPacket(null, ByteBufCodecs.BOOL.cast()));
        put(FunctionType.HasUserAsync,                      codecPacket(UUIDUtil.STREAM_CODEC.cast(), ByteBufCodecs.BOOL.cast()));
        put(FunctionType.GetPersonalBankOwnerAsync,         codecPacket(null, User.STREAM_CODEC));
        put(FunctionType.CreateBankAsync,                   codecPacket(ParamGroup_ItemID_long.STREAM_CODEC, ByteBufCodecs.BOOL.cast()));
        put(FunctionType.RemoveBankAsync,                   codecPacket(ItemID.STREAM_CODEC, null));
        put(FunctionType.RemoveEmptyBanksAsync,             codecPacket(null, ExtraCodecUtils.listStreamCodec(ItemID.STREAM_CODEC)));
        put(FunctionType.RemoveAllBanksAsync,               codecPacket(null, null));
        put(FunctionType.HasAnyBankAsync,                   codecPacket(null, ByteBufCodecs.BOOL.cast()));
        put(FunctionType.HasBankAsync,                      codecPacket(ItemID.STREAM_CODEC, ByteBufCodecs.BOOL.cast()));
        //put(FunctionType.GetBankAsync,                      codecPacket(ItemID.STREAM_CODEC, null));
        //put(FunctionType.GetOrCreateBankAsync,              codecPacket(ItemID.STREAM_CODEC, null));
        //put(FunctionType.GetAllBanksAsync,                  codecPacket(null, null));
        put(FunctionType.ToJsonAsync,                       codecPacket(null, ExtraCodecUtils.JSON_ELEMENT_CODEC));
        put(FunctionType.ToJsonStringAsync,                 codecPacket(null, ByteBufCodecs.STRING_UTF8.cast()));

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
        public static <T> InputData of(FunctionType functionType, int accountNr, T result)
        {
            BankIdentifyAndDataPacket<T> packet = BankIdentifyAndDataPacket.of(accountNr, result);
            return (InputData) AsyncFunctionInputData.of(codecs.get(functionType).inputParamsCodec, functionType, packet, InputData::new);
        }
        public static InputData of(FunctionType functionType, int accountNr)
        {
            BankIdentifyAndDataPacket packet = BankIdentifyAndDataPacket.of(accountNr, null);
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
                info("Sending request to server for function: "+input.function.toString());
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
            String playerInfo = "";
            String playerName = "";
            if(playerSender != null) {
                playerName = tryGetPlayerName(playerSender);
                playerInfo = " from player: " + playerName;
            }
            if(AsyncForwardingRequest.DEBUG_ENABLE_LOGS)
                info("Received request to handle on master server for function: "+input.function.toString() + playerInfo);
            BankIdentifyAndDataPacket inputData = input.decodeParams();
            if(!isAllowedToCallByUntrustedSlaveServer(input))
            {
                if(!BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().isSlaveServerTrusted(slaveID))
                {
                    warn("The slave server: '"+slaveID+"' try's to call the function: '"+input.function.toString()+"' which is not allowed for an untrusted slave server!");
                    return CompletableFuture.completedFuture(OutputData.of(input.function));
                }
            }
            if(playerSender != null)
            {
                if(!isAllowedToCallByClient(input))
                {
                    warn("The player '"+playerName+"' try's to call the function: '"+input.function.toString()+"' which is not allowed from the client side!");
                    return CompletableFuture.completedFuture(OutputData.of(input.function));
                }
            }
            int accountNr = inputData.accountNr;
            IServerBankManager serverBankManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
            if(serverBankManager == null) {
                if(BACKEND_INSTANCES.isSlaveServer)
                {
                    throw new RuntimeException("[AsyncBankAccount]: This server is configured to be a slave server but the slave seems not to be connected to its master.\n" +
                            "This server instance has no IServerBankManager!");
                }
                throw new RuntimeException("Server bank manager not found");
            }
            ISyncServerBankAccount bankAccount = serverBankManager.getBankAccount(accountNr);
            if(bankAccount == null)
                return CompletableFuture.completedFuture(OutputData.of(input.function));


            return CompletableFuture.completedFuture(switch (input.function) {
                case FunctionType.GetAccountDataAsync_1 ->              OutputData.of(input.function, bankAccount.getAccountData());
                case FunctionType.GetAccountDataAsync_2 ->              OutputData.of(input.function, bankAccount.getAccountData((ItemID)inputData.extra));
                case FunctionType.GetBankDataAsync_1	-> 		        OutputData.of(input.function, bankAccount.getBankData((ItemID)inputData.extra));
                case FunctionType.GetBankDataAsync_2	-> 		        OutputData.of(input.function, bankAccount.getBankData());
                case FunctionType.GetUserDataAsync_1	-> 		        OutputData.of(input.function, bankAccount.getUserData((UUID)inputData.extra));
                case FunctionType.GetUserDataAsync_2	-> 		        OutputData.of(input.function, bankAccount.getUserData());
                case FunctionType.GetPersonalBankOwnerDataAsync	-> 		OutputData.of(input.function, bankAccount.getPersonalBankOwnerData());
                //case FunctionType.GetAccountNumberAsync	-> 		OutputData.of(input.function, bankAccount.getAccountData());
                case FunctionType.SetAccountNameAsync	-> 		        {
                    if(playerSender != null && !bankAccount.hasPermission(playerSender, BankPermission.MANAGE))
                        yield OutputData.of(input.function);
                    bankAccount.setAccountName((String)inputData.extra);
                    yield OutputData.of(input.function);
                }
                case FunctionType.GetAccountNameAsync	-> 		        OutputData.of(input.function, bankAccount.getAccountName());
                case FunctionType.SetAccountIconAsync	-> 		        {
                    if(playerSender != null && !bankAccount.hasPermission(playerSender, BankPermission.MANAGE))
                        yield OutputData.of(input.function);
                    bankAccount.setAccountIcon((ItemID)inputData.extra);
                    yield OutputData.of(input.function);
                }
                case FunctionType.GetAccountIconAsync	-> 		        OutputData.of(input.function, bankAccount.getAccountIcon());
                case FunctionType.GetPermissionAsync	-> 		        OutputData.of(input.function, bankAccount.getPermission((UUID)inputData.extra));
                case FunctionType.HasPermissionAsync	-> 		        {
                    ParamGroup_UUID_int data = (ParamGroup_UUID_int)inputData.extra;
                    yield OutputData.of(input.function, bankAccount.hasPermission(data.uuid, data.integer));
                }
                case FunctionType.SetPermissionAsync	-> 		        {
                    ParamGroup_UUID_int data = (ParamGroup_UUID_int)inputData.extra;
                    bankAccount.setPermission(data.uuid, data.integer);
                    yield OutputData.of(input.function);
                }
                case FunctionType.AddUserAsync	-> 		                {
                    ParamGroup_User_int  data = (ParamGroup_User_int)inputData.extra;
                    bankAccount.addUser(data.user, data.integer);
                    yield OutputData.of(input.function);
                }
                case FunctionType.SetUsersAsync	-> 		               {
                    bankAccount.setUsers((Map<User, Integer>)inputData.extra);
                    yield OutputData.of(input.function);
                }
                case FunctionType.RemoveUserAsync	-> 		            {
                    bankAccount.removeUser((UUID)inputData.extra);
                    yield OutputData.of(input.function);
                }
                case FunctionType.HasAnyUserAsync	-> 		            OutputData.of(input.function, bankAccount.hasAnyUser());
                case FunctionType.HasUserAsync	-> 		                OutputData.of(input.function, bankAccount.hasUser((UUID)inputData.extra));
                case FunctionType.GetPersonalBankOwnerAsync	-> 		    OutputData.of(input.function, bankAccount.getPersonalBankOwner());
                case FunctionType.CreateBankAsync	-> 		            {
                    ParamGroup_ItemID_long data = (ParamGroup_ItemID_long)inputData.extra;
                    yield OutputData.of(input.function, bankAccount.createBank(data.itemID, data.longValue) != null);
                }
                case FunctionType.RemoveBankAsync	-> 		            {
                    bankAccount.removeBank((ItemID)inputData.extra);
                    yield OutputData.of(input.function);
                }
                case FunctionType.RemoveEmptyBanksAsync	-> 		        OutputData.of(input.function, bankAccount.removeEmptyBanks());
                case FunctionType.RemoveAllBanksAsync	-> 		        {
                    bankAccount.removeAllBanks();
                    yield OutputData.of(input.function);
                }
                case FunctionType.HasAnyBankAsync	-> 		            OutputData.of(input.function, bankAccount.hasAnyBank());
                case FunctionType.HasBankAsync	-> 		                OutputData.of(input.function, bankAccount.hasBank((ItemID)inputData.extra));
                //case FunctionType.GetBankAsync	-> 		OutputData.of(input.function, bankAccount.getAccountData());
                //case FunctionType.GetOrCreateBankAsync	-> 		OutputData.of(input.function, bankAccount.getAccountData());
                //case FunctionType.GetAllBanksAsync	-> 		OutputData.of(input.function, bankAccount.getAccountData());
                case FunctionType.ToJsonAsync	-> 		                OutputData.of(input.function, bankAccount.toJson());
                case FunctionType.ToJsonStringAsync	-> 		            OutputData.of(input.function, bankAccount.toJsonString());

            });
        }
        @Override
        protected boolean isAllowedToCallByClient(InputData input)
        {
            return switch (input.function) {
                case FunctionType.GetAccountDataAsync_1,
                     FunctionType.GetAccountDataAsync_2,
                     FunctionType.GetBankDataAsync_1,
                     FunctionType.GetBankDataAsync_2,
                     FunctionType.GetUserDataAsync_1,
                     FunctionType.GetUserDataAsync_2,
                     FunctionType.GetPersonalBankOwnerDataAsync,
                     FunctionType.SetAccountNameAsync,
                     FunctionType.GetAccountNameAsync,
                     FunctionType.SetAccountIconAsync,
                     FunctionType.GetAccountIconAsync,
                     FunctionType.GetPermissionAsync,
                     FunctionType.HasPermissionAsync,
                     FunctionType.HasAnyUserAsync,
                     FunctionType.HasUserAsync,
                     FunctionType.GetPersonalBankOwnerAsync,
                     FunctionType.HasAnyBankAsync,
                     FunctionType.HasBankAsync,
                     FunctionType.ToJsonAsync,
                     FunctionType.ToJsonStringAsync -> true;

                default -> false;
            };
        }
        @Override
        protected boolean isAllowedToCallByUntrustedSlaveServer(InputData input) {
            return switch (input.function) {
                case FunctionType.GetAccountDataAsync_1,
                     FunctionType.GetAccountDataAsync_2,
                     FunctionType.GetBankDataAsync_1,
                     FunctionType.GetBankDataAsync_2,
                     FunctionType.GetUserDataAsync_1,
                     FunctionType.GetUserDataAsync_2,
                     FunctionType.GetPersonalBankOwnerDataAsync,
                     //FunctionType.SetAccountNameAsync,
                     FunctionType.GetAccountNameAsync,
                     //FunctionType.SetAccountIconAsync,
                     FunctionType.GetAccountIconAsync,
                     FunctionType.GetPermissionAsync,
                     FunctionType.HasPermissionAsync,
                     FunctionType.HasAnyUserAsync,
                     FunctionType.HasUserAsync,
                     FunctionType.GetPersonalBankOwnerAsync,
                     FunctionType.HasAnyBankAsync,
                     FunctionType.HasBankAsync,
                     FunctionType.ToJsonAsync,
                     FunctionType.ToJsonStringAsync -> true;

                default -> false;
            };
        }
    }

    /**
     * Makes sure that the instance exists from the beginning on and not only on the first usage
     */
    public static void setupNetworkPacket()
    {
        Request instance = Request.instance;
    }

    private CompletableFuture<OutputData> sendRequest(InputData input)
    {
        CompletableFuture<OutputData> future = new CompletableFuture<>();
        CompletableFuture<OutputData> tmpFuture;
        if(isClientSide)
            tmpFuture = Request.instance.sendRequestToServer(input);
        else
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

    public record BankIdentifyAndDataPacket<T>(int accountNr, T extra) {

        public static <T> BankIdentifyAndDataPacket<T> of(int accountNr, T extra)
        {
            return new BankIdentifyAndDataPacket<>(accountNr, extra);
        }

        // Encode: write accountNr, itemID, then the extra using the provided codec
        public static <T> void encode(
                RegistryFriendlyByteBuf buf,
                BankIdentifyAndDataPacket<T> params,
                StreamCodec<RegistryFriendlyByteBuf, T> extraCodec
        ) {
            buf.writeInt(params.accountNr);
            ExtraCodecUtils.nullable(extraCodec).encode(buf, params.extra);
        }

        // Decode: read accountNr, itemID, then the extra using the provided codec
        public static <T> BankIdentifyAndDataPacket<T> decode(
                RegistryFriendlyByteBuf buf,
                StreamCodec<RegistryFriendlyByteBuf, T> extraCodec
        ) {
            int accountNr = buf.readInt();
            T extra = ExtraCodecUtils.nullable(extraCodec).decode(buf);
            return new BankIdentifyAndDataPacket<>(accountNr, extra);
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

    // ================================================================================================================
    //
    //
    //       Main Interface implementation below
    //
    //
    // ================================================================================================================






    @Override
    public CompletableFuture<BankAccountData> getAccountDataAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(new BankAccountData(accountNr,"", null, null, Map.of(), Map.of()));
        CompletableFuture<BankAccountData> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetAccountDataAsync_1, accountNr);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<@Nullable BankAccountData> getAccountDataAsync(ItemID itemID) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(null);
        CompletableFuture<BankAccountData> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetAccountDataAsync_2, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<@Nullable BankData> getBankDataAsync(ItemID itemID) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(null);
        CompletableFuture<@Nullable BankData> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetBankDataAsync_1, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<List<BankData>> getBankDataAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<BankData>> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetBankDataAsync_2, accountNr);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<@Nullable BankUserData> getUserDataAsync(UUID userUUID) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(null);
        CompletableFuture<@Nullable BankUserData> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetUserDataAsync_1, accountNr, userUUID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<List<BankUserData>> getUserDataAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<BankUserData>> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetUserDataAsync_2, accountNr);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<@Nullable UserData> getPersonalBankOwnerDataAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(null);
        CompletableFuture<@Nullable UserData> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetPersonalBankOwnerDataAsync, accountNr);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public int getAccountNumberAsync() {
        return accountNr;
    }

    @Override
    public void setAccountNameAsync(String accountName) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return;
        InputData inputData = InputData.of(FunctionType.SetAccountNameAsync, accountNr, accountName);
        sendRequest(inputData);
    }

    @Override
    public CompletableFuture<String> getAccountNameAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture("");
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetAccountNameAsync, accountNr);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public void setAccountIconAsync(@Nullable ItemID accountIcon) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return;
        InputData inputData = InputData.of(FunctionType.SetAccountIconAsync, accountNr, accountIcon);
        sendRequest(inputData);
    }

    @Override
    public CompletableFuture<@Nullable ItemID> getAccountIconAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(null);
        CompletableFuture<@Nullable ItemID> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetAccountIconAsync, accountNr);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Integer> getPermissionAsync(UUID userUUID) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(0);
        CompletableFuture<Integer> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetPermissionAsync, accountNr, userUUID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Boolean> hasPermissionAsync(UUID userUUID, int permission) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(false);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        try {
            InputData inputData = InputData.of(FunctionType.HasPermissionAsync, accountNr, new ParamGroup_UUID_int(userUUID, permission));
            CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
            outputDataFuture.thenAccept((outputData) -> future.complete(outputData.decodeResult()));
        }catch(Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    @Override
    public void setPermissionAsync(UUID userUUID, int permission) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return;
        InputData inputData = InputData.of(FunctionType.SetPermissionAsync, accountNr, new ParamGroup_UUID_int(userUUID, permission));
        sendRequest(inputData);
    }

    @Override
    public void addUserAsync(User user, int permission) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return;
        InputData inputData = InputData.of(FunctionType.AddUserAsync, accountNr, new ParamGroup_User_int(user, permission));
        sendRequest(inputData);
    }

    @Override
    public void setUsersAsync(Map<User, Integer> userList) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return;
        InputData inputData = InputData.of(FunctionType.SetUsersAsync, accountNr, userList);
        sendRequest(inputData);
    }

    @Override
    public void removeUserAsync(UUID userUUID) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return;
        InputData inputData = InputData.of(FunctionType.RemoveUserAsync, accountNr, userUUID);
        sendRequest(inputData);
    }

    @Override
    public CompletableFuture<Boolean> hasAnyUserAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(false);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.HasAnyUserAsync, accountNr);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Boolean> hasUserAsync(UUID userUUID) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(false);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.HasUserAsync, accountNr, userUUID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<@Nullable User> getPersonalBankOwnerAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(null);
        CompletableFuture<@Nullable User> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetPersonalBankOwnerAsync, accountNr);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<@Nullable IAsyncBank> createBankAsync(ItemID itemID, long startBalance) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(null);

        // Returns bool!
        CompletableFuture<@Nullable IAsyncBank> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.CreateBankAsync, accountNr, new ParamGroup_ItemID_long(itemID, startBalance));
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)->
        {
            if(outputData.decodeResult())
                future.complete(createBank(itemID));
            else
                future.complete(null);
        });
        return future;
    }

    @Override
    public void removeBankAsync(ItemID itemID) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return;
        InputData inputData = InputData.of(FunctionType.RemoveBankAsync, accountNr, itemID);
        sendRequest(inputData);
    }

    @Override
    public CompletableFuture<List<ItemID>> removeEmptyBanksAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<ItemID>> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.RemoveEmptyBanksAsync, accountNr);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public void removeAllBanksAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return;
        InputData inputData = InputData.of(FunctionType.RemoveAllBanksAsync, accountNr);
        sendRequest(inputData);
    }

    @Override
    public CompletableFuture<Boolean> hasAnyBankAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(false);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.HasAnyBankAsync, accountNr);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Boolean> hasBankAsync(ItemID itemID) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(false);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.HasBankAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<@Nullable IAsyncBank> getBankAsync(ItemID itemID) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(null);
        CompletableFuture<@Nullable IAsyncBank> future = new CompletableFuture<>();
        hasBankAsync(itemID).thenAccept((hasItem) ->{
            if(hasItem)
                future.complete(createBank(itemID));
            else
                future.complete(null);
        });
        return future;
    }

    @Override
    public CompletableFuture<@Nullable IAsyncBank> getOrCreateBankAsync(ItemID itemID) {
        return createBankAsync(itemID, 0);
    }

    @Override
    public CompletableFuture<Map<ItemID, IAsyncBank>> getAllBanksAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(Map.of());
        CompletableFuture<Map<ItemID, IAsyncBank>> future = new CompletableFuture<>();
        getBankDataAsync().thenAccept((bankData) -> {
            Map<ItemID, IAsyncBank>  map = new HashMap<>();
            for(BankData data : bankData)
            {
                map.put(data.itemID(), createBank(data.itemID()));
            }
            future.complete(map);
        });
        return future;
    }

    @Override
    public CompletableFuture<JsonElement> toJsonAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(new JsonObject());
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.ToJsonAsync, accountNr);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<String> toJsonStringAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture("");
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.ToJsonStringAsync, accountNr);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }



    private static void info(String msg)
    {
        BACKEND_INSTANCES.LOGGER.info("[AsyncBankAccount] " + msg);
    }
    private static void error(String msg)
    {
        BACKEND_INSTANCES.LOGGER.error("[AsyncBankAccount] " + msg);
    }
    private static void error(String msg, Throwable e)
    {
        BACKEND_INSTANCES.LOGGER.error("[AsyncBankAccount] " + msg, e);
    }
    private static void warn(String msg)
    {
        BACKEND_INSTANCES.LOGGER.warn("[AsyncBankAccount] " + msg);
    }
    private static void debug(String msg)
    {
        BACKEND_INSTANCES.LOGGER.debug("[AsyncBankAccount] " + msg);
    }

}
