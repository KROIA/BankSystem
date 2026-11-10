package net.kroia.banksystem.api.diagnostics;

import net.kroia.banksystem.util.ItemID;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Records money items created or destroyed outside the bank. Rows go to THIS server's diagnostics DB:
 * works locally on every server including slaves (villager trades run there), no master round trip. Never blocks, never throws.
 *
 * @since 2.2.0
 */
public interface INoteFlowLog {
    /**
     * Call on the server thread: {@code villager} is read synchronously, before the write is queued.
     *
     * @param source   namespaced kind, e.g. "stockmarket:villager_pay"
     * @param player   player involved, or null
     * @param item     money ItemID
     * @param amount   signed item count: positive = created, negative = destroyed
     * @param villager the trading villager, or null; BankSystem stores its UUID, dimension id and block position
     * @param context  other free text, or null
     */
    void record(String source, @Nullable UUID player, ItemID item, long amount, @Nullable Entity villager, @Nullable String context);

    /** {@link #record(String, UUID, ItemID, long, Entity, String)} without a villager. */
    default void record(String source, @Nullable UUID player, ItemID item, long amount, @Nullable String context) {
        record(source, player, item, amount, null, context);
    }
}
