package net.kroia.banksystem.networking.packet.server_sender;

import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.banksystem.util.BankSystemClientHooks;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.modutilities.PlayerUtilities;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class SyncOpenGUIPacket extends BankSystemNetworkPacket {

    enum GUIType
    {
        BANK_SYSTEM_SETTING,
        BANK_ACCOUNT,
        ATM_SCREEN, // ATM screen is not implemented yet
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
        packet.sendToClient(player);
    }
    public static void send_openBankAccountScreen(ServerPlayer player, UUID targetPlayerUUID)
    {
        SyncOpenGUIPacket packet = new SyncOpenGUIPacket();
        packet.guiType = GUIType.BANK_ACCOUNT;
        packet.targetPlayerUUID = targetPlayerUUID;
        packet.sendToClient(player);
    }

    public static void send_openATMScreen(ServerPlayer player)
    {
        SyncOpenGUIPacket packet = new SyncOpenGUIPacket();
        packet.guiType = GUIType.ATM_SCREEN;
        packet.sendToClient(player);
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
            case ATM_SCREEN:
                BankSystemClientHooks.openATMScreen();
                break;
        }
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(guiType);

        if(targetPlayerUUID == null)
            targetPlayerUUID = new UUID(0,0);
        buf.writeUUID(targetPlayerUUID);
    }

    @Override
    public void decode(FriendlyByteBuf buf) {
        guiType = buf.readEnum(GUIType.class);
        targetPlayerUUID = buf.readUUID();
    }
}
