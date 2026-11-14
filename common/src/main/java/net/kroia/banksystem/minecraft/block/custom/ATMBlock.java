package net.kroia.banksystem.minecraft.block.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.networking.ui.SyncOpenGUIPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class ATMBlock  extends TerminalBlock {

    /**
     * BS-258: per player, the ATM block their ATM screen was opened at on this server (gone when opened by the ATM
     * software). The converter commit and banknote withdraw write it as the ledger rows' block position.
     */
    // ponytail: one entry per player who used an ATM, never pruned; prune on logout if the player count gets huge
    private static final java.util.Map<java.util.UUID, net.kroia.banksystem.banking.ledger.TransactionLedger.LedgerPos> OPENED_AT = new java.util.concurrent.ConcurrentHashMap<>();

    /** @return the ATM block {@code player} opened their ATM screen at, or null (software, or never opened here). */
    public static @org.jetbrains.annotations.Nullable net.kroia.banksystem.banking.ledger.TransactionLedger.LedgerPos openedAt(java.util.UUID player) {
        return OPENED_AT.get(player);
    }

    /** Records ({@code pos} non-null) or clears where {@code player} opened the ATM screen. */
    public static void setOpenedAt(java.util.UUID player, @org.jetbrains.annotations.Nullable net.kroia.banksystem.banking.ledger.TransactionLedger.LedgerPos pos) {
        if (pos == null) OPENED_AT.remove(player);
        else OPENED_AT.put(player, pos);
    }

    public static final String NAME = "atm_block";

    public ATMBlock() {
        super(BankSystemMod.id(NAME));
    }


    @Override
    public void openGui(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {

        if (level.isClientSide())
            return;

        // open screen
        if (player instanceof ServerPlayer sPlayer) {
            setOpenedAt(sPlayer.getUUID(), net.kroia.banksystem.banking.ledger.TransactionLedger.LedgerPos.of(level, pos)); // BS-258
            SyncOpenGUIPacket.send_openATMScreen(sPlayer);
        }
    }
}
