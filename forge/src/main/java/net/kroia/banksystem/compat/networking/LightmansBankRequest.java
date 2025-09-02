package net.kroia.banksystem.compat.networking;

import io.github.lightman314.lightmanscurrency.api.money.bank.reference.BankReference;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class LightmansBankRequest  extends BankSystemGenericRequest<UUID, List<BankReference>> {
    @Override
    public String getRequestTypeID() {
        return "lightmansbanks";
    }

    @Override
    public void encodeInput(FriendlyByteBuf buf, UUID input) {
        buf.writeUUID(input);
    }

    @Override
    public void encodeOutput(FriendlyByteBuf buf, List<BankReference> output) {
        buf.writeInt(output.size());
        for(BankReference r : output){
            r.encode(buf);
        }
    }

    @Override
    public UUID decodeInput(FriendlyByteBuf buf) {
        return buf.readUUID();
    }

    @Override
    public List<BankReference> decodeOutput(FriendlyByteBuf buf) {
        List<BankReference> banks = new ArrayList<>();
        int size = buf.readInt();
        for(int i = 0; i < size; ++i){
            banks.add(BankReference.decode(buf));
        }
        return banks;
    }
}
