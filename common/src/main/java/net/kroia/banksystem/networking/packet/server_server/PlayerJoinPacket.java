package net.kroia.banksystem.networking.packet.server_server;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.kroia.modutilities.networking.multi_server.ForwardPacketContext;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class PlayerJoinPacket extends BankSystemNetworkPacket {

    public static final Type<PlayerJoinPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "player_join_packet"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerJoinPacket> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, p->p.playerUUID,
            ByteBufCodecs.STRING_UTF8, p -> p.playerName,
            PlayerJoinPacket::new
    );


    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }


    public final UUID playerUUID;
    public final String playerName;

    public PlayerJoinPacket(UUID playerUUID, String playerName) {
        this.playerUUID = playerUUID;
        this.playerName = playerName;
    }

    public static boolean sendPacketToMaster(UUID playerUUID, String playerName) {
        PlayerJoinPacket  packet = new PlayerJoinPacket(playerUUID, playerName);
        return packet.sendToMaster(playerUUID);
    }

    @Override
    protected void handleOnMaster(ForwardPacketContext context)
    {
        ISyncServerBankManager bankManager = getSyncBankManager();
        bankManager.onPlayerJoin(playerUUID, playerName);
    };
}
