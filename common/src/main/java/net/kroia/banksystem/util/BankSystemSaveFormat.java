package net.kroia.banksystem.util;

/**
 * Central registry of the on-disk <b>save format versions</b> used by BankSystem's world
 * data files ({@code ItemIDs.nbt}, {@code Meta_data.nbt}).
 * <p>
 * Every file carries an integer under {@link #KEY_FORMAT_VERSION}. Files written before
 * versioning existed have <b>no</b> such key — the absence of the key implies
 * {@link #ITEM_IDS_FORMAT_LEGACY} (format 1). The loaders treat a missing key as the
 * legacy format and re-save the file in the current format once (one-shot conversion).
 * <p>
 * <b>Bump policy:</b> bump a {@code *_FORMAT_CURRENT} constant only when a format change
 * would be <b>misread</b> by older code — i.e. an older build would silently load wrong
 * data (remapped shorts, re-interpreted keys, changed value semantics). Changes that an
 * older build would merely <i>ignore</i> (a new optional key it does not know about) do
 * <b>not</b> require a bump; NBT readers skip unknown keys safely.
 * <p>
 * <b>Forward-compatibility contract:</b> a loader accepts every file whose version is
 * {@code <=} its own {@code *_FORMAT_CURRENT} (older formats are migrated tolerantly) and
 * <b>refuses</b> any file whose version is {@code >} its {@code *_FORMAT_CURRENT} — such a
 * file was written by a newer BankSystem build and its content cannot be interpreted
 * safely. The refusal aborts server startup <b>before</b> any state is mutated and with
 * all saves prohibited, so the newer-format file on disk stays byte-identical (see
 * {@link ItemIDsNewerFormatException} / {@link BankSystemStartupAbortException}).
 */
public final class BankSystemSaveFormat {

    /** NBT key under which the format version integer is stored in each data file. */
    public static final String KEY_FORMAT_VERSION = "formatVersion";

    /**
     * NBT key inside {@code ItemIDs.nbt} holding <b>quarantined alias evidence</b>: raw
     * alias pairs ({@code from}/{@code to} shorts, same entry shape as {@code itemIDAliases})
     * that are corruption <i>evidence</i> for the cent-shift world-repair guard
     * ({@link ItemIDWorldRepair}) but can NOT live in the runtime alias table because their
     * source short is also a live map key (the {@code putAlias} write-side invariant rejects
     * them at load). Without this key, the one-shot post-load re-save would silently strip
     * the rejected fingerprint aliases from the file on the very first startup — destroying
     * the only evidence that the corruption ever happened. Quarantined pairs are re-read on
     * every load and fed into detection alongside the persisted alias table; they are
     * cleared when a confirmed repair is applied.
     * <p>
     * <b>No format bump required</b> (see the bump policy above): older builds simply
     * ignore the unknown key. <b>Known limitations with v2.0.3:</b> that build already has
     * the {@code putAlias} load guard and its one-shot re-save, so a world opened even once
     * under v2.0.3 has its rejected fingerprint aliases stripped from {@code itemIDAliases}
     * <i>before</i> this quarantine mechanism ever sees them — such worlds are permanently
     * evidence-stripped and this guard cannot detect their corruption (only symptom-based
     * manual diagnosis remains). Likewise, a downgrade to v2.0.3 drops the quarantine key
     * on its next save (it does not know to re-write it), losing evidence that was already
     * quarantined. The quarantine therefore protects worlds whose first post-corruption
     * startup happens on v2.0.4 or later.
     */
    public static final String KEY_QUARANTINED_ALIASES = "quarantinedAliases";

    /**
     * NBT key inside {@code Meta_data.nbt} holding the <b>renumbering-tripwire digest</b>:
     * a compact list of {@code {s: short, n: item registry name}} entries describing the
     * short→item mapping the registry had at the last successful save (see
     * {@code ItemIDManager.buildShortNameDigest()}). On load the digest is diffed against
     * the live post-load registry; every persisted short that now resolves to a DIFFERENT
     * item is ERROR-reported (report-only — the generic tripwire for the "shorts silently
     * changed meaning" bug class). Written on every {@code save_metadata()}, so legitimate
     * renames (admin-confirmed collapse merges, applied world repairs) update the digest on
     * the next save and warn at most once. Absent on older saves → the diff is silent.
     * <p>
     * <b>No format bump required</b> (see the bump policy above): older builds simply
     * ignore the unknown key — nothing is misread — and they drop it on their next save,
     * which merely disables the tripwire until a current build saves again.
     */
    public static final String KEY_ITEM_ID_DIGEST = "itemIDShortNameDigest";

    /**
     * Implicit format of {@code ItemIDs.nbt} files that carry <b>no</b>
     * {@link #KEY_FORMAT_VERSION} key at all (every file written before v2.0.4).
     * Never written to disk — it exists only as the loader-side default for absent keys.
     */
    public static final int ITEM_IDS_FORMAT_LEGACY = 1;

    /**
     * Current format of {@code ItemIDs.nbt}. Format 2 = the first explicitly stamped
     * format: item map + alias table + monotonic {@code nextShortCounter}, written by
     * v2.0.4 and later.
     */
    public static final int ITEM_IDS_FORMAT_CURRENT = 2;

    /**
     * Current format of {@code Meta_data.nbt}. Format 2 = the first explicitly stamped
     * format: mod version string, applied component set, optional repair audit record.
     * Files without the key are legacy format 1 and are re-stamped by the next
     * {@code save_metadata()} call.
     */
    public static final int META_DATA_FORMAT_CURRENT = 2;

    /** Static constants only — never instantiated. */
    private BankSystemSaveFormat() {
    }
}
