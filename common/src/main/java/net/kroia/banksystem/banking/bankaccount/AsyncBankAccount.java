package net.kroia.banksystem.banking.bankaccount;

import com.google.gson.JsonElement;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bank.IAsyncBank;
import net.kroia.banksystem.api.bankaccount.IAsyncBankAccount;
import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.banking.clientdata.BankUserData;
import net.kroia.banksystem.banking.clientdata.UserData;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.async_function_forwarding.AsyncForwardingRequest;
import net.kroia.banksystem.util.async_function_forwarding.AsyncFunctionDataCodecs;
import net.kroia.banksystem.util.async_function_forwarding.AsyncFunctionInputData;
import net.kroia.banksystem.util.async_function_forwarding.AsyncFunctionOutputData;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.kroia.modutilities.networking.client_server.arrs.AsynchronousRequestResponseSystem;
import net.minecraft.network.RegistryFriendlyByteBuf;
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
        SyncServerBankAccount.setBackend(backend);
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
    public static AsyncBankAccount createBank(int accountNr, boolean clientSide)
    {
        return new AsyncBankAccount(accountNr, clientSide);
    }


    /**
     * Enumeration to specify each function that can be forwarded
     */
    public enum FunctionType
    {
        GetAccountDataAsync_1,
        GetAccountDataAsync_2
    }

    /**
     * Map of codec pairs for each function
     * If a codec is set to null, that means the argument is not available.
     *      -> inputParamsCodec  == null: The function does not take any parameters
     *      -> outputParamsCodec == null: The function does not return any value
     */
    public static final Map<FunctionType, AsyncFunctionDataCodecs> codecs = new HashMap<>(){{
        put(FunctionType.GetAccountDataAsync_1, new AsyncFunctionDataCodecs(BankIdentifyAndDataPacket.streamCodec(null), BankAccountData.STREAM_CODEC));
        put(FunctionType.GetAccountDataAsync_2, new AsyncFunctionDataCodecs(BankIdentifyAndDataPacket.streamCodec(ItemID.STREAM_CODEC), BankAccountData.STREAM_CODEC));
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
        public CompletableFuture<OutputData> handleOnMasterServer(InputData input, @Nullable UUID playerSender) {
            info("Received request to handle on master server for function: "+input.function.toString());
            BankIdentifyAndDataPacket inputData = input.decodeParams();
            int accountNr = inputData.accountNr;
            ISyncServerBankAccount bankAccount = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().getBankAccount(accountNr);
            if(bankAccount == null)
                return CompletableFuture.completedFuture(OutputData.of(input.function));


            return CompletableFuture.completedFuture(switch (input.function) {
                case FunctionType.GetAccountDataAsync_1 -> OutputData.of(input.function, bankAccount.getAccountData());
                case FunctionType.GetAccountDataAsync_2 -> OutputData.of(input.function, bankAccount.getAccountData((ItemID)inputData.extra));


            });
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
        if(isClientSide)
            tmpFuture = Request.instance.sendRequestToServer(input);
        else
            tmpFuture = Request.instance.sendRequestToMaster(input);

        tmpFuture.thenAccept(outputData ->{
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

    /*private record ParamGroup_UUID_bool(UUID uuid, boolean bool)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, ParamGroup_UUID_bool> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, p -> p.uuid,
                ByteBufCodecs.BOOL, p -> p.bool,
                ParamGroup_UUID_bool::new
        );
    }*/

    // ================================================================================================================
    //
    //
    //       Main Interface implementation below
    //
    //
    // ================================================================================================================






    @Override
    public CompletableFuture<BankAccountData> getAccountDataAsync() {
        CompletableFuture<BankAccountData> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetAccountDataAsync_1, accountNr);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<@Nullable BankAccountData> getAccountDataAsync(ItemID itemID) {
        CompletableFuture<BankAccountData> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetAccountDataAsync_2, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<@Nullable BankData> getBankDataAsync(ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<List<BankData>> getBankDataAsync() {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable BankUserData> getUserDataAsync(UUID userUUID) {
        return null;
    }

    @Override
    public CompletableFuture<List<BankUserData>> getUserDataAsync() {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable UserData> getPersonalBankOwnerDataAsync() {
        return null;
    }

    @Override
    public int getAccountNumberAsync() {
        return accountNr;
    }

    @Override
    public void setAccountNameAsync(String accountName) {

    }

    @Override
    public CompletableFuture<String> getAccountNameAsync() {
        return null;
    }

    @Override
    public void setAccountIconAsync(@Nullable ItemID accountIcon) {

    }

    @Override
    public CompletableFuture<@Nullable ItemID> getAccountIconAsync() {
        return null;
    }

    @Override
    public CompletableFuture<Integer> getPermissionAsync(UUID userUUID) {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> hasPermissionAsync(UUID userUUID, int permission) {
        return null;
    }

    @Override
    public void setPermissionAsync(UUID userUUID, int permission) {

    }

    @Override
    public void addUserAsync(User user, int permission) {

    }

    @Override
    public void setUsersAsync(Map<User, Integer> userList) {

    }

    @Override
    public void removeUserAsync(UUID userUUID) {

    }

    @Override
    public CompletableFuture<Boolean> hasAnyUserAsync() {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> hasUserAsync(UUID userUUID) {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable User> getPersonalBankOwnerAsync() {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable IAsyncBank> createBankAsync(ItemID itemID, long startBalance) {
        return null;
    }

    @Override
    public void removeBankAsync(ItemID itemID) {

    }

    @Override
    public CompletableFuture<List<ItemID>> removeEmptyBanksAsync() {
        return null;
    }

    @Override
    public void removeAllBanksAsync() {

    }

    @Override
    public CompletableFuture<Boolean> hasAnyBankAsync() {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> hasBankAsync(ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable IAsyncBank> getBankAsync(ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<@Nullable IAsyncBank> getOrCreateBankAsync(ItemID itemID) {
        return null;
    }

    @Override
    public CompletableFuture<Map<ItemID, IAsyncBank>> getAllBanksAsync() {
        return null;
    }

    @Override
    public CompletableFuture<JsonElement> toJsonAsync() {
        return null;
    }

    @Override
    public CompletableFuture<String> toJsonStringAsync() {
        return null;
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
