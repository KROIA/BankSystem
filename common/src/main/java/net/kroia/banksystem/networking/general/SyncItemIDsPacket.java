package net.kroia.banksystem.networking.general;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.banksystem.util.VolatileItemComponents;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
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

    /**
     * Wire format for {@link #items}: NBT (CompoundTag) via
     * {@link ByteBufCodecs#TRUSTED_COMPOUND_TAG} — NOT vanilla's
     * {@link ItemStack#STREAM_CODEC}, which encodes items by their runtime numeric
     * registry ID. Numeric IDs are stable master↔client (broadcast via vanilla
     * handshake), but NOT stable master↔slave — each server computes its own numeric
     * ID table independently and even a byte-identical mod set can diverge if mod
     * load order differs. Encoding by resource key (via
     * {@link ItemStack#save(net.minecraft.core.HolderLookup.Provider, Tag)} on the
     * sender / {@link ItemStack#parseOptional(net.minecraft.core.HolderLookup.Provider, CompoundTag)}
     * on the receiver) is stable across independent server processes and degrades to
     * an unresolvable stack (dropped in {@link ItemIDManager#receiveSyncPacket}) when
     * the receiving side lacks the mod, instead of silently binding the short to a
     * wrong item.
     * <p>
     * Master↔client uses the same NBT codec for uniformity; vanilla broadcasts the
     * master's registry to clients at connect time, so client-side
     * {@code parseOptional} always resolves items that the master registered.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncItemIDsPacket> STREAM_CODEC = StreamCodec.composite(
            ExtraCodecUtils.mapStreamCodec(ItemID.STREAM_CODEC, ByteBufCodecs.TRUSTED_COMPOUND_TAG, HashMap<ItemID, CompoundTag>::new), p -> p.items,
            ExtraCodecUtils.listStreamCodec(ByteBufCodecs.STRING_UTF8), p -> p.volatileComponentIds,
            ExtraCodecUtils.listStreamCodec(ByteBufCodecs.STRING_UTF8), p -> p.depositGatedComponentIds,
            ExtraCodecUtils.mapStreamCodec(ItemID.STREAM_CODEC, ItemID.STREAM_CODEC, HashMap<ItemID, ItemID>::new), p -> p.aliases,
            SyncItemIDsPacket::new
    );

    /**
     * Raw NBT payload of the sender's ItemID -> ItemStack table. Kept in serialized
     * form (rather than eagerly inflated back to {@link ItemStack}) so the "item is
     * unresolvable on this side" case can be reported and dropped by
     * {@link ItemIDManager#receiveSyncPacket} — a stack constructed from a tag whose
     * host mod is missing here would otherwise silently degrade to
     * {@link ItemStack#EMPTY} inside a lossy codec, hiding the divergence.
     */
    private final Map<ItemID, CompoundTag> items;

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
     * Creates a sync packet from live {@link ItemStack} entries — converts each stack
     * to its NBT form using the current server's registry access. See
     * {@link #STREAM_CODEC} for why NBT (not the numeric-ID codec) is used on the wire.
     * Automatically attaches the sender's current config-sourced volatile + deposit-gated
     * component lists and ItemID alias table.
     */
    public SyncItemIDsPacket(Map<ItemID, ItemStack> itemStacks)
    {
        this(toItemTagMap(itemStacks),
                VolatileItemComponents.getConfigComponentIdStrings(),
                VolatileItemComponents.getGatedConfigComponentIdStrings(),
                ItemIDManager.getItemIDAliasMap());
    }

    /** Codec constructor. */
    public SyncItemIDsPacket(Map<ItemID, CompoundTag> items, List<String> volatileComponentIds, List<String> depositGatedComponentIds, Map<ItemID, ItemID> aliases)
    {
        this.items = items;
        this.volatileComponentIds = volatileComponentIds;
        this.depositGatedComponentIds = depositGatedComponentIds;
        this.aliases = aliases;
    }

    /**
     * Converts a live {@code ItemID -> ItemStack} map to the NBT wire representation.
     * Called on the sending server thread. Uses the running server's
     * {@link RegistryAccess} to serialize each stack via
     * {@link ItemStack#save(net.minecraft.core.HolderLookup.Provider, Tag)} — the same
     * mechanism {@link ItemIDManager#save(CompoundTag)} uses for the on-disk
     * {@code ItemIDs.nbt} file, and thus subject to the same failure discipline:
     * an entry that cannot be serialized in the current registry context is dropped
     * with a WARN (receiver would fail to parse it anyway).
     */
    private static Map<ItemID, CompoundTag> toItemTagMap(Map<ItemID, ItemStack> itemStacks)
    {
        Map<ItemID, CompoundTag> tags = new HashMap<>(itemStacks.size());
        RegistryAccess access = UtilitiesPlatform.getRegistryAccessServerSide();
        if(access == null)
            return tags;
        for(Map.Entry<ItemID, ItemStack> entry : itemStacks.entrySet())
        {
            ItemStack stack = entry.getValue();
            if(stack == null || stack.isEmpty())
                continue;
            try
            {
                Tag stackTag = stack.save(access, new CompoundTag());
                if(stackTag instanceof CompoundTag compoundTag)
                    tags.put(entry.getKey(), compoundTag);
            }
            catch(Exception ignored)
            {
                // Match ItemIDManager.save()'s discipline: silently drop entries that
                // can't be serialized here. The receiver's parseOptional would fail
                // on them anyway; no point sending unparseable bytes on the wire.
            }
        }
        return tags;
    }

    /**
     * @return the sender's ItemID -> serialized ItemStack NBT map. Callers must
     *         inflate to {@link ItemStack} via
     *         {@link ItemStack#parseOptional(net.minecraft.core.HolderLookup.Provider, CompoundTag)}
     *         on their own registry to handle unresolvable entries gracefully.
     */
    public Map<ItemID, CompoundTag> getItems()
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
        for(Map.Entry<ItemID, CompoundTag> entry : items.entrySet())
        {
            // Tag NBT carries the item's resource key under "id" — friendlier than the
            // full CompoundTag dump for debug logs. Falls back to the raw tag when the
            // key is absent (defensive; a well-formed save() output always includes it).
            CompoundTag tag = entry.getValue();
            String label = (tag != null && tag.contains("id")) ? tag.getString("id") : String.valueOf(tag);
            s.append(entry.getKey()).append(" -> Stack=").append(label).append("\n");
        }
        return "SyncItemIDsPacket\n"+s.toString();
    }
}
