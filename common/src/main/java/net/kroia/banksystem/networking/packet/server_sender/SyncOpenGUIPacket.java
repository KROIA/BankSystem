package net.kroia.banksystem.networking.packet.server_sender;

import net.kroia.banksystem.BankSystemClientHooks;
import net.kroia.banksystem.networking.BankSystemNetworking;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.modutilities.PlayerUtilities;
import net.kroia.modutilities.networking.NetworkPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class SyncOpenGUIPacket extends NetworkPacket {

    enum GUIType
    {
        BANK_SYSTEM_SETTING,
        BANK_ACCOUNT
    }

    GUIType guiType;
    UUID targetPlayerUUID;


    public SyncOpenGUIPacket() {
        super();
    }
    public SyncOpenGUIPacket(FriendlyByteBuf friendlyByteBuf) {
        super(friendlyByteBuf);
    }

    public static void send_openBankSystemSettingScreen(ServerPlayer player)
    {
        // check if player is in creative mode
        if(!player.isCreative())
        {
            PlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getNeedCreativeModeForThisScreenMessage());
            return;
        }

        SyncOpenGUIPacket packet = new SyncOpenGUIPacket();
        packet.guiType = GUIType.BANK_SYSTEM_SETTING;
        BankSystemNetworking.sendToClient(player, packet);
    }
    public static void send_openBankAccountScreen(ServerPlayer player, UUID targetPlayerUUID)
    {
        SyncOpenGUIPacket packet = new SyncOpenGUIPacket();
        packet.guiType = GUIType.BANK_ACCOUNT;
        packet.targetPlayerUUID = targetPlayerUUID;
        BankSystemNetworking.sendToClient(player, packet);
    }

    @Override
    protected void handleOnClient()
    {
        switch(guiType)
        {
            case BANK_SYSTEM_SETTING:
                BankSystemClientHooks.openBankSystemSettingScreen();
                break;
            case BANK_ACCOUNT:
                BankSystemClientHooks.openBankAccountScreen(targetPlayerUUID);
                break;
        }
    }

    @Override
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeEnum(guiType);

        if(targetPlayerUUID == null)
            targetPlayerUUID = new UUID(0,0);
        buf.writeUUID(targetPlayerUUID);
    }

    @Override
    public void fromBytes(FriendlyByteBuf buf) {
        guiType = buf.readEnum(GUIType.class);
        targetPlayerUUID = buf.readUUID();
    }
}
