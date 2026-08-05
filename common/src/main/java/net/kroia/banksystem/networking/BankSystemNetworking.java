package net.kroia.banksystem.networking;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.banking.bank.AsyncBank;
import net.kroia.banksystem.banking.bankaccount.AsyncBankAccount;
import net.kroia.banksystem.banking.bankmanager.AsyncBankManager;
import net.kroia.banksystem.minecraft.command.AsyncBankSystemCommandHandler;
import net.kroia.banksystem.networking.currency.BindExternalAccountRequest;
import net.kroia.banksystem.networking.currency.ListBindableAccountsRequest;
import net.kroia.banksystem.networking.currency.ListBindingsForAccountRequest;
import net.kroia.banksystem.networking.currency.ListCurrencyProvidersRequest;
import net.kroia.banksystem.networking.currency.UnbindExternalAccountRequest;
import net.kroia.banksystem.networking.entity.*;
import net.kroia.banksystem.networking.general.*;
import net.kroia.banksystem.networking.multi_server.*;
import net.kroia.banksystem.networking.ui.SyncOpenGUIPacket;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.BankSystemGenericStream;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.modutilities.networking.NetworkPacketManager;
import net.kroia.modutilities.networking.client_server.arrs.AsynchronousRequestResponseSystem;
import net.kroia.modutilities.networking.client_server.streaming.StreamSystem;

public class BankSystemNetworking extends NetworkPacketManager {

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BankSystemNetworkPacket.setBackend(backend);
        BankSystemGenericRequest.setBackend(backend);
        BankSystemGenericStream.setBackend(backend);
    }


    public static RemoveEmptyBanksRequest REMOVE_EMPTY_BANKS_REQUEST = (RemoveEmptyBanksRequest) AsynchronousRequestResponseSystem.register(new RemoveEmptyBanksRequest());
    public static UpdateBankAccountRequest UPDATE_BANK_ACCOUNT_REQUEST = (UpdateBankAccountRequest) AsynchronousRequestResponseSystem.register(new UpdateBankAccountRequest());
    public static BankTerminalBlockDataRequest BANK_TERMINAL_BLOCK_DATA_REQUEST = (BankTerminalBlockDataRequest) AsynchronousRequestResponseSystem.register(new BankTerminalBlockDataRequest());
    public static AllowedItemsRequest ALLOWED_ITEMS_REQUEST = (AllowedItemsRequest) AsynchronousRequestResponseSystem.register(new AllowedItemsRequest());
    public static DropItemsInPlayerInventoryRequest DROP_ITEMS_IN_PLAYER_INVENTORY_REQUEST = (DropItemsInPlayerInventoryRequest)AsynchronousRequestResponseSystem.register(new DropItemsInPlayerInventoryRequest());
    public static DepositItemsInBankRequest DEPOSIT_ITEMS_IN_BANK_REQUEST = (DepositItemsInBankRequest)AsynchronousRequestResponseSystem.register(new DepositItemsInBankRequest());
    public static WithdrawItemsFromBankRequest WITHDRAW_ITEMS_FROM_BANK_REQUEST = (WithdrawItemsFromBankRequest)AsynchronousRequestResponseSystem.register(new WithdrawItemsFromBankRequest());
    public static RegisterItemStacksBatchRequest REGISTER_ITEM_STACKS_BATCH_REQUEST = (RegisterItemStacksBatchRequest)AsynchronousRequestResponseSystem.register(new RegisterItemStacksBatchRequest());

    public static ServerInfoRequest SERVER_INFO_REQUEST = (ServerInfoRequest)AsynchronousRequestResponseSystem.register(new ServerInfoRequest());
    public static ServerNetworkInfoRequest SERVER_NETWORK_INFO_REQUEST = (ServerNetworkInfoRequest)AsynchronousRequestResponseSystem.register(new ServerNetworkInfoRequest());
    public static BanksystemMetadataRequest BANKSYSTEM_METADATA_REQUEST = (BanksystemMetadataRequest)AsynchronousRequestResponseSystem.register(new BanksystemMetadataRequest());
    public static BalanceHistoryRequest BALANCE_HISTORY_REQUEST = (BalanceHistoryRequest)AsynchronousRequestResponseSystem.register(new BalanceHistoryRequest());
    public static GetUserCustomDataRequest GET_USER_CUSTOM_DATA_REQUEST = (GetUserCustomDataRequest)AsynchronousRequestResponseSystem.register(new GetUserCustomDataRequest());
    public static UpdateUserCustomDataRequest UPDATE_USER_CUSTOM_DATA_REQUEST = (UpdateUserCustomDataRequest)AsynchronousRequestResponseSystem.register(new UpdateUserCustomDataRequest());
    public static ModSettingsRequest MOD_SETTINGS_REQUEST = (ModSettingsRequest)AsynchronousRequestResponseSystem.register(new ModSettingsRequest());

    // Currency-binding ARRS requests (Task #33, v2.0.5). All routed to master; writes are
    // MANAGE-gated + untrusted-slave-gated, reads are permission- or open-info as appropriate.
    public static ListCurrencyProvidersRequest LIST_CURRENCY_PROVIDERS_REQUEST = (ListCurrencyProvidersRequest) AsynchronousRequestResponseSystem.register(new ListCurrencyProvidersRequest());
    public static ListBindableAccountsRequest LIST_BINDABLE_ACCOUNTS_REQUEST = (ListBindableAccountsRequest) AsynchronousRequestResponseSystem.register(new ListBindableAccountsRequest());
    public static ListBindingsForAccountRequest LIST_BINDINGS_FOR_ACCOUNT_REQUEST = (ListBindingsForAccountRequest) AsynchronousRequestResponseSystem.register(new ListBindingsForAccountRequest());
    public static BindExternalAccountRequest BIND_EXTERNAL_ACCOUNT_REQUEST = (BindExternalAccountRequest) AsynchronousRequestResponseSystem.register(new BindExternalAccountRequest());
    public static UnbindExternalAccountRequest UNBIND_EXTERNAL_ACCOUNT_REQUEST = (UnbindExternalAccountRequest) AsynchronousRequestResponseSystem.register(new UnbindExternalAccountRequest());

    // ATM Money Converter tab (Task #39, v2.0.7). Cache is per-server (see
    // ConverterCacheManager); the four write packets stay local (needsRoutingToMaster()==false).
    // The one bank-crossing branch (commit-to-bank) routes via DepositItemsInBankRequest on
    // slave, inheriting the Task #26 untrusted-slave gate on master.
    public static GetConverterCachePacket GET_CONVERTER_CACHE_REQUEST = (GetConverterCachePacket) AsynchronousRequestResponseSystem.register(new GetConverterCachePacket());

    public static BankAccountChangeStream BANKSYSTEM_ACCOUNT_CHANGE_STREAM = (BankAccountChangeStream) StreamSystem.register(new BankAccountChangeStream());

    public BankSystemNetworking() {
        super(BankSystemMod.MOD_ID, "bank_system_channel");

        setupClientReceiverPackets();
        setupServerReceiverPackets();
        setupServerServerPackets();

        AsyncBankManager.setupNetworkPacket();
        AsyncBankAccount.setupNetworkPacket();
        AsyncBank.setupNetworkPacket();
        AsyncBankSystemCommandHandler.setupNetworkPacket();
        net.kroia.banksystem.banking.company.AsyncCompanyManager.setupNetworkPacket();

        this.setupARRS(); // Setup the Asynchronous Request Response System (ARRS)
        this.setupStreamSystem();
    }
    private static String getClassName(String name) {
        String sub = name.substring(name.lastIndexOf(".")+1).toLowerCase();
        return sub;
    }

    @Override
    public void setupClientReceiverPackets()
    {
        registerS2C(SyncOpenGUIPacket.TYPE, SyncOpenGUIPacket.STREAM_CODEC);
        registerS2C(SyncBankUploadDataPacket.TYPE, SyncBankUploadDataPacket.STREAM_CODEC);
        registerS2C(SyncBankDownloadDataPacket.TYPE, SyncBankDownloadDataPacket.STREAM_CODEC);
        registerS2C(SyncItemIDsPacket.TYPE, SyncItemIDsPacket.STREAM_CODEC);
        registerS2C(PlayerJoinSyncPacket.TYPE, PlayerJoinSyncPacket.STREAM_CODEC);
        // Task #46 (v2.0.8) — Company share visuals + supply sync (S2C).
        registerS2C(S2CCompanyVisualUpdatePacket.TYPE, S2CCompanyVisualUpdatePacket.STREAM_CODEC);
        registerS2C(S2CCompanyVisualBulkPacket.TYPE, S2CCompanyVisualBulkPacket.STREAM_CODEC);
        registerS2C(S2CCompanyVisualSupplyUpdatePacket.TYPE, S2CCompanyVisualSupplyUpdatePacket.STREAM_CODEC);
        // Task #47 (v2.0.8) — Share Stamper bind screen open (S2C).
        registerS2C(OpenStamperBindScreenPacket.TYPE, OpenStamperBindScreenPacket.STREAM_CODEC);

    }

    @Override
    public void setupServerReceiverPackets()
    {
        registerC2S(UpdateBankTerminalBlockEntityPacket.TYPE, UpdateBankTerminalBlockEntityPacket.STREAM_CODEC);
        registerC2S(UpdateBankTerminalCraftingSettingsPacket.TYPE, UpdateBankTerminalCraftingSettingsPacket.STREAM_CODEC);
        registerC2S(SetBankTerminalGhostRecipePacket.TYPE, SetBankTerminalGhostRecipePacket.STREAM_CODEC);
        registerC2S(FillBankTerminalCraftingGridPacket.TYPE, FillBankTerminalCraftingGridPacket.STREAM_CODEC);
        registerC2S(UpdateBankUploadBlockEntityPacket.TYPE, UpdateBankUploadBlockEntityPacket.STREAM_CODEC);
        registerC2S(UpdateBankDownloadBlockEntityPacket.TYPE, UpdateBankDownloadBlockEntityPacket.STREAM_CODEC);
        registerC2S(UpdateDisplayBlockConfigPacket.TYPE, UpdateDisplayBlockConfigPacket.STREAM_CODEC);
        registerC2S(WithdrawMoneyPacket.TYPE, WithdrawMoneyPacket.STREAM_CODEC);
        registerC2S(RegisterItemIDPacket.TYPE, RegisterItemIDPacket.STREAM_CODEC);
        // ATM Money Converter tab (Task #39, v2.0.7).
        registerC2S(ConverterDepositPacket.TYPE, ConverterDepositPacket.STREAM_CODEC);
        registerC2S(ConverterWithdrawPacket.TYPE, ConverterWithdrawPacket.STREAM_CODEC);
        registerC2S(ConverterDropAllPacket.TYPE, ConverterDropAllPacket.STREAM_CODEC);
        registerC2S(ConverterCommitToBankPacket.TYPE, ConverterCommitToBankPacket.STREAM_CODEC);
        // Task #47 (v2.0.8) — Share Stamper C2S mutations.
        registerC2S(StampSharesRequest.TYPE, StampSharesRequest.STREAM_CODEC);
        // Task #47 (v2.0.8) — Share Stamper bind request (C2S).
        registerC2S(SetStamperBindingRequest.TYPE, SetStamperBindingRequest.STREAM_CODEC);
        registerC2S(CloseStamperBindScreenPacket.TYPE, CloseStamperBindScreenPacket.STREAM_CODEC);
    }

    @Override
    public void setupServerServerPackets()
    {
        //registerS2S(PlayerJoinPacket.TYPE, PlayerJoinPacket.STREAM_CODEC);
        registerS2S(ClientConsoleMessagePacket.TYPE, ClientConsoleMessagePacket.STREAM_CODEC);
    }
}
