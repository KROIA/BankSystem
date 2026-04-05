package net.kroia.banksystem.networking;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.networking.packet.client_sender.update.WithdrawMoneyPacket;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankDownloadBlockEntityPacket;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankTerminalBlockEntityPacket;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankUploadBlockEntityPacket;
import net.kroia.banksystem.networking.packet.server_sender.SyncOpenGUIPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDownloadDataPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankUploadDataPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncItemIDsPacket;
import net.kroia.banksystem.networking.packet.server_server.PlayerJoinPacket;
import net.kroia.banksystem.networking.request.*;
import net.kroia.modutilities.networking.NetworkPacketManager;
import net.kroia.modutilities.networking.client_server.arrs.AsynchronousRequestResponseSystem;

public class BankSystemNetworking extends NetworkPacketManager {

    public static ItemInfoRequest ITEM_INFO_REQUEST = (ItemInfoRequest) AsynchronousRequestResponseSystem.register(new ItemInfoRequest());
    public static BankManagerDataRequest BANK_MANAGER_DATA_REQUEST = (BankManagerDataRequest) AsynchronousRequestResponseSystem.register(new BankManagerDataRequest());
    public static BankAccountDataRequest BANK_ACCOUNT_DATA_REQUEST = (BankAccountDataRequest) AsynchronousRequestResponseSystem.register(new BankAccountDataRequest());
    //public static MinimalBankDataRequest MINIMAL_BANK_DATA_REQUEST = (MinimalBankDataRequest) AsynchronousRequestResponseSystem.register(new MinimalBankDataRequest());
    public static AllowItemRequest ALLOW_ITEM_REQUEST = (AllowItemRequest) AsynchronousRequestResponseSystem.register(new AllowItemRequest());
    public static DisallowItemRequest DISALLOW_ITEM_REQUEST = (DisallowItemRequest) AsynchronousRequestResponseSystem.register(new DisallowItemRequest());
    public static RemoveEmptyBanksRequest REMOVE_EMPTY_BANKS_REQUEST = (RemoveEmptyBanksRequest) AsynchronousRequestResponseSystem.register(new RemoveEmptyBanksRequest());
    public static BankAccountNumbersRequest BANK_ACCOUNT_NUMBERS_REQUEST = (BankAccountNumbersRequest) AsynchronousRequestResponseSystem.register(new BankAccountNumbersRequest());
    public static UpdateBankAccountRequest UPDATE_BANK_ACCOUNT_REQUEST = (UpdateBankAccountRequest) AsynchronousRequestResponseSystem.register(new UpdateBankAccountRequest());
    public static BankTerminalBlockDataRequest BANK_TERMINAL_BLOCK_DATA_REQUEST = (BankTerminalBlockDataRequest) AsynchronousRequestResponseSystem.register(new BankTerminalBlockDataRequest());
    public static BankSelectionScreenDataRequest BANK_SELECTION_SCREEN_DATA_REQUEST = (BankSelectionScreenDataRequest) AsynchronousRequestResponseSystem.register(new BankSelectionScreenDataRequest());
    public static BankAccountDeleteRequest DELETE_BANK_ACCOUNT_REQUEST = (BankAccountDeleteRequest) AsynchronousRequestResponseSystem.register(new BankAccountDeleteRequest());
    public static AllowedItemsRequest ALLOWED_ITEMS_REQUEST = (AllowedItemsRequest) AsynchronousRequestResponseSystem.register(new AllowedItemsRequest());
    public BankSystemNetworking() {
        super(BankSystemMod.MOD_ID, "bank_system_channel");

        setupClientReceiverPackets();
        setupServerReceiverPackets();

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
    }

    @Override
    public void setupServerServerPackets()
    {
        registerS2S(PlayerJoinPacket.TYPE, PlayerJoinPacket.STREAM_CODEC);
    }
}
