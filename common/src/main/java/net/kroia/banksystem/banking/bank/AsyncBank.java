package net.kroia.banksystem.banking.bank;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.IAsyncBank;
import net.kroia.banksystem.api.bank.ISyncServerBank;
import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.api.ledger.LedgerActor;
import net.kroia.banksystem.api.ledger.LedgerType;
import net.kroia.banksystem.banking.ledger.TransactionLedger;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.banking.clientdata.BankManagerData;
import net.kroia.banksystem.data.TransferJournal;
import net.kroia.banksystem.networking.multi_server.TransferStatusRequest;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.MultiServerUtils;
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
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;

public class AsyncBank implements IAsyncBank {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
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
        //GetItemIDAsync,
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
            put(FunctionType.GetMinimalDataAsync,                 codecPacket(null, BankData.STREAM_CODEC));
            put(FunctionType.GetBalanceAsync,                     codecPacket(null, ByteBufCodecs.VAR_LONG.cast()));
            put(FunctionType.GetLockedBalanceAsync,               codecPacket(null, ByteBufCodecs.VAR_LONG.cast()));
            put(FunctionType.GetTotalBalanceAsync,                codecPacket(null, ByteBufCodecs.VAR_LONG.cast()));
            put(FunctionType.GetRealBalanceAsync,                 codecPacket(null, ByteBufCodecs.DOUBLE.cast()));
            put(FunctionType.GetRealLockedBalanceAsync,           codecPacket(null, ByteBufCodecs.DOUBLE.cast()));
            put(FunctionType.GetRealTotalBalanceAsync,            codecPacket(null, ByteBufCodecs.DOUBLE.cast()));
            //put(FunctionType.GetItemIDAsync,                      codecPacket(null, ItemID.STREAM_CODEC));
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
            BankIdentifyAndDataPacket<T> packet = BankIdentifyAndDataPacket.of(accountNr, itemID, result, transferIdFor(functionType));
            return (InputData) AsyncFunctionInputData.of(codecs.get(functionType).inputParamsCodec, functionType, packet, InputData::new);
        }
        public static InputData of(FunctionType functionType, int accountNr, ItemID itemID)
        {
            BankIdentifyAndDataPacket packet = BankIdentifyAndDataPacket.of(accountNr, itemID, null, transferIdFor(functionType));
            return (InputData) AsyncFunctionInputData.of(codecs.get(functionType).inputParamsCodec, functionType, packet, InputData::new);
        }

        /**
         * Task #125: a deposit / withdraw carries the tag's journal id, else a fresh one; every other call none.
         * Task #162: an account-to-account transfer always gets a fresh id (a tag's journal id may already name a deposit).
         */
        private static @Nullable String transferIdFor(FunctionType function)
        {
            if (Request.ledgerAllowList(function) == TransactionLedger.SLAVE_TRANSFER_CODES) return TransferJournal.newTransferId();
            if (!journaled(function)) return null;
            TransferJournal.Ref ref = TransactionLedger.currentTag().journal();
            return ref != null ? ref.transferId() : TransferJournal.newTransferId();
        }
    }

    /** Task #162: value-moving functions with a transfer_id: deposit / withdraw and account-to-account transfers. */
    private static boolean hasTransferId(FunctionType function)
    {
        return journaled(function) || Request.ledgerAllowList(function) == TransactionLedger.SLAVE_TRANSFER_CODES;
    }

    /** Deposit / withdraw functions: the ones with slave TransferJournal rows and a transfer_id on the wire. */
    private static boolean journaled(FunctionType function)
    {
        Set<LedgerType> codes = Request.ledgerAllowList(function);
        return codes == TransactionLedger.SLAVE_DEPOSIT_CODES || codes == TransactionLedger.SLAVE_WITHDRAW_CODES;
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
            if(BankSystemMod.ENABLE_DEV_FEATURES && AsyncForwardingRequest.DEBUG_ENABLE_LOGS)
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
                playerName =  tryGetPlayerName(playerSender);
                playerInfo = " from player: " + playerName;
            }
            if(BankSystemMod.ENABLE_DEV_FEATURES && AsyncForwardingRequest.DEBUG_ENABLE_LOGS)
                info("Received request to handle on master server for function: "+input.function.toString() + playerInfo);
            if(!isRequestAllowed(input, slaveID, playerSender, playerName))
                return CompletableFuture.completedFuture(OutputData.of(input.function));

            BankIdentifyAndDataPacket inputData = input.decodeParams();
            // Task #126 (B6) / #162: a repeated deposit / withdraw / transfer gets the first reply; nothing is applied twice.
            final String transferId = hasTransferId(input.function) && slaveID != null && !slaveID.isEmpty() ? inputData.transferId : null;
            CompletableFuture<OutputData> seen = TransferStatusRequest.replay(slaveID, transferId);
            if(seen != null)
                return seen;
            int accountNr = inputData.accountNr;
            IServerBankManager serverBankManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
            if(serverBankManager == null) {
                if(BACKEND_INSTANCES.isSlaveServer)
                {
                    throw new RuntimeException("[AsyncBank]: This server is configured to be a slave server but the slave seems not to be connected to its master.\n" +
                            "This server instance has no IServerBankManager!");
                }
                throw new RuntimeException("Server bank manager not found");
            }
            ISyncServerBankAccount bankAccount = serverBankManager.getBankAccount(accountNr);
            if(bankAccount == null)
                return CompletableFuture.completedFuture(OutputData.of(input.function));

            ItemID itemID = inputData.itemID;
            ISyncServerBank bank = bankAccount.getBank(itemID);
            if(bank == null)
                return CompletableFuture.completedFuture(OutputData.of(input.function));

            // Task #104: balance reads go to account members and admins only.
            if(isBalanceRead(input.function) && !canReadAccount(playerSender, slaveID, bankAccount))
                return CompletableFuture.completedFuture(deniedRead(input.function));

            final BankIdentifyAndDataPacket<?> params = inputData;
            Supplier<OutputData> execute = () -> switch (input.function) {
                case FunctionType.GetMinimalDataAsync ->                    OutputData.of(input.function, bank.getMinimalData());
                case FunctionType.GetBalanceAsync ->                        OutputData.of(input.function, bank.getBalance());
                case FunctionType.GetLockedBalanceAsync ->					OutputData.of(input.function, bank.getLockedBalance());
                case FunctionType.GetTotalBalanceAsync ->					OutputData.of(input.function, bank.getTotalBalance());
                case FunctionType.GetRealBalanceAsync ->					OutputData.of(input.function, bank.getRealBalance());
                case FunctionType.GetRealLockedBalanceAsync ->				OutputData.of(input.function, bank.getRealLockedBalance());
                case FunctionType.GetRealTotalBalanceAsync ->				OutputData.of(input.function, bank.getRealTotalBalance());
                //case FunctionType.GetItemIDAsync ->							OutputData.of(input.function, bank.getItemID());
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
            };
            TransferStatusRequest.applying(slaveID, transferId);
            OutputData out = executeWithLedger(input.function, params, bank, slaveID, execute);
            if(transferId != null)
                TransferStatusRequest.remember(slaveID, transferId, out.decodeResult() == BankStatus.SUCCESS, Map.of(), out);
            return CompletableFuture.completedFuture(out);
        }

        /**
         * Task #109 — runs the bank call and writes the ledger row on the master from the type
         * code the slave sent with it. Reads write nothing. Value-moving calls check the code
         * against the function's allow-list: NO_LOG writes nothing (raw calls, e.g. StockMarket
         * settlement), an allowed code is written as sent, anything else is written as
         * UNKNOWN_LEGACY with a "rejected code" note. Transfers always keep the TRANSFER pair
         * written by {@code ServerBank.transfer}; the code only picks type and actor.
         */
        private static OutputData executeWithLedger(FunctionType function, BankIdentifyAndDataPacket<?> params,
                                                    ISyncServerBank bank, String slaveID, Supplier<OutputData> execute) {
            Set<LedgerType> allowed = ledgerAllowList(function);
            if (allowed == null) return execute.get();
            TransactionLedger.Resolved resolved = TransactionLedger.resolveSlaveCode(params.ledgerCode(), allowed,
                    true, params.ledgerNote(), slaveID, "AsyncBank." + function.name());
            LedgerActor actor = TransactionLedger.slaveActor(params.ledgerActor());
            // Task #117: the tag lets ServerBank refuse player movements on frozen accounts.
            Supplier<OutputData> run = resolved == null ? execute
                    : () -> TransactionLedger.tagged(resolved.type(), params.ledgerActor(), null, execute);
            if (allowed == TransactionLedger.SLAVE_TRANSFER_CODES)
                return TransactionLedger.withActor(actor, () -> TransactionLedger.withTransferType(resolved, run));
            OutputData out = run.get();
            if (resolved != null) {
                Object result = out.decodeResult();
                if (result == null || result == BankStatus.SUCCESS || Boolean.TRUE.equals(result))
                    TransactionLedger.logBank(bank, resolved.type(), rawAmount(params.extra(), bank), actor, resolved.note(),
                            params.transferId()); // Task #125: ref = the slave's transfer_id
            }
            return out;
        }

        /** Task #109 — allowed slave type codes per value-moving function; null for reads. */
        static @Nullable Set<LedgerType> ledgerAllowList(FunctionType function) {
            return switch (function) {
                case DepositAsync, DepositRealAsync -> TransactionLedger.SLAVE_DEPOSIT_CODES;
                case WithdrawAsync, WithdrawRealAsync, WithdrawLockedAsync, WithdrawLockedRealAsync,
                     WithdrawLockedPreferedAsync, WithdrawLockedPreferedRealAsync -> TransactionLedger.SLAVE_WITHDRAW_CODES;
                case TransferAsync, TransferRealAsync, TransferFromLockedAsync, TransferFromLockedRealAsync,
                     TransferFromLockedPreferedAsync, TransferFromLockedPreferedRealAsync -> TransactionLedger.SLAVE_TRANSFER_CODES;
                // Raw only (NO_LOG): total balance unchanged, or StockMarket / admin raw writes.
                case SetBalanceAsync, SetRealBalanceAsync, LockAmountAsync, LockAmountRealAsync,
                     UnlockAmountAsync, UnlockAmountRealAsync, UnlockAllAsync -> Set.of();
                default -> null;
            };
        }

        private static long rawAmount(@Nullable Object extra, ISyncServerBank bank) {
            if (extra instanceof Long l) return l;
            if (extra instanceof Double d) return bank.convertToRawAmount(d);
            return 0L;
        }
        /** Task #104: functions that return balances of the bank. Item names and amount math stay open. */
        private static boolean isBalanceRead(FunctionType function) {
            return switch (function) {
                case GetMinimalDataAsync, GetBalanceAsync, GetLockedBalanceAsync, GetTotalBalanceAsync,
                     GetRealBalanceAsync, GetRealLockedBalanceAsync, GetRealTotalBalanceAsync,
                     GetNormalizedBalanceAsync, GetNormalizedLockedBalanceAsync, GetNormalizedTotalBalanceAsync,
                     GetFormattedBalanceAsync, GetFormattedLockedBalanceAsync, GetFormattedTotalBalanceAsync,
                     ToStringAsync, ToStringNoOwnerAsync, ToJsonAsync, ToJsonStringAsync -> true;
                default -> false;
            };
        }

        /** Task #104: denied reads answer 0 for numbers, else an empty result that decodes as null. */
        private static OutputData deniedRead(FunctionType function) {
            return switch (function) {
                case GetBalanceAsync, GetLockedBalanceAsync, GetTotalBalanceAsync -> OutputData.of(function, 0L);
                case GetRealBalanceAsync, GetRealLockedBalanceAsync, GetRealTotalBalanceAsync -> OutputData.of(function, 0.0);
                default -> OutputData.of(function);
            };
        }

        @Override
        protected boolean isAllowedToCallByClient(InputData input)
        {
            return switch (input.function) {
                case FunctionType.GetMinimalDataAsync,
                     FunctionType.GetBalanceAsync,
                     FunctionType.GetLockedBalanceAsync,
                     FunctionType.GetTotalBalanceAsync,
                     FunctionType.GetRealBalanceAsync,
                     FunctionType.GetRealLockedBalanceAsync,
                     FunctionType.GetRealTotalBalanceAsync,
                     //FunctionType.GetItemIDAsync,
                     FunctionType.GetItemNameAsync,
                     FunctionType.ConvertToRawAmountAsync,
                     FunctionType.ConvertToRealAmountAsync,
                     FunctionType.GetNormalizedBalanceAsync,
                     FunctionType.GetNormalizedLockedBalanceAsync,
                     FunctionType.GetNormalizedTotalBalanceAsync,
                     FunctionType.GetFormattedBalanceAsync,
                     FunctionType.GetFormattedLockedBalanceAsync,
                     FunctionType.GetFormattedTotalBalanceAsync,
                     FunctionType.GetNormalizedAmountAsync_1,
                     FunctionType.GetNormalizedAmountAsync_2,
                     FunctionType.GetFormattedAmountAsync_1,
                     FunctionType.GetFormattedAmountAsync_2,
                     FunctionType.ToStringAsync,
                     FunctionType.ToStringNoOwnerAsync,
                     FunctionType.ToJsonAsync,
                     FunctionType.ToJsonStringAsync -> true;

                default -> false;
            };
        }
        @Override
        protected boolean isAllowedToCallByUntrustedSlaveServer(InputData input)
        {
            return switch (input.function) {
                case FunctionType.GetMinimalDataAsync,
                     FunctionType.GetBalanceAsync,
                     FunctionType.GetLockedBalanceAsync,
                     FunctionType.GetTotalBalanceAsync,
                     FunctionType.GetRealBalanceAsync,
                     FunctionType.GetRealLockedBalanceAsync,
                     FunctionType.GetRealTotalBalanceAsync,
                     //FunctionType.GetItemIDAsync,
                     FunctionType.GetItemNameAsync,
                     FunctionType.ConvertToRawAmountAsync,
                     FunctionType.ConvertToRealAmountAsync,
                     FunctionType.GetNormalizedBalanceAsync,
                     FunctionType.GetNormalizedLockedBalanceAsync,
                     FunctionType.GetNormalizedTotalBalanceAsync,
                     FunctionType.GetFormattedBalanceAsync,
                     FunctionType.GetFormattedLockedBalanceAsync,
                     FunctionType.GetFormattedTotalBalanceAsync,
                     FunctionType.GetNormalizedAmountAsync_1,
                     FunctionType.GetNormalizedAmountAsync_2,
                     FunctionType.GetFormattedAmountAsync_1,
                     FunctionType.GetFormattedAmountAsync_2,
                     FunctionType.ToStringAsync,
                     FunctionType.ToStringNoOwnerAsync,
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

    /**
     * Task #121: a failed or null reply fails the returned future instead of hanging it.
     * Task #126 (B6) / #162: a slave's deposit / withdraw / transfer that was never sent or not applied (status check) completes with
     * FAILED_NO_MASTER_CONNECTION, so callers roll back; an unknown outcome is checked with the master first and fails
     * only if it stays unknown (then nothing may be refunded).
     */
    private CompletableFuture<OutputData> sendRequest(InputData input)
    {
        final long sentMs = System.currentTimeMillis();
        CompletableFuture<OutputData> tmpFuture;
        if(isClientSide)
            tmpFuture = Request.instance.sendRequestToServer(input);
        else
            tmpFuture = Request.instance.sendRequestToMaster(input);

        boolean slave = !isClientSide && BACKEND_INSTANCES != null && BACKEND_INSTANCES.isSlaveServer;
        CompletableFuture<OutputData> out;
        if(slave && hasTransferId(input.function)) {
            BankIdentifyAndDataPacket<?> params = input.decodeParams();
            String transferId = params.transferId();
            out = TransferStatusRequest.resolve(tmpFuture,
                    () -> OutputData.of(input.function, BankStatus.FAILED_NO_MASTER_CONNECTION),
                    remainder -> OutputData.of(input.function, BankStatus.SUCCESS),
                    transferId == null ? null : () -> TransferStatusRequest.ask(null, transferId, sentMs));
        }
        else {
            out = tmpFuture.thenApply(outputData -> {
                if(outputData == null)
                    throw new IllegalStateException("ARRS request not sent: " + input.function);
                if(BankSystemMod.ENABLE_DEV_FEATURES && AsyncForwardingRequest.DEBUG_ENABLE_LOGS)
                    info("Response received for request: "+ input.function.toString());
                return outputData;
            });
        }
        if(slave)
            journal(input, out);
        return out;
    }

    /**
     * Task #124 (B2) — slave TransferJournal rows for a deposit / withdraw: REQUESTED now (tag context and id from
     * {@link TransactionLedger#tagged}, or a fresh id), CONFIRMED / DELIVERED on SUCCESS, TIMED_OUT when the future
     * fails. Other replies write nothing here (the master applied nothing; a caller that gives items back writes RETURNED).
     */
    private void journal(InputData input, CompletableFuture<OutputData> out)
    {
        try {
            if (!journaled(input.function)) return;
            boolean deposit = Request.ledgerAllowList(input.function) == TransactionLedger.SLAVE_DEPOSIT_CODES;
            BankIdentifyAndDataPacket<?> p = input.decodeParams();
            long raw = p.extra() instanceof Double d ? net.kroia.banksystem.banking.bankmanager.BankManager.convertToRawAmountStatic(d)
                    : p.extra() instanceof Long l ? l : 0L;
            // Task #125: the id that went on the wire, so the master's ledger ref matches these rows
            TransferJournal.Ref tagRef = TransactionLedger.currentTag().journal();
            TransferJournal.Ref ref = new TransferJournal.Ref(p.transferId() != null ? p.transferId() : TransferJournal.newTransferId(),
                    tagRef == null ? null : tagRef.context());
            TransferJournal.requested(ref, deposit ? TransferJournal.TO_MASTER : TransferJournal.FROM_MASTER,
                    TransferJournal.ASYNC_BANK, accountNr, p.ledgerActor(), Map.of(itemID, raw),
                    p.ledgerCode() == TransactionLedger.NO_LOG ? null : p.ledgerCode());
            out.whenComplete((o, ex) -> {
                if (ex != null)
                    TransferJournal.step(ref.transferId(), TransferJournal.TIMED_OUT, null,
                            String.valueOf(ex.getCause() != null ? ex.getCause() : ex));
                else if (o.decodeResult() == BankStatus.SUCCESS)
                    TransferJournal.step(ref.transferId(), deposit ? TransferJournal.CONFIRMED : TransferJournal.DELIVERED, null, null);
            });
        } catch (RuntimeException ignored) {
            // diagnostics never affect the bank call
        }
    }



    // ================================================================================================================
    //
    //
    //       Custom Objects to hold multiple parameters, passed by a function call
    //       These objects are used to bundle the arguments from a function that uses multiple arguments
    //
    //
    // ================================================================================================================

    /**
     * @param ledgerCode  Task #109: ledger type code of this call ({@link TransactionLedger#NO_LOG}
     *                    for raw calls); the master checks it per function
     * @param ledgerActor player who caused the call, or null (SYSTEM)
     * @param ledgerNote  free-text note for the ledger row
     * @param transferId  Task #125: the transfer's id (deposit / withdraw, and Task #162 transfers; else null); for a
     *                    deposit / withdraw the master writes it as the ledger row's {@code ref}. Last field on the wire,
     *                    null from an older slave.
     */
    public record BankIdentifyAndDataPacket<T>(int accountNr, ItemID itemID, T extra,
                                               int ledgerCode, @Nullable UUID ledgerActor, @Nullable String ledgerNote,
                                               @Nullable String transferId) {

        /** Picks up the ledger tag set with {@link TransactionLedger#tagged} on this thread. */
        public static <T> BankIdentifyAndDataPacket<T> of(int accountNr, ItemID itemID, T extra, @Nullable String transferId)
        {
            TransactionLedger.SlaveTag tag = TransactionLedger.currentTag();
            return new BankIdentifyAndDataPacket<>(accountNr, itemID, extra, tag.code(), tag.actor(), tag.note(), transferId);
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
            ByteBufCodecs.VAR_INT.encode(buf, params.ledgerCode);
            ExtraCodecUtils.nullable(net.minecraft.core.UUIDUtil.STREAM_CODEC).encode(buf, params.ledgerActor);
            ExtraCodecUtils.nullable(ByteBufCodecs.STRING_UTF8).encode(buf, params.ledgerNote);
            TransferJournal.ID_CODEC.encode(buf, params.transferId); // Task #125: keep last
        }

        // Decode: read accountNr, itemID, then the extra using the provided codec
        public static <T> BankIdentifyAndDataPacket<T> decode(
                RegistryFriendlyByteBuf buf,
                StreamCodec<RegistryFriendlyByteBuf, T> extraCodec
        ) {
            int accountNr = buf.readInt();
            ItemID itemID = ItemID.STREAM_CODEC.decode(buf);
            T extra = ExtraCodecUtils.nullable(extraCodec).decode(buf);
            int ledgerCode = ByteBufCodecs.VAR_INT.decode(buf);
            UUID ledgerActor = ExtraCodecUtils.nullable(net.minecraft.core.UUIDUtil.STREAM_CODEC).decode(buf);
            String ledgerNote = ExtraCodecUtils.nullable(ByteBufCodecs.STRING_UTF8).decode(buf);
            String transferId = TransferJournal.ID_CODEC.decode(buf);
            return new BankIdentifyAndDataPacket<>(accountNr, itemID, extra, ledgerCode, ledgerActor, ledgerNote, transferId);
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

    public record ParamGroup_long_int(long longValue, int integer) // public for tests (Task #162)
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
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(new BankData(itemID, 0,0));
        CompletableFuture<BankData> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetMinimalDataAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<Long> getBalanceAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(0L);
        CompletableFuture<Long> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetBalanceAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<Long> getLockedBalanceAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(0L);
        CompletableFuture<Long> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetLockedBalanceAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<Long> getTotalBalanceAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(0L);
        CompletableFuture<Long> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetTotalBalanceAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<Double> getRealBalanceAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(0.0);
        CompletableFuture<Double> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetRealBalanceAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<Double> getRealLockedBalanceAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(0.0);
        CompletableFuture<Double> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetRealLockedBalanceAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<Double> getRealTotalBalanceAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(0.0);
        CompletableFuture<Double> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetRealTotalBalanceAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public ItemID getItemIDAsync() {
        return itemID;
    }

    @Override
    public CompletableFuture<String> getItemNameAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(itemID.getName());
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetItemNameAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<Boolean> setBalanceAsync(long balance) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(false);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.SetBalanceAsync, accountNr, itemID, balance);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<Boolean> setRealBalanceAsync(double balance) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(false);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.SetRealBalanceAsync, accountNr, itemID, balance);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> depositAsync(long amount) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_MASTER_CONNECTION);
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.DepositAsync, accountNr, itemID, amount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> depositRealAsync(double amount) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_MASTER_CONNECTION);
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.DepositRealAsync, accountNr, itemID, amount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<Boolean> hasSufficientFundsAsync(long amount) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(false);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.HasSufficientFundsAsync, accountNr, itemID, amount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> withdrawAsync(long amount) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_MASTER_CONNECTION);
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.WithdrawAsync, accountNr, itemID, amount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> withdrawRealAsync(double amount) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_MASTER_CONNECTION);
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.WithdrawRealAsync, accountNr, itemID, amount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> withdrawLockedAsync(long amount) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_MASTER_CONNECTION);
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.WithdrawLockedAsync, accountNr, itemID, amount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> withdrawLockedRealAsync(double amount) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_MASTER_CONNECTION);
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.WithdrawLockedRealAsync, accountNr, itemID, amount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> withdrawLockedPreferedAsync(long amount) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_MASTER_CONNECTION);
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.WithdrawLockedPreferedAsync, accountNr, itemID, amount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> withdrawLockedPreferedRealAsync(double amount) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_MASTER_CONNECTION);
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.WithdrawLockedPreferedRealAsync, accountNr, itemID, amount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> transferAsync(long amount, int toAccount) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_MASTER_CONNECTION);
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.TransferAsync, accountNr, itemID, new ParamGroup_long_int(amount, toAccount));
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> transferRealAsync(double amount, int toAccount) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_MASTER_CONNECTION);
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.TransferRealAsync, accountNr, itemID, new ParamGroup_double_int(amount, toAccount));
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> transferFromLockedAsync(long amount, int toAccount) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_MASTER_CONNECTION);
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.TransferFromLockedAsync, accountNr, itemID, new ParamGroup_long_int(amount, toAccount));
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> transferFromLockedRealAsync(double amount, int toAccount) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_MASTER_CONNECTION);
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.TransferFromLockedRealAsync, accountNr, itemID, new ParamGroup_double_int(amount, toAccount));
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> transferFromLockedPreferedAsync(long amount, int toAccount) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_MASTER_CONNECTION);
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.TransferFromLockedPreferedAsync, accountNr, itemID, new ParamGroup_long_int(amount, toAccount));
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> transferFromLockedPreferedRealAsync(double amount, int toAccount) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_MASTER_CONNECTION);
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.TransferFromLockedPreferedRealAsync, accountNr, itemID, new ParamGroup_double_int(amount, toAccount));
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> lockAmountAsync(long amount) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_MASTER_CONNECTION);
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.LockAmountAsync, accountNr, itemID, amount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> lockAmountRealAsync(double amount) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_MASTER_CONNECTION);
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.LockAmountRealAsync, accountNr, itemID, amount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> unlockAmountAsync(long amount) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_MASTER_CONNECTION);
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.UnlockAmountAsync, accountNr, itemID, amount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<BankStatus> unlockAmountRealAsync(double amount) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_MASTER_CONNECTION);
        CompletableFuture<BankStatus> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.UnlockAmountRealAsync, accountNr, itemID, amount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public void unlockAllAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return;
        InputData inputData = InputData.of(FunctionType.UnlockAllAsync, accountNr, itemID);
        sendRequest(inputData);
    }

    @Override
    public CompletableFuture<Long> convertToRawAmountAsync(double realAmount) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(0L);
        CompletableFuture<Long> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.ConvertToRawAmountAsync, accountNr, itemID, realAmount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<Double> convertToRealAmountAsync(long rawAmount) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(0.0);
        CompletableFuture<Double> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.ConvertToRealAmountAsync, accountNr, itemID, rawAmount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<String> getNormalizedBalanceAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture("");
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetNormalizedBalanceAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<String> getNormalizedLockedBalanceAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture("");
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetNormalizedLockedBalanceAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<String> getNormalizedTotalBalanceAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture("");
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetNormalizedTotalBalanceAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<String> getFormattedBalanceAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture("");
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetFormattedBalanceAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<String> getFormattedLockedBalanceAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture("");
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetFormattedLockedBalanceAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<String> getFormattedTotalBalanceAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture("");
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetFormattedTotalBalanceAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<String> getNormalizedAmountAsync(double realAmount) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture("");
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetNormalizedAmountAsync_1, accountNr, itemID, realAmount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<String> getNormalizedAmountAsync(long rawAmount) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture("");
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetNormalizedAmountAsync_2, accountNr, itemID, rawAmount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<String> getFormattedAmountAsync(double realAmount) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture("");
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetFormattedAmountAsync_1, accountNr, itemID, realAmount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<String> getFormattedAmountAsync(long rawAmount) {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture("");
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetFormattedAmountAsync_2, accountNr, itemID, rawAmount);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<String> toStringAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture("");
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.ToStringAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<String> toStringNoOwnerAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture("");
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.ToStringNoOwnerAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<JsonElement> toJsonAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture(new JsonObject());
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.ToJsonAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
        return future;
    }

    @Override
    public CompletableFuture<String> toJsonStringAsync() {
        if(!MultiServerUtils.canInteractWithBankSystem())
            return CompletableFuture.completedFuture("");
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.ToJsonStringAsync, accountNr, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        AsyncFunctionOutputData.relay(outputDataFuture, future);
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
