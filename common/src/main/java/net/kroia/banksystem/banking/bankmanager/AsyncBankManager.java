package net.kroia.banksystem.banking.bankmanager;

import com.google.gson.JsonElement;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bank.IAsyncBank;
import net.kroia.banksystem.api.bank.ISyncServerBank;
import net.kroia.banksystem.api.bankaccount.IAsyncBankAccount;
import net.kroia.banksystem.api.bankmanager.IAsyncBankManager;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.banking.User;
import net.kroia.banksystem.banking.bank.AsyncBank;
import net.kroia.banksystem.banking.bankaccount.AsyncBankAccount;
import net.kroia.banksystem.banking.bankaccount.SyncServerBankAccount;
import net.kroia.banksystem.banking.clientdata.BankAccountData;
import net.kroia.banksystem.banking.clientdata.BankManagerData;
import net.kroia.banksystem.banking.clientdata.ItemInfoData;
import net.kroia.banksystem.util.ItemID;
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
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * The AsyncBankManager is used to forward the methods to a server or master server
 */
public class AsyncBankManager implements IAsyncBankManager {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
        SyncServerBankAccount.setBackend(backend);
    }
    private final boolean isClientSide;

    private AsyncBankManager(boolean clientSide) {
        this.isClientSide = clientSide;
    }

    public static AsyncBankManager createClientManager()
    {
        return new AsyncBankManager(true);
    }
    public static AsyncBankManager createSlaveServerManager()
    {
        return new AsyncBankManager(false);
    }

    private AsyncBankAccount createBankAccount(int accountNr)
    {
        return AsyncBankAccount.createBankAccount(accountNr, isClientSide);
    }
    private AsyncBank createBank(int accountNr, ItemID itemID)
    {
        return AsyncBank.createBank(accountNr, itemID, isClientSide);
    }


    /**
     * Enumeration to specify each function that can be forwarded
     */
    public enum FunctionType
    {
        GetBankManagerDataAsync,
        GetBankManagerUserMapDataAsync,
        GetBankManagerBankAccountsDataAsync,
        SetBanksystemAdminModeAsync,
        IsBanksystemAdminAsync,
        GetAllowedItemsAsync,
        GetBlacklistedItemsAsync,
        GetNotRemovableItemsAsync,
        GetItemInfoDataAsync,
        AddUserAsync_1,
        RemoveUserAsync,
        UserExistsAsync,
        GetUserByUUIDAsync,
        GetUserByNameAsync,
        BankAccountExistsAsync,
        BankAccountHasBankAsync,
        //CreatePersonalBankAccountAsync,
        CreatePersonalBankAccountGetAccountNrAsync_1,
        CreatePersonalBankAccountGetAccountNrAsync_2,
        GetPersonalBankAccountNrAsync_1,
        GetPersonalBankAccountNrAsync_2,
        //CreateBankAccountAsync,
        CreateBankAccountGetAccountNrAsync,
        //GetBankAccountAsync,
        //GetBankAccountsAsync_1,
        GetBankAccountNumbersAsync_1,
        GetBankAccountNumbersAsync_2,
        GetBankAccountsDataAsync_1,
        //GetBankAccountsAsync_2,
        GetBankAccountsDataAsync_2,
        //GetPersonalBankAccountAsync_1,
        GetPersonalBankAccountDataAsync_1,
        //GetPersonalBankAccountAsync_2,
        GetPersonalBankAccountDataAsync_2,
        //GetOrCreatePersonalBankAccountAsync_1,
        //GetOrCreatePersonalBankAccountAsync_2,
        UserHasPersonalBankAccountAsync,
        DeleteBankAccountAsync,
        PersonalBankExistsAsync_1,
        PersonalBankExistsAsync_2,
        //GetPersonalBankAsync_1,
        //GetPersonalBankAsync_2,
        GetOrCreatePersonalBankAsync_1,
        GetOrCreatePersonalBankAsync_2,
        IsItemIDAllowedAsync,
        AllowItemIDAsync,
        DisallowItemIDAsync,
        IsItemIDNotRemovableAsync,
        IsItemIDBlacklistedAsync,
        GetRealMoneyCirculationAsync,
        GetRealLockedMoneyCirculationAsync,
        GetRealItemCirculationAsync,
        GetRealLockedItemCirculationAsync,
        GetCirculationDataJsonAsync,
        GetCirculationDataJsonStringAsync,
        ToJsonAsync,
        ToJsonStringAsync,
        OnPlayerJoinAsync
    }




    /**
     * Map of codec pairs for each function
     * If a codec is set to null, that means the argument is not available.
     *      -> inputParamsCodec  == null: The function does not take any parameters
     *      -> outputParamsCodec == null: The function does not return any value
     */
    public static final Map<FunctionType, AsyncFunctionDataCodecs> codecs = new HashMap<>(){{
        put(FunctionType.GetBankManagerDataAsync,                   new AsyncFunctionDataCodecs(null, BankManagerData.STREAM_CODEC));
        put(FunctionType.GetBankManagerUserMapDataAsync,            new AsyncFunctionDataCodecs(null, BankManagerData.UserMapData.STREAM_CODEC));
        put(FunctionType.GetBankManagerBankAccountsDataAsync,       new AsyncFunctionDataCodecs(null, BankManagerData.BankAccountsData.STREAM_CODEC));
        put(FunctionType.SetBanksystemAdminModeAsync,               new AsyncFunctionDataCodecs(ParamGroup_UUID_bool.STREAM_CODEC, ByteBufCodecs.BOOL.cast()));
        put(FunctionType.IsBanksystemAdminAsync,				    new AsyncFunctionDataCodecs(UUIDUtil.STREAM_CODEC.cast(),ByteBufCodecs.BOOL.cast()));
        put(FunctionType.GetAllowedItemsAsync,					    new AsyncFunctionDataCodecs(null, ExtraCodecUtils.listStreamCodec(ItemID.STREAM_CODEC)));
        put(FunctionType.GetBlacklistedItemsAsync,				    new AsyncFunctionDataCodecs(null, ExtraCodecUtils.listStreamCodec(ItemID.STREAM_CODEC)));
        put(FunctionType.GetNotRemovableItemsAsync,				    new AsyncFunctionDataCodecs(null, ExtraCodecUtils.listStreamCodec(ItemID.STREAM_CODEC)));
        put(FunctionType.GetItemInfoDataAsync,					    new AsyncFunctionDataCodecs(null, ItemInfoData.STREAM_CODEC));
        put(FunctionType.AddUserAsync_1,						    new AsyncFunctionDataCodecs(ParamGroup_UUID_String.STREAM_CODEC, null));
        //put(FunctionType.AddUserAsync_2,						    new AsyncFunctionDataCodecs(null, null));
        //put(FunctionType.AddUserAsync_3,						    new AsyncFunctionDataCodecs(null, null));
        put(FunctionType.RemoveUserAsync,						    new AsyncFunctionDataCodecs(UUIDUtil.STREAM_CODEC.cast(), ByteBufCodecs.BOOL.cast()));
        put(FunctionType.UserExistsAsync,						    new AsyncFunctionDataCodecs(UUIDUtil.STREAM_CODEC.cast(), ByteBufCodecs.BOOL.cast()));
        put(FunctionType.GetUserByUUIDAsync,					    new AsyncFunctionDataCodecs(UUIDUtil.STREAM_CODEC.cast(), User.STREAM_CODEC));
        put(FunctionType.GetUserByNameAsync,					    new AsyncFunctionDataCodecs(ByteBufCodecs.STRING_UTF8.cast(), User.STREAM_CODEC));
        put(FunctionType.BankAccountExistsAsync,					new AsyncFunctionDataCodecs(ByteBufCodecs.INT.cast(), ByteBufCodecs.BOOL.cast()));
        put(FunctionType.BankAccountHasBankAsync,					new AsyncFunctionDataCodecs(ParamGroup_int_ItemID.STREAM_CODEC, ByteBufCodecs.BOOL.cast()));
        //put(FunctionType.CreatePersonalBankAccountAsync,		    new AsyncFunctionDataCodecs(null, null));
        put(FunctionType.CreatePersonalBankAccountGetAccountNrAsync_1,new AsyncFunctionDataCodecs(UUIDUtil.STREAM_CODEC.cast(), ByteBufCodecs.INT.cast()));
        put(FunctionType.CreatePersonalBankAccountGetAccountNrAsync_2,new AsyncFunctionDataCodecs(ByteBufCodecs.STRING_UTF8.cast(), ByteBufCodecs.INT.cast()));
        put(FunctionType.GetPersonalBankAccountNrAsync_1,           new AsyncFunctionDataCodecs(UUIDUtil.STREAM_CODEC.cast(), ByteBufCodecs.INT.cast()));
        put(FunctionType.GetPersonalBankAccountNrAsync_2,           new AsyncFunctionDataCodecs(ByteBufCodecs.STRING_UTF8.cast(), ByteBufCodecs.INT.cast()));
        //put(FunctionType.CreateBankAccountAsync,				    new AsyncFunctionDataCodecs(null, null));
        put(FunctionType.CreateBankAccountGetAccountNrAsync,		new AsyncFunctionDataCodecs(ByteBufCodecs.STRING_UTF8.cast(), ByteBufCodecs.INT.cast()));
        //put(FunctionType.GetBankAccountAsync,					    new AsyncFunctionDataCodecs(null, null));
        //put(FunctionType.GetBankAccountsAsync_1,				    new AsyncFunctionDataCodecs(null, null));
        put(FunctionType.GetBankAccountNumbersAsync_1,				new AsyncFunctionDataCodecs(UUIDUtil.STREAM_CODEC.cast(), ExtraCodecUtils.listStreamCodec(ByteBufCodecs.INT.cast())));
        put(FunctionType.GetBankAccountNumbersAsync_2,				new AsyncFunctionDataCodecs(ItemID.STREAM_CODEC, ExtraCodecUtils.listStreamCodec(ByteBufCodecs.INT.cast())));
        put(FunctionType.GetBankAccountsDataAsync_1,				    new AsyncFunctionDataCodecs(UUIDUtil.STREAM_CODEC.cast(), ExtraCodecUtils.listStreamCodec(BankAccountData.STREAM_CODEC)));
        //put(FunctionType.GetBankAccountsAsync_2,				    new AsyncFunctionDataCodecs(null, null));
        put(FunctionType.GetBankAccountsDataAsync_2,				new AsyncFunctionDataCodecs(ItemID.STREAM_CODEC, ExtraCodecUtils.listStreamCodec(BankAccountData.STREAM_CODEC)));
        //put(FunctionType.GetPersonalBankAccountAsync_1,			    new AsyncFunctionDataCodecs(null, null));
        put(FunctionType.GetPersonalBankAccountDataAsync_1,			    new AsyncFunctionDataCodecs(UUIDUtil.STREAM_CODEC.cast(), BankAccountData.STREAM_CODEC));
        //put(FunctionType.GetPersonalBankAccountAsync_2,			    new AsyncFunctionDataCodecs(null, null));
        put(FunctionType.GetPersonalBankAccountDataAsync_2,			    new AsyncFunctionDataCodecs(ByteBufCodecs.STRING_UTF8.cast(), BankAccountData.STREAM_CODEC));
        //put(FunctionType.GetOrCreatePersonalBankAccountAsync_1,	    new AsyncFunctionDataCodecs(null, null));
        //put(FunctionType.GetOrCreatePersonalBankAccountAsync_2,	    new AsyncFunctionDataCodecs(null, null));
        put(FunctionType.UserHasPersonalBankAccountAsync,		    new AsyncFunctionDataCodecs(UUIDUtil.STREAM_CODEC.cast(), ByteBufCodecs.BOOL.cast()));
        put(FunctionType.DeleteBankAccountAsync,				    new AsyncFunctionDataCodecs(ByteBufCodecs.INT.cast(), ByteBufCodecs.BOOL.cast()));
        put(FunctionType.PersonalBankExistsAsync_1,				    new AsyncFunctionDataCodecs(ParamGroup_UUID_ItemID.STREAM_CODEC, ByteBufCodecs.BOOL.cast()));
        put(FunctionType.PersonalBankExistsAsync_2,				    new AsyncFunctionDataCodecs(ParamGroup_String_ItemID.STREAM_CODEC, ByteBufCodecs.BOOL.cast()));
        //put(FunctionType.GetPersonalBankAsync_1,				    new AsyncFunctionDataCodecs(null, null));
        //put(FunctionType.GetPersonalBankAsync_2,				    new AsyncFunctionDataCodecs(null, null));
        put(FunctionType.GetOrCreatePersonalBankAsync_1,		    new AsyncFunctionDataCodecs(ParamGroup_UUID_ItemID.STREAM_CODEC, ByteBufCodecs.INT.cast()));
        put(FunctionType.GetOrCreatePersonalBankAsync_2,		    new AsyncFunctionDataCodecs(ParamGroup_String_ItemID.STREAM_CODEC, ByteBufCodecs.INT.cast()));
        put(FunctionType.IsItemIDAllowedAsync,					    new AsyncFunctionDataCodecs(ItemID.STREAM_CODEC, ByteBufCodecs.BOOL.cast()));
        put(FunctionType.AllowItemIDAsync,						    new AsyncFunctionDataCodecs(ItemID.STREAM_CODEC, ByteBufCodecs.BOOL.cast()));
        put(FunctionType.DisallowItemIDAsync,					    new AsyncFunctionDataCodecs(ItemID.STREAM_CODEC, ByteBufCodecs.BOOL.cast()));
        put(FunctionType.IsItemIDNotRemovableAsync,				    new AsyncFunctionDataCodecs(ItemID.STREAM_CODEC, ByteBufCodecs.BOOL.cast()));
        put(FunctionType.IsItemIDBlacklistedAsync,				    new AsyncFunctionDataCodecs(ItemID.STREAM_CODEC, ByteBufCodecs.BOOL.cast()));
        put(FunctionType.GetRealMoneyCirculationAsync,			    new AsyncFunctionDataCodecs(null, ByteBufCodecs.DOUBLE.cast()));
        put(FunctionType.GetRealLockedMoneyCirculationAsync,	    new AsyncFunctionDataCodecs(null, ByteBufCodecs.DOUBLE.cast()));
        put(FunctionType.GetRealItemCirculationAsync,			    new AsyncFunctionDataCodecs(ItemID.STREAM_CODEC, ByteBufCodecs.DOUBLE.cast()));
        put(FunctionType.GetRealLockedItemCirculationAsync,		    new AsyncFunctionDataCodecs(ItemID.STREAM_CODEC, ByteBufCodecs.DOUBLE.cast()));
        put(FunctionType.GetCirculationDataJsonAsync,			    new AsyncFunctionDataCodecs(null, ExtraCodecUtils.JSON_ELEMENT_CODEC));
        put(FunctionType.GetCirculationDataJsonStringAsync,		    new AsyncFunctionDataCodecs(null, ByteBufCodecs.STRING_UTF8.cast()));
        put(FunctionType.ToJsonAsync,							    new AsyncFunctionDataCodecs(null, ExtraCodecUtils.JSON_ELEMENT_CODEC));
        put(FunctionType.ToJsonStringAsync,						    new AsyncFunctionDataCodecs(null, ByteBufCodecs.STRING_UTF8.cast()));
        put(FunctionType.OnPlayerJoinAsync,						    new AsyncFunctionDataCodecs( ParamGroup_UUID_String.STREAM_CODEC, null));
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
        public static <T> InputData of(FunctionType functionType, T result)
        {
            return (InputData) AsyncFunctionInputData.of(codecs.get(functionType).inputParamsCodec, functionType, result, InputData::new);
        }
        public static InputData of(FunctionType functionType)
        {
            return (InputData) AsyncFunctionInputData.of(functionType, InputData::new);
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
            ISyncServerBankManager bankManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
            return CompletableFuture.completedFuture(switch (input.function) {
                case FunctionType.GetBankManagerDataAsync -> OutputData.of(input.function, bankManager.getBankManagerData());
                case FunctionType.GetBankManagerUserMapDataAsync -> OutputData.of(input.function, bankManager.getBankManagerUserMapData());
                case FunctionType.GetBankManagerBankAccountsDataAsync -> OutputData.of(input.function, bankManager.getBankManagerBankAccountsData());
                case FunctionType.SetBanksystemAdminModeAsync -> {
                    ParamGroup_UUID_bool param = input.decodeParams();
                    yield OutputData.of(input.function, bankManager.setBanksystemAdminMode(param.uuid, param.bool));
                }
                case FunctionType.IsBanksystemAdminAsync -> OutputData.of(input.function, bankManager.isBanksystemAdmin(input.decodeParams()));
                case FunctionType.GetAllowedItemsAsync -> OutputData.of(input.function, bankManager.getAllowedItems());
                case FunctionType.GetBlacklistedItemsAsync -> OutputData.of(input.function, bankManager.getBlacklistedItems());
                case FunctionType.GetNotRemovableItemsAsync -> OutputData.of(input.function, bankManager.getNotRemovableItems());
                case FunctionType.GetItemInfoDataAsync -> OutputData.of(input.function, bankManager.getItemInfoData(input.decodeParams()));
                case FunctionType.AddUserAsync_1 -> {
                    ParamGroup_UUID_String param = input.decodeParams();
                    bankManager.addUser(param.uuid, param.string);
                    yield OutputData.of(input.function);
                }
                case FunctionType.RemoveUserAsync -> OutputData.of(input.function, bankManager.removeUser(input.decodeParams()));
                case FunctionType.UserExistsAsync -> OutputData.of(input.function, bankManager.userExists(input.decodeParams()));
                case FunctionType.GetUserByUUIDAsync -> OutputData.of(input.function, bankManager.getUserByUUID(input.decodeParams()));
                case FunctionType.GetUserByNameAsync -> OutputData.of(input.function, bankManager.getUserByName(input.decodeParams()));
                case FunctionType.BankAccountExistsAsync -> OutputData.of(input.function, bankManager.bankAccountExists(input.decodeParams()));
                case FunctionType.BankAccountHasBankAsync -> {
                    ParamGroup_int_ItemID  param = input.decodeParams();
                    yield OutputData.of(input.function, bankManager.bankAccountHasBank(param.integer, param.itemID));
                }
                //case FunctionType.CreatePersonalBankAccountAsync -> OutputData.of(input.function, bankManager.createPersonalBankAccount(input.decodeParams()));
                case FunctionType.CreatePersonalBankAccountGetAccountNrAsync_1 -> OutputData.of(input.function, bankManager.createPersonalBankAccountGetAccountNr((UUID)input.decodeParams()));
                case FunctionType.CreatePersonalBankAccountGetAccountNrAsync_2 -> OutputData.of(input.function, bankManager.createPersonalBankAccountGetAccountNr((String)input.decodeParams()));
                case FunctionType.GetPersonalBankAccountNrAsync_1 -> OutputData.of(input.function, bankManager.getPersonalBankAccountNr((UUID)input.decodeParams()));
                case FunctionType.GetPersonalBankAccountNrAsync_2 -> OutputData.of(input.function, bankManager.getPersonalBankAccountNr((String)input.decodeParams()));
                //case FunctionType.CreateBankAccountAsync -> OutputData.of(input.function, bankManager.createBankAccount(input.decodeParams()));
                case FunctionType.CreateBankAccountGetAccountNrAsync -> OutputData.of(input.function, bankManager.createBankAccountGetAccountNr(input.decodeParams()));
                //case FunctionType.GetBankAccountAsync -> OutputData.of(input.function, bankManager.getBankAccount(input.decodeParams()));
                //case FunctionType.GetBankAccountsAsync_1 -> OutputData.of(input.function, bankManager. );
                case FunctionType.GetBankAccountNumbersAsync_1 -> OutputData.of(input.function, bankManager.getBankAccountNumbers((UUID)input.decodeParams()));
                case FunctionType.GetBankAccountNumbersAsync_2 -> OutputData.of(input.function, bankManager.getBankAccountNumbers((ItemID)input.decodeParams()));
                case FunctionType.GetBankAccountsDataAsync_1 -> OutputData.of(input.function, bankManager.getBankAccountsData((UUID)input.decodeParams()));
                //case FunctionType.GetBankAccountsAsync_2 -> OutputData.of(input.function, bankManager. );
                case FunctionType.GetBankAccountsDataAsync_2 -> OutputData.of(input.function, bankManager.getBankAccountsData((ItemID)input.decodeParams()));
                //case FunctionType.GetPersonalBankAccountAsync_1 -> OutputData.of(input.function, bankManager. );
                case FunctionType.GetPersonalBankAccountDataAsync_1 -> OutputData.of(input.function, bankManager.getPersonalBankAccountData((UUID)input.decodeParams()));
                //case FunctionType.GetPersonalBankAccountAsync_2 -> OutputData.of(input.function, bankManager. );
                case FunctionType.GetPersonalBankAccountDataAsync_2 -> OutputData.of(input.function, bankManager.getPersonalBankAccountData((String)input.decodeParams()));
                //case FunctionType.GetOrCreatePersonalBankAccountAsync_1 -> OutputData.of(input.function, bankManager. );
                //case FunctionType.GetOrCreatePersonalBankAccountAsync_2 -> OutputData.of(input.function, bankManager. );
                case FunctionType.UserHasPersonalBankAccountAsync -> OutputData.of(input.function, bankManager.userHasPersonalBankAccount(input.decodeParams()));
                case FunctionType.DeleteBankAccountAsync -> OutputData.of(input.function, bankManager.deleteBankAccount(input.decodeParams()));
                case FunctionType.PersonalBankExistsAsync_1 -> {
                    ParamGroup_UUID_ItemID param = input.decodeParams();
                    yield OutputData.of(input.function, bankManager.personalBankExists(param.uuid, param.itemID));
                }
                case FunctionType.PersonalBankExistsAsync_2 -> {
                    ParamGroup_String_ItemID param = input.decodeParams();
                    yield OutputData.of(input.function, bankManager.personalBankExists(param.string, param.itemID));
                }
                //case FunctionType.GetPersonalBankAsync_1 -> OutputData.of(input.function, bankManager.);
                //case FunctionType.GetPersonalBankAsync_2 -> OutputData.of(input.function, bankManager. );
                case FunctionType.GetOrCreatePersonalBankAsync_1 -> {
                    ParamGroup_UUID_ItemID param = input.decodeParams();
                    ISyncServerBank bank = bankManager.getOrCreatePersonalBank(param.uuid, param.itemID);
                    yield OutputData.of(input.function, (bank != null)?bankManager.getPersonalBankAccount(param.uuid()).getAccountNumber(): 0);
                }
                case FunctionType.GetOrCreatePersonalBankAsync_2 -> {
                    ParamGroup_String_ItemID param = input.decodeParams();
                    ISyncServerBank bank = bankManager.getOrCreatePersonalBank(param.string, param.itemID);
                    yield OutputData.of(input.function, (bank != null)?bankManager.getPersonalBankAccount(param.string).getAccountNumber(): 0);
                }
                case FunctionType.IsItemIDAllowedAsync -> OutputData.of(input.function, bankManager.isItemIDAllowed(input.decodeParams()));
                case FunctionType.AllowItemIDAsync -> OutputData.of(input.function, bankManager.allowItemID(input.decodeParams()) );
                case FunctionType.DisallowItemIDAsync -> OutputData.of(input.function, bankManager.disallowItemID(input.decodeParams()) );
                case FunctionType.IsItemIDNotRemovableAsync -> OutputData.of(input.function, bankManager.isItemIDNotRemovable(input.decodeParams()) );
                case FunctionType.IsItemIDBlacklistedAsync -> OutputData.of(input.function, bankManager.isItemIDBlacklisted(input.decodeParams()) );
                case FunctionType.GetRealMoneyCirculationAsync -> OutputData.of(input.function, bankManager.getRealMoneyCirculation() );
                case FunctionType.GetRealLockedMoneyCirculationAsync -> OutputData.of(input.function, bankManager.getRealLockedMoneyCirculation() );
                case FunctionType.GetRealItemCirculationAsync -> OutputData.of(input.function, bankManager.getRealItemCirculation(input.decodeParams()) );
                case FunctionType.GetRealLockedItemCirculationAsync -> OutputData.of(input.function, bankManager.getRealLockedItemCirculation(input.decodeParams()) );
                case FunctionType.GetCirculationDataJsonAsync -> OutputData.of(input.function, bankManager.getCirculationDataJson() );
                case FunctionType.GetCirculationDataJsonStringAsync -> OutputData.of(input.function, bankManager.getCirculationDataJsonString() );
                case FunctionType.ToJsonAsync -> OutputData.of(input.function, bankManager.toJson() );
                case FunctionType.ToJsonStringAsync -> OutputData.of(input.function, bankManager.toJsonString() );
                case FunctionType.OnPlayerJoinAsync -> {
                    ParamGroup_UUID_String  param = input.decodeParams();
                    bankManager.onPlayerJoin(param.uuid, param.string);
                    yield OutputData.of(input.function);
                }

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

    private record ParamGroup_UUID_bool(UUID uuid, boolean bool)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, ParamGroup_UUID_bool> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, p -> p.uuid,
                ByteBufCodecs.BOOL, p -> p.bool,
                ParamGroup_UUID_bool::new
        );
    }
    private record ParamGroup_UUID_String(UUID uuid, String string)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, ParamGroup_UUID_String> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, p -> p.uuid,
                ByteBufCodecs.STRING_UTF8, p -> p.string,
                ParamGroup_UUID_String::new
        );
    }
    private record ParamGroup_int_ItemID(int integer, ItemID itemID)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, ParamGroup_int_ItemID> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, p -> p.integer,
                ItemID.STREAM_CODEC, p -> p.itemID,
                ParamGroup_int_ItemID::new
        );
    }
    private record ParamGroup_UUID_ItemID(UUID uuid, ItemID itemID)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, ParamGroup_UUID_ItemID> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, p -> p.uuid,
                ItemID.STREAM_CODEC, p -> p.itemID,
                ParamGroup_UUID_ItemID::new
        );
    }
    private record ParamGroup_String_ItemID(String string, ItemID itemID)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, ParamGroup_String_ItemID> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, p -> p.string,
                ItemID.STREAM_CODEC, p -> p.itemID,
                ParamGroup_String_ItemID::new
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
    public CompletableFuture<BankManagerData> getBankManagerDataAsync() {
        CompletableFuture<BankManagerData> future = new CompletableFuture<>();
        InputData inputData = new InputData(FunctionType.GetBankManagerDataAsync);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<BankManagerData.UserMapData> getBankManagerUserMapDataAsync() {
        CompletableFuture<BankManagerData.UserMapData> future = new CompletableFuture<>();
        InputData inputData = new InputData(FunctionType.GetBankManagerUserMapDataAsync);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<BankManagerData.BankAccountsData> getBankManagerBankAccountsDataAsync() {
        CompletableFuture<BankManagerData.BankAccountsData> future = new CompletableFuture<>();
        InputData inputData = new InputData(FunctionType.GetBankManagerBankAccountsDataAsync);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Boolean> setBanksystemAdminModeAsync(UUID playerUUID, boolean isAdmin) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.SetBanksystemAdminModeAsync, new ParamGroup_UUID_bool(playerUUID, isAdmin));
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Boolean> isBanksystemAdminAsync(UUID playerUUID) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.IsBanksystemAdminAsync, playerUUID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<List<ItemID>> getAllowedItemsAsync() {
        CompletableFuture<List<ItemID>> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetAllowedItemsAsync);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<List<ItemID>> getBlacklistedItemsAsync() {
        CompletableFuture<List<ItemID>> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetBlacklistedItemsAsync);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<List<ItemID>> getNotRemovableItemsAsync() {
        CompletableFuture<List<ItemID>> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetNotRemovableItemsAsync);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<ItemInfoData> getItemInfoDataAsync(@NotNull ItemID itemID) {
        CompletableFuture<ItemInfoData> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetItemInfoDataAsync);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public void addUserAsync(@NotNull ServerPlayer player) {
        addUserAsync(player.getUUID(), player.getName().getString());
    }

    @Override
    public void addUserAsync(@NotNull UUID playerUUID, @NotNull String playerName) {
        InputData inputData = InputData.of(FunctionType.AddUserAsync_1, new ParamGroup_UUID_String(playerUUID, playerName));
        sendRequest(inputData);
    }

    @Override
    public void addUserAsync(@NotNull User user) {
        addUserAsync(user.getUUID(), user.getName());
    }

    @Override
    public CompletableFuture<Boolean> removeUserAsync(UUID userUUID) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.RemoveUserAsync, userUUID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Boolean> userExistsAsync(UUID userUUID) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.UserExistsAsync, userUUID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<@Nullable User> getUserByUUIDAsync(UUID userUUID) {
        CompletableFuture<@Nullable User> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetUserByUUIDAsync, userUUID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<@Nullable User> getUserByNameAsync(String name) {
        CompletableFuture<@Nullable User> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetUserByNameAsync, name);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }





    @Override
    public CompletableFuture<Boolean> bankAccountExistsAsync(int accountNumber)
    {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.BankAccountExistsAsync, accountNumber);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Boolean> bankAccountHasBankAsync(int accountNumber, ItemID itemID) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.BankAccountHasBankAsync, new ParamGroup_int_ItemID(accountNumber, itemID));
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }


    @Override
    public CompletableFuture<@Nullable IAsyncBankAccount> createPersonalBankAccountAsync(UUID user) {
        CompletableFuture<@Nullable IAsyncBankAccount> future =  new CompletableFuture<>();
        CompletableFuture<Integer> accountNrFuture = createPersonalBankAccountGetAccountNrAsync(user);
        accountNrFuture.thenAccept(accountNr ->
        {
            if(accountNr <= 0)
            {
                future.complete(null);
                return;
            }
            future.complete(createBankAccount(accountNr));
        });
        return future;
    }

    @Override
    public CompletableFuture<Integer> createPersonalBankAccountGetAccountNrAsync(UUID user) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.CreatePersonalBankAccountGetAccountNrAsync_1, user);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Integer> createPersonalBankAccountGetAccountNrAsync(String userName) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.CreatePersonalBankAccountGetAccountNrAsync_2, userName);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Integer> getPersonalBankAccountNrAsync(UUID user) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetPersonalBankAccountNrAsync_1, user);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Integer> getPersonalBankAccountNrAsync(String userName) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetPersonalBankAccountNrAsync_2, userName);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<@Nullable IAsyncBankAccount> createBankAccountAsync(String accountName) {
        CompletableFuture<@Nullable IAsyncBankAccount> future =  new CompletableFuture<>();
        CompletableFuture<Integer> accountNrFuture = createBankAccountGetAccountNrAsync(accountName);
        accountNrFuture.thenAccept(accountNr ->
        {
            if(accountNr <= 0)
            {
                future.complete(null);
                return;
            }
            future.complete(createBankAccount(accountNr));
        });
        return future;
    }

    @Override
    public CompletableFuture<Integer> createBankAccountGetAccountNrAsync(String accountName) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.CreateBankAccountGetAccountNrAsync, accountName);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<@Nullable IAsyncBankAccount> getBankAccountAsync(int accountNumber) {
        CompletableFuture<@Nullable IAsyncBankAccount>  future = new CompletableFuture<>();
        CompletableFuture<Boolean> accountExistsFuture = bankAccountExistsAsync(accountNumber);
        accountExistsFuture.thenAccept(accountExists ->{
           if(accountExists)
           {
               future.complete(createBankAccount(accountNumber));
           }
           else
           {
               future.complete(null);
           }
        });
        return future;
    }

    @Override
    public CompletableFuture<List<IAsyncBankAccount>> getBankAccountsAsync(UUID userUUID) {
        CompletableFuture<List<IAsyncBankAccount>> future = new CompletableFuture<>();
        getBankAccountNumbersAsync(userUUID).thenAccept((bankAccountDataList)->{
            List<IAsyncBankAccount> list = new ArrayList<>();
            for(Integer accountNr : bankAccountDataList)
                list.add(createBankAccount(accountNr));
            future.complete(list);
        });
        return future;
    }

    @Override
    public CompletableFuture<List<Integer>> getBankAccountNumbersAsync(UUID userUUID) {
        CompletableFuture<List<Integer>> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetBankAccountNumbersAsync_1, userUUID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<List<Integer>> getBankAccountNumbersAsync(ItemID itemID) {
        CompletableFuture<List<Integer>> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetBankAccountNumbersAsync_2, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<List<BankAccountData>> getBankAccountsDataAsync(UUID userUUID)
    {
        CompletableFuture<List<BankAccountData>> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetBankAccountsDataAsync_1, userUUID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }


    @Override
    public CompletableFuture<List<IAsyncBankAccount>> getBankAccountsAsync(ItemID itemID) {
        CompletableFuture<List<IAsyncBankAccount>> future = new CompletableFuture<>();
        getBankAccountNumbersAsync(itemID).thenAccept((bankAccountDataList)->{
            List<IAsyncBankAccount> list = new ArrayList<>();
            for(Integer accountNr : bankAccountDataList)
                list.add(createBankAccount(accountNr));
            future.complete(list);
        });
        return future;
    }
    @Override
    public CompletableFuture<List<BankAccountData>> getBankAccountsDataAsync(ItemID itemID)
    {
        CompletableFuture<List<BankAccountData>> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetBankAccountsDataAsync_2, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<@Nullable IAsyncBankAccount> getPersonalBankAccountAsync(UUID userUUID) {
        CompletableFuture<@Nullable IAsyncBankAccount> future = new CompletableFuture<>();
        getPersonalBankAccountNrAsync(userUUID).thenAccept(accountNr -> {
            if(accountNr > 0)
            {
                future.complete(createBankAccount(accountNr));
            }
        });
        return future;
    }
    @Override
    public CompletableFuture<@Nullable BankAccountData> getPersonalBankAccountDataAsync(UUID userUUID)
    {
        CompletableFuture<BankAccountData> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetPersonalBankAccountDataAsync_1, userUUID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<@Nullable IAsyncBankAccount> getPersonalBankAccountAsync(String userName) {
        CompletableFuture<@Nullable IAsyncBankAccount> future = new CompletableFuture<>();
        getPersonalBankAccountNrAsync(userName).thenAccept(accountNr -> {
            if(accountNr > 0)
            {
                future.complete(createBankAccount(accountNr));
            }
        });
        return future;
    }
    @Override
    public CompletableFuture<@Nullable BankAccountData> getPersonalBankAccountDataAsync(String userName)
    {
        CompletableFuture<BankAccountData> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetPersonalBankAccountDataAsync_2, userName);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<@Nullable IAsyncBankAccount> getOrCreatePersonalBankAccountAsync(UUID userUUID) {
        CompletableFuture<@Nullable IAsyncBankAccount> future = new CompletableFuture<>();
        CompletableFuture<Integer> createdAccount = createPersonalBankAccountGetAccountNrAsync(userUUID);
        createdAccount.thenAccept(account -> {
           future.complete(createBankAccount(account));
        });
        return future;
    }

    @Override
    public CompletableFuture<@Nullable IAsyncBankAccount> getOrCreatePersonalBankAccountAsync(@NotNull String userName) {
        CompletableFuture<@Nullable IAsyncBankAccount> future = new CompletableFuture<>();
        CompletableFuture<Integer> createdAccount = createPersonalBankAccountGetAccountNrAsync(userName);
        createdAccount.thenAccept(account -> {
            future.complete(createBankAccount(account));
        });
        return future;
    }

    @Override
    public CompletableFuture<Boolean> userHasPersonalBankAccountAsync(UUID userUUID) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.UserHasPersonalBankAccountAsync, userUUID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Boolean> deleteBankAccountAsync(int accountNumber) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.DeleteBankAccountAsync, accountNumber);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Boolean> personalBankExistsAsync(UUID owner, ItemID itemID) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.PersonalBankExistsAsync_1, new ParamGroup_UUID_ItemID(owner, itemID));
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Boolean> personalBankExistsAsync(String ownerName, ItemID itemID) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.PersonalBankExistsAsync_2, new ParamGroup_String_ItemID(ownerName, itemID));
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<@Nullable IAsyncBank> getPersonalBankAsync(UUID owner, ItemID itemID) {
        CompletableFuture<@Nullable IAsyncBank> future = new CompletableFuture<>();
        getPersonalBankAccountNrAsync(owner).thenAccept(accountNr -> {
            if(accountNr > 0)
            {
                bankAccountHasBankAsync(accountNr, itemID).thenAccept(bankAccount -> {
                    if(bankAccount)
                        future.complete(createBank(accountNr, itemID));
                    else
                        future.complete(null);
                });
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<@Nullable IAsyncBank> getPersonalBankAsync(String ownerName, ItemID itemID) {
        CompletableFuture<@Nullable IAsyncBank> future = new CompletableFuture<>();
        getPersonalBankAccountNrAsync(ownerName).thenAccept(accountNr -> {
            if(accountNr > 0)
            {
                bankAccountHasBankAsync(accountNr, itemID).thenAccept(bankAccount -> {
                    if(bankAccount)
                        future.complete(createBank(accountNr, itemID));
                    else
                        future.complete(null);
                });
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<@Nullable IAsyncBank> getOrCreatePersonalBankAsync(UUID owner, ItemID itemID) {
        CompletableFuture<@Nullable IAsyncBank> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetOrCreatePersonalBankAsync_1, new ParamGroup_UUID_ItemID(owner, itemID));
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> {
            int accountNr = outputData.decodeResult();
            if(accountNr > 0){
                IAsyncBank bank = createBank(accountNr, itemID);
                future.complete(bank);
            }
            else
                future.complete(null);
        });
        return future;
    }

    @Override
    public CompletableFuture<@Nullable IAsyncBank> getOrCreatePersonalBankAsync(String ownerName, ItemID itemID) {
        CompletableFuture<@Nullable IAsyncBank> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetOrCreatePersonalBankAsync_2, new ParamGroup_String_ItemID(ownerName, itemID));
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> {
            int accountNr = outputData.decodeResult();
            if(accountNr > 0){
                IAsyncBank bank = createBank(accountNr, itemID);
                future.complete(bank);
            }
            else
                future.complete(null);
        });
        return future;
    }

    @Override
    public CompletableFuture<Boolean> isItemIDAllowedAsync(ItemID itemID) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.IsItemIDAllowedAsync, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Boolean> allowItemIDAsync(ItemID itemID) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.AllowItemIDAsync, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Boolean> disallowItemIDAsync(ItemID itemID) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.DisallowItemIDAsync, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Boolean> isItemIDNotRemovableAsync(ItemID itemID) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.IsItemIDNotRemovableAsync, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Boolean> isItemIDBlacklistedAsync(ItemID itemID) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.IsItemIDBlacklistedAsync, itemID);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Double> getRealMoneyCirculationAsync() {
        CompletableFuture<Double> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetRealMoneyCirculationAsync);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Double> getRealLockedMoneyCirculationAsync() {
        CompletableFuture<Double> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetRealLockedMoneyCirculationAsync);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Double> getRealItemCirculationAsync(ItemID itemID) {
        CompletableFuture<Double> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetRealItemCirculationAsync);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<Double> getRealLockedItemCirculationAsync(ItemID itemID) {
        CompletableFuture<Double> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetRealLockedItemCirculationAsync);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<JsonElement> getCirculationDataJsonAsync() {
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetCirculationDataJsonAsync);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<String> getCirculationDataJsonStringAsync() {
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.GetCirculationDataJsonStringAsync);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<JsonElement> toJsonAsync() {
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.ToJsonAsync);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public CompletableFuture<String> toJsonStringAsync() {
        CompletableFuture<String> future = new CompletableFuture<>();
        InputData inputData = InputData.of(FunctionType.ToJsonStringAsync);
        CompletableFuture<OutputData> outputDataFuture = sendRequest(inputData);
        outputDataFuture.thenAccept((outputData)-> future.complete(outputData.decodeResult()));
        return future;
    }

    @Override
    public void onPlayerJoinAsync(UUID playerUUID, String playerName) {
        InputData inputData = InputData.of(FunctionType.OnPlayerJoinAsync, new ParamGroup_UUID_String(playerUUID, playerName));
        sendRequest(inputData);
    }



    private static void info(String msg)
    {
        BACKEND_INSTANCES.LOGGER.info("[AsyncBankManager] " + msg);
    }
    private static void error(String msg)
    {
        BACKEND_INSTANCES.LOGGER.error("[AsyncBankManager] " + msg);
    }
    private static void error(String msg, Throwable e)
    {
        BACKEND_INSTANCES.LOGGER.error("[AsyncBankManager] " + msg, e);
    }
    private static void warn(String msg)
    {
        BACKEND_INSTANCES.LOGGER.warn("[AsyncBankManager] " + msg);
    }
    private static void debug(String msg)
    {
        BACKEND_INSTANCES.LOGGER.debug("[AsyncBankManager] " + msg);
    }

}
