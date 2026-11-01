package net.kroia.banksystem.networking.entity;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.minecraft.menu.custom.BankTerminalContainerMenu;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * C2S packet carrying an explicitly selected "ghost" crafting recipe for the
 * Bank Terminal (sent by the JEI "+" button while "Use Bank Items" is active):
 * the recipe is shown as ghost items in the grid and its ingredients are sourced
 * entirely from the bank when the player takes the result.
 * <p>
 * The server never trusts the id directly: the open
 * {@link BankTerminalContainerMenu} verifies it resolves to an existing
 * 3x3-craftable {@code RecipeType.CRAFTING} recipe and that "Use Bank Items" is
 * actually active (server-validated flag) before storing it.
 */
public class SetBankTerminalGhostRecipePacket extends BankSystemNetworkPacket {

    public static final Type<SetBankTerminalGhostRecipePacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "set_bank_terminal_ghost_recipe_packet"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetBankTerminalGhostRecipePacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, p -> p.pos,
            ResourceLocation.STREAM_CODEC, p -> p.recipeId,
            SetBankTerminalGhostRecipePacket::new
    );

    private final BlockPos pos;
    private final ResourceLocation recipeId;

    public SetBankTerminalGhostRecipePacket(BlockPos pos, ResourceLocation recipeId) {
        super();
        this.pos = pos;
        this.recipeId = recipeId;
    }

    @Override
    protected boolean needsRoutingToMaster() { return false; }

    public static void sendPacketToServer(BlockPos pos, ResourceLocation recipeId) {
        new SetBankTerminalGhostRecipePacket(pos, recipeId).sendToServer();
    }

    @Override
    protected void handleOnServer(NetworkManager.PacketContext context)
    {
        ServerPlayer player = (ServerPlayer) context.getPlayer();
        if (player.distanceToSqr(this.pos.getX() + 0.5, this.pos.getY() + 0.5, this.pos.getZ() + 0.5) > BankSystemMod.MAX_INTERACT_DISTANCE_SQR)
            return;
        if (player.containerMenu instanceof BankTerminalContainerMenu menu
                && menu.getBlockPos().equals(this.pos)) {
            menu.applyGhostRecipe(this.recipeId);
        } else {
            BACKEND_INSTANCES.LOGGER.warn("[SetBankTerminalGhostRecipePacket] Player " + player.getUUID()
                    + " has no open BankTerminal menu at " + this.pos);
        }
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
