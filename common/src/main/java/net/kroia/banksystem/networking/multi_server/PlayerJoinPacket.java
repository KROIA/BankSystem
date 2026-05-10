package net.kroia.banksystem.networking.multi_server;

/*
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
*/