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
 * C2S packet asking the server to physically fill the Bank Terminal's crafting
 * grid with the given recipe's ingredients (bank-list "craft this item" while
 * "Use Bank Items" is OFF — the JEI-independent counterpart of JEI's "+").
 * <p>
 * The server never trusts the id: the open {@link BankTerminalContainerMenu}
 * validates it resolves to a 3x3-craftable crafting recipe and sources every
 * ingredient itself (displaced grid items first, then player inventory, then
 * the terminal block inventory — see
 * {@link BankTerminalContainerMenu#applyPhysicalRecipeFill}). With bank mode
 * active the request degrades to a ghost-recipe selection server-side.
 */
public class FillBankTerminalCraftingGridPacket extends BankSystemNetworkPacket {

    public static final Type<FillBankTerminalCraftingGridPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "fill_bank_terminal_crafting_grid_packet"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FillBankTerminalCraftingGridPacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, p -> p.pos,
            ResourceLocation.STREAM_CODEC, p -> p.recipeId,
            FillBankTerminalCraftingGridPacket::new
    );

    private final BlockPos pos;
    private final ResourceLocation recipeId;

    public FillBankTerminalCraftingGridPacket(BlockPos pos, ResourceLocation recipeId) {
        super();
        this.pos = pos;
        this.recipeId = recipeId;
    }

    @Override
    protected boolean needsRoutingToMaster() { return false; }

    public static void sendPacketToServer(BlockPos pos, ResourceLocation recipeId) {
        new FillBankTerminalCraftingGridPacket(pos, recipeId).sendToServer();
    }

    @Override
    protected void handleOnServer(NetworkManager.PacketContext context)
    {
        ServerPlayer player = (ServerPlayer) context.getPlayer();
        if (player.distanceToSqr(this.pos.getX() + 0.5, this.pos.getY() + 0.5, this.pos.getZ() + 0.5) > BankSystemMod.MAX_INTERACT_DISTANCE_SQR)
            return;
        if (player.containerMenu instanceof BankTerminalContainerMenu menu
                && menu.getBlockPos().equals(this.pos)) {
            menu.applyPhysicalRecipeFill(this.recipeId);
        } else {
            BACKEND_INSTANCES.LOGGER.warn("[FillBankTerminalCraftingGridPacket] Player " + player.getUUID()
                    + " has no open BankTerminal menu at " + this.pos);
        }
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
