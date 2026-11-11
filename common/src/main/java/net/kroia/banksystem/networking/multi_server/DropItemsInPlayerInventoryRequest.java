package net.kroia.banksystem.networking.multi_server;

import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.kroia.modutilities.UtilitiesPlatform;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class DropItemsInPlayerInventoryRequest extends BankSystemGenericRequest<DropItemsInPlayerInventoryRequest.InputData,DropItemsInPlayerInventoryRequest.OutputData> {


    /** @param transferId Task #125: the master's transfer_id (its ledger ref); last field on the wire, null from an older master */
    public record InputData(UUID playerReceiver, Map<ItemID, Long> items, boolean forceDrop, @Nullable String transferId)
    {
        public InputData(UUID playerReceiver, Map<ItemID, Long> items, boolean forceDrop) {
            this(playerReceiver, items, forceDrop, null);
        }
        public static final StreamCodec<RegistryFriendlyByteBuf, InputData> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, p->p.playerReceiver,
                ExtraCodecUtils.mapStreamCodec(ItemID.STREAM_CODEC, ByteBufCodecs.VAR_LONG, HashMap<ItemID, Long>::new), p -> p.items,
                ByteBufCodecs.BOOL, p -> p.forceDrop,
                net.kroia.banksystem.data.TransferJournal.ID_CODEC, p -> p.transferId, // Task #125: keep last
                InputData::new
        );
    }
    public record OutputData(UUID playerReceiver, Map<ItemID, Long> items)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, OutputData> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, p->p.playerReceiver,
                ExtraCodecUtils.mapStreamCodec(ItemID.STREAM_CODEC, ByteBufCodecs.VAR_LONG, HashMap<ItemID, Long>::new), p -> p.items,
                OutputData::new
        );
    }

    /**
     *
     * @param slaveID
     * @param playerReceiver
     * @param items
     * @return a future containing the items that have not been dropped
     */
    public static CompletableFuture<Map<ItemID, Long>> sendToSlave(String slaveID, UUID playerReceiver, Map<ItemID, Long> items)
    {
        return sendToSlave(slaveID, playerReceiver, items, false);
    }

    /**
     *
     * @param slaveID
     * @param playerReceiver
     * @param items
     * @param forceDrop if true, items that don't fit in inventory are dropped at the player's feet
     * @return a future containing the items that have not been dropped
     */
    public static CompletableFuture<Map<ItemID, Long>> sendToSlave(String slaveID, UUID playerReceiver, Map<ItemID, Long> items, boolean forceDrop)
    {
        return sendToSlave(slaveID, playerReceiver, items, forceDrop, null);
    }

    /**
     * Task #125: same, plus the master's transfer_id (the ref of its debit / refund rows) for the slave's journal rows.
     * Task #126 (B6): never sent → all items come back as not dropped. An unknown outcome asks the slave's delivery cache;
     * if it stays unknown (or there is no id) the future fails and nothing may be refunded.
     */
    public static CompletableFuture<Map<ItemID, Long>> sendToSlave(String slaveID, UUID playerReceiver, Map<ItemID, Long> items,
                                                                   boolean forceDrop, @Nullable String transferId)
    {
        InputData inputData = new InputData(playerReceiver, items, forceDrop, transferId);
        final long sentMs = System.currentTimeMillis();
        CompletableFuture<Map<ItemID, Long>> sent = BankSystemNetworking.DROP_ITEMS_IN_PLAYER_INVENTORY_REQUEST
                .sendRequestToSlave(slaveID, inputData).thenApply(r -> r == null ? null : r.items);
        return TransferStatusRequest.resolve(sent, () -> items, remainder -> remainder,
                transferId == null ? null : () -> TransferStatusRequest.ask(slaveID, transferId, sentMs));
    }

    @Override
    public String getRequestTypeID() {
        return DropItemsInPlayerInventoryRequest.class.getName();
    }

    public static Map<ItemID, Long> dropItems(MinecraftServer server, UUID playerReceiver, Map<ItemID, Long> itemsToDrop)
    {
        return dropItems(server, playerReceiver, itemsToDrop, false);
    }

    public static Map<ItemID, Long> dropItems(MinecraftServer server, UUID playerReceiver, Map<ItemID, Long> itemsToDrop, boolean forceDrop)
    {
        if(server == null)
        {
            return itemsToDrop;
        }
        ServerPlayer player = ServerPlayerUtilities.getOnlinePlayer(playerReceiver);
        if(player == null)
        {
            return itemsToDrop;
        }

        Map<ItemID, Long> notDroppedItems = new HashMap<>();
        for(Map.Entry<ItemID, Long> entry : itemsToDrop.entrySet())
        {
            ItemID itemID = entry.getKey();
            long amount = entry.getValue();
            ItemStack stack = itemID.getStack().copy();
            if(stack.isEmpty())
            {
                notDroppedItems.put(itemID, amount);
            }
            else
            {
                int remaining = 0;
                int nextStackSize = 0;
                do
                {
                    nextStackSize = (int)Math.min(amount, stack.getMaxStackSize());
                    // Insert via vanilla Inventory.add: it merges only into stacks that are
                    // component-equal (ItemStack.isSameItemSameComponents, which mods like
                    // TFC hook so e.g. rotten never merges with fresh). ItemID/item-type
                    // matching must not drive this placement — identity ignores volatile/
                    // gated components, so a same-ID stack in the player inventory can be
                    // component-distinct (e.g. spoiled) and merging onto it would silently
                    // rewrite the withdrawn items' state.
                    // Inventory.add mutates the passed stack's count to the leftover amount.
                    ItemStack insertStack = stack.copy();
                    insertStack.setCount(nextStackSize);
                    player.getInventory().add(insertStack);
                    remaining = insertStack.getCount();
                    amount -= (nextStackSize-remaining);
                }while(remaining != nextStackSize && amount > 0);

                if(forceDrop && amount > 0)
                {
                    // Drop remaining items at the player's feet
                    while(amount > 0)
                    {
                        nextStackSize = (int)Math.min(amount, stack.getMaxStackSize());
                        ItemStack dropStack = stack.copy();
                        dropStack.setCount(nextStackSize);
                        net.kroia.modutilities.PlayerCompat.drop(player, dropStack);
                        amount -= nextStackSize;
                    }
                }

                if(amount > 0)
                {
                    notDroppedItems.put(itemID, amount);
                }
            }
        }
        return notDroppedItems;
    }

    public CompletableFuture<OutputData> handleOnSlaveServer(InputData input, @Nullable UUID playerSender) {
        // Task #126 (B6): delivery cache. A repeated DROP gets the first reply; nothing is dropped twice.
        CompletableFuture<OutputData> seen = TransferStatusRequest.replay(TransferStatusRequest.MASTER, input.transferId);
        if (seen != null)
            return seen;
        MinecraftServer server = UtilitiesPlatform.getServer();
        TransferStatusRequest.applying(TransferStatusRequest.MASTER, input.transferId);
        Map<ItemID, Long> notDropped = dropItems(server, input.playerReceiver, input.items, input.forceDrop);
        // Task #124 (B2): DELIVERED / FAILED journal rows. Task #125: under the master's transfer_id (fresh id from an older master).
        net.kroia.banksystem.data.TransferJournal.drop(input.transferId != null ? input.transferId
                        : net.kroia.banksystem.data.TransferJournal.newTransferId(),
                input.playerReceiver, input.items, notDropped);
        OutputData out = new OutputData(input.playerReceiver, notDropped);
        TransferStatusRequest.remember(TransferStatusRequest.MASTER, input.transferId, !notDropped.equals(input.items), notDropped, out);
        return CompletableFuture.completedFuture(out);
    }


    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, InputData input) {
        InputData.STREAM_CODEC.encode(buf, input);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, OutputData output) {
        OutputData.STREAM_CODEC.encode(buf, output);
    }

    @Override
    public InputData decodeInput(RegistryFriendlyByteBuf buf) {
        return InputData.STREAM_CODEC.decode(buf);
    }

    @Override
    public OutputData decodeOutput(RegistryFriendlyByteBuf buf) {
        return OutputData.STREAM_CODEC.decode(buf);
    }





}
