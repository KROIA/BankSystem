package net.kroia.banksystem.util;

/**
 * Thrown during master-server world load when a BankSystem data file carries a
 * {@link BankSystemSaveFormat#KEY_FORMAT_VERSION format version} <b>above</b> the highest
 * version this build understands — i.e. the world was last saved by a <b>newer</b>
 * BankSystem build. Loading such a file could silently misinterpret remapped or
 * re-encoded data, so the load refuses <b>before mutating any static state</b>.
 * <p>
 * The exception message names the file's version, the highest supported version, the
 * running mod version, and instructs the admin to update the mod. Because it is thrown
 * before anything was loaded, and the {@code BankSystemDataHandler} save gates mark the
 * whole session save-prohibited while it passes through, the on-disk file written by the
 * newer build stays byte-identical — a temporary downgrade can never corrupt the world.
 *
 * @see BankSystemSaveFormat forward-compatibility contract
 * @see BankSystemStartupAbortException shared abort-handling contract
 */
public class ItemIDsNewerFormatException extends BankSystemStartupAbortException {

    /**
     * @param report the human-readable refusal report (becomes the exception/crash message)
     */
    public ItemIDsNewerFormatException(String report) {
        super(report);
    }
}
