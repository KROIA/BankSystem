package net.kroia.banksystem.networking.general;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.banksystem.util.VolatileItemComponents;
import net.minecraft.network.codec.ByteBufCodecs;
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
            ExtraCodecUtils.listStreamCodec(ByteBufCodecs.STRING_UTF8), p -> p.volatileComponentIds,
            ExtraCodecUtils.listStreamCodec(ByteBufCodecs.STRING_UTF8), p -> p.depositGatedComponentIds,
            ExtraCodecUtils.mapStreamCodec(ItemID.STREAM_CODEC, ItemID.STREAM_CODEC, HashMap<ItemID, ItemID>::new), p -> p.aliases,
            SyncItemIDsPacket::new
    );

    private final Map<ItemID, ItemStack> items;

    /**
     * Config-sourced volatile component type ids (see
     * {@link VolatileItemComponents#getConfigComponentIdStrings()}) of the sending side.
     * Receivers (clients and slave servers) adopt this list before processing the item map,
     * so all sides normalize ItemStacks identically. The datapack-tag half of the volatile
     * set is distributed by vanilla tag syncing and does not need to travel here.
     */
    private final List<String> volatileComponentIds;

    /**
     * Config-sourced deposit-gated component type ids (see
     * {@link VolatileItemComponents#getGatedConfigComponentIdStrings()}) of the sending side.
     * Synced for the same reason as {@link #volatileComponentIds}: gated components are
     * invisible to ItemID identity on every side, and slave servers run the deposit gate
     * locally for the blocks that live on them.
     */
    private final List<String> depositGatedComponentIds;

    /**
     * Alias table (merged ID → canonical ID) of the sending side, produced by
     * {@link ItemIDManager#renormalizeAndMerge()}. Synced so that bank balances / markets
     * keyed by a merged ID also resolve on clients and slave servers.
     */
    private final Map<ItemID, ItemID> aliases;


    /**
     * Creates a sync packet for the given items, automatically attaching the sender's
     * current config-sourced volatile + deposit-gated component lists and ItemID alias table.
     */
    public SyncItemIDsPacket(Map<ItemID, ItemStack> items)
    {
        this(items,
                VolatileItemComponents.getConfigComponentIdStrings(),
                VolatileItemComponents.getGatedConfigComponentIdStrings(),
                ItemIDManager.getItemIDAliasMap());
    }

    /** Codec constructor. */
    public SyncItemIDsPacket(Map<ItemID, ItemStack> items, List<String> volatileComponentIds, List<String> depositGatedComponentIds, Map<ItemID, ItemID> aliases)
    {
        this.items = items;
        this.volatileComponentIds = volatileComponentIds;
        this.depositGatedComponentIds = depositGatedComponentIds;
        this.aliases = aliases;
    }

    public Map<ItemID, ItemStack> getItems()
    {
        return items;
    }

    /**
     * @return the sender's config-sourced volatile component type ids (never null).
     */
    public List<String> getVolatileComponentIds()
    {
        return volatileComponentIds;
    }

    /**
     * @return the sender's config-sourced deposit-gated component type ids (never null).
     */
    public List<String> getDepositGatedComponentIds()
    {
        return depositGatedComponentIds;
    }

    /**
     * @return the sender's ItemID alias table (merged ID → canonical ID, never null).
     */
    public Map<ItemID, ItemID> getAliases()
    {
        return aliases;
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
        // Adopt the sender's component lists only on REMOTE clients. In singleplayer the
        // client shares VolatileItemComponents' static state with the integrated server —
        // this packet's lists are a snapshot from send time and may be OLDER than the shared
        // state by the time the client thread processes the packet. Adopting them would
        // asynchronously roll back the server's applied set (observed tearing a
        // renormalize pass running concurrently on the server thread).
        boolean isRemoteClient = UtilitiesPlatform.getServer() == null;
        ItemIDManager.receiveSyncPacket(this, isRemoteClient);
    }

    @Override
    protected void handleOnServer(NetworkManager.PacketContext context)
    {

    }


    @Override
    protected void handleOnSlave(ForwardPacketContext context)
    {
        ItemIDManager.receiveSyncPacket(this);
        // Task #22: after applying the master's authoritative items + aliases, release the
        // slave-side registration latch and run default registration via register-if-absent.
        // Master's shorts stay untouched; only genuinely new-in-mod-update items (that the
        // master doesn't yet know about) mint fresh slave-local shorts. Idempotent —
        // subsequent incremental sync packets find the latch already released and return.
        ItemIDManager.finalizeSlaveSync();
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
