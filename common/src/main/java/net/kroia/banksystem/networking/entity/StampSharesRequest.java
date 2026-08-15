package net.kroia.banksystem.networking.entity;

import dev.architectury.networking.NetworkManager;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.minecraft.entity.custom.ShareStamperBlockEntity;
import net.kroia.banksystem.util.BankSystemNetworkPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;

/**
 * Task #47 (v2.1.0) — C2S request to mutate a {@link ShareStamperBlockEntity}. All ops
 * gated by proximity + MANAGE permission on master.
 */
public class StampSharesRequest extends BankSystemNetworkPacket {

    // Task v2.1.0 — QUEUE_STAMPS + TOGGLE_HOPPER_REDEEM retired. Replaced by
    // SET_PROCESSING (Start/Stop toggle) and SET_AUTO_IO (bit0=autoInput,
    // bit1=autoOutput carried in `count`).
    public enum Op { UNUSED_LEGACY_0, SET_MODE, UNUSED_LEGACY_1, BIND_COMPANY, SET_PROCESSING, SET_AUTO_IO }

    public static final Type<StampSharesRequest> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "stamp_shares_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StampSharesRequest> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, p -> p.pos,
            ByteBufCodecs.VAR_INT, p -> p.op.ordinal(),
            ByteBufCodecs.VAR_INT, p -> p.count,
            ByteBufCodecs.BOOL, p -> p.flagValue,
            StampSharesRequest::new);

    final BlockPos pos;
    final Op op;
    final int count;
    final boolean flagValue;

    public StampSharesRequest(BlockPos pos, int opOrdinal, int count, boolean flagValue) {
        this.pos = pos;
        Op[] vals = Op.values();
        this.op = vals[Math.max(0, Math.min(vals.length - 1, opOrdinal))];
        this.count = count;
        this.flagValue = flagValue;
    }

    public static void send(BlockPos pos, Op op, int count, boolean flagValue) {
        new StampSharesRequest(pos, op.ordinal(), count, flagValue).sendToServer();
    }

    @Override
    protected boolean needsRoutingToMaster() { return false; }

    @Override
    protected void handleOnServer(NetworkManager.PacketContext context) {
        ServerPlayer player = (ServerPlayer) context.getPlayer();
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                > BankSystemMod.MAX_INTERACT_DISTANCE_SQR) return;
        BlockEntity be = player.level().getBlockEntity(pos);
        if (!(be instanceof ShareStamperBlockEntity stamper)) return;

        if (op == Op.BIND_COMPANY) {
            // MANAGE gate against the target companyId (carried in `count`).
            int targetCompanyId = count;
            if (!stamperCanBindTo(stamper, player, targetCompanyId)) return;
            stamper.bind(targetCompanyId, player.getUUID());
            return;
        }
        if (!stamper.hasManagePermission(player.getUUID())) return;
        switch (op) {
            case SET_MODE -> stamper.setMode(count == 1
                    ? ShareStamperBlockEntity.Mode.REDEEM
                    : ShareStamperBlockEntity.Mode.STAMP);
            case SET_PROCESSING -> stamper.setProcessing(flagValue);
            case SET_AUTO_IO -> stamper.setAutoIo((count & 1) != 0, (count & 2) != 0);
            default -> {}
        }
    }

    private static boolean stamperCanBindTo(ShareStamperBlockEntity stamper,
                                            ServerPlayer player, int targetCompanyId) {
        net.kroia.banksystem.banking.company.CompanyManager cm =
                net.kroia.banksystem.banking.company.CompanyManager.get();
        if (cm == null) return false;
        net.kroia.banksystem.banking.company.Company c = cm.getById(targetCompanyId);
        if (c == null) return false;
        if (c.isFounder(player.getUUID())) return true;
        if (BACKEND_INSTANCES == null || BACKEND_INSTANCES.SERVER_BANK_MANAGER == null) return false;
        net.kroia.banksystem.api.bankmanager.IServerBankManager bm =
                BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        if (bm == null) return false;
        if (bm.isBanksystemAdmin(player.getUUID())) return true;
        net.kroia.banksystem.api.bankaccount.IServerBankAccount acc = bm.getBankAccount(c.getBankAccountNr());
        return acc != null && acc.hasPermission(player.getUUID(),
                net.kroia.banksystem.banking.BankPermission.MANAGE);
    }

    @Override public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
