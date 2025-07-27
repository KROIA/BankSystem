package net.kroia.banksystem.networking.packet.client_sender.update.entity;

import net.kroia.banksystem.entity.custom.BankTerminalBlockEntity;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.HashMap;

public class UpdateBankTerminalBlockEntityPacket extends BankSystemNetworkPacket {

    private BlockPos pos;

    private HashMap<ItemID, Long> itemTransferFromMarket;
    private boolean sendItemsToBank;

    public UpdateBankTerminalBlockEntityPacket(BlockPos pos, HashMap<ItemID, Long> itemTransferToMarketAmounts, boolean sendItemsToMarket) {
        super();
        this.pos = pos;
        this.itemTransferFromMarket = itemTransferToMarketAmounts;
        this.sendItemsToBank = sendItemsToMarket;
    }


    public UpdateBankTerminalBlockEntityPacket(FriendlyByteBuf buf) {
        super(buf);
    }

    public BlockPos getPos() {
        return pos;
    }

    public HashMap<ItemID, Long> getItemTransferFromMarket() {
        return itemTransferFromMarket;
    }
    public boolean isSendItemsToBank() {
        return sendItemsToBank;
    }


    public static void sendPacketToServer(BlockPos pos, HashMap<ItemID, Long> itemTransferToBankAmounts, boolean sendItemsToMarket) {
        new UpdateBankTerminalBlockEntityPacket(pos, itemTransferToBankAmounts, sendItemsToMarket).sendToServer();
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeBlockPos(pos);
        buf.writeBoolean(sendItemsToBank);
        buf.writeInt(itemTransferFromMarket.size());
        itemTransferFromMarket.forEach((itemID, amount) -> {
            buf.writeItem(itemID.getStack());
            buf.writeLong(amount);
        });
    }

    @Override
    public void decode(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        this.itemTransferFromMarket = new HashMap<>();
        this.sendItemsToBank = buf.readBoolean();
        int size = buf.readInt();
        for (int i = 0; i < size; i++) {
            ItemID itemID = new ItemID(buf.readItem());
            long amount = buf.readLong();
            this.itemTransferFromMarket.put(itemID, amount);
        }
    }

    @Override
    protected void handleOnServer(ServerPlayer sender) {
        BlockEntity blockEntity = sender.level().getBlockEntity(this.pos);
        if(blockEntity instanceof BankTerminalBlockEntity bankTerminalBlockEntity) {
            bankTerminalBlockEntity.handlePacket(this, sender);
        }else
        {
            BACKEND_INSTANCES.LOGGER.error("BankTerminalBlockEntity not found at position "+this.pos);
        }
    }
}
