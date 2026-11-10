package net.kroia.banksystem.data;

import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.networking.ExtraCodecUtils;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Task #124 (B2) — slave-side TransferJournal rows in {@link DiagnosticsDatabase}: value moving between a slave
 * world and the master bank. Written on SLAVES ONLY (master and single servers write nothing); never updated or
 * deleted. Full columns only on REQUESTED (ASYNC_BANK, CONVERTER_DEPOSIT) and on the DROP rows; follow-up rows
 * carry transfer_id, ts, step, detail and items_json only when partial. Amounts are raw units as in
 * TransactionLog.amount. Never throws, never blocks.
 */
public final class TransferJournal {
    public static final String TO_MASTER = "TO_MASTER", FROM_MASTER = "FROM_MASTER";
    public static final String ASYNC_BANK = "ASYNC_BANK", CONVERTER_DEPOSIT = "CONVERTER_DEPOSIT", DROP = "DROP";
    public static final String REQUESTED = "REQUESTED", CONFIRMED = "CONFIRMED", DELIVERED = "DELIVERED",
            RETURNED = "RETURNED", TIMED_OUT = "TIMED_OUT", FAILED = "FAILED";

    private static final String INSERT = "INSERT INTO TransferJournal (transfer_id, ts, direction, path, step, account_number, "
            + "player_uuid, items_json, ledger_type, context, detail) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    private TransferJournal() {}

    /** Journal fields a caller attaches to one transfer (see {@code TransactionLedger.tagged}). */
    public record Ref(String transferId, @Nullable String context) {}

    public static Ref ref(@Nullable String context) {
        return new Ref(newTransferId(), context);
    }

    /** The server that starts a transfer creates the id: the slave for ASYNC_BANK / CONVERTER_DEPOSIT, the master for DROP. */
    public static String newTransferId() {
        return UUID.randomUUID().toString();
    }

    private static final StreamCodec<ByteBuf, UUID> UUID_OR_NULL = ExtraCodecUtils.nullable(UUIDUtil.STREAM_CODEC);

    /**
     * Task #125 — transfer_id on the wire, always the LAST field of a request (16-byte UUID). An older peer's
     * decoder ignores the trailing bytes; a payload from an older peer has none left and decodes to null. An id
     * that is not a canonical UUID string goes out as null (debug log), never throws.
     */
    public static final StreamCodec<ByteBuf, String> ID_CODEC = StreamCodec.of(
            (buf, id) -> UUID_OR_NULL.encode(buf, toUuid(id)),
            buf -> {
                if (!buf.isReadable()) return null;
                UUID u = UUID_OR_NULL.decode(buf);
                return u == null ? null : u.toString();
            });

    private static @Nullable UUID toUuid(@Nullable String id) {
        if (id == null) return null;
        try {
            UUID u = UUID.fromString(id);
            if (u.toString().equals(id)) return u; // non-canonical forms would not match the journal rows
        } catch (IllegalArgumentException ignored) {
        }
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null)
            BACKEND_INSTANCES.LOGGER.debug("[TransferJournal] transfer id is not a UUID, sent as null: " + id);
        return null;
    }

    public static void requested(Ref ref, String direction, String path, @Nullable Integer account, @Nullable UUID player,
                                 Map<ItemID, Long> rawItems, @Nullable Integer ledgerType) {
        write(ref.transferId(), direction, path, REQUESTED, account, player, json(rawItems), ledgerType, ref.context(), null);
    }

    /** Follow-up row. @param partialItems raw units, only when the step moved part of the transfer; else null */
    public static void step(String transferId, String step, @Nullable Map<ItemID, Long> partialItems, @Nullable String detail) {
        write(transferId, null, null, step, null, null, partialItems == null ? null : json(partialItems), null, null, detail);
    }

    /**
     * The slave's DROP rows: DELIVERED for what was dropped, FAILED for what was not (both when partial).
     * @param items      item counts the master asked for
     * @param notDropped item counts left over
     */
    public static void drop(String transferId, UUID player, Map<ItemID, Long> items, Map<ItemID, Long> notDropped) {
        Map<ItemID, Long> delivered = new HashMap<>(), failed = new HashMap<>();
        items.forEach((id, n) -> {
            long left = Math.max(0L, Math.min(n, notDropped.getOrDefault(id, 0L)));
            if (n - left > 0) delivered.put(id, rawUnits(id, n - left));
            if (left > 0) failed.put(id, rawUnits(id, left));
        });
        if (!delivered.isEmpty())
            write(transferId, FROM_MASTER, DROP, DELIVERED, null, player, json(delivered), null, null, null);
        if (!failed.isEmpty())
            write(transferId, FROM_MASTER, DROP, FAILED, null, player, json(failed), null, null, null);
    }

    /** Raw units of {@code count} physical items: a note's worth in cents, else count x ITEM_FRACTION_SCALE_FACTOR. */
    public static long rawUnits(ItemID id, long count) {
        try {
            long per = id.getStack().getItem() instanceof MoneyItem m && MoneyItem.isMoney(id)
                    ? m.worth() : BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
            return Math.multiplyExact(count, per);
        } catch (RuntimeException e) {
            return count; // overflow / unknown item: keep the count rather than nothing
        }
    }

    /** Raw item counts per denomination to raw units. */
    public static Map<ItemID, Long> rawUnits(Map<ItemID, Long> counts) {
        Map<ItemID, Long> out = new HashMap<>();
        counts.forEach((id, n) -> out.put(id, rawUnits(id, n)));
        return out;
    }

    static String json(Map<ItemID, Long> items) {
        JsonObject o = new JsonObject();
        items.forEach((id, n) -> o.addProperty(Short.toString(id.getShort()), n));
        return o.toString();
    }

    private static void write(String transferId, @Nullable String direction, @Nullable String path, String step,
                              @Nullable Integer account, @Nullable UUID player, @Nullable String itemsJson,
                              @Nullable Integer ledgerType, @Nullable String context, @Nullable String detail) {
        try {
            if (BACKEND_INSTANCES == null || !BACKEND_INSTANCES.isSlaveServer) return;
            DiagnosticsDatabase db = BACKEND_INSTANCES.DIAGNOSTICS_DB;
            if (db == null) return;
            db.execute(INSERT, transferId, System.currentTimeMillis(), direction, path, step, account,
                    player == null ? null : player.toString(), itemsJson, ledgerType, context, detail);
        } catch (RuntimeException ignored) {
            // best-effort: diagnostics never affect gameplay
        }
    }
}
