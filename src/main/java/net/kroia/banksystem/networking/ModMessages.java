package net.kroia.banksystem.networking;


import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.networking.packet.client_sender.request.RequestAllowNewBankItemIDPacket;
import net.kroia.banksystem.networking.packet.client_sender.request.RequestDisallowBankingItemIDPacket;
import net.kroia.banksystem.networking.packet.client_sender.request.RequestBankDataPacket;
import net.kroia.banksystem.networking.packet.client_sender.request.RequestPotentialBankItemIDsPacket;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankTerminalBlockEntityPacket;
import net.kroia.banksystem.networking.packet.server_sender.SyncOpenBankSystemSettingsGUIPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDataPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncPotentialBankItemIDsPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public class ModMessages {
    private static SimpleChannel INSTANCE;

    private static int packetId = 0;
    private static int id() {
        return packetId++;
    }

    public static void register() {
        SimpleChannel net = NetworkRegistry.ChannelBuilder
                .named(new ResourceLocation(BankSystemMod.MODID, "messages"))
                .networkProtocolVersion(() -> "1.0")
                .clientAcceptedVersions(s -> true)
                .serverAcceptedVersions(s -> true)
                .simpleChannel();

        INSTANCE = net;

        net.messageBuilder(RequestBankDataPacket.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(RequestBankDataPacket::new)
                .encoder(RequestBankDataPacket::toBytes)
                .consumerMainThread(RequestBankDataPacket::handle)
                .add();

        net.messageBuilder(UpdateBankTerminalBlockEntityPacket.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(UpdateBankTerminalBlockEntityPacket::new)
                .encoder(UpdateBankTerminalBlockEntityPacket::toBytes)
                .consumerMainThread(UpdateBankTerminalBlockEntityPacket::handle)
                .add();

        net.messageBuilder(SyncBankDataPacket.class, id(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(SyncBankDataPacket::new)
                .encoder(SyncBankDataPacket::toBytes)
                .consumerMainThread(SyncBankDataPacket::handle)
                .add();

        net.messageBuilder(SyncOpenBankSystemSettingsGUIPacket.class, id(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(SyncOpenBankSystemSettingsGUIPacket::new)
                .encoder(SyncOpenBankSystemSettingsGUIPacket::toBytes)
                .consumerMainThread(SyncOpenBankSystemSettingsGUIPacket::handle)
                .add();

        net.messageBuilder(RequestAllowNewBankItemIDPacket.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(RequestAllowNewBankItemIDPacket::new)
                .encoder(RequestAllowNewBankItemIDPacket::toBytes)
                .consumerMainThread(RequestAllowNewBankItemIDPacket::handle)
                .add();

        net.messageBuilder(RequestDisallowBankingItemIDPacket.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(RequestDisallowBankingItemIDPacket::new)
                .encoder(RequestDisallowBankingItemIDPacket::toBytes)
                .consumerMainThread(RequestDisallowBankingItemIDPacket::handle)
                .add();

        net.messageBuilder(RequestPotentialBankItemIDsPacket.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(RequestPotentialBankItemIDsPacket::new)
                .encoder(RequestPotentialBankItemIDsPacket::toBytes)
                .consumerMainThread(RequestPotentialBankItemIDsPacket::handle)
                .add();

        net.messageBuilder(SyncPotentialBankItemIDsPacket.class, id(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(SyncPotentialBankItemIDsPacket::new)
                .encoder(SyncPotentialBankItemIDsPacket::toBytes)
                .consumerMainThread(SyncPotentialBankItemIDsPacket::handle)
                .add();


    }

    public static <MSG> void sendToServer(MSG message) {
        try{
            INSTANCE.sendToServer(message);
        } catch (Exception e) {
            BankSystemMod.LOGGER.error("Failed to send message to server_sender: " + e.getMessage());
        }
    }

    public static <MSG> void sendToPlayer(MSG message, ServerPlayer player) {
        try{
            INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), message);
        } catch (Exception e) {
            BankSystemMod.LOGGER.error("Failed to send message to player: " + e.getMessage());
        }
    }
}