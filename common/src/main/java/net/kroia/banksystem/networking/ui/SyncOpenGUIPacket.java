package net.kroia.banksystem.networking.ui;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.util.BankSystemClientHooks;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.modutilities.ServerPlayerUtilities;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class SyncOpenGUIPacket extends BankSystemNetworkPacket {
    enum GUIType
    {
        BANK_SYSTEM_MANAGE,
        BANK_ACCOUNT,
        ATM_SCREEN,
        TEST_SCREEN,
    }

    public static final Type<SyncOpenGUIPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "sync_open_gui_packet"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncOpenGUIPacket> STREAM_CODEC = StreamCodec.composite(
            ExtraCodecUtils.enumStreamCodec(GUIType.class), p -> p.guiType,
            ExtraCodecUtils.nullable(UUIDUtil.STREAM_CODEC), p -> p.targetPlayerUUID,
            ByteBufCodecs.INT, p -> p.accountNumber,
            ByteBufCodecs.BOOL, p -> p.isAdminMode,
            SyncOpenGUIPacket::new
    );





    GUIType guiType;
    UUID targetPlayerUUID;
    int accountNumber;
    boolean isAdminMode;


    private SyncOpenGUIPacket() {
        super();
    }

    public SyncOpenGUIPacket(GUIType guiType, UUID targetPlayerUUID, int accountNumber, boolean isAdminMode) {
        super();
        this.guiType = guiType;
        this.targetPlayerUUID = targetPlayerUUID;
        this.accountNumber = accountNumber;
        this.isAdminMode = isAdminMode;
    }
   /* public SyncOpenGUIPacket(RegistryFriendlyByteBuf friendlyByteBuf) {
        super(friendlyByteBuf);
    }*/

    public static void send_openBankSystemManageScreen(ServerPlayer player)
    {
        if(player == null)
            return;
        // check if player is in creative mode
        if(!player.isCreative())
        {
            ServerPlayerUtilities.printToClientConsole(player, BankSystemTextMessages.getNeedCreativeModeForThisScreenMessage());
            return;
        }

        SyncOpenGUIPacket packet = new SyncOpenGUIPacket(GUIType.BANK_SYSTEM_MANAGE, null, 0, false);
        packet.sendToClient(player);
    }
    public static void send_openBankAccountScreen(ServerPlayer player, UUID targetPlayerUUID, int accountNumber, boolean isAdminMode)
    {
        if(player == null)
            return;
        SyncOpenGUIPacket packet = new SyncOpenGUIPacket(GUIType.BANK_ACCOUNT, targetPlayerUUID, accountNumber, isAdminMode);
        packet.sendToClient(player);
    }

    public static void send_openATMScreen(ServerPlayer player)
    {
        if(player == null)
            return;
        SyncOpenGUIPacket packet = new SyncOpenGUIPacket(GUIType.ATM_SCREEN, null, 0, false);
        packet.sendToClient(player);
    }
    public static void send_openTestScreen(ServerPlayer player)
    {
        if(player == null)
            return;
        SyncOpenGUIPacket packet = new SyncOpenGUIPacket(GUIType.TEST_SCREEN, null, 0, false);
        packet.sendToClient(player);
    }

    @Override
    protected void handleOnClient(NetworkManager.PacketContext context)
    {
        switch(guiType)
        {
            case BANK_SYSTEM_MANAGE:
                BankSystemClientHooks.openBankSystemSettingScreen();
                break;
            case BANK_ACCOUNT:
                BankSystemClientHooks.openBankAccountScreen(accountNumber, isAdminMode);
                break;
            case ATM_SCREEN:
                BankSystemClientHooks.openATMScreen();
                break;
            case TEST_SCREEN:
                BankSystemClientHooks.openTestScreen();
                break;
        }
    }

    /*
    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(guiType);

        if(targetPlayerUUID == null)
            targetPlayerUUID = new UUID(0,0);
        buf.writeUUID(targetPlayerUUID);
        buf.writeInt(accountNumber);
        buf.writeBoolean(isAdminMode);
    }

    @Override
    public void decode(FriendlyByteBuf buf) {
        guiType = buf.readEnum(GUIType.class);
        targetPlayerUUID = buf.readUUID();
        accountNumber = buf.readInt();
        isAdminMode = buf.readBoolean();
    }*/

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
