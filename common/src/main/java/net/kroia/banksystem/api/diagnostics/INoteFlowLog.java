package net.kroia.banksystem.api.diagnostics;

import net.kroia.banksystem.util.ItemID;
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
     * @param source  namespaced kind, e.g. "stockmarket:villager_pay"
     * @param player  player involved, or null
     * @param item    money ItemID
     * @param amount  signed item count: positive = created, negative = destroyed
     * @param context free text: where / which villager
     */
    void record(String source, @Nullable UUID player, ItemID item, long amount, @Nullable String context);
}
