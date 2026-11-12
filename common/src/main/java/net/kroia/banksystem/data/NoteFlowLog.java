package net.kroia.banksystem.data;

import net.kroia.banksystem.api.diagnostics.INoteFlowLog;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.util.ItemID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/** Task #124 (B5) — NoteFlow rows in this server's {@link DiagnosticsDatabase}; no-op without a live DB. */
public final class NoteFlowLog implements INoteFlowLog {
    public static final NoteFlowLog INSTANCE = new NoteFlowLog();
    /** Notes go into the converter cache (destroyed, negative). */
    public static final String CONVERTER_IN = "banksystem:converter_in";
    /** Notes come out of the converter cache (created, positive). */
    public static final String CONVERTER_OUT = "banksystem:converter_out";
    /** Net notes a creative player took from the creative inventory (positive); see {@link CreativeNoteFlow}. */
    public static final String CREATIVE_CREATE = "banksystem:creative_create";
    /** Net notes a creative player deleted through the creative inventory (negative); see {@link CreativeNoteFlow}. */
    public static final String CREATIVE_DESTROY = "banksystem:creative_destroy";
    /** Notes handed out by {@code /give} (positive), one row per target player. */
    public static final String GIVE = "banksystem:give";

    private NoteFlowLog() {}

    @Override
    public void record(String source, @Nullable UUID player, ItemID item, long amount, @Nullable Entity villager, @Nullable String context) {
        try {
            DiagnosticsDatabase db = DiagnosticsDatabase.live();
            if (db == null || source == null || item == null) return;
            // Read the entity here, on the caller's thread; only plain values go to the writer thread.
            String villagerUuid = null, dimension = null;
            Integer x = null, y = null, z = null;
            if (villager != null) {
                BlockPos pos = villager.blockPosition();
                villagerUuid = villager.getUUID().toString();
                dimension = villager.level().dimension().location().toString(); // mc<=1.21.10
                //dimension = villager.level().dimension().identifier().toString(); // mc>=1.21.11
                x = pos.getX();
                y = pos.getY();
                z = pos.getZ();
            }
            db.execute("INSERT INTO NoteFlow (ts, source, player_uuid, item_id, amount, villager_uuid, dimension, x, y, z, context) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    System.currentTimeMillis(), source, player == null ? null : player.toString(), (int) item.getShort(),
                    amount, villagerUuid, dimension, x, y, z, context);
        } catch (RuntimeException ignored) {
            // never throws to callers
        }
    }

    /** One row per money denomination; {@code in} = into the cache (negative). Non-money ItemIDs are skipped. */
    public static void converter(boolean in, UUID player, Map<ItemID, Long> counts, @Nullable String context) {
        try {
            counts.forEach((id, n) -> {
                if (n != null && n > 0 && MoneyItem.isMoney(id))
                    INSTANCE.record(in ? CONVERTER_IN : CONVERTER_OUT, player, id, in ? -n : n, context);
            });
        } catch (RuntimeException ignored) {
            // diagnostics never affect gameplay
        }
    }
}
