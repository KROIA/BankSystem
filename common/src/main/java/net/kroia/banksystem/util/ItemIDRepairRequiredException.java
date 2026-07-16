package net.kroia.banksystem.util;

/**
 * Thrown during master-server world load when the ItemID corruption-repair guard
 * ({@code ItemIDManager.applyCorruptionRepairGuard()} / {@link ItemIDWorldRepair})
 * detected a repairable <b>cent-shift world corruption</b> — an {@code ItemIDs.nbt} that
 * was overwritten by a pre-v2.0.3 buggy build with a fresh cent-shifted default mapping
 * while all other world data (bank balances, chunk block entities, StockMarket saves,
 * balance-history DB) still references the old shorts — and the admin has not yet
 * confirmed the repair via the one-shot {@code CONFIRM_ITEMID_REPAIR} setting.
 * <p>
 * The exception message carries the <b>full repair report</b>
 * ({@link ItemIDWorldRepair#buildRepairReport}): what was detected, the complete proposed
 * short→item remap table, the mixed-epoch caveat for data written since the corruption,
 * and the admin's two options (restore {@code ItemIDs.nbt} from a world backup, or set
 * {@code CONFIRM_ITEMID_REPAIR: true} to apply the repair once).
 * <p>
 * Nothing has been mutated at the point it is thrown — the guard evaluates the repair
 * plan on snapshot copies only. {@code BankSystemDataHandler.load_itemIDs()} marks the
 * session save-prohibited while this exception passes through (see
 * {@link BankSystemStartupAbortException}), so the corrupted-but-consistent state on disk
 * stays exactly as it was until the admin decides.
 */
public class ItemIDRepairRequiredException extends BankSystemStartupAbortException {

    /**
     * @param report the full human-readable repair report (becomes the exception/crash message)
     */
    public ItemIDRepairRequiredException(String report) {
        super(report);
    }
}
