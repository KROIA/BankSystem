package net.kroia.banksystem.banking.bank;

import com.google.gson.JsonElement;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.IAsyncBank;
import net.kroia.banksystem.api.bank.ISyncServerBank;
import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
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
import net.minecraft.network.codec.ByteBufCodecs;
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
        GetBalanceAsync,
        GetLockedBalanceAsync,
        GetTotalBalanceAsync,
        GetRealBalanceAsync,
        GetRealLockedBalanceAsync,
        GetRealTotalBalanceAsync,
        GetItemIDAsync,
        GetItemNameAsync,
        SetBalanceAsync,
        SetRealBalanceAsync,
        DepositAsync,
        DepositRealAsync,
        HasSufficientFundsAsync,
        WithdrawAsync,
        WithdrawRealAsync,
        WithdrawLockedAsync,
        WithdrawLockedRealAsync,
        WithdrawLockedPreferedAsync,
        WithdrawLockedPreferedRealAsync,
        TransferAsync,
        TransferRealAsync,
        TransferFromLockedAsync,
        TransferFromLockedRealAsync,
        TransferFromLockedPreferedAsync,
        TransferFromLockedPreferedRealAsync,
        LockAmountAsync,
        LockAmountRealAsync,
        UnlockAmountAsync,
        UnlockAmountRealAsync,
        UnlockAllAsync,
        ConvertToRawAmountAsync,
        ConvertToRealAmountAsync,
        GetNormalizedBalanceAsync,
        GetNormalizedLockedBalanceAsync,
        GetNormalizedTotalBalanceAsync,
        GetFormattedBalanceAsync,
        GetFormattedLockedBalanceAsync,
        GetFormattedTotalBalanceAsync,
        GetNormalizedAmountAsync_1,
        GetNormalizedAmountAsync_2,
        GetFormattedAmountAsync_1,
        GetFormattedAmountAsync_2,
        ToStringAsync,
        ToStringNoOwnerAsync,
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
            put(FunctionType.GetMinimalDataAsync,                 codecPacket(null, BankManagerData.STREAM_CODEC));
            put(FunctionType.GetBalanceAsync,                     codecPacket(null, ByteBufCodecs.VAR_LONG.cast()));
            put(FunctionType.GetLockedBalanceAsync,               codecPacket(null, ByteBufCodecs.VAR_LONG.cast()));
            put(FunctionType.GetTotalBalanceAsync,                codecPacket(null, ByteBufCodecs.VAR_LONG.cast()));
            put(FunctionType.GetRealBalanceAsync,                 codecPacket(null, ByteBufCodecs.DOUBLE.cast()));
            put(FunctionType.GetRealLockedBalanceAsync,           codecPacket(null, ByteBufCodecs.DOUBLE.cast()));
            put(FunctionType.GetRealTotalBalanceAsync,            codecPacket(null, ByteBufCodecs.DOUBLE.cast()));
            put(FunctionType.GetItemIDAsync,                      codecPacket(null, ItemID.STREAM_CODEC));
            put(FunctionType.GetItemNameAsync,                    codecPacket(null, ByteBufCodecs.STRING_UTF8.cast()));
            put(FunctionType.SetBalanceAsync,                     codecPacket(ByteBufCodecs.VAR_LONG.cast(), ByteBufCodecs.BOOL.cast()));
            put(FunctionType.SetRealBalanceAsync,                 codecPacket(ByteBufCodecs.DOUBLE.cast(), ByteBufCodecs.BOOL.cast()));
            put(FunctionType.DepositAsync,                        codecPacket(ByteBufCodecs.VAR_LONG.cast(), BankStatus.STREAM_CODEC));
            put(FunctionType.DepositRealAsync,                    codecPacket(ByteBufCodecs.DOUBLE.cast(), BankStatus.STREAM_CODEC));
            put(FunctionType.HasSufficientFundsAsync,             codecPacket(ByteBufCodecs.VAR_LONG.cast(), ByteBufCodecs.BOOL.cast()));
            put(FunctionType.WithdrawAsync,                       codecPacket(ByteBufCodecs.VAR_LONG.cast(), BankStatus.STREAM_CODEC));
            put(FunctionType.WithdrawRealAsync,                   codecPacket(ByteBufCodecs.DOUBLE.cast(), BankStatus.STREAM_CODEC));
            put(FunctionType.WithdrawLockedAsync,                 codecPacket(ByteBufCodecs.VAR_LONG.cast(), BankStatus.STREAM_CODEC));
            put(FunctionType.WithdrawLockedRealAsync,             codecPacket(ByteBufCodecs.DOUBLE.cast(), BankStatus.STREAM_CODEC));
            put(FunctionType.WithdrawLockedPreferedAsync,         codecPacket(ByteBufCodecs.VAR_LONG.cast(), BankStatus.STREAM_CODEC));
            put(FunctionType.WithdrawLockedPreferedRealAsync,     codecPacket(ByteBufCodecs.DOUBLE.cast(), BankStatus.STREAM_CODEC));
            put(FunctionType.TransferAsync,                       codecPacket(ParamGroup_long_int.STREAM_CODEC, BankStatus.STREAM_CODEC));
            put(FunctionType.TransferRealAsync,                   codecPacket(ParamGroup_double_int.STREAM_CODEC, BankStatus.STREAM_CODEC));
            put(FunctionType.TransferFromLockedAsync,             codecPacket(ParamGroup_long_int.STREAM_CODEC, BankStatus.STREAM_CODEC));
            put(FunctionType.TransferFromLockedRealAsync,         codecPacket(ParamGroup_double_int.STREAM_CODEC, BankStatus.STREAM_CODEC));
            put(FunctionType.TransferFromLockedPreferedAsync,     codecPacket(ParamGroup_long_int.STREAM_CODEC, BankStatus.STREAM_CODEC));
            put(FunctionType.TransferFromLockedPreferedRealAsync, codecPacket(ParamGroup_double_int.STREAM_CODEC, BankStatus.STREAM_CODEC));
            put(FunctionType.LockAmountAsync,                     codecPacket(ByteBufCodecs.VAR_LONG.cast(), BankStatus.STREAM_CODEC));
            put(FunctionType.LockAmountRealAsync,                 codecPacket(ByteBufCodecs.DOUBLE.cast(), BankStatus.STREAM_CODEC));
            put(FunctionType.UnlockAmountAsync,                   codecPacket(ByteBufCodecs.VAR_LONG.cast(), BankStatus.STREAM_CODEC));
            put(FunctionType.UnlockAmountRealAsync,               codecPacket(ByteBufCodecs.DOUBLE.cast(), BankStatus.STREAM_CODEC));
            put(FunctionType.UnlockAllAsync,                      codecPacket(null, null));
            put(FunctionType.ConvertToRawAmountAsync,             codecPacket(ByteBufCodecs.DOUBLE.cast(), ByteBufCodecs.VAR_LONG.cast()));
            put(FunctionType.ConvertToRealAmountAsync,            codecPacket(ByteBufCodecs.VAR_LONG.cast(), ByteBufCodecs.DOUBLE.cast()));
            put(FunctionType.GetNormalizedBalanceAsync,           codecPacket(null, ByteBufCodecs.STRING_UTF8.cast()));
            put(FunctionType.GetNormalizedLockedBalanceAsync,     codecPacket(null, ByteBufCodecs.STRING_UTF8.cast()));
            put(FunctionType.GetNormalizedTotalBalanceAsync,      codecPacket(null, ByteBufCodecs.STRING_UTF8.cast()));
            put(FunctionType.GetFormattedBalanceAsync,            codecPacket(null, ByteBufCodecs.STRING_UTF8.cast()));
            put(FunctionType.GetFormattedLockedBalanceAsync,      codecPacket(null, ByteBufCodecs.STRING_UTF8.cast()));
            put(FunctionType.GetFormattedTotalBalanceAsync,       codecPacket(null, ByteBufCodecs.STRING_UTF8.cast()));
            put(FunctionType.GetNormalizedAmountAsync_1,          codecPacket(ByteBufCodecs.DOUBLE.cast(), ByteBufCodecs.STRING_UTF8.cast()));
            put(FunctionType.GetNormalizedAmountAsync_2,          codecPacket(ByteBufCodecs.VAR_LONG.cast(), ByteBufCodecs.STRING_UTF8.cast()));
            put(FunctionType.GetFormattedAmountAsync_1,           codecPacket(ByteBufCodecs.DOUBLE.cast(), ByteBufCodecs.STRING_UTF8.cast()));
            put(FunctionType.GetFormattedAmountAsync_2,           codecPacket(ByteBufCodecs.VAR_LONG.cast(), ByteBufCodecs.STRING_UTF8.cast()));
            put(FunctionType.ToStringAsync,                       codecPacket(null, ByteBufCodecs.STRING_UTF8.cast()));
            put(FunctionType.ToStringNoOwnerAsync,                codecPacket(null, ByteBufCodecs.STRING_UTF8.cast()));
            put(FunctionType.ToJsonAsync,                         codecPacket(null, ExtraCodecUtils.JSON_ELEMENT_CODEC));
            put(FunctionType.ToJsonStringAsync,                   codecPacket(null, ByteBufCodecs.STRING_UTF8.cast()));
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
                case FunctionType.GetMinimalDataAsync ->                    OutputData.of(input.function, bank.getMinimalData());
                case FunctionType.GetBalanceAsync ->                        OutputData.of(input.function, bank.getBalance());
                case FunctionType.GetLockedBalanceAsync ->					OutputData.of(input.function, bank.getLockedBalance());
                case FunctionType.GetTotalBalanceAsync ->					OutputData.of(input.function, bank.getTotalBalance());
                case FunctionType.GetRealBalanceAsync ->					OutputData.of(input.function, bank.getRealBalance());
                case FunctionType.GetRealLockedBalanceAsync ->				OutputData.of(input.function, bank.getRealLockedBalance());
                case FunctionType.GetRealTotalBalanceAsync ->				OutputData.of(input.function, bank.getRealTotalBalance());
                case FunctionType.GetItemIDAsync ->							OutputData.of(input.function, bank.getItemID());
                case FunctionType.GetItemNameAsync ->						OutputData.of(input.function, bank.getItemName());
                case FunctionType.SetBalanceAsync ->						OutputData.of(input.function, bank.setBalance((long)inputData.extra));
                case FunctionType.SetRealBalanceAsync ->					OutputData.of(input.function, bank.setRealBalance((double)inputData.extra));
                case FunctionType.DepositAsync ->							OutputData.of(input.function, bank.deposit((long)inputData.extra));
                case FunctionType.DepositRealAsync ->						OutputData.of(input.function, bank.depositReal((double)inputData.extra));
                case FunctionType.HasSufficientFundsAsync ->				OutputData.of(input.function, bank.hasSufficientFunds((long)inputData.extra));
                case FunctionType.WithdrawAsync ->							OutputData.of(input.function, bank.withdraw((long)inputData.extra));
                case FunctionType.WithdrawRealAsync ->						OutputData.of(input.function, bank.withdrawReal((double)inputData.extra));
                case FunctionType.WithdrawLockedAsync ->					OutputData.of(input.function, bank.withdrawLocked((long)inputData.extra));
                case FunctionType.WithdrawLockedRealAsync ->				OutputData.of(input.function, bank.withdrawLockedReal((double)inputData.extra));
                case FunctionType.WithdrawLockedPreferedAsync ->			OutputData.of(input.function, bank.withdrawLockedPrefered((long)inputData.extra));
                case FunctionType.WithdrawLockedPreferedRealAsync ->		OutputData.of(input.function, bank.withdrawLockedPreferedReal((double)inputData.extra));
                case FunctionType.TransferAsync ->							{
                    ParamGroup_long_int data = (ParamGroup_long_int)inputData.extra;
                    yield OutputData.of(input.function, bank.transfer(data.longValue, data.integer));
                }
                case FunctionType.TransferRealAsync ->						{
                    ParamGroup_double_int  data = (ParamGroup_double_int)inputData.extra;
                    yield OutputData.of(input.function, bank.transferReal(data.doubleValue, data.integer));
                }
                case FunctionType.TransferFromLockedAsync ->				{
                    ParamGroup_long_int data = (ParamGroup_long_int)inputData.extra;
                    yield OutputData.of(input.function, bank.transferFromLocked(data.longValue, data.integer));
                }
                case FunctionType.TransferFromLockedRealAsync ->			{
                    ParamGroup_double_int  data = (ParamGroup_double_int)inputData.extra;
                    yield OutputData.of(input.function, bank.transferFromLockedReal(data.doubleValue, data.integer));
                }
                case FunctionType.TransferFromLockedPreferedAsync ->		{
                    ParamGroup_long_int data = (ParamGroup_long_int)inputData.extra;
                    yield OutputData.of(input.function, bank.transferFromLockedPrefered(data.longValue, data.integer));
                }
                case FunctionType.TransferFromLockedPreferedRealAsync ->	{
                    ParamGroup_double_int  data = (ParamGroup_double_int)inputData.extra;
                    yield OutputData.of(input.function, bank.transferFromLockedPreferedReal(data.doubleValue, data.integer));
                }
                case FunctionType.LockAmountAsync ->						OutputData.of(input.function, bank.lockAmount((long)inputData.extra));
                case FunctionType.LockAmountRealAsync ->					OutputData.of(input.function, bank.lockAmountReal((double)inputData.extra));
                case FunctionType.UnlockAmountAsync ->						OutputData.of(input.function, bank.unlockAmount((long)inputData.extra));
                case FunctionType.UnlockAmountRealAsync ->					OutputData.of(input.function, bank.unlockAmountReal((double)inputData.extra));
                case FunctionType.UnlockAllAsync ->							{
                    bank.unlockAll();
                    yield OutputData.of(input.function);
                }
                case FunctionType.ConvertToRawAmountAsync ->				OutputData.of(input.function, bank.convertToRawAmount((double)inputData.extra));
                case FunctionType.ConvertToRealAmountAsync ->				OutputData.of(input.function, bank.convertToRealAmount((long)inputData.extra));
                case FunctionType.GetNormalizedBalanceAsync ->				OutputData.of(input.function, bank.getNormalizedBalance());
                case FunctionType.GetNormalizedLockedBalanceAsync ->		OutputData.of(input.function, bank.getNormalizedLockedBalance());
                case FunctionType.GetNormalizedTotalBalanceAsync ->			OutputData.of(input.function, bank.getNormalizedTotalBalance());
                case FunctionType.GetFormattedBalanceAsync ->				OutputData.of(input.function, bank.getFormattedBalance());
                case FunctionType.GetFormattedLockedBalanceAsync ->			OutputData.of(input.function, bank.getFormattedLockedBalance());
                case FunctionType.GetFormattedTotalBalanceAsync ->			OutputData.of(input.function, bank.getFormattedTotalBalance());
                case FunctionType.GetNormalizedAmountAsync_1 ->				OutputData.of(input.function, bank.getNormalizedAmount((double)inputData.extra));
                case FunctionType.GetNormalizedAmountAsync_2 ->				OutputData.of(input.function, bank.getNormalizedAmount((long)inputData.extra));
                case FunctionType.GetFormattedAmountAsync_1 ->				OutputData.of(input.function, bank.getFormattedAmount((double)inputData.extra));
                case FunctionType.GetFormattedAmountAsync_2 ->				OutputData.of(input.function, bank.getFormattedAmount((long)inputData.extra));
                case FunctionType.ToStringAsync ->							OutputData.of(input.function, bank.toString());
                case FunctionType.ToStringNoOwnerAsync ->					OutputData.of(input.function, bank.toStringNoOwner());
                case FunctionType.ToJsonAsync ->							OutputData.of(input.function, bank.toJson());
                case FunctionType.ToJsonStringAsync ->						OutputData.of(input.function, bank.toJsonString());

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

    private record ParamGroup_long_int(long longValue, int integer)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, ParamGroup_long_int> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, p -> p.longValue,
                ByteBufCodecs.INT, p -> p.integer,
                ParamGroup_long_int::new
        );
    }
    private record ParamGroup_double_int(double doubleValue, int integer)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, ParamGroup_double_int> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.DOUBLE, p -> p.doubleValue,
                ByteBufCodecs.INT, p -> p.integer,
                ParamGroup_double_int::new
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
        CompletableFuture<Long> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetLockedBalanceAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Long> getTotalBalanceAsync() {
        CompletableFuture<Long> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetTotalBalanceAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Double> getRealBalanceAsync() {
        CompletableFuture<Double> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetRealBalanceAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Double> getRealLockedBalanceAsync() {
        CompletableFuture<Double> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetRealLockedBalanceAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Double> getRealTotalBalanceAsync() {
        CompletableFuture<Double> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetRealTotalBalanceAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<ItemID> getItemIDAsync() {
        CompletableFuture<ItemID> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetItemIDAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<String> getItemNameAsync() {
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetItemNameAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Boolean> setBalanceAsync(long balance) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.SetBalanceAsync, accountNr, itemID, balance);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Boolean> setRealBalanceAsync(double balance) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.SetRealBalanceAsync, accountNr, itemID, balance);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> depositAsync(long amount) {
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.DepositAsync, accountNr, itemID, amount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> depositRealAsync(double amount) {
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.DepositRealAsync, accountNr, itemID, amount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Boolean> hasSufficientFundsAsync(long amount) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.HasSufficientFundsAsync, accountNr, itemID, amount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> withdrawAsync(long amount) {
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.WithdrawAsync, accountNr, itemID, amount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> withdrawRealAsync(double amount) {
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.WithdrawRealAsync, accountNr, itemID, amount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> withdrawLockedAsync(long amount) {
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.WithdrawLockedAsync, accountNr, itemID, amount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> withdrawLockedRealAsync(double amount) {
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.WithdrawLockedRealAsync, accountNr, itemID, amount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> withdrawLockedPreferedAsync(long amount) {
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.WithdrawLockedPreferedAsync, accountNr, itemID, amount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> withdrawLockedPreferedRealAsync(double amount) {
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.WithdrawLockedPreferedRealAsync, accountNr, itemID, amount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> transferAsync(long amount, int toAccount) {
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.TransferAsync, accountNr, itemID, new ParamGroup_long_int(amount, toAccount));
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> transferRealAsync(double amount, int toAccount) {
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.TransferRealAsync, accountNr, itemID, new ParamGroup_double_int(amount, toAccount));
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> transferFromLockedAsync(long amount, int toAccount) {
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.TransferFromLockedAsync, accountNr, itemID, new ParamGroup_long_int(amount, toAccount));
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> transferFromLockedRealAsync(double amount, int toAccount) {
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.TransferFromLockedRealAsync, accountNr, itemID, new ParamGroup_double_int(amount, toAccount));
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> transferFromLockedPreferedAsync(long amount, int toAccount) {
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.TransferFromLockedPreferedAsync, accountNr, itemID, new ParamGroup_long_int(amount, toAccount));
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> transferFromLockedPreferedRealAsync(double amount, int toAccount) {
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.TransferFromLockedPreferedRealAsync, accountNr, itemID, new ParamGroup_double_int(amount, toAccount));
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> lockAmountAsync(long amount) {
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.LockAmountAsync, accountNr, itemID, amount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> lockAmountRealAsync(double amount) {
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.LockAmountRealAsync, accountNr, itemID, amount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> unlockAmountAsync(long amount) {
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.UnlockAmountAsync, accountNr, itemID, amount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> unlockAmountRealAsync(double amount) {
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.UnlockAmountRealAsync, accountNr, itemID, amount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public void unlockAllAsync() {
        InputData inputData = InputData.of(FunctionType.UnlockAllAsync, accountNr, itemID);
        sendRequest(inputData);
    }

    @Override
    public CompletableFuture<Long> convertToRawAmountAsync(double realAmount) {
        CompletableFuture<Long> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.ConvertToRawAmountAsync, accountNr, itemID, realAmount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Double> convertToRealAmountAsync(long rawAmount) {
        CompletableFuture<Double> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.ConvertToRealAmountAsync, accountNr, itemID, rawAmount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<String> getNormalizedBalanceAsync() {
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetNormalizedBalanceAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<String> getNormalizedLockedBalanceAsync() {
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetNormalizedLockedBalanceAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<String> getNormalizedTotalBalanceAsync() {
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetNormalizedTotalBalanceAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<String> getFormattedBalanceAsync() {
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetFormattedBalanceAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<String> getFormattedLockedBalanceAsync() {
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetFormattedLockedBalanceAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<String> getFormattedTotalBalanceAsync() {
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetFormattedTotalBalanceAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<String> getNormalizedAmountAsync(double realAmount) {
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetNormalizedAmountAsync_1, accountNr, itemID, realAmount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<String> getNormalizedAmountAsync(long rawAmount) {
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetNormalizedAmountAsync_2, accountNr, itemID, rawAmount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<String> getFormattedAmountAsync(double realAmount) {
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetFormattedAmountAsync_1, accountNr, itemID, realAmount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<String> getFormattedAmountAsync(long rawAmount) {
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetFormattedAmountAsync_2, accountNr, itemID, rawAmount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<String> toStringAsync() {
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.ToStringAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<String> toStringNoOwnerAsync() {
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.ToStringNoOwnerAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<JsonElement> toJsonAsync() {
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.ToJsonAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<String> toJsonStringAsync() {
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.ToJsonStringAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
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
