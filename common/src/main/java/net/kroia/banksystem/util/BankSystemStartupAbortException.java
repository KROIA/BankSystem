package net.kroia.banksystem.util;

/**
 * Shared supertype of every exception that deliberately <b>aborts master-server startup</b>
 * during BankSystem world-data load, with the guarantee that <b>nothing has been mutated
 * or saved on disk</b> at the point it is thrown.
 * <p>
 * Concrete subtypes:
 * <ul>
 *   <li>{@link ItemIDMergeAbortedException} — the ItemID merge guard detected an
 *       unconfirmed collapse merge (component-set change would fuse distinct items).</li>
 *   <li>{@link ItemIDsNewerFormatException} — a data file was written by a newer
 *       BankSystem build (format version above this build's supported version).</li>
 *   <li>{@link ItemIDRepairRequiredException} — the corruption-repair guard detected a
 *       repairable cent-shift world corruption that requires one-shot admin confirmation
 *       ({@code CONFIRM_ITEMID_REPAIR}).</li>
 * </ul>
 * All subtypes share the same handling contract in
 * {@code BankSystemDataHandler.load_itemIDs()} / {@code load_metadata()}: the affected
 * subsystem is marked NOT_LOADED, the whole session is marked <b>save-prohibited</b>
 * (the vanilla crash-save and the shutdown save are suppressed), and the exception is
 * rethrown so it propagates out of {@code loadAll()} through the {@code SERVER_STARTED}
 * lifecycle handler, aborting startup with a crash report on all loaders. The exception
 * message carries the full human-readable report into the log / crash report.
 * <p>
 * It is intentionally a {@link RuntimeException} so it can traverse the loader lifecycle
 * call chain without checked-exception plumbing.
 */
public class BankSystemStartupAbortException extends RuntimeException {

    /**
     * @param report the human-readable abort report (becomes the exception/crash message)
     */
    public BankSystemStartupAbortException(String report) {
        super(report);
    }
}
