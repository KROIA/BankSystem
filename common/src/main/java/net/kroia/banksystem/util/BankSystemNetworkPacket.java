package net.kroia.banksystem.util;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.modutilities.ModUtilitiesMod;
import net.kroia.modutilities.UtilitiesPlatform;
import net.kroia.modutilities.networking.NetworkPacket;
import net.kroia.modutilities.networking.PacketHandler;
import net.kroia.modutilities.networking.arrs.AsynchronousRequestResponseSystem;
import net.kroia.modutilities.networking.arrs.GenericRequestPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public abstract class BankSystemNetworkPacket extends NetworkPacket {
    protected static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BankSystemNetworkPacket.BACKEND_INSTANCES = backend;
    }

    public BankSystemNetworkPacket() {
        super();
    }


    public static final PacketHandler<BankSystemNetworkPacket> HANDLER = new PacketHandler<>(){
        @Override
        public void handleServer(BankSystemNetworkPacket packet, NetworkManager.PacketContext context) {
            BankSystemNetworkPacket packet1 = (BankSystemNetworkPacket) packet;
            if(packet1 != null) {
                packet1.handleServer(context);
            }
        }

        @Override
        public void handleClient(BankSystemNetworkPacket packet, NetworkManager.PacketContext context) {
            BankSystemNetworkPacket packet1 = (BankSystemNetworkPacket) packet;
            if(packet1 != null) {
                packet1.handleClient(context);
            }
        }
    };

    public void handleServer(NetworkManager.PacketContext context)
    {

    }

    public void handleClient(NetworkManager.PacketContext context)
    {

    }




    protected void sendToServer()
    {
        BACKEND_INSTANCES.NETWORKING.sendToServer(this);
    }
    protected void sendToClient(ServerPlayer player)
    {
        BACKEND_INSTANCES.NETWORKING.sendToClient(player, this);
    }

    protected void info(String message) {
        BACKEND_INSTANCES.LOGGER.info("[BankSystemNetworkPacket] "+ message);
    }
    protected void error(String message) {
        BACKEND_INSTANCES.LOGGER.error("[BankSystemNetworkPacket] "+ message);
    }
    protected void error(String message, Throwable throwable) {
        BACKEND_INSTANCES.LOGGER.error("[BankSystemNetworkPacket] "+ message, throwable);
    }
    protected void warn(String message) {
        BACKEND_INSTANCES.LOGGER.warn("[BankSystemNetworkPacket] "+ message);
    }
    protected void debug(String message) {
        BACKEND_INSTANCES.LOGGER.debug("[BankSystemNetworkPacket] "+ message);
    }






}
