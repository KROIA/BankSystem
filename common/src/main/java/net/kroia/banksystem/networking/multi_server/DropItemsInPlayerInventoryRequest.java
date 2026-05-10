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


    public record InputData(UUID playerReceiver, Map<ItemID, Long> items, boolean forceDrop)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, InputData> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, p->p.playerReceiver,
                ExtraCodecUtils.mapStreamCodec(ItemID.STREAM_CODEC, ByteBufCodecs.VAR_LONG, HashMap<ItemID, Long>::new), p -> p.items,
                ByteBufCodecs.BOOL, p -> p.forceDrop,
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
        InputData inputData = new InputData(playerReceiver, items, forceDrop);
        CompletableFuture<Map<ItemID, Long>> future = new CompletableFuture<>();
        BankSystemNetworking.DROP_ITEMS_IN_PLAYER_INVENTORY_REQUEST.sendRequestToSlave(slaveID, inputData).whenComplete((response, ex) -> {
            if (ex != null || response == null) {
                future.complete(items); // Return all items as not-dropped
            } else {
                future.complete(response.items);
            }
        });
        return future;
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
                    stack.setCount(nextStackSize);
                    remaining = ServerPlayerUtilities.addToPlayerInventory(player, stack);
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
                        player.drop(dropStack, false);
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
        MinecraftServer server = UtilitiesPlatform.getServer();
        return CompletableFuture.completedFuture(new OutputData(input.playerReceiver, dropItems(server, input.playerReceiver, input.items, input.forceDrop)));
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
