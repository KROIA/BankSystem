package net.kroia.banksystem.api.currency;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * A persistable, opaque reference to an external currency-mod account.
 * <p>
 * Instances are handed to BankSystem by an {@link ExternalCurrencyProvider} via
 * {@link ExternalCurrencyProvider#listBindableAccounts(java.util.UUID)}, are shown
 * in the binding UI, and — once the user picks one — are stored in the
 * BankSystem savedata so the binding can be re-opened on the next world load
 * (see the {@code BankAccountBindings} subsystem in Stage 2).
 * <p>
 * <b>Persistence stability contract.</b> Instances of this record are serialized to
 * NBT and written to world savedata. Field names in the NBT layout and the
 * semantics of {@link #providerId()} and {@link #accountKey()} MUST remain stable
 * across mod versions: an existing bound account must still resolve after a mod
 * update. Cosmetic fields ({@link #label()}, {@link #shared()}) may be refreshed
 * whenever the binding is re-opened; the {@code (providerId, accountKey)} pair is
 * the durable identity.
 * <p>
 * The {@link #accountKey()} is provider-specific and opaque to BankSystem — it is
 * whatever string the provider needs to re-open the same account. Common shapes:
 * a UUID string, a numeric ID, or a mod-defined composite key.
 *
 * @param providerId  Stable id of the owning provider (matches
 *                    {@link ExternalCurrencyProvider#providerId()}). Never {@code null}
 *                    or empty.
 * @param accountKey  Provider-specific opaque identifier used to re-open the account.
 *                    Never {@code null} or empty.
 * @param label       Human-readable display name for the UI (e.g. account owner,
 *                    account nickname). May be updated on refresh; do not treat as
 *                    part of identity. Never {@code null}; use an empty string if
 *                    the provider has no label.
 * @param shared      {@code true} if this external account is co-owned / multi-user
 *                    on the provider side. BankSystem uses this to gate which
 *                    BankSystem account kinds may bind to it (personal vs. shared).
 *
 * @since 2.0.5
 */
public record ExternalAccountRef(
        @NotNull String providerId,
        @NotNull String accountKey,
        @NotNull String label,
        boolean shared
) {

    /**
     * Network codec for wire transport (Stage 3, Task #33).
     * <p>
     * Fields are encoded as plain UTF-8 strings + a boolean — small, allocation-free, and does
     * not depend on registry access. Used by the currency-binding ARRS request classes
     * (list-bindable-accounts response, bind-request input) to move refs between master, slave,
     * and client without going through the NBT layer.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, ExternalAccountRef> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ExternalAccountRef::providerId,
                    ByteBufCodecs.STRING_UTF8, ExternalAccountRef::accountKey,
                    ByteBufCodecs.STRING_UTF8, ExternalAccountRef::label,
                    ByteBufCodecs.BOOL, ExternalAccountRef::shared,
                    ExternalAccountRef::new
            );

    /** NBT key holding the {@link #providerId()} string. */
    public static final String NBT_KEY_PROVIDER_ID = "providerId";
    /** NBT key holding the {@link #accountKey()} string. */
    public static final String NBT_KEY_ACCOUNT_KEY = "accountKey";
    /** NBT key holding the {@link #label()} string. */
    public static final String NBT_KEY_LABEL = "label";
    /** NBT key holding the {@link #shared()} boolean. */
    public static final String NBT_KEY_SHARED = "shared";

    /**
     * Compact constructor with null / blank guards. {@code providerId} and
     * {@code accountKey} must be non-empty; {@code label} may be empty but not
     * {@code null}.
     */
    public ExternalAccountRef {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(accountKey, "accountKey");
        Objects.requireNonNull(label, "label");
        if (providerId.isEmpty())
            throw new IllegalArgumentException("providerId must not be empty");
        if (accountKey.isEmpty())
            throw new IllegalArgumentException("accountKey must not be empty");
    }

    /**
     * Serializes this reference into a new {@link CompoundTag}.
     * <p>
     * The layout is fixed by the {@code NBT_KEY_*} constants on this class and is
     * covered by the class-level persistence-stability contract.
     *
     * @return a new {@link CompoundTag} carrying all four fields.
     */
    public @NotNull CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        writeToNbt(tag);
        return tag;
    }

    /**
     * Writes this reference's fields into the given {@link CompoundTag}. Existing
     * entries under the {@code NBT_KEY_*} keys are overwritten; other entries in
     * the tag are left untouched, so callers may compose this into a larger tag.
     *
     * @param tag the tag to populate; must not be {@code null}.
     */
    public void writeToNbt(@NotNull CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");
        tag.putString(NBT_KEY_PROVIDER_ID, providerId);
        tag.putString(NBT_KEY_ACCOUNT_KEY, accountKey);
        tag.putString(NBT_KEY_LABEL, label);
        tag.putBoolean(NBT_KEY_SHARED, shared);
    }

    /**
     * Deserializes an {@link ExternalAccountRef} from a {@link CompoundTag} written
     * by {@link #toNbt()} or {@link #writeToNbt(CompoundTag)}.
     * <p>
     * Returns {@code null} if the tag is missing either required identity field
     * ({@code providerId}, {@code accountKey}) — this mirrors how legacy / partial
     * savedata rows should be skipped rather than crash the load path.
     *
     * @param tag the tag to read; may be {@code null}, in which case {@code null}
     *            is returned.
     * @return the deserialized reference, or {@code null} if the tag is missing
     *         required fields.
     */
    public static @Nullable ExternalAccountRef fromNbt(@Nullable CompoundTag tag) {
        if (tag == null) return null;
        if (!tag.contains(NBT_KEY_PROVIDER_ID) || !tag.contains(NBT_KEY_ACCOUNT_KEY))
            return null;
        String providerId = tag.getString(NBT_KEY_PROVIDER_ID);
        String accountKey = tag.getString(NBT_KEY_ACCOUNT_KEY);
        if (providerId.isEmpty() || accountKey.isEmpty())
            return null;
        String label = tag.contains(NBT_KEY_LABEL) ? tag.getString(NBT_KEY_LABEL) : "";
        boolean shared = tag.contains(NBT_KEY_SHARED) && tag.getBoolean(NBT_KEY_SHARED);
        return new ExternalAccountRef(providerId, accountKey, label, shared);
    }
}
