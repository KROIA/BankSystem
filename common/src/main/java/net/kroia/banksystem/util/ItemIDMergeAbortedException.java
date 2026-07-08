package net.kroia.banksystem.util;

/**
 * Thrown during master-server world load when the effective volatile/deposit-gated
 * component set changed since the world was last normalized <b>and</b> applying the new
 * set would irreversibly merge genuinely distinct ItemIDs (see
 * {@link ItemIDManager#detectCollapseCollisions}).
 * <p>
 * The exception message contains the full merge report: every merge group with its
 * canonical ID, the IDs that would become aliases, the affected item names, the
 * components that make them distinct today, and instructions for the admin (either
 * revert the component-list change, or set {@code CONFIRM_ITEMID_MERGE: true} in
 * {@code settings.json} to approve the merge once).
 * <p>
 * It is intentionally a {@link RuntimeException}: it propagates out of
 * {@code BankSystemDataHandler.loadAll()} through the {@code SERVER_STARTED} lifecycle
 * handler and aborts server startup with a crash report on both Fabric and NeoForge.
 * Nothing has been mutated or saved at the point it is thrown — and
 * {@code BankSystemDataHandler.load_itemIDs()} additionally marks the whole session
 * <b>save-prohibited</b> while this exception passes through it, so the vanilla
 * crash-save ("Saving worlds" after the startup failure) and the shutdown save are
 * suppressed as well. The world data on disk therefore stays exactly as it was.
 */
public class ItemIDMergeAbortedException extends RuntimeException {

    /**
     * @param report the human-readable merge report (becomes the exception/crash message)
     */
    public ItemIDMergeAbortedException(String report) {
        super(report);
    }
}
