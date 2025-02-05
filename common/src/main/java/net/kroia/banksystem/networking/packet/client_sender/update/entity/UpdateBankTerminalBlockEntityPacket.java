package net.kroia.banksystem.networking.packet.client_sender.update.entity;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.entity.custom.BankTerminalBlockEntity;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.modutilities.networking.NetworkPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.HashMap;

public class UpdateBankTerminalBlockEntityPacket extends NetworkPacket {

    private BlockPos pos;

    private HashMap<String, Long> itemTransferFromMarket;
    private boolean sendItemsToMarket;
    public UpdateBankTerminalBlockEntityPacket(BlockPos pos, HashMap<String, Long> itemTransferToMarketAmounts, boolean sendItemsToMarket) {
        super();
        this.pos = pos;
        this.itemTransferFromMarket = itemTransferToMarketAmounts;
        this.sendItemsToMarket = sendItemsToMarket;
    }


    public UpdateBankTerminalBlockEntityPacket(FriendlyByteBuf buf) {
        super(buf);
    }

    public BlockPos getPos() {
        return pos;
    }

    public HashMap<String, Long> getItemTransferFromMarket() {
        return itemTransferFromMarket;
    }
    public boolean isSendItemsToMarket() {
        return sendItemsToMarket;
    }


    public static void sendPacketToServer(BlockPos pos, HashMap<String, Long> itemTransferToMarketAmounts, boolean sendItemsToMarket) {
        BankSystemNetworking.sendToServer(new UpdateBankTerminalBlockEntityPacket(pos, itemTransferToMarketAmounts, sendItemsToMarket));
    }

    @Override
    public void toBytes(FriendlyByteBuf buf)
    {
        buf.writeBlockPos(pos);
        buf.writeBoolean(sendItemsToMarket);
        buf.writeInt(itemTransferFromMarket.size());
        itemTransferFromMarket.forEach((itemID, amount) -> {
            buf.writeUtf(itemID);
            buf.writeLong(amount);
        });
    }

    @Override
    public void fromBytes(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        this.itemTransferFromMarket = new HashMap<>();
        this.sendItemsToMarket = buf.readBoolean();
        int size = buf.readInt();
        for (int i = 0; i < size; i++) {
            String itemID = buf.readUtf();
            long amount = buf.readLong();
            this.itemTransferFromMarket.put(itemID, amount);
        }
    }

    @Override
    protected void handleOnServer(ServerPlayer sender) {
        Thread t = Thread.currentThread();
        BlockEntity blockEntity = sender.level().getBlockEntity(this.pos);
        if(blockEntity instanceof BankTerminalBlockEntity bankTerminalBlockEntity) {
            bankTerminalBlockEntity.handlePacket(this, sender);
        }else
        {
            BankSystemMod.LOGGER.error("BankTerminalBlockEntity not found at position "+this.pos);
        }
    }
}
