package net.kroia.banksystem.networking;


import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.networking.packets.server_sender.SyncBankDataPacket;
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

        net.messageBuilder(SyncBankDataPacket.class, id(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(SyncBankDataPacket::new)
                .encoder(SyncBankDataPacket::toBytes)
                .consumerMainThread(SyncBankDataPacket::handle)
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