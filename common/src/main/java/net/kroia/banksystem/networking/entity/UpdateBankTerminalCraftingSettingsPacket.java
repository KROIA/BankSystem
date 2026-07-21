package net.kroia.banksystem.networking.entity;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.minecraft.menu.custom.BankTerminalContainerMenu;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * C2S packet sent when the player toggles one of the Bank Terminal crafting
 * checkboxes ("Use Bank Items" / "Auto-deposit output") or changes the selected
 * bank account while a crafting flag is active.
 * <p>
 * The server never trusts the flags directly: the open
 * {@link BankTerminalContainerMenu} re-validates the WITHDRAW / DEPOSIT
 * permissions on the target account and forces unauthorized flags off before
 * applying and persisting them (criterion B.12).
 */
public class UpdateBankTerminalCraftingSettingsPacket extends BankSystemNetworkPacket {

    public static final Type<UpdateBankTerminalCraftingSettingsPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "update_bank_terminal_crafting_settings_packet"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateBankTerminalCraftingSettingsPacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, p -> p.pos,
            ByteBufCodecs.BOOL, p -> p.useBankItems,
            ByteBufCodecs.BOOL, p -> p.depositOutputToBank,
            ByteBufCodecs.INT, p -> p.accountNumber,
            UpdateBankTerminalCraftingSettingsPacket::new
    );

    private final BlockPos pos;
    private final boolean useBankItems;
    private final boolean depositOutputToBank;
    private final int accountNumber;

    public UpdateBankTerminalCraftingSettingsPacket(BlockPos pos, boolean useBankItems, boolean depositOutputToBank, int accountNumber) {
        super();
        this.pos = pos;
        this.useBankItems = useBankItems;
        this.depositOutputToBank = depositOutputToBank;
        this.accountNumber = accountNumber;
    }

    @Override
    protected boolean needsRoutingToMaster() { return false; }

    public static void sendPacketToServer(BlockPos pos, boolean useBankItems, boolean depositOutputToBank, int accountNumber) {
        new UpdateBankTerminalCraftingSettingsPacket(pos, useBankItems, depositOutputToBank, accountNumber).sendToServer();
    }

    @Override
    protected void handleOnServer(NetworkManager.PacketContext context)
    {
        ServerPlayer player = (ServerPlayer) context.getPlayer();
        if (player.distanceToSqr(this.pos.getX() + 0.5, this.pos.getY() + 0.5, this.pos.getZ() + 0.5) > BankSystemMod.MAX_INTERACT_DISTANCE_SQR)
            return;
        // Only the player's currently open Bank Terminal menu at this position may
        // change its crafting settings.
        if (player.containerMenu instanceof BankTerminalContainerMenu menu
                && menu.getBlockPos().equals(this.pos)) {
            menu.applyCraftingSettings(this.useBankItems, this.depositOutputToBank, this.accountNumber);
        } else {
            BACKEND_INSTANCES.LOGGER.warn("[UpdateBankTerminalCraftingSettingsPacket] Player " + player.getUUID()
                    + " has no open BankTerminal menu at " + this.pos);
        }
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
