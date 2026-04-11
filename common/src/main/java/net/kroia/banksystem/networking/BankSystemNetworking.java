package net.kroia.banksystem.networking;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.bank.AsyncBank;
import net.kroia.banksystem.banking.bankaccount.AsyncBankAccount;
import net.kroia.banksystem.banking.bankmanager.AsyncBankManager;
import net.kroia.banksystem.command.AsyncBankSystemCommandHandler;
import net.kroia.banksystem.networking.packet.client_sender.update.WithdrawMoneyPacket;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankDownloadBlockEntityPacket;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankTerminalBlockEntityPacket;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankUploadBlockEntityPacket;
import net.kroia.banksystem.networking.packet.general.RegisterItemIDPacket;
import net.kroia.banksystem.networking.packet.general.SyncItemIDsPacket;
import net.kroia.banksystem.networking.packet.server_sender.SyncOpenGUIPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDownloadDataPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankUploadDataPacket;
import net.kroia.banksystem.networking.packet.server_server.*;
import net.kroia.banksystem.networking.request.AllowedItemsRequest;
import net.kroia.banksystem.networking.request.BankTerminalBlockDataRequest;
import net.kroia.banksystem.networking.request.RemoveEmptyBanksRequest;
import net.kroia.banksystem.networking.request.UpdateBankAccountRequest;
import net.kroia.modutilities.networking.NetworkPacketManager;
import net.kroia.modutilities.networking.client_server.arrs.AsynchronousRequestResponseSystem;

public class BankSystemNetworking extends NetworkPacketManager {

    public static RemoveEmptyBanksRequest REMOVE_EMPTY_BANKS_REQUEST = (RemoveEmptyBanksRequest) AsynchronousRequestResponseSystem.register(new RemoveEmptyBanksRequest());
    public static UpdateBankAccountRequest UPDATE_BANK_ACCOUNT_REQUEST = (UpdateBankAccountRequest) AsynchronousRequestResponseSystem.register(new UpdateBankAccountRequest());
    public static BankTerminalBlockDataRequest BANK_TERMINAL_BLOCK_DATA_REQUEST = (BankTerminalBlockDataRequest) AsynchronousRequestResponseSystem.register(new BankTerminalBlockDataRequest());
    public static AllowedItemsRequest ALLOWED_ITEMS_REQUEST = (AllowedItemsRequest) AsynchronousRequestResponseSystem.register(new AllowedItemsRequest());
    public static DropItemsInPlayerInventoryRequest DROP_ITEMS_IN_PLAYER_INVENTORY_REQUEST = (DropItemsInPlayerInventoryRequest)AsynchronousRequestResponseSystem.register(new DropItemsInPlayerInventoryRequest());
    public static DepositItemsInBankRequest DEPOSIT_ITEMS_IN_BANK_REQUEST = (DepositItemsInBankRequest)AsynchronousRequestResponseSystem.register(new DepositItemsInBankRequest());
    public static WithdrawItemsFromBankRequest WITHDRAW_ITEMS_FROM_BANK_REQUEST = (WithdrawItemsFromBankRequest)AsynchronousRequestResponseSystem.register(new WithdrawItemsFromBankRequest());

    public static ServerInfoRequest SERVER_INFO_REQUEST = (ServerInfoRequest)AsynchronousRequestResponseSystem.register(new ServerInfoRequest());
    public static ServerNetworkInfoRequest SERVER_NETWORK_INFO_REQUEST = (ServerNetworkInfoRequest)AsynchronousRequestResponseSystem.register(new ServerNetworkInfoRequest());
    public static BanksystemMetadataRequest BANKSYSTEM_METADATA_REQUEST = (BanksystemMetadataRequest)AsynchronousRequestResponseSystem.register(new BanksystemMetadataRequest());


    public BankSystemNetworking() {
        super(BankSystemMod.MOD_ID, "bank_system_channel");

        setupClientReceiverPackets();
        setupServerReceiverPackets();
        setupServerServerPackets();

        AsyncBankManager.setupNetworkPacket();
        AsyncBankAccount.setupNetworkPacket();
        AsyncBank.setupNetworkPacket();
        AsyncBankSystemCommandHandler.setupNetworkPacket();

        this.setupARRS(); // Setup the Asynchronous Request Response System (ARRS)
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

    }

    @Override
    public void setupServerReceiverPackets()
    {
        registerC2S(UpdateBankTerminalBlockEntityPacket.TYPE, UpdateBankTerminalBlockEntityPacket.STREAM_CODEC);
        registerC2S(UpdateBankUploadBlockEntityPacket.TYPE, UpdateBankUploadBlockEntityPacket.STREAM_CODEC);
        registerC2S(UpdateBankDownloadBlockEntityPacket.TYPE, UpdateBankDownloadBlockEntityPacket.STREAM_CODEC);
        registerC2S(WithdrawMoneyPacket.TYPE, WithdrawMoneyPacket.STREAM_CODEC);
        registerC2S(RegisterItemIDPacket.TYPE, RegisterItemIDPacket.STREAM_CODEC);
    }

    @Override
    public void setupServerServerPackets()
    {
        registerS2S(PlayerJoinPacket.TYPE, PlayerJoinPacket.STREAM_CODEC);
        registerS2S(ClientConsoleMessagePacket.TYPE, ClientConsoleMessagePacket.STREAM_CODEC);
    }
}
