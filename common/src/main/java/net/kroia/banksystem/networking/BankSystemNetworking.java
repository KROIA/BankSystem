package net.kroia.banksystem.networking;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.networking.packet.client_sender.request.RequestAllowNewBankItemIDPacket;
import net.kroia.banksystem.networking.packet.client_sender.request.RequestBankDataPacket;
import net.kroia.banksystem.networking.packet.client_sender.request.RequestDisallowBankingItemIDPacket;
import net.kroia.banksystem.networking.packet.client_sender.request.RequestItemInfoPacket;
import net.kroia.banksystem.networking.packet.client_sender.update.UpdateBankAccountPacket;
import net.kroia.banksystem.networking.packet.client_sender.update.WithdrawMoneyPacket;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankDownloadBlockEntityPacket;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankTerminalBlockEntityPacket;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankUploadBlockEntityPacket;
import net.kroia.banksystem.networking.packet.server_sender.SyncOpenGUIPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDataPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDownloadDataPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankUploadDataPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncItemInfoPacket;
import net.kroia.banksystem.networking.request.AllowItemRequest;
import net.kroia.banksystem.networking.request.ItemInfoRequest;
import net.kroia.banksystem.networking.request.MinimalBankManagerDataRequest;
import net.kroia.banksystem.networking.request.TestRequest;
import net.kroia.modutilities.networking.NetworkManager;
import net.kroia.modutilities.networking.arrs.AsynchronousRequestResponseSystem;

public class BankSystemNetworking extends NetworkManager {


    public static TestRequest TEST_REQUEST = (TestRequest) AsynchronousRequestResponseSystem.register(new TestRequest());
    public static ItemInfoRequest ITEM_INFO_REQUEST = (ItemInfoRequest) AsynchronousRequestResponseSystem.register(new ItemInfoRequest());
    public static MinimalBankManagerDataRequest MINIMAL_BANK_DATA_REQUEST = (MinimalBankManagerDataRequest) AsynchronousRequestResponseSystem.register(new MinimalBankManagerDataRequest());
    public static AllowItemRequest ALLOW_ITEM_REQUEST = (AllowItemRequest) AsynchronousRequestResponseSystem.register(new AllowItemRequest());
    public BankSystemNetworking() {
        super(BankSystemMod.MOD_ID);

        setupClientReceiverPackets();
        setupServerReceiverPackets();

        this.setupARRS(); // Setup the Asynchronous Request Response System (ARRS)

    }

    @Override
    public void setupClientReceiverPackets()
    {
        register(SyncBankDataPacket.class, SyncBankDataPacket::encode, SyncBankDataPacket::new, SyncBankDataPacket::receive);
        register(SyncOpenGUIPacket.class, SyncOpenGUIPacket::encode, SyncOpenGUIPacket::new, SyncOpenGUIPacket::receive);
        register(SyncItemInfoPacket.class, SyncItemInfoPacket::encode, SyncItemInfoPacket::new, SyncItemInfoPacket::receive);
        register(SyncBankUploadDataPacket.class, SyncBankUploadDataPacket::encode, SyncBankUploadDataPacket::new, SyncBankUploadDataPacket::receive);
        register(SyncBankDownloadDataPacket.class, SyncBankDownloadDataPacket::encode, SyncBankDownloadDataPacket::new, SyncBankDownloadDataPacket::receive);
    }

    @Override
    public void setupServerReceiverPackets()
    {
        register(RequestAllowNewBankItemIDPacket.class, RequestAllowNewBankItemIDPacket::encode, RequestAllowNewBankItemIDPacket::new, RequestAllowNewBankItemIDPacket::receive);
        register(RequestBankDataPacket.class, RequestBankDataPacket::encode, RequestBankDataPacket::new, RequestBankDataPacket::receive);
        register(RequestDisallowBankingItemIDPacket.class, RequestDisallowBankingItemIDPacket::encode, RequestDisallowBankingItemIDPacket::new, RequestDisallowBankingItemIDPacket::receive);
        register(UpdateBankTerminalBlockEntityPacket.class, UpdateBankTerminalBlockEntityPacket::encode, UpdateBankTerminalBlockEntityPacket::new, UpdateBankTerminalBlockEntityPacket::receive);
        register(RequestItemInfoPacket.class, RequestItemInfoPacket::encode, RequestItemInfoPacket::new, RequestItemInfoPacket::receive);
        register(UpdateBankAccountPacket.class, UpdateBankAccountPacket::encode, UpdateBankAccountPacket::new, UpdateBankAccountPacket::receive);
        register(UpdateBankUploadBlockEntityPacket.class, UpdateBankUploadBlockEntityPacket::encode, UpdateBankUploadBlockEntityPacket::new, UpdateBankUploadBlockEntityPacket::receive);
        register(UpdateBankDownloadBlockEntityPacket.class, UpdateBankDownloadBlockEntityPacket::encode, UpdateBankDownloadBlockEntityPacket::new, UpdateBankDownloadBlockEntityPacket::receive);
        register(WithdrawMoneyPacket.class, WithdrawMoneyPacket::encode, WithdrawMoneyPacket::new, WithdrawMoneyPacket::receive);
    }
}
