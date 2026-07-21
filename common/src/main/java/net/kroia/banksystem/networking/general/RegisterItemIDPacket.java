package net.kroia.banksystem.networking.general;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.banksystem.util.MultiServerUtils;
import net.kroia.modutilities.UtilitiesPlatform;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.kroia.modutilities.networking.multi_server.ForwardPacketContext;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
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

    /**
     * Wire format: NBT (CompoundTag) via {@link ByteBufCodecs#TRUSTED_COMPOUND_TAG} for
     * the same reason as {@link SyncItemIDsPacket#STREAM_CODEC} — vanilla's
     * {@link ItemStack#STREAM_CODEC} encodes by the sender's runtime numeric registry ID,
     * which is stable client↔server (numeric registry synced at handshake) but NOT stable
     * across the server↔server hop performed when a slave forwards this packet to master.
     * Resource keys (via {@link ItemStack#save(net.minecraft.core.HolderLookup.Provider, Tag)}
     * / {@link ItemStack#parseOptional(net.minecraft.core.HolderLookup.Provider, CompoundTag)})
     * are stable across independent processes; the receiver drops entries it cannot resolve
     * (see {@link ItemIDManager#receiveRegisterItemIDPacket}) instead of registering a wrong
     * item under the requested identity.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, RegisterItemIDPacket> STREAM_CODEC = StreamCodec.composite(
            ExtraCodecUtils.listStreamCodec(ByteBufCodecs.TRUSTED_COMPOUND_TAG), p -> p.toRegister,
            RegisterItemIDPacket::new
    );

    /**
     * Raw NBT payload of the sender's requested items. Kept in serialized form until
     * {@link ItemIDManager#receiveRegisterItemIDPacket} inflates each entry via
     * {@link ItemStack#parseOptional} on the receiver's registry — see {@link #STREAM_CODEC}.
     */
    private final List<CompoundTag> toRegister;

    public static void sendRegisterItemIDPacketToMaster(ItemStack item)
    {
        List<ItemStack> items = new ArrayList<>();
        items.add(item);
        sendRegisterItemIDPacketToMaster(items);
    }
    public static void sendRegisterItemIDPacketToMaster(List<ItemStack> items)
    {
        RegisterItemIDPacket packet = new RegisterItemIDPacket(toItemTagList(items));
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
        sendRegisterItemIDPacketToServer(items);
    }
    public static void sendRegisterItemIDPacketToServer(List<ItemStack> items)
    {
        RegisterItemIDPacket packet = new RegisterItemIDPacket(toItemTagList(items));
        packet.sendToServer();
    }

    /**
     * Serializes a list of live {@link ItemStack}s into their NBT wire representation
     * using this side's {@link RegistryAccess}. Empty / unserializable entries are
     * dropped — the receiver would fail to parse them anyway (see {@link #STREAM_CODEC}).
     * Called from both client-side (via
     * {@link ItemIDManager#registerItemStackClientSide}) and server-side (via
     * {@link ItemIDManager#registerItemStackServerSide}) so {@code getRegistryAccess()}
     * picks the correct side automatically.
     */
    private static List<CompoundTag> toItemTagList(List<ItemStack> stacks)
    {
        List<CompoundTag> tags = new ArrayList<>(stacks.size());
        RegistryAccess access = UtilitiesPlatform.getRegistryAccess();
        if(access == null)
            return tags;
        for(ItemStack stack : stacks)
        {
            if(stack == null || stack.isEmpty())
                continue;
            try
            {
                Tag stackTag = stack.save(access, new CompoundTag());
                if(stackTag instanceof CompoundTag compoundTag)
                    tags.add(compoundTag);
            }
            catch(Exception ignored)
            {
                // Matches ItemIDManager.save()'s discipline — unserializable stacks are
                // dropped rather than sent as unparseable bytes.
            }
        }
        return tags;
    }


    /** Codec constructor. */
    public RegisterItemIDPacket(List<CompoundTag> toRegister)
    {
        this.toRegister = toRegister;
    }

    /**
     * @return the raw NBT list of requested items. Callers inflate via
     *         {@link ItemStack#parseOptional} on their own registry — unresolvable
     *         entries (mod missing on this side) are surfaced as {@link ItemStack#EMPTY}
     *         and must be dropped by the caller instead of registered.
     */
    public List<CompoundTag> getItems()
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
