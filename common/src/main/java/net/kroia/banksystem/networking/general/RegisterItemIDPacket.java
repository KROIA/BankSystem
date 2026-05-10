package net.kroia.banksystem.networking.general;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.banksystem.util.MultiServerUtils;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.kroia.modutilities.networking.multi_server.ForwardPacketContext;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class RegisterItemIDPacket extends BankSystemNetworkPacket
{
    public static final Type<RegisterItemIDPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "register_item_id_packet"));
    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, RegisterItemIDPacket> STREAM_CODEC = StreamCodec.composite(
            ExtraCodecUtils.listStreamCodec(ItemStack.STREAM_CODEC), p -> p.toRegister,
            RegisterItemIDPacket::new
    );

    private final List<ItemStack> toRegister;

    public static void sendRegisterItemIDPacketToMaster(ItemStack item)
    {
        List<ItemStack> items = new ArrayList<>();
        items.add(item);
        RegisterItemIDPacket packet = new RegisterItemIDPacket(items);
        if(MultiServerUtils.checkConnectionToMaster())
        {
            packet.sendToMaster();
        }
        else
        {
            packet.sendToServer();
        }
    }
    public static void sendRegisterItemIDPacketToMaster(List<ItemStack> items)
    {
        RegisterItemIDPacket packet = new RegisterItemIDPacket(items);
        if(MultiServerUtils.checkConnectionToMaster())
        {
            packet.sendToMaster();
        }
        else
        {
            packet.sendToServer();
        }
    }
    public static void sendRegisterItemIDPacketToServer(ItemStack item)
    {
        List<ItemStack> items = new ArrayList<>();
        items.add(item);
        RegisterItemIDPacket packet = new RegisterItemIDPacket(items);
        packet.sendToServer();
    }
    public static void sendRegisterItemIDPacketToServer(List<ItemStack> items)
    {
        RegisterItemIDPacket packet = new RegisterItemIDPacket(items);
        packet.sendToServer();
    }


    public RegisterItemIDPacket(List<ItemStack> toRegister)
    {
        this.toRegister = toRegister;
    }

    public List<ItemStack> getItems()
    {
        return toRegister;
    }


    @Override
    protected boolean needsRoutingToMaster()
    {
        return true;
    }

    @Override
    protected void handleOnServer(NetworkManager.PacketContext context) {
        ItemIDManager.receiveRegisterItemIDPacket(this);
    }
    @Override
    protected void handleOnMaster(ForwardPacketContext context)
    {
        ItemIDManager.receiveRegisterItemIDPacket(this);
    }
}
