package net.kroia.banksystem.networking.request;


import net.kroia.modutilities.networking.arrs.GenericRequest;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class TestRequest extends GenericRequest<Integer, String>
{

    @Override
    public String getRequestTypeID() {
        /*String name1 = TestRequest.class.getSimpleName();
        String name2 = TestRequest.class.getName();
        return name2;*/
        return "TestRequest_"+Integer.TYPE.getName()+"_"+String.class.getName();
    }

    @Override
    public String handleOnClient(Integer input) {
        return "Hello, your request has been processed successfully by the client! Input: " + input;
    }
    @Override
    public String handleOnServer(Integer input, ServerPlayer sender) {
        return "Hello, your request has been processed successfully by the server! Input: " + input;
    }

    @Override
    public void encodeInput(FriendlyByteBuf buf, Integer input) {
        if (input != null) {
            buf.writeInt(input.intValue());
        } else {
            buf.writeInt(0); // or some default value
        }
    }

    @Override
    public void encodeOutput(FriendlyByteBuf buf, String output) {
        if (output != null) {
            buf.writeUtf(output);
        } else {
            buf.writeUtf(""); // or some default value
        }
    }

    @Override
    public Integer decodeInput(FriendlyByteBuf buf) {
        int value = buf.readInt();
        return value;
    }

    @Override
    public String decodeOutput(FriendlyByteBuf buf) {
        return buf.readUtf(); // Assuming output is a String
    }
}
