package net.kroia.banksystem.networking.entity;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Task #47 (v2.0.8) — S2C payload delivering the list of companies the caller may bind
 * a Share Stamper to. Sent from the server-side {@code useWithoutItem} handler when the
 * clicked stamper is unbound and the caller has at least one manageable company.
 */
public class OpenStamperBindScreenPacket extends BankSystemNetworkPacket {

    public record Entry(int companyId, String name) {}

    public static final Type<OpenStamperBindScreenPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "open_stamper_bind_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenStamperBindScreenPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBlockPos(p.pos);
                        buf.writeVarInt(p.entries.size());
                        for (Entry e : p.entries) {
                            buf.writeVarInt(e.companyId);
                            buf.writeUtf(e.name);
                        }
                    },
                    buf -> {
                        BlockPos pos = buf.readBlockPos();
                        int n = buf.readVarInt();
                        List<Entry> out = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            int id = buf.readVarInt();
                            String name = buf.readUtf();
                            out.add(new Entry(id, name));
                        }
                        return new OpenStamperBindScreenPacket(pos, out);
                    });

    public final BlockPos pos;
    public final List<Entry> entries;

    public OpenStamperBindScreenPacket(BlockPos pos, List<Entry> entries) {
        this.pos = pos;
        this.entries = entries;
    }

    public static void send(net.minecraft.server.level.ServerPlayer player, BlockPos pos, List<Entry> entries) {
        new OpenStamperBindScreenPacket(pos, entries).sendToClient(player);
    }

    @Override
    protected void handleOnClient(NetworkManager.PacketContext context) {
        net.minecraft.client.Minecraft.getInstance().execute(() ->
                net.minecraft.client.Minecraft.getInstance().setScreen(
                        new net.kroia.banksystem.screen.custom.StamperBindScreen(pos, entries)));
    }

    @Override
    protected void handleOnServer(NetworkManager.PacketContext context) { /* S2C only */ }

    @Override public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
