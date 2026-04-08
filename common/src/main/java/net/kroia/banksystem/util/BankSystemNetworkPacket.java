package net.kroia.banksystem.util;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.modutilities.networking.client_server.NetworkPacket;
import net.kroia.modutilities.networking.server_server.ForwardPacketContext;
import net.kroia.modutilities.networking.server_server.ServerServerManager;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

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

    @Override
    protected void handleOnMaster(ForwardPacketContext context) {
        handleOnMaster(context.senderPlayerUUID);
    };
    protected void handleOnMaster(UUID playerUUID) {

    };

    protected ISyncServerBankManager getSyncBankManager()
    {
        return BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
    }


    @Override
    protected boolean needsRoutingToMaster() { return BACKEND_INSTANCES.isSlaveServer; }

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

    protected boolean sendToMaster()
    {
        if(ServerServerManager.isRunning() && ServerServerManager.isSlave())
        {
            return ServerServerManager.sendToMaster(null, this);
        }
        return false;
    }
    protected boolean sendToMaster(UUID senderPlayerUUID)
    {
        if(ServerServerManager.isRunning() && ServerServerManager.isSlave())
        {
            return ServerServerManager.sendToMaster(senderPlayerUUID, this);
        }
        return false;
    }
    protected void broadcastToSlaves()
    {
        if(ServerServerManager.isRunning() && ServerServerManager.isMaster())
        {
            ServerServerManager.broadcastToSlaves(this);
        }
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
