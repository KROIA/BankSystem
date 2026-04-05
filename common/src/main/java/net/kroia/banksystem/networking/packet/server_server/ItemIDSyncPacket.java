package net.kroia.banksystem.networking.packet.server_server;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

public class ItemIDSyncPacket extends BankSystemNetworkPacket {
    public static final Type<ItemIDSyncPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "player_join_packet"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ItemIDSyncPacket> STREAM_CODEC = StreamCodec.composite(
            ExtraCodecUtils.mapStreamCodec(ItemID.STREAM_CODEC, ItemStack.STREAM_CODEC, HashMap<ItemID, ItemStack>::new), p -> p.items,
            ItemIDSyncPacket::new
    );


    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }


    private final Map<ItemID, ItemStack> items;


    public ItemIDSyncPacket(Map<ItemID, ItemStack> items)
    {
        this.items = items;
    }

    public Map<ItemID, ItemStack> getItems()
    {
        return items;
    }
}
