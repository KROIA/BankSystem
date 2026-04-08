package net.kroia.banksystem.banking.bank;

import com.google.gson.JsonElement;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.IAsyncBank;
import net.kroia.banksystem.api.bank.ISyncServerBank;
import net.kroia.banksystem.banking.bankaccount.SyncServerBankAccount;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.banking.clientdata.BankManagerData;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class AsyncBank implements IAsyncBank {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
        SyncServerBankAccount.setBackend(backend);
    }


    private final boolean isClientSide;
    private final int accountNr;
    private final ItemID itemID;

    private AsyncBank(int accountNr, ItemID itemID, boolean clientSide) {
        this.accountNr = accountNr;
        this.itemID = itemID;
        this.isClientSide = clientSide;
    }

    public static AsyncBank createClientBank(int accountNr, ItemID itemID)
    {
        return new AsyncBank(accountNr, itemID, true);
    }
    public static AsyncBank createSlaveServerBank(int accountNr, ItemID itemID)
    {
        return new AsyncBank(accountNr, itemID,false);
    }
    public static AsyncBank createBank(int accountNr, ItemID itemID, boolean clientSide)
    {
        return new AsyncBank(accountNr, itemID,clientSide);
    }


    /**
     * Enumeration to specify each function that can be forwarded
     */
    public enum FunctionType
    {
        GetMinimalDataAsync,
        GetBalanceAsync
    }

    /**
     * Map of codec pairs for each function
     * If a codec is set to null, that means the argument is not available.
     *      -> inputParamsCodec  == null: The function does not take any parameters
     *      -> outputParamsCodec == null: The function does not return any value
     */
    public static final Map<FunctionType, AsyncFunctionDataCodecs> codecs = new HashMap<>(){{
             put(FunctionType.GetMinimalDataAsync, new AsyncFunctionDataCodecs(BankIdentifyAndDataPacket.streamCodec(null), BankManagerData.STREAM_CODEC));
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
        public static <T> InputData of(FunctionType functionType, int accountNr, ItemID itemID, T result)
        {
            BankIdentifyAndDataPacket<T> packet = BankIdentifyAndDataPacket.of(accountNr, itemID, result);
            return (InputData) AsyncFunctionInputData.of(codecs.get(functionType).inputParamsCodec, functionType, packet, InputData::new);
        }
        public static InputData of(FunctionType functionType, int accountNr, ItemID itemID)
        {
            BankIdentifyAndDataPacket packet = BankIdentifyAndDataPacket.of(accountNr, itemID, null);
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

            ItemID itemID = inputData.itemID;
            ISyncServerBank bank = bankAccount.getBank(itemID);
            if(bank == null)
                return CompletableFuture.completedFuture(OutputData.of(input.function));

            return CompletableFuture.completedFuture(switch (input.function) {
                case FunctionType.GetMinimalDataAsync -> OutputData.of(input.function, bank.getMinimalData());
                case FunctionType.GetBalanceAsync -> OutputData.of(input.function, bank.getBalance());


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

    public record BankIdentifyAndDataPacket<T>(int accountNr, ItemID itemID, T extra) {

        public static <T> BankIdentifyAndDataPacket<T> of(int accountNr, ItemID itemID, T extra)
        {
            return new BankIdentifyAndDataPacket<>(accountNr, itemID, extra);
        }

        // Encode: write accountNr, itemID, then the extra using the provided codec
        public static <T> void encode(
                RegistryFriendlyByteBuf buf,
                BankIdentifyAndDataPacket<T> params,
                StreamCodec<RegistryFriendlyByteBuf, T> extraCodec
        ) {
            buf.writeInt(params.accountNr);
            ItemID.STREAM_CODEC.encode(buf, params.itemID);
            ExtraCodecUtils.nullable(extraCodec).encode(buf, params.extra);
        }

        // Decode: read accountNr, itemID, then the extra using the provided codec
        public static <T> BankIdentifyAndDataPacket<T> decode(
                RegistryFriendlyByteBuf buf,
                StreamCodec<RegistryFriendlyByteBuf, T> extraCodec
        ) {
            int accountNr = buf.readInt();
            ItemID itemID = ItemID.STREAM_CODEC.decode(buf);
            T extra = ExtraCodecUtils.nullable(extraCodec).decode(buf);
            return new BankIdentifyAndDataPacket<>(accountNr, itemID, extra);
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
    public CompletableFuture<BankData> getMinimalDataAsync() {
        CompletableFuture<BankData> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetMinimalDataAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Long> getBalanceAsync() {
        CompletableFuture<Long> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetBalanceAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Long> getLockedBalanceAsync() {
        return null;
    }

    @Override
    public CompletableFuture<Long> getTotalBalanceAsync() {
        return null;
    }

    @Override
    public CompletableFuture<Double> getRealBalanceAsync() {
        return null;
    }

    @Override
    public CompletableFuture<Double> getRealLockedBalanceAsync() {
        return null;
    }

    @Override
    public CompletableFuture<Double> getRealTotalBalanceAsync() {
        return null;
    }

    @Override
    public CompletableFuture<ItemID> getItemIDAsync() {
        return null;
    }

    @Override
    public CompletableFuture<String> getItemNameAsync() {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> setBalanceAsync(long balance) {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> setRealBalanceAsync(double balance) {
        return null;
    }

    @Override
    public CompletableFuture<BankStatus> depositAsync(long amount) {
        return null;
    }

    @Override
    public CompletableFuture<BankStatus> depositRealAsync(double amount) {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> hasSufficientFundsAsync(long amount) {
        return null;
    }

    @Override
    public CompletableFuture<BankStatus> withdrawAsync(long amount) {
        return null;
    }

    @Override
    public CompletableFuture<BankStatus> withdrawRealAsync(double amount) {
        return null;
    }

    @Override
    public CompletableFuture<BankStatus> withdrawLockedAsync(long amount) {
        return null;
    }

    @Override
    public CompletableFuture<BankStatus> withdrawLockedRealAsync(double amount) {
        return null;
    }

    @Override
    public CompletableFuture<BankStatus> withdrawLockedPreferedAsync(long amount) {
        return null;
    }

    @Override
    public CompletableFuture<BankStatus> withdrawLockedPreferedRealAsync(double amount) {
        return null;
    }

    @Override
    public CompletableFuture<BankStatus> transferAsync(long amount, int toAccount) {
        return null;
    }

    @Override
    public CompletableFuture<BankStatus> transferRealAsync(double amount, int toAccount) {
        return null;
    }

    @Override
    public CompletableFuture<BankStatus> transferFromLockedAsync(long amount, int toAccount) {
        return null;
    }

    @Override
    public CompletableFuture<BankStatus> transferFromLockedRealAsync(double amount, int toAccount) {
        return null;
    }

    @Override
    public CompletableFuture<BankStatus> transferFromLockedPreferedAsync(long amount, int toAccount) {
        return null;
    }

    @Override
    public CompletableFuture<BankStatus> transferFromLockedPreferedRealAsync(double amount, int toAccount) {
        return null;
    }

    @Override
    public CompletableFuture<BankStatus> lockAmountAsync(long amount) {
        return null;
    }

    @Override
    public CompletableFuture<BankStatus> lockAmountRealAsync(double amount) {
        return null;
    }

    @Override
    public CompletableFuture<BankStatus> unlockAmountAsync(long amount) {
        return null;
    }

    @Override
    public CompletableFuture<BankStatus> unlockAmountRealAsync(double amount) {
        return null;
    }

    @Override
    public void unlockAllAsync() {

    }

    @Override
    public CompletableFuture<Long> convertToRawAmountAsync(double realAmount) {
        return null;
    }

    @Override
    public CompletableFuture<Double> convertToRealAmountAsync(long rawAmount) {
        return null;
    }

    @Override
    public CompletableFuture<String> getNormalizedBalanceAsync() {
        return null;
    }

    @Override
    public CompletableFuture<String> getNormalizedLockedBalanceAsync() {
        return null;
    }

    @Override
    public CompletableFuture<String> getNormalizedTotalBalanceAsync() {
        return null;
    }

    @Override
    public CompletableFuture<String> getFormattedBalanceAsync() {
        return null;
    }

    @Override
    public CompletableFuture<String> getFormattedLockedBalanceAsync() {
        return null;
    }

    @Override
    public CompletableFuture<String> getFormattedTotalBalanceAsync() {
        return null;
    }

    @Override
    public CompletableFuture<String> getNormalizedAmountAsync(double realAmount) {
        return null;
    }

    @Override
    public CompletableFuture<String> getNormalizedAmountAsync(long rawAmount) {
        return null;
    }

    @Override
    public CompletableFuture<String> getFormattedAmountAsync(double realAmount) {
        return null;
    }

    @Override
    public CompletableFuture<String> getFormattedAmountAsync(long rawAmount) {
        return null;
    }

    @Override
    public CompletableFuture<String> toStringAsync() {
        return null;
    }

    @Override
    public CompletableFuture<String> toStringNoOwnerAsync() {
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
        BACKEND_INSTANCES.LOGGER.info("[AsyncBank] " + msg);
    }
    private static void error(String msg)
    {
        BACKEND_INSTANCES.LOGGER.error("[AsyncBank] " + msg);
    }
    private static void error(String msg, Throwable e)
    {
        BACKEND_INSTANCES.LOGGER.error("[AsyncBank] " + msg, e);
    }
    private static void warn(String msg)
    {
        BACKEND_INSTANCES.LOGGER.warn("[AsyncBank] " + msg);
    }
    private static void debug(String msg)
    {
        BACKEND_INSTANCES.LOGGER.debug("[AsyncBank] " + msg);
    }

}
