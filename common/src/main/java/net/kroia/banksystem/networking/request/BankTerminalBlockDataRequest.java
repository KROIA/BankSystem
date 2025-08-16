package net.kroia.banksystem.networking.request;

import net.kroia.banksystem.entity.custom.BankTerminalBlockEntity;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.kroia.modutilities.networking.INetworkPayloadEncoder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class BankTerminalBlockDataRequest extends BankSystemGenericRequest<BlockPos, BankTerminalBlockDataRequest.Output> {

    public static class Output implements INetworkPayloadEncoder
    {
        public int selectedBankAccount = 0;


        @Override
        public void encode(FriendlyByteBuf buf) {
            buf.writeInt(selectedBankAccount);
        }

        public static Output decode(FriendlyByteBuf buf) {
            Output output = new Output();
            output.selectedBankAccount = buf.readInt();
            return output;
        }
    }

    @Override
    public String getRequestTypeID() {
        return BankTerminalBlockDataRequest.class.getSimpleName();
    }

    @Override
    public Output handleOnClient(BlockPos input) {
        return null;
    }

    @Override
    public Output handleOnServer(BlockPos input, ServerPlayer sender) {
        Output output = new Output();
        // Here you would typically retrieve the selected bank account from the block entity or player data
        // For example:
        BankTerminalBlockEntity blockEntity = (BankTerminalBlockEntity) sender.level().getBlockEntity(input);
        if(blockEntity == null) {
            return output; // or handle the error appropriately
        }
        output.selectedBankAccount = blockEntity.getSelectedBankAccount(sender.getUUID());
        return output;
    }

    @Override
    public void encodeInput(FriendlyByteBuf buf, BlockPos input) {
        buf.writeBlockPos(input);
    }

    @Override
    public void encodeOutput(FriendlyByteBuf buf, Output output) {
        output.encode(buf);
    }

    @Override
    public BlockPos decodeInput(FriendlyByteBuf buf) {
        return buf.readBlockPos();
    }

    @Override
    public Output decodeOutput(FriendlyByteBuf buf) {
        return Output.decode(buf);
    }


}
