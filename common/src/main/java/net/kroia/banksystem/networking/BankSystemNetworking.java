package net.kroia.banksystem.networking;

import dev.architectury.networking.NetworkChannel;
import io.netty.buffer.Unpooled;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.item.BankSystemCreativeModeTab;
import net.kroia.banksystem.networking.packet.client_sender.request.*;
import net.kroia.banksystem.networking.packet.client_sender.update.UpdateBankAccountPacket;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankTerminalBlockEntityPacket;
import net.kroia.banksystem.networking.packet.server_sender.SyncOpenBankSystemSettingsGUIPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDataPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncItemInfoPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncPotentialBankItemIDsPacket;
import net.kroia.modutilities.networking.INetworkPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
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
        CHANNEL.register(SyncOpenBankSystemSettingsGUIPacket.class, SyncOpenBankSystemSettingsGUIPacket::toBytes, SyncOpenBankSystemSettingsGUIPacket::new, SyncOpenBankSystemSettingsGUIPacket::receive);
        CHANNEL.register(SyncItemInfoPacket.class, SyncItemInfoPacket::toBytes, SyncItemInfoPacket::new, SyncItemInfoPacket::receive);
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
        /*if(CHANNEL.canServerReceive(UpdateBankTerminalBlockEntityPacket.class))
        {
            BankSystemMod.LOGGER.info("Server can receive UpdateBankTerminalBlockEntityPacket");
        }
        else {
            BankSystemMod.LOGGER.error("Server cannot receive UpdateBankTerminalBlockEntityPacket");
        }*/
    }


    public static void sendToServer(INetworkPacket packet) {
        CHANNEL.sendToServer(packet);
    }
    public static void sendToClient(ServerPlayer receiver, INetworkPacket packet) {
        CHANNEL.sendToPlayer(receiver, packet);
    }
}
