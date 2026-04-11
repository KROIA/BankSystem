package net.kroia.banksystem.networking.general;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.modutilities.UtilitiesPlatform;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.kroia.modutilities.networking.multi_server.ForwardPacketContext;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SyncItemIDsPacket extends BankSystemNetworkPacket
{
    public static final Type<SyncItemIDsPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "sync_item_ids_packet"));
    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncItemIDsPacket> STREAM_CODEC = StreamCodec.composite(
            ExtraCodecUtils.mapStreamCodec(ItemID.STREAM_CODEC, ItemStack.STREAM_CODEC, HashMap<ItemID, ItemStack>::new), p -> p.items,
            SyncItemIDsPacket::new
    );

    private final Map<ItemID, ItemStack> items;


    public SyncItemIDsPacket(Map<ItemID, ItemStack> items)
    {
        this.items = items;
    }

    public Map<ItemID, ItemStack> getItems()
    {
        return items;
    }


    public void broadcastToClients()
    {
        MinecraftServer server = UtilitiesPlatform.getServer();
        if(server == null)
            return;
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        sendToClients(players);
    }

    public static void broadcastToSlaves(Map<ItemID, ItemStack> items)
    {
        SyncItemIDsPacket packet = new SyncItemIDsPacket(items);
        packet.broadcastToSlaves();
    }
    public static void sendToSlave(String slaveID, Map<ItemID, ItemStack> items)
    {
        SyncItemIDsPacket packet = new SyncItemIDsPacket(items);
        packet.sendToSlave(slaveID);
    }
    public static void sendAllItemsToSlave(String slaveID)
    {
        sendToSlave(slaveID, ItemIDManager.getItemIDMap());
    }


    @Override
    public void handleOnClient(NetworkManager.PacketContext context)
    {
        ItemIDManager.receiveSyncPacket(this);
    }

    @Override
    protected void handleOnServer(NetworkManager.PacketContext context)
    {

    }


    @Override
    protected void handleOnSlave(ForwardPacketContext context)
    {
        ItemIDManager.receiveSyncPacket(this);
    }

    @Override
    public String toString()
    {
        StringBuilder s = new StringBuilder();
        for(Map.Entry<ItemID, ItemStack> entry : items.entrySet())
        {
            s.append(entry.getKey()).append(" -> Stack=").append(entry.getValue().getItem()).append("\n");
        }
        return "SyncItemIDsPacket\n"+s.toString();
    }
}
