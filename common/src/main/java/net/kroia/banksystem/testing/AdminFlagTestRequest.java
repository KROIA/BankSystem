package net.kroia.banksystem.testing;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.util.BankSystemGenericRequest;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * BS-209 — test only: a trusted slave sets a player's BankSystem admin flag on the master, the fixture of the slave
 * admin-command tests (the product path, SetBanksystemAdminModeAsync, accepts a slave only while the master syncs the
 * flag with permissions). Lives in {@code testing/} (not compiled in release builds) and is registered by
 * {@link DevBootstrap}. Answered only for a trusted slave without a player sender; anything else gets false.
 */
public class AdminFlagTestRequest extends BankSystemGenericRequest<AdminFlagTestRequest.Input, Boolean> {

    public static final AdminFlagTestRequest INSTANCE = new AdminFlagTestRequest();

    public record Input(UUID player, boolean admin) {
        static final StreamCodec<RegistryFriendlyByteBuf, Input> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Input::player,
                ByteBufCodecs.BOOL, Input::admin,
                Input::new);
    }

    @Override
    public String getRequestTypeID() {
        return AdminFlagTestRequest.class.getName();
    }

    @Override
    public CompletableFuture<Boolean> handleOnMasterServer(Input in, String slaveID, @Nullable UUID playerSender) {
        MinecraftServer server = dev.architectury.utils.GameInstance.getServer();
        var instances = BankSystemModBackend.getInstances_forTesting();
        if (playerSender != null || slaveID == null || slaveID.isEmpty() || isFromUntrustedSlave(slaveID)
                || server == null || instances.SERVER_BANK_MANAGER == null)
            return CompletableFuture.completedFuture(false);
        return CompletableFuture.supplyAsync(() -> {
            ISyncServerBankManager manager = instances.SERVER_BANK_MANAGER.getSync();
            return manager != null && manager.setBanksystemAdminMode(in.player(), in.admin());
        }, server);
    }

    @Override
    public void encodeInput(RegistryFriendlyByteBuf buf, Input input) { Input.CODEC.encode(buf, input); }

    @Override
    public void encodeOutput(RegistryFriendlyByteBuf buf, Boolean output) { ByteBufCodecs.BOOL.encode(buf, output); }

    @Override
    public Input decodeInput(RegistryFriendlyByteBuf buf) { return Input.CODEC.decode(buf); }

    @Override
    public Boolean decodeOutput(RegistryFriendlyByteBuf buf) { return ByteBufCodecs.BOOL.decode(buf); }
}
