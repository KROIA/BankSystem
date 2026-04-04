package net.kroia.banksystem.util;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.modutilities.networking.client_server.NetworkPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public abstract class BankSystemNetworkPacket extends NetworkPacket {
    protected static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BankSystemNetworkPacket.BACKEND_INSTANCES = backend;
    }

    public BankSystemNetworkPacket() {
        super();
    }

    @Override
    protected void handleOnClient(NetworkManager.PacketContext context) {

    }

    @Override
    protected void handleOnServer(NetworkManager.PacketContext context) {
        ServerPlayer player = (ServerPlayer) context.getPlayer();
        handleOnServer(player);
    }
    protected void handleOnServer(ServerPlayer player) {

    }



    protected void sendToServer()
    {
        BACKEND_INSTANCES.NETWORKING.sendToServer(this);
    }
    protected void sendToClient(ServerPlayer player)
    {
        BACKEND_INSTANCES.NETWORKING.sendToClient(player, this);
    }
    protected void sendToClients(List<ServerPlayer> player)
    {
        BACKEND_INSTANCES.NETWORKING.sendToClients(player, this);
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
