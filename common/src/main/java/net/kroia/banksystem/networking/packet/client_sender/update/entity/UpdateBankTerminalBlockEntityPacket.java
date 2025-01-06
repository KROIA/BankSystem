package net.kroia.banksystem.networking.packet.client_sender.update.entity;

import dev.architectury.networking.simple.MessageType;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.entity.custom.BankTerminalBlockEntity;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.modutilities.networking.NetworkPacketC2S;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.HashMap;

public class UpdateBankTerminalBlockEntityPacket extends NetworkPacketC2S {

    private BlockPos pos;

    private HashMap<String, Long> itemTransferFromMarket;
    private boolean sendItemsToMarket;
    @Override
    public MessageType getType() {
        return BankSystemNetworking.UPDATE_BANK_TERMINAL_BLOCK_ENTITY;
    }
    public UpdateBankTerminalBlockEntityPacket(BlockPos pos, HashMap<String, Long> itemTransferToMarketAmounts, boolean sendItemsToMarket) {
        super();
        this.pos = pos;
        this.itemTransferFromMarket = itemTransferToMarketAmounts;
        this.sendItemsToMarket = sendItemsToMarket;
    }


    public UpdateBankTerminalBlockEntityPacket(RegistryFriendlyByteBuf buf) {
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
        new UpdateBankTerminalBlockEntityPacket(pos, itemTransferToMarketAmounts, sendItemsToMarket).sendToServer();
    }

    @Override
    public void toBytes(RegistryFriendlyByteBuf buf)
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
    public void fromBytes(RegistryFriendlyByteBuf buf) {
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
