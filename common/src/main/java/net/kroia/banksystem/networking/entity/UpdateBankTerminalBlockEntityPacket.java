package net.kroia.banksystem.networking.entity;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.minecraft.entity.custom.BankTerminalBlockEntity;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;

public class UpdateBankTerminalBlockEntityPacket extends BankSystemNetworkPacket {

    public static final Type<UpdateBankTerminalBlockEntityPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "update_bank_terminal_block_entity_packet"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateBankTerminalBlockEntityPacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, p -> p.pos,
            ExtraCodecUtils.mapStreamCodec(ItemID.STREAM_CODEC, ByteBufCodecs.VAR_LONG, HashMap::new), p -> p.itemTransferFromMarket,
            ByteBufCodecs.BOOL, p -> p.sendItemsToBank,
            ByteBufCodecs.INT, p -> p.selectedBankAccount,
            UpdateBankTerminalBlockEntityPacket::new
    );


    private BlockPos pos;
    private HashMap<ItemID, Long> itemTransferFromMarket;
    private boolean sendItemsToBank;
    private int selectedBankAccount; // This can be used to specify which bank account is being updated

    /**
     *
     *
     * @param pos
     * @param itemTransferToMarketAmounts map of items and amount. The amount is not the backend value but whole item counts
     *                                    Only whole items can be withdrawn
     * @param sendItemsToMarket
     * @param selectedBankAccount
     */
    public UpdateBankTerminalBlockEntityPacket(BlockPos pos, HashMap<ItemID, Long> itemTransferToMarketAmounts, boolean sendItemsToMarket, int selectedBankAccount) {
        super();
        this.pos = pos;
        this.itemTransferFromMarket = itemTransferToMarketAmounts;
        this.sendItemsToBank = sendItemsToMarket;
        this.selectedBankAccount = selectedBankAccount;
    }


    @Override
    protected boolean needsRoutingToMaster() { return false; }



    public BlockPos getPos() {
        return pos;
    }

    public HashMap<ItemID, Long> getItemTransferFromMarket() {
        return itemTransferFromMarket;
    }
    public boolean isSendItemsToBank() {
        return sendItemsToBank;
    }
    public int getSelectedBankAccount() {
        return selectedBankAccount;
    }


    public static void sendPacketToServer(BlockPos pos, HashMap<ItemID, Long> itemTransferToBankAmounts, boolean sendItemsToMarket, int selectedBankAccount) {
        new UpdateBankTerminalBlockEntityPacket(pos, itemTransferToBankAmounts, sendItemsToMarket, selectedBankAccount).sendToServer();
    }

    @Override
    protected void handleOnServer(NetworkManager.PacketContext context)
    {
        ServerPlayer player = (ServerPlayer) context.getPlayer();
        if (player.distanceToSqr(this.pos.getX() + 0.5, this.pos.getY() + 0.5, this.pos.getZ() + 0.5) > BankSystemMod.MAX_INTERACT_DISTANCE_SQR)
            return;
        BlockEntity blockEntity = player.level().getBlockEntity(this.pos);
        if(blockEntity instanceof BankTerminalBlockEntity bankTerminalBlockEntity) {
            bankTerminalBlockEntity.handlePacket(this, player);
        }else
        {
            BACKEND_INSTANCES.LOGGER.error("BankTerminalBlockEntity not found at position "+this.pos);
        }
    }


    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
