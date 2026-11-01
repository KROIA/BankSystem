package net.kroia.banksystem.networking.entity;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.util.BankCraftingRecipes;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * One chunk of the shaped/shapeless crafting recipes the Bank Terminal needs on the
 * client (the client has no recipe manager since MC 1.21.2). See {@link BankCraftingRecipes}.
 */
public class S2CCraftingRecipesPacket extends BankSystemNetworkPacket {

    public static final Type<S2CCraftingRecipesPacket> TYPE = new Type<>(BankSystemMod.id("s2c_crafting_recipes"));

    private static final StreamCodec<RegistryFriendlyByteBuf, List<RecipeHolder<?>>> RECIPES_CODEC =
            RecipeHolder.STREAM_CODEC.apply(ByteBufCodecs.list());

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CCraftingRecipesPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBoolean(p.first);
                        buf.writeBoolean(p.last);
                        RECIPES_CODEC.encode(buf, p.recipes);
                    },
                    buf -> new S2CCraftingRecipesPacket(buf.readBoolean(), buf.readBoolean(), RECIPES_CODEC.decode(buf)));

    private final boolean first;
    private final boolean last;
    private final List<RecipeHolder<?>> recipes;

    public S2CCraftingRecipesPacket(boolean first, boolean last, List<RecipeHolder<?>> recipes) {
        this.first = first;
        this.last = last;
        this.recipes = recipes;
    }

    public static void sendTo(ServerPlayer player, boolean first, boolean last, List<RecipeHolder<?>> recipes) {
        new S2CCraftingRecipesPacket(first, last, recipes).sendToClient(player);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    @Override
    public void handleOnClient(NetworkManager.PacketContext context) {
        BankCraftingRecipes.acceptChunk(first, last, recipes);
    }
}
