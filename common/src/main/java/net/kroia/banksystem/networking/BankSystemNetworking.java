package net.kroia.banksystem.networking;

import dev.architectury.networking.NetworkChannel;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.networking.packet.client_sender.request.*;
import net.kroia.banksystem.networking.packet.client_sender.update.UpdateBankAccountPacket;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankDownloadBlockEntityPacket;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankTerminalBlockEntityPacket;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankUploadBlockEntityPacket;
import net.kroia.banksystem.networking.packet.server_sender.SyncOpenGUIPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.*;
import net.kroia.modutilities.networking.INetworkPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public class BankSystemNetworking {

    public static final NetworkChannel CHANNEL = createChannel();

    private static NetworkChannel createChannel()
    {
        NetworkChannel chanel = NetworkChannel.create(new ResourceLocation(BankSystemMod.MOD_ID, "networking_channel"));
        return chanel;
    }

    public static void setupClientReceiverPackets()
    {
        CHANNEL.register(SyncBankDataPacket.class, SyncBankDataPacket::toBytes, SyncBankDataPacket::new, SyncBankDataPacket::receive);
        CHANNEL.register(SyncPotentialBankItemIDsPacket.class, SyncPotentialBankItemIDsPacket::toBytes, SyncPotentialBankItemIDsPacket::new, SyncPotentialBankItemIDsPacket::receive);
        CHANNEL.register(SyncOpenGUIPacket.class, SyncOpenGUIPacket::toBytes, SyncOpenGUIPacket::new, SyncOpenGUIPacket::receive);
        CHANNEL.register(SyncItemInfoPacket.class, SyncItemInfoPacket::toBytes, SyncItemInfoPacket::new, SyncItemInfoPacket::receive);
        CHANNEL.register(SyncBankUploadDataPacket.class, SyncBankUploadDataPacket::toBytes, SyncBankUploadDataPacket::new, SyncBankUploadDataPacket::receive);
        CHANNEL.register(SyncBankDownloadDataPacket.class, SyncBankDownloadDataPacket::toBytes, SyncBankDownloadDataPacket::new, SyncBankDownloadDataPacket::receive);
    }
    public static void setupServerReceiverPackets()
    {
        CHANNEL.register(RequestAllowNewBankItemIDPacket.class, RequestAllowNewBankItemIDPacket::toBytes, RequestAllowNewBankItemIDPacket::new, RequestAllowNewBankItemIDPacket::receive);
        CHANNEL.register(RequestBankDataPacket.class, RequestBankDataPacket::toBytes, RequestBankDataPacket::new, RequestBankDataPacket::receive);
        CHANNEL.register(RequestDisallowBankingItemIDPacket.class, RequestDisallowBankingItemIDPacket::toBytes, RequestDisallowBankingItemIDPacket::new, RequestDisallowBankingItemIDPacket::receive);
        CHANNEL.register(RequestPotentialBankItemIDsPacket.class, RequestPotentialBankItemIDsPacket::toBytes, RequestPotentialBankItemIDsPacket::new, RequestPotentialBankItemIDsPacket::receive);
        CHANNEL.register(UpdateBankTerminalBlockEntityPacket.class, UpdateBankTerminalBlockEntityPacket::toBytes, UpdateBankTerminalBlockEntityPacket::new, UpdateBankTerminalBlockEntityPacket::receive);
        CHANNEL.register(RequestItemInfoPacket.class, RequestItemInfoPacket::toBytes, RequestItemInfoPacket::new, RequestItemInfoPacket::receive);
        CHANNEL.register(UpdateBankAccountPacket.class, UpdateBankAccountPacket::toBytes, UpdateBankAccountPacket::new, UpdateBankAccountPacket::receive);
        CHANNEL.register(UpdateBankUploadBlockEntityPacket.class, UpdateBankUploadBlockEntityPacket::toBytes, UpdateBankUploadBlockEntityPacket::new, UpdateBankUploadBlockEntityPacket::receive);
        CHANNEL.register(UpdateBankDownloadBlockEntityPacket.class, UpdateBankDownloadBlockEntityPacket::toBytes, UpdateBankDownloadBlockEntityPacket::new, UpdateBankDownloadBlockEntityPacket::receive);
    }


    public static void sendToServer(INetworkPacket packet) {
        CHANNEL.sendToServer(packet);
    }
    public static void sendToClient(ServerPlayer receiver, INetworkPacket packet) {
        CHANNEL.sendToPlayer(receiver, packet);
    }
}
