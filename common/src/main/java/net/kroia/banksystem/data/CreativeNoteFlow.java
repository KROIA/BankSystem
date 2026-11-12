package net.kroia.banksystem.data;

import net.kroia.banksystem.banking.company.ShareIntegrity;
import net.kroia.banksystem.banking.ledger.TransactionLedger;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * BS-227 — nets the money notes a creative player creates or deletes through the creative inventory
 * (fed by {@code ServerGamePacketListenerImplMixin}). Moving a note between slots arrives as -n then +n,
 * so changes are summed per player and denomination and only the net is written: as
 * {@link NoteFlowLog#CREATIVE_CREATE} / {@link NoteFlowLog#CREATIVE_DESTROY} rows once the player made no
 * creative change for {@link #IDLE_MS}, on logout, and on server stop. Never throws.
 * BS-235: stamped shares are netted per company the same way; a net creation raises a SHARE_CREATED alert.
 */
public final class CreativeNoteFlow {
    public static final long IDLE_MS = 10_000;

    private static final class Pending {
        final Map<ItemID, Long> net = new HashMap<>();
        /** BS-235: net stamped shares per company id. */
        final Map<Integer, Long> shares = new HashMap<>();
        long lastChangeMs;
    }

    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private CreativeNoteFlow() {}

    /** Adds {@code signedCount} of {@code stack}'s item; non-money stacks are ignored. */
    public static void add(UUID player, ItemStack stack, long signedCount) {
        try {
            if (stack.isEmpty() || signedCount == 0) return;
            Integer companyId = StampedShareItem.getCompanyId(stack);
            if (companyId != null) {
                addShare(player, companyId, signedCount, System.currentTimeMillis());
                return;
            }
            if (!MoneyItem.isMoney(stack)) return;
            ItemID id = ItemIDManager.getItemID(stack);
            if (id.getShort() != ItemID.INVALID_ID.getShort()) add(player, id, signedCount, System.currentTimeMillis());
        } catch (RuntimeException ignored) {
            // diagnostics never affect gameplay
        }
    }

    /** Also the test entry point: explicit ItemID and clock. */
    public static synchronized void add(UUID player, ItemID id, long signedCount, long nowMs) {
        Pending p = PENDING.computeIfAbsent(player, k -> new Pending());
        p.net.merge(id, signedCount, Long::sum);
        p.lastChangeMs = nowMs;
    }

    /** BS-235: stamped shares created (+) or deleted (-) in the creative inventory; also the test entry point. */
    public static synchronized void addShare(UUID player, int companyId, long signedCount, long nowMs) {
        Pending p = PENDING.computeIfAbsent(player, k -> new Pending());
        p.shares.merge(companyId, signedCount, Long::sum);
        p.lastChangeMs = nowMs;
    }

    /** Server tick: writes every player idle for {@link #IDLE_MS}. */
    public static void tick() {
        tick(System.currentTimeMillis());
    }

    public static synchronized void tick(long nowMs) {
        if (PENDING.isEmpty()) return;
        for (Iterator<Map.Entry<UUID, Pending>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Pending> e = it.next();
            if (nowMs - e.getValue().lastChangeMs < IDLE_MS) continue;
            it.remove();
            write(e.getKey(), e.getValue());
        }
    }

    /** Logout: writes this player's pending net now. */
    public static synchronized void flush(UUID player) {
        Pending p = PENDING.remove(player);
        if (p != null) write(player, p);
    }

    /** Server stop: call before the diagnostics DB closes. */
    public static synchronized void flushAll() {
        PENDING.forEach(CreativeNoteFlow::write);
        PENDING.clear();
    }

    private static void write(UUID player, Pending p) {
        p.net.forEach((id, n) -> {
            if (n > 0) NoteFlowLog.INSTANCE.record(NoteFlowLog.CREATIVE_CREATE, player, id, n, null);
            else if (n < 0) NoteFlowLog.INSTANCE.record(NoteFlowLog.CREATIVE_DESTROY, player, id, n, null);
        });
        // BS-235: shares created from nothing are reported to the admins right away
        p.shares.forEach((companyId, n) -> {
            if (n > 0) ShareIntegrity.reportCreated(String.valueOf(TransactionLedger.player(player).name()),
                    companyId, n, "creative inventory");
        });
    }
}
