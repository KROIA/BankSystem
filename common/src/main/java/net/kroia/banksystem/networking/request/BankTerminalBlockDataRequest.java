package net.kroia.banksystem.networking.request;


import net.kroia.banksystem.entity.custom.BankTerminalBlockEntity;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;

public class BankTerminalBlockDataRequest extends BankSystemGenericRequest<BlockPos, BankTerminalBlockDataRequest.Output> {

    public record Output(int selectedBankAccount)
    {
        public static final StreamCodec<RegistryFriendlyByteBuf, Output> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, Output::selectedBankAccount,
                Output::new
        );
        /*public int selectedBankAccount = 0;
        //public int userPermission = 0;


        @Override
        public void encode(RegistryFriendlyByteBuf buf) {
            buf.writeInt(selectedBankAccount);
            //buf.writeInt(userPermission);
        }

        public static Output decode(RegistryFriendlyByteBuf buf) {
            Output output = new Output();
            output.selectedBankAccount = buf.readInt();
            //output.userPermission = buf.readInt();
            return output;
        }*/
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

        // Here you would typically retrieve the selected bank account from the block entity or player data
        // For example:
        BankTerminalBlockEntity blockEntity = (BankTerminalBlockEntity) sender.level().getBlockEntity(input);
        if(blockEntity == null) {
            return new Output(0); // or handle the error appropriately
        }

        Output output = new Output(blockEntity.getSelectedBankAccount(sender.getUUID()));
        //IBankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getBankAccount(output.selectedBankAccount);
        /*if(account != null) {
            output.userPermission = account.getPermission(sender.getUUID());
        } else {
            output.userPermission = 0; // Default permission if account is not found
        }*/
        return output;
    }

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, BlockPos input) {
        BlockPos.STREAM_CODEC.encode(buf, input);
    }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, Output output) {
        Output.STREAM_CODEC.encode(buf, output);
    }

    @Override
    public BlockPos decodeInput(RegistryFriendlyByteBuf buf) {
        return BlockPos.STREAM_CODEC.decode(buf);
    }

    @Override
    public Output decodeOutput(RegistryFriendlyByteBuf buf) {
        return Output.STREAM_CODEC.decode(buf);
    }


}
