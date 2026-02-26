package net.kroia.banksystem.networking;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.networking.packet.client_sender.update.WithdrawMoneyPacket;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankDownloadBlockEntityPacket;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankTerminalBlockEntityPacket;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankUploadBlockEntityPacket;
import net.kroia.banksystem.networking.packet.server_sender.SyncOpenGUIPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDownloadDataPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankUploadDataPacket;
import net.kroia.banksystem.networking.request.*;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.modutilities.networking.NetworkPacket;
import net.kroia.modutilities.networking.PacketHandler;
import net.kroia.modutilities.networking.PacketManager;
import net.kroia.modutilities.networking.arrs.AsynchronousRequestResponseSystem;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public class BankSystemNetworking extends PacketManager {

    public static ItemInfoRequest ITEM_INFO_REQUEST = (ItemInfoRequest) AsynchronousRequestResponseSystem.register(new ItemInfoRequest());
    public static BankManagerDataRequest BANK_MANAGER_DATA_REQUEST = (BankManagerDataRequest) AsynchronousRequestResponseSystem.register(new BankManagerDataRequest());
    public static BankAccountDataRequest BANK_ACCOUNT_DATA_REQUEST = (BankAccountDataRequest) AsynchronousRequestResponseSystem.register(new BankAccountDataRequest());
    //public static MinimalBankDataRequest MINIMAL_BANK_DATA_REQUEST = (MinimalBankDataRequest) AsynchronousRequestResponseSystem.register(new MinimalBankDataRequest());
    public static AllowItemRequest ALLOW_ITEM_REQUEST = (AllowItemRequest) AsynchronousRequestResponseSystem.register(new AllowItemRequest());
    public static DisallowItemRequest DISALLOW_ITEM_REQUEST = (DisallowItemRequest) AsynchronousRequestResponseSystem.register(new DisallowItemRequest());
    public static RemoveEmptyBanksRequest REMOVE_EMPTY_BANKS_REQUEST = (RemoveEmptyBanksRequest) AsynchronousRequestResponseSystem.register(new RemoveEmptyBanksRequest());
    public static itemFractionScaleFactorRequest ITEM_FRACTION_SCALE_FACTOR_REQUEST = (itemFractionScaleFactorRequest) AsynchronousRequestResponseSystem.register(new itemFractionScaleFactorRequest());
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
        register(SyncOpenGUIPacket.class, SyncOpenGUIPacket::encode, SyncOpenGUIPacket::new, SyncOpenGUIPacket::receive);
        register(SyncBankUploadDataPacket.class, SyncBankUploadDataPacket::encode, SyncBankUploadDataPacket::new, SyncBankUploadDataPacket::receive);
        register(SyncBankDownloadDataPacket.TYPE, SyncBankDownloadDataPacket.STREAM_CODEC, BankSystemNetworkPacket.HANDLER);
    }

    @Override
    public void setupServerReceiverPackets()
    {
        register(UpdateBankTerminalBlockEntityPacket.class, UpdateBankTerminalBlockEntityPacket::encode, UpdateBankTerminalBlockEntityPacket::new, UpdateBankTerminalBlockEntityPacket::receive);
        register(UpdateBankUploadBlockEntityPacket.class, UpdateBankUploadBlockEntityPacket::encode, UpdateBankUploadBlockEntityPacket::new, UpdateBankUploadBlockEntityPacket::receive);
        register(UpdateBankDownloadBlockEntityPacket.class, UpdateBankDownloadBlockEntityPacket::encode, UpdateBankDownloadBlockEntityPacket::new, UpdateBankDownloadBlockEntityPacket::receive);
        register(WithdrawMoneyPacket.class, WithdrawMoneyPacket::encode, WithdrawMoneyPacket::new, WithdrawMoneyPacket::receive);
    }



    public <T extends NetworkPacket> void register(CustomPacketPayload.Type<T> packetType, StreamCodec<RegistryFriendlyByteBuf, T> streamCodec) {

        register(packetType, streamCodec, BankSystemNetworkPacket.HANDLER);
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, packetType, streamCodec, BankSystemNetworkPacket.HANDLER::handleServer);
    }
}
