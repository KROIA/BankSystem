package net.kroia.banksystem.networking.entity;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation; // mc<=1.21.10
//import net.minecraft.resources.Identifier; // mc>=1.21.11
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * S2C: opens the config screen of the display controller at {@code pos}. Sent by the server once it has
 * checked it can serve the screen (a slave without master refuses), so the client never opens it on its own.
 */
public class OpenDisplayConfigScreenPacket extends BankSystemNetworkPacket {

    public static final Type<OpenDisplayConfigScreenPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "open_display_config_screen")); // mc<=1.21.10
            //Identifier.fromNamespaceAndPath(BankSystemMod.MOD_ID, "open_display_config_screen")); // mc>=1.21.11

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenDisplayConfigScreenPacket> STREAM_CODEC =
            BlockPos.STREAM_CODEC.map(OpenDisplayConfigScreenPacket::new, p -> p.pos).cast();

    private final BlockPos pos;

    public OpenDisplayConfigScreenPacket(BlockPos pos) {
        this.pos = pos;
    }

    public static void send(ServerPlayer player, BlockPos pos) {
        new OpenDisplayConfigScreenPacket(pos).sendToClient(player);
    }

    @Override
    protected void handleOnClient(NetworkManager.PacketContext context) {
        net.kroia.banksystem.util.BankSystemClientHooks.openDisplayConfigScreen(pos);
    }

    @Override
    protected void handleOnServer(NetworkManager.PacketContext context) { /* S2C only */ }

    @Override public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
