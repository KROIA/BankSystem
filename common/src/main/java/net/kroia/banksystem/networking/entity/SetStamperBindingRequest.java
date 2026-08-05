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
 * Task #47 (v2.0.8) — C2S packet: bind the Share Stamper at {@code pos} to companyId.
 * Master validates MANAGE on the target company + proximity.
 */
public class SetStamperBindingRequest extends BankSystemNetworkPacket {

    public static final Type<SetStamperBindingRequest> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "set_stamper_binding"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetStamperBindingRequest> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, p -> p.pos,
                    ByteBufCodecs.VAR_INT, p -> p.companyId,
                    SetStamperBindingRequest::new);

    final BlockPos pos;
    final int companyId;

    public SetStamperBindingRequest(BlockPos pos, int companyId) {
        this.pos = pos;
        this.companyId = companyId;
    }

    public static void send(BlockPos pos, int companyId) {
        new SetStamperBindingRequest(pos, companyId).sendToServer();
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
        net.kroia.banksystem.banking.company.CompanyManager cm =
                net.kroia.banksystem.banking.company.CompanyManager.get();
        if (cm == null) return;

        // Unbind path — companyId == 0 means clear binding. MANAGE-gated on the
        // currently-bound company. Contents are NOT cleared; block stays in world.
        if (companyId == 0) {
            int cur = stamper.getBoundCompanyId();
            if (cur < 0) return; // nothing to do
            net.kroia.banksystem.banking.company.Company boundCompany = cm.getById(cur);
            boolean allowedUnbind = boundCompany != null && stamper.hasManagePermission(player.getUUID());
            if (!allowedUnbind) return;
            stamper.unbind();
            net.minecraft.world.level.block.state.BlockState st0 = player.level().getBlockState(pos);
            player.level().sendBlockUpdated(pos, st0, st0, 3);
            return;
        }

        if (stamper.getBoundCompanyId() >= 0) return; // already bound; use unbind path first
        net.kroia.banksystem.banking.company.Company company = cm.getById(companyId);
        if (company == null) return;
        // MANAGE gate: founder OR MANAGE on bound bank account OR banksystem admin.
        boolean allowed = company.isFounder(player.getUUID());
        if (!allowed && BACKEND_INSTANCES != null && BACKEND_INSTANCES.SERVER_BANK_MANAGER != null) {
            net.kroia.banksystem.api.bankmanager.IServerBankManager bm =
                    BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
            if (bm != null) {
                if (bm.isBanksystemAdmin(player.getUUID())) allowed = true;
                else {
                    net.kroia.banksystem.api.bankaccount.IServerBankAccount acc =
                            bm.getBankAccount(company.getBankAccountNr());
                    if (acc != null && acc.hasPermission(player.getUUID(),
                            net.kroia.banksystem.banking.BankPermission.MANAGE)) allowed = true;
                }
            }
        }
        if (!allowed) return;
        stamper.bind(companyId, player.getUUID());
        // Force BE sync to nearby clients so their tooltip / getDestroyProgress refresh.
        net.minecraft.world.level.block.state.BlockState st = player.level().getBlockState(pos);
        player.level().sendBlockUpdated(pos, st, st, 3);

        // Task v2.0.8 follow-up — auto-open the main Share Stamper GUI after a
        // successful bind so the player doesn't have to right-click the block a
        // second time. Only on the bind path (companyId != 0); the unbind path
        // above returns early. The viewer lock stays with this player through the
        // handoff — the incoming ShareStamperContainerMenu takes over ownership
        // via tryAcquireViewer's same-UUID equals branch, and stopOpen releases
        // it when the container menu eventually closes. The client-side
        // StamperBindScreen suppresses its CloseStamperBindScreenPacket so it
        // doesn't clobber the lock mid-handoff.
        if (!stamper.tryAcquireViewer(player.getUUID())) return;
        dev.architectury.registry.menu.MenuRegistry.openExtendedMenu(player, stamper, buf -> buf.writeBlockPos(pos));
    }

    @Override public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
