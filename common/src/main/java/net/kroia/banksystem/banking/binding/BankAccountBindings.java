package net.kroia.banksystem.banking.binding;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.api.BankSystemAPI;
import net.kroia.banksystem.api.currency.ExternalAccountRef;
import net.kroia.banksystem.api.currency.ExternalCurrencyProvider;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-world savedata for BankSystem-slot &rarr; external-account bindings
 * (Task #33, v2.0.5).
 * <p>
 * One instance lives on the master server for the lifetime of a world; slave
 * servers keep the instance empty (bindings are master-authoritative, and no
 * slave-side ServerBank ever consults them). Rows are keyed by
 * {@code (bankAccountId, itemIdShort)} — see {@link BindingKey}. The row itself
 * ({@link BindingRow}) carries the {@link ExternalAccountRef}, the BankSystem-
 * local {@code lockedBalance}, and an audit-only {@code boundAt} timestamp.
 * <p>
 * Callers must invoke every mutation on the server thread. The class does not
 * hard-fail off-thread accesses (a WARN is logged instead) so a badly-timed
 * caller never crashes the load path.
 * <p>
 * Persistence lives in a dedicated file (name defined by
 * {@link net.kroia.banksystem.util.BankSystemDataHandler}) to keep unbinding a
 * clean "drop a row" operation rather than editing account NBT.
 *
 * @since 2.0.5
 */
public class BankAccountBindings {

    /** NBT key for the top-level list holding every {@link BindingRow}. */
    private static final String NBT_ROWS = "rows";
    /** NBT key stamping the on-disk schema version (currently 1). */
    private static final String NBT_VERSION = "version";
    /** Current on-disk schema version. */
    private static final int SCHEMA_VERSION = 1;

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

    /**
     * Wires the shared {@code Instances} container onto this class so its
     * logger can be reached from static helpers. Called once during backend
     * construction — mirrors {@code ItemIDManager.setBackend}.
     */
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BankAccountBindings.BACKEND_INSTANCES = backend;
    }

    /**
     * Convenience accessor: returns the singleton instance held in
     * {@code Instances.BANK_ACCOUNT_BINDINGS}, or {@code null} if the backend
     * hasn't been wired yet (pre-startup / after shutdown). Used by
     * {@link net.kroia.banksystem.banking.bank.ServerBank} to consult the
     * table without threading the instance through every call site.
     */
    public static @Nullable BankAccountBindings get() {
        return BACKEND_INSTANCES != null ? BACKEND_INSTANCES.BANK_ACCOUNT_BINDINGS : null;
    }

    /**
     * Raw-BankSystem-units per one physical item of the slot's currency
     * (Task #38b, v2.0.5). Returns:
     * <ul>
     *   <li>{@link BankSystemModSettings#ITEM_FRACTION_SCALE_FACTOR} (100) when
     *       the slot is unbound, when the bindings table isn't available, or
     *       when the provider is missing / its item template is unresolvable.
     *       Preserves the pre-#38b behavior for every non-bound path.</li>
     *   <li>The provider's {@code baseUnitsPerItem(templateStack)} when the
     *       slot is bound and that value resolves &gt; 0. For a Lightman's
     *       Currency {@code coin_gold} slot on default config this is 81; for
     *       a Numismatics {@code spur} slot it is 100 (equal to the global
     *       constant — the reason Numismatics needs no per-slot rewiring).</li>
     * </ul>
     * Callers use this at every site that converts raw balance ↔ physical
     * item count (withdraw math, display formatting, chart Y-axis, wealth
     * pricing) so the LC coprime-ratio problem (81 vs. 100) does not produce
     * drift or lost coins.
     */
    public static long getRawUnitsPerItem(int accountId, @NotNull ItemID slotItemId) {
        BankAccountBindings self = get();
        if (self == null) return BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
        BindingRow row = self.getBinding(accountId, slotItemId);
        if (row == null) return BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
        BankSystemAPI api = BankSystemMod.getAPI();
        if (api == null) return BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
        ExternalCurrencyProvider provider = api.getCurrencyProvider(row.ref().providerId());
        if (provider == null) return BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
        net.minecraft.world.item.ItemStack template = ItemIDManager.getItemStackTemplate(slotItemId);
        if (template == null || template.isEmpty()) return BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
        long r = provider.baseUnitsPerItem(template);
        return r > 0L ? r : BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
    }

    private final Map<BindingKey, BindingRow> bindings = new HashMap<>();

    /**
     * Session-scoped log dedup for the "provider unavailable" WARN path.
     * A missing provider (mod uninstalled while a binding still points at it)
     * would otherwise flood logs on every balance read; we WARN once per
     * (accountId, itemShort) per session instead. Cleared on {@link #clear()}.
     */
    private final Set<BindingKey> unavailableWarnedKeys = new HashSet<>();

    /**
     * Session-scoped dedup flag for the "multiple bindings claim the same stack"
     * WARN emitted by {@link #findBindingAcceptingItem(int, ItemStack)}. Chains
     * are supposed to be disjoint (LC's coin_* items and Numismatics' spur/bevel/
     * ... are non-overlapping); a collision means a misconfigured account with
     * two providers claiming the same item. First-wins routing keeps deposits
     * moving; the WARN surfaces the mistake to admins.
     */
    private final AtomicBoolean bindingCollisionWarned = new AtomicBoolean(false);

    private boolean dirty = false;

    /**
     * Adds or replaces the binding row for the given slot. Called by the
     * bind service AFTER it has validated that binding is safe (balance zero,
     * provider available, ref resolves, shared-state matches).
     *
     * @param accountId account number
     * @param itemId    slot; must be non-null and valid
     * @param ref       external account reference; must be non-null
     * @return the newly created row
     */
    public @NotNull BindingRow bind(int accountId, @NotNull ItemID itemId,
                                    @NotNull ExternalAccountRef ref) {
        BindingKey key = BindingKey.of(accountId, itemId);
        BindingRow row = new BindingRow(accountId, itemId.getShort(), ref, 0L, System.currentTimeMillis());
        bindings.put(key, row);
        // A fresh bind means the provider is available right now — drop any
        // previously-logged "unavailable" dedup entry so a later degradation
        // will WARN again.
        unavailableWarnedKeys.remove(key);
        dirty = true;
        return row;
    }

    /**
     * Removes the binding row for the given slot. Idempotent — a call for an
     * unknown slot is a successful no-op (returns {@code null}).
     *
     * @param accountId account number
     * @param itemId    slot
     * @return the removed row, or {@code null} if no row existed
     */
    public @Nullable BindingRow unbind(int accountId, @NotNull ItemID itemId) {
        BindingKey key = BindingKey.of(accountId, itemId);
        BindingRow removed = bindings.remove(key);
        if (removed != null) {
            dirty = true;
        }
        unavailableWarnedKeys.remove(key);
        return removed;
    }

    /**
     * @param accountId account number
     * @param itemId    slot
     * @return the live binding row for the slot, or {@code null} if unbound
     */
    public @Nullable BindingRow getBinding(int accountId, @NotNull ItemID itemId) {
        return bindings.get(BindingKey.of(accountId, itemId));
    }

    /**
     * @return {@code true} iff a binding row exists for the given slot
     */
    public boolean hasBinding(int accountId, @NotNull ItemID itemId) {
        return bindings.containsKey(BindingKey.of(accountId, itemId));
    }

    /**
     * Convenience accessor: returns the locked-balance for the given slot's
     * binding row, or 0 when no binding exists.
     */
    public long getLocked(int accountId, @NotNull ItemID itemId) {
        BindingRow row = getBinding(accountId, itemId);
        return row == null ? 0L : row.lockedBalance();
    }

    /**
     * Overwrites the locked-balance amount for the given slot's binding row.
     * No-op if no row exists (the caller should check {@link #getBinding} first
     * when it matters).
     */
    public void setLocked(int accountId, @NotNull ItemID itemId, long newAmount) {
        BindingRow row = getBinding(accountId, itemId);
        if (row == null) return;
        long clamped = Math.max(0L, newAmount);
        if (clamped != row.lockedBalance()) {
            row.setLockedBalance(clamped);
            dirty = true;
        }
    }

    /**
     * Overwrites the sub-native-unit dust balance for the given slot. Negative
     * values are clamped to zero. No-op if the slot is unbound.
     */
    public void setDust(int accountId, @NotNull ItemID itemId, long newAmount) {
        BindingRow row = getBinding(accountId, itemId);
        if (row == null) return;
        long clamped = Math.max(0L, newAmount);
        if (clamped != row.dustBalance()) {
            row.setDustBalance(clamped);
            dirty = true;
        }
    }

    /**
     * Convenience accessor: dust for the given slot, or 0 when unbound.
     */
    public long getDust(int accountId, @NotNull ItemID itemId) {
        BindingRow row = getBinding(accountId, itemId);
        return row == null ? 0L : row.dustBalance();
    }

    /**
     * Finds the binding row on the given account whose provider accepts the
     * supplied stack as part of its currency chain via
     * {@link ExternalCurrencyProvider#baseUnitsPerItem(ItemStack)}.
     * Returns {@code null} if no binding claims the stack or if the account
     * has no bindings.
     * <p>
     * Used by deposit routing (Task #38) to redirect non-base coin variants
     * (e.g. LC {@code coin_copper}, Numismatics {@code bevel}) into the
     * account's base-coin binding slot at their converted per-item value
     * instead of creating a per-variant BankSystem bank.
     * <p>
     * When two rows both claim the stack — which the chain-disjointness
     * invariant says should never happen — this returns the first hit and
     * WARNs once per session.
     *
     * @param accountId BankSystem account number
     * @param stack     the stack being deposited; {@link ItemStack#EMPTY} returns null
     * @return the accepting binding row, or {@code null}
     */
    public @Nullable BindingRow findBindingAcceptingItem(int accountId, @NotNull ItemStack stack) {
        if (stack.isEmpty()) return null;
        BankSystemAPI api = BankSystemMod.getAPI();
        if (api == null) return null;

        BindingRow first = null;
        for (BindingRow row : bindings.values()) {
            if (row.bankAccountId() != accountId) continue;
            ExternalCurrencyProvider provider = api.getCurrencyProvider(row.ref().providerId());
            if (provider == null) continue;
            long units = provider.baseUnitsPerItem(stack);
            if (units <= 0L) continue;
            if (first == null) {
                first = row;
            } else {
                if (bindingCollisionWarned.compareAndSet(false, true)) {
                    warn("Multiple bindings on account " + accountId + " accept "
                            + stack.getItem() + " (providers: " + first.ref().providerId()
                            + ", " + row.ref().providerId() + "). Using the first — "
                            + "check for a misconfigured account.");
                }
                break;
            }
        }
        return first;
    }

    /**
     * Lists every binding row belonging to the given account. Returned list is
     * a defensive copy — safe to iterate while mutations happen.
     */
    public @NotNull List<BindingRow> listBindingsFor(int accountId) {
        List<BindingRow> result = new ArrayList<>();
        for (BindingRow row : bindings.values()) {
            if (row.bankAccountId() == accountId) {
                result.add(row);
            }
        }
        return result;
    }

    /**
     * Cascade-removes every binding row for the given account. Called when the
     * BankSystem account is deleted.
     *
     * @param accountId account number
     * @return the number of rows removed
     */
    public int removeAllForAccount(int accountId) {
        int removed = 0;
        for (var it = bindings.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (entry.getKey().bankAccountId() == accountId) {
                it.remove();
                unavailableWarnedKeys.remove(entry.getKey());
                removed++;
            }
        }
        if (removed > 0) dirty = true;
        return removed;
    }

    /**
     * Records that a provider-unavailable WARN has already been emitted for
     * this slot in the current session. Second and subsequent calls for the
     * same slot return {@code false} — the caller uses that to skip the log.
     *
     * @return {@code true} if the caller SHOULD log now; {@code false} if a
     *         WARN was already emitted this session
     */
    public boolean shouldWarnUnavailable(int accountId, @NotNull ItemID itemId) {
        return unavailableWarnedKeys.add(BindingKey.of(accountId, itemId));
    }

    /**
     * Snapshot of every binding row for iteration by admin tools / tests.
     */
    public @NotNull Map<BindingKey, BindingRow> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(bindings));
    }

    /**
     * @return {@code true} when a mutation has occurred since the last
     *         {@link #clearChanges()} or save
     */
    public boolean hasChanges() {
        return dirty;
    }

    /** Clears the dirty flag — called by the data handler after a successful save. */
    public void clearChanges() {
        dirty = false;
    }

    /**
     * Drops all in-memory state. Called on server stop to prevent bleeding
     * across worlds.
     */
    public void clear() {
        bindings.clear();
        unavailableWarnedKeys.clear();
        dirty = false;
    }

    /**
     * Writes the current state into the given tag. Format: a versioned list
     * of per-row compound tags (see {@link BindingRow#toNbt()}).
     *
     * @param tag destination tag; must not be {@code null}
     * @return {@code true} on success (currently always)
     */
    public boolean save(@NotNull CompoundTag tag) {
        tag.putInt(NBT_VERSION, SCHEMA_VERSION);
        ListTag rows = new ListTag();
        for (BindingRow row : bindings.values()) {
            rows.add(row.toNbt());
        }
        tag.put(NBT_ROWS, rows);
        return true;
    }

    /**
     * Populates the state from a tag previously written by {@link #save(CompoundTag)}.
     * Rows whose NBT is unusable (missing keys, unknown provider id, malformed
     * ref, ...) are skipped with a WARN; unknown schema versions abort the load
     * with a WARN and leave the map empty (the file gets backed up aside by
     * the data handler's usual recovery flow).
     *
     * @param tag source tag; may be {@code null}
     * @return {@code true} on successful load, {@code false} on version
     *         mismatch or when {@code tag} is {@code null}
     */
    public boolean load(@Nullable CompoundTag tag) {
        bindings.clear();
        unavailableWarnedKeys.clear();
        dirty = false;
        if (tag == null) return false;
        int version = tag.contains(NBT_VERSION) ? tag.getInt(NBT_VERSION) : 1;
        if (version > SCHEMA_VERSION) {
            warn("BankAccountBindings.nbt has schema version " + version
                    + ", but this build only supports up to " + SCHEMA_VERSION
                    + ". Refusing to load — the file will be backed up aside "
                    + "and a fresh (empty) one written in its place.");
            return false;
        }
        if (!tag.contains(NBT_ROWS, Tag.TAG_LIST)) {
            // Empty but well-formed file — treat as a clean-slate load.
            return true;
        }
        ListTag rows = tag.getList(NBT_ROWS, Tag.TAG_COMPOUND);
        int skipped = 0;
        for (int i = 0; i < rows.size(); i++) {
            BindingRow row = BindingRow.fromNbt(rows.getCompound(i));
            if (row == null) {
                skipped++;
                continue;
            }
            bindings.put(new BindingKey(row.bankAccountId(), row.itemIdShort()), row);
        }
        if (skipped > 0) {
            warn("Skipped " + skipped + " malformed row(s) while loading BankAccountBindings.nbt "
                    + "(missing keys / unusable ref).");
        }
        return true;
    }

    // Logger helpers — quiet no-ops if the backend has not been wired yet.
    private static void warn(String msg) {
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
            BACKEND_INSTANCES.LOGGER.warn("[BankAccountBindings] " + msg);
        }
    }
}
