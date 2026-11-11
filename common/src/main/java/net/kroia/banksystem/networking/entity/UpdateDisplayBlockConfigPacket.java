package net.kroia.banksystem.networking.entity;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.api.bankmanager.IAsyncBankManager;
import net.kroia.banksystem.minecraft.entity.custom.BankSystemDisplayBlockEntity;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation; // mc<=1.21.10
//import net.minecraft.resources.Identifier; // mc>=1.21.11
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class UpdateDisplayBlockConfigPacket extends BankSystemNetworkPacket {

    public static final Type<UpdateDisplayBlockConfigPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "update_display_block_config_packet")); // mc<=1.21.10
    //public static final Type<UpdateDisplayBlockConfigPacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath(BankSystemMod.MOD_ID, "update_display_block_config_packet")); // mc>=1.21.11

    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateDisplayBlockConfigPacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, p -> p.pos,
            ByteBufCodecs.STRING_UTF8, p -> p.displayType,
            ByteBufCodecs.INT, p -> p.accountNumber,
            UpdateDisplayBlockConfigPacket::new
    );

    private final BlockPos pos;
    private final String displayType;
    private final int accountNumber;

    public UpdateDisplayBlockConfigPacket(BlockPos pos, String displayType, int accountNumber) {
        super();
        this.pos = pos;
        this.displayType = displayType;
        this.accountNumber = accountNumber;
    }

    @Override
    protected boolean needsRoutingToMaster() { return false; }

    public static void sendToServer(BlockPos pos, String displayType, int accountNumber) {
        new UpdateDisplayBlockConfigPacket(pos, displayType, accountNumber).sendToServer();
    }

    @Override
    protected void handleOnServer(NetworkManager.PacketContext context) {
        ServerPlayer player = (ServerPlayer) context.getPlayer();
        if (player.distanceToSqr(this.pos.getX() + 0.5, this.pos.getY() + 0.5, this.pos.getZ() + 0.5) > BankSystemMod.MAX_INTERACT_DISTANCE_SQR)
            return;
        BankSystemDisplayBlockEntity.DisplayType type = BankSystemDisplayBlockEntity.DisplayType.fromId(this.displayType);
        if (type == BankSystemDisplayBlockEntity.DisplayType.NONE || this.accountNumber <= 0
                || !(player.level().getBlockEntity(this.pos) instanceof BankSystemDisplayBlockEntity))
            return;
        // The display shows the account's balances to everyone nearby, so only someone who may read it can pick it.
        mayShowAccount(player.getUUID(), this.accountNumber).thenAccept(allowed -> player.level().getServer().execute(() -> {
            if (!allowed) {
                warn("Player " + player.getName().getString() + " may not show account " + this.accountNumber + " on a display");
                return;
            }
            if (player.level().getBlockEntity(this.pos) instanceof BankSystemDisplayBlockEntity displayEntity)
                displayEntity.setConfig(type, this.accountNumber);
        }));
    }

    /** True if {@code player} is a member or a BankSystem admin of the account. Works on master and slaves. */
    public static CompletableFuture<Boolean> mayShowAccount(UUID player, int accountNumber) {
        IAsyncBankManager manager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getAsync();
        return manager.getBankAccountNumbersAsync(player)
                .thenCombine(manager.isBanksystemAdminAsync(player),
                        (accounts, admin) -> Boolean.TRUE.equals(admin) || (accounts != null && accounts.contains(accountNumber)))
                .exceptionally(ex -> false);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
