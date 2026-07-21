package net.kroia.banksystem.banking.binding;

import net.kroia.banksystem.api.currency.ExternalAccountRef;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One persisted row in the {@link BankAccountBindings} savedata.
 * <p>
 * A row links a specific {@code IServerBank} slot (identified by
 * {@code (bankAccountId, itemIdShort)}) to one external currency-mod account
 * described by {@link #ref()}. It also carries the BankSystem-local
 * {@link #lockedBalance() locked balance} for that slot — external mods do not
 * expose a locked-balance concept, so BankSystem tracks it here.
 * <p>
 * Only {@link #lockedBalance} is mutable; {@link #ref}, {@link #boundAt},
 * {@link #bankAccountId} and {@link #itemIdShort} are set at bind time and
 * never change afterwards.
 * <p>
 * All mutations must happen on the server thread — the caller
 * ({@link BankAccountBindings}) is responsible for that discipline.
 *
 * @since 2.0.5
 */
public final class BindingRow {

    // NBT keys — stable identifiers, safe to change only with a schema-version bump.
    private static final String NBT_ACCOUNT_ID = "accountId";
    private static final String NBT_ITEM_SHORT = "itemShort";
    private static final String NBT_REF = "ref";
    private static final String NBT_LOCKED_BALANCE = "lockedBalance";
    private static final String NBT_DUST_BALANCE = "dustBalance";
    private static final String NBT_BOUND_AT = "boundAt";

    private final int bankAccountId;
    private final short itemIdShort;
    private final @NotNull ExternalAccountRef ref;
    private long lockedBalance;
    /**
     * Sub-native-unit remainder in BankSystem raw units. Always in
     * [0, {@code ExternalAccount.nativeScale() - 1}]. Captures the fractional
     * portion of any external op that couldn't be represented in the external
     * mod's native granularity (e.g. 0.17 spurs of a 154.17-spur trade). This
     * keeps money conservation exact — nothing is silently rounded away.
     */
    private long dustBalance;
    private final long boundAt;

    /**
     * Constructs a fresh binding row.
     *
     * @param bankAccountId BankSystem account number
     * @param itemIdShort   BankSystem item slot short
     * @param ref           external account reference; never {@code null}
     * @param lockedBalance initial locked-balance (typically 0)
     * @param boundAt       epoch millis at which the bind happened (audit only)
     */
    public BindingRow(int bankAccountId, short itemIdShort, @NotNull ExternalAccountRef ref,
                      long lockedBalance, long boundAt) {
        this(bankAccountId, itemIdShort, ref, lockedBalance, 0L, boundAt);
    }

    /**
     * Full-field constructor including {@code dustBalance}. Used by NBT
     * deserialization to preserve sub-native-unit remainders across restarts.
     */
    public BindingRow(int bankAccountId, short itemIdShort, @NotNull ExternalAccountRef ref,
                      long lockedBalance, long dustBalance, long boundAt) {
        this.bankAccountId = bankAccountId;
        this.itemIdShort = itemIdShort;
        this.ref = ref;
        this.lockedBalance = Math.max(0L, lockedBalance);
        this.dustBalance = Math.max(0L, dustBalance);
        this.boundAt = boundAt;
    }

    public int bankAccountId() {
        return bankAccountId;
    }

    public short itemIdShort() {
        return itemIdShort;
    }

    public @NotNull ExternalAccountRef ref() {
        return ref;
    }

    public long lockedBalance() {
        return lockedBalance;
    }

    public long dustBalance() {
        return dustBalance;
    }

    public long boundAt() {
        return boundAt;
    }

    /**
     * Overwrites the locked-balance amount. Values below zero are clamped to zero.
     * Only {@link BankAccountBindings} should call this directly — callers
     * outside the package should go through
     * {@link BankAccountBindings#setLocked(int, net.kroia.banksystem.util.ItemID, long)}
     * so persistence gets marked dirty.
     *
     * @param newAmount new locked-balance amount, in BankSystem raw units
     */
    void setLockedBalance(long newAmount) {
        this.lockedBalance = Math.max(0L, newAmount);
    }

    /**
     * Overwrites the sub-native-unit dust balance. Values below zero are clamped
     * to zero. Package-private — callers go through
     * {@link BankAccountBindings#setDust(int, net.kroia.banksystem.util.ItemID, long)}.
     */
    void setDustBalance(long newAmount) {
        this.dustBalance = Math.max(0L, newAmount);
    }

    /**
     * Serializes this row into a fresh {@link CompoundTag}.
     *
     * @return an NBT tag carrying every field of this row
     */
    public @NotNull CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(NBT_ACCOUNT_ID, bankAccountId);
        tag.putShort(NBT_ITEM_SHORT, itemIdShort);
        tag.put(NBT_REF, ref.toNbt());
        tag.putLong(NBT_LOCKED_BALANCE, lockedBalance);
        tag.putLong(NBT_DUST_BALANCE, dustBalance);
        tag.putLong(NBT_BOUND_AT, boundAt);
        return tag;
    }

    /**
     * Deserializes a row from a compound tag previously written by {@link #toNbt()}.
     * Returns {@code null} when required fields are missing or malformed —
     * callers treat that as a "skip this row" outcome rather than a load abort.
     *
     * @param tag the NBT tag to read (may be {@code null})
     * @return the deserialized row, or {@code null} if the tag is unusable
     */
    public static @Nullable BindingRow fromNbt(@Nullable CompoundTag tag) {
        if (tag == null) return null;
        if (!tag.contains(NBT_ACCOUNT_ID) || !tag.contains(NBT_ITEM_SHORT) || !tag.contains(NBT_REF))
            return null;
        int accountId = tag.getInt(NBT_ACCOUNT_ID);
        short itemShort = tag.getShort(NBT_ITEM_SHORT);
        ExternalAccountRef ref = ExternalAccountRef.fromNbt(tag.getCompound(NBT_REF));
        if (ref == null) return null;
        long locked = tag.contains(NBT_LOCKED_BALANCE) ? tag.getLong(NBT_LOCKED_BALANCE) : 0L;
        long dust = tag.contains(NBT_DUST_BALANCE) ? tag.getLong(NBT_DUST_BALANCE) : 0L;
        long boundAt = tag.contains(NBT_BOUND_AT) ? tag.getLong(NBT_BOUND_AT) : 0L;
        return new BindingRow(accountId, itemShort, ref, locked, dust, boundAt);
    }

    @Override
    public String toString() {
        return "BindingRow{account=" + bankAccountId
                + ", itemShort=" + itemIdShort
                + ", ref=" + ref
                + ", locked=" + lockedBalance
                + ", dust=" + dustBalance
                + ", boundAt=" + boundAt + "}";
    }
}
