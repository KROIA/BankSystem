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
import net.kroia.modutilities.networking.NetworkManager;

public class BankSystemNetworking extends NetworkManager {

    public BankSystemNetworking() {
        super(BankSystemMod.MOD_ID);

        setupClientReceiverPackets();
        setupServerReceiverPackets();
    }

    @Override
    public void setupClientReceiverPackets()
    {
        register(SyncBankDataPacket.class, SyncBankDataPacket::toBytes, SyncBankDataPacket::new, SyncBankDataPacket::receive);
        register(SyncOpenGUIPacket.class, SyncOpenGUIPacket::toBytes, SyncOpenGUIPacket::new, SyncOpenGUIPacket::receive);
        register(SyncItemInfoPacket.class, SyncItemInfoPacket::toBytes, SyncItemInfoPacket::new, SyncItemInfoPacket::receive);
        register(SyncBankUploadDataPacket.class, SyncBankUploadDataPacket::toBytes, SyncBankUploadDataPacket::new, SyncBankUploadDataPacket::receive);
        register(SyncBankDownloadDataPacket.class, SyncBankDownloadDataPacket::toBytes, SyncBankDownloadDataPacket::new, SyncBankDownloadDataPacket::receive);
    }

    @Override
    public void setupServerReceiverPackets()
    {
        register(RequestAllowNewBankItemIDPacket.class, RequestAllowNewBankItemIDPacket::toBytes, RequestAllowNewBankItemIDPacket::new, RequestAllowNewBankItemIDPacket::receive);
        register(RequestBankDataPacket.class, RequestBankDataPacket::toBytes, RequestBankDataPacket::new, RequestBankDataPacket::receive);
        register(RequestDisallowBankingItemIDPacket.class, RequestDisallowBankingItemIDPacket::toBytes, RequestDisallowBankingItemIDPacket::new, RequestDisallowBankingItemIDPacket::receive);
        register(UpdateBankTerminalBlockEntityPacket.class, UpdateBankTerminalBlockEntityPacket::toBytes, UpdateBankTerminalBlockEntityPacket::new, UpdateBankTerminalBlockEntityPacket::receive);
        register(RequestItemInfoPacket.class, RequestItemInfoPacket::toBytes, RequestItemInfoPacket::new, RequestItemInfoPacket::receive);
        register(UpdateBankAccountPacket.class, UpdateBankAccountPacket::toBytes, UpdateBankAccountPacket::new, UpdateBankAccountPacket::receive);
        register(UpdateBankUploadBlockEntityPacket.class, UpdateBankUploadBlockEntityPacket::toBytes, UpdateBankUploadBlockEntityPacket::new, UpdateBankUploadBlockEntityPacket::receive);
        register(UpdateBankDownloadBlockEntityPacket.class, UpdateBankDownloadBlockEntityPacket::toBytes, UpdateBankDownloadBlockEntityPacket::new, UpdateBankDownloadBlockEntityPacket::receive);
        register(WithdrawMoneyPacket.class, WithdrawMoneyPacket::toBytes, WithdrawMoneyPacket::new, WithdrawMoneyPacket::receive);
    }
}
