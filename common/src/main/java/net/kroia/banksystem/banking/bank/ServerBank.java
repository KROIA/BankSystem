package net.kroia.banksystem.banking.bank;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.BankSystemModSettings;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.IServerBank;
import net.kroia.banksystem.api.bank.ISyncServerBank;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankaccount.ISyncServerBankAccount;
import net.kroia.banksystem.api.bankmanager.IServerBankManager;
import net.kroia.banksystem.api.currency.ExternalAccount;
import net.kroia.banksystem.api.currency.ExternalCurrencyProvider;
import net.kroia.banksystem.api.currency.ProviderFeature;
import net.kroia.banksystem.banking.bankmanager.BankManager;
import net.kroia.banksystem.banking.binding.BankAccountBindings;
import net.kroia.banksystem.banking.binding.BindingRow;
import net.kroia.banksystem.banking.clientdata.BankData;
import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.kroia.banksystem.util.BankSystemTextMessages;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.JsonUtilities;
import net.kroia.modutilities.persistence.ServerSaveable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * Master-side bank ledger for one {@code (account, item)} slot.
 * <p>
 * <b>External-currency binding (Task #33, v2.0.5).</b> Each slot may optionally
 * bind to a third-party mod's account via
 * {@link net.kroia.banksystem.banking.binding.BankAccountBindings}. When a
 * binding row exists for the {@code (accountId, itemID)} of this bank, the 11
 * balance-operation methods delegate to the bound {@link ExternalAccount}; the
 * local {@link #balance} / {@link #lockedBalance} fields go unused. When no
 * binding row exists, every operation uses the original local-only code path,
 * byte-for-byte identical to pre-Stage-2 behavior.
 * <p>
 * <b>Save behavior for bound slots.</b> The local balance fields are FORCED
 * to {@code 0L} in {@link #save(net.minecraft.nbt.CompoundTag)} while the slot
 * is bound — the external mod is the authoritative source, so persisting a
 * (potentially stale) local copy would only invite drift. On unbind the local
 * fields get re-materialized from a final external read (see the unbind
 * service in {@code ServerBankManager}).
 */
public class ServerBank implements ServerSaveable, IServerBank {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        ServerBank.BACKEND_INSTANCES = backend;
    }


    public record BankSQL_Data(int bankAccountNr, ItemID itemID, long balance, long lockedBalance)
    {

    }

    /**
     * Matches {@code ServerBankAccount.INVALID_ACCOUNT_NUMBER}. Duplicated here as a
     * literal to avoid a compile-time dependency on ServerBankAccount from the low-
     * level bank class (they already sit at different layers).
     */
    private static final int UNATTACHED_ACCOUNT_ID = 0;


    protected long balance;
    protected long lockedBalance;
    protected boolean changeFlag = false;
    private ItemID itemID;

    /**
     * Watchdog cache (Issue #67, v2.0.6) for detecting out-of-band external mutations
     * (e.g. player uses Numismatics' own Bank Terminal or Lightman's ATM while a
     * BankSystem terminal is open elsewhere). Every 1 Hz {@code ServerBankManager}
     * tick, {@link #pollExternalDrift()} compares the current external balance to
     * this cached value; a mismatch flips {@link #changeFlag} so the bound
     * bank's next {@code ServerBankAccount.update()} pass will notify subscribed
     * {@code BankTerminalScreen} instances via the ARRS change stream.
     * <p>
     * {@link Long#MIN_VALUE} = never seeded. Seeded at bind time by
     * {@link #primeDriftCache(long)} so the first tick after bind does not fire a
     * spurious flag flip (the cache already matches external). Unbound slots
     * ignore this field — {@link #pollExternalDrift()} short-circuits on a
     * {@code null} binding row.
     */
    private long lastSeenExternalBalance = Long.MIN_VALUE;

    /**
     * The BankSystem account number this bank belongs to (Task #33, v2.0.5).
     * <p>
     * Set to the owning account's number when the bank is added to a
     * {@code ServerBankAccount}. {@code 0} ({@link #UNATTACHED_ACCOUNT_ID}) means
     * "not attached yet" — as it is for freshly-created banks between
     * {@link #create(ItemID, long)} and being put into the account's map, and for
     * banks freshly restored from NBT before the account calls
     * {@link #attachToAccount(int)} on them. While the field is {@code 0} the
     * binding table cannot resolve a row for this bank, so every operation takes
     * the unbound path — the safe default.
     */
    private int accountId = UNATTACHED_ACCOUNT_ID;

    private ServerBank()
    {

    }
    private ServerBank(ItemID itemID, long balance)
    {
        this.itemID = itemID;
        this.balance = Math.max(balance, 0); // Ensure balance is not negative
        this.lockedBalance = 0;
    }

    /**
     * Attaches this bank to the given BankSystem account (Task #33, v2.0.5).
     * <p>
     * Called by {@code ServerBankAccount} every time a bank is placed into the
     * account's map — on creation ({@code createBank} / {@code createPersonal} /
     * {@code getOrCreateBank}) and after NBT load. The account id is the key the
     * {@link BankAccountBindings} table uses to find the (if any) binding row for
     * the {@code (accountId, itemID)} slot this bank represents. Idempotent when
     * called with the same value.
     *
     * @param accountId the BankSystem account number this bank belongs to
     */
    public void attachToAccount(int accountId) {
        this.accountId = accountId;
    }

    /**
     * @return the BankSystem account this bank is attached to, or
     *         {@code 0} ({@code ServerBankAccount.INVALID_ACCOUNT_NUMBER}) if it
     *         has not been attached yet
     */
    public int getAccountId() {
        return accountId;
    }

    /**
     * Fast look-up: returns the binding row for this bank's slot, or
     * {@code null} when there is no binding table, the bank has not been attached
     * to an account, or no row exists for the slot. The unbound path in every
     * balance method uses this as its leading guard (return-null → fall through
     * to the original local logic).
     */
    private @Nullable BindingRow lookupBindingRow() {
        if (accountId == UNATTACHED_ACCOUNT_ID) return null;
        BankAccountBindings bindings = BankAccountBindings.get();
        if (bindings == null) return null;
        return bindings.getBinding(accountId, itemID);
    }

    /**
     * Read/write context for a bound operation. Carries the current
     * {@link BindingRow}, an open {@link ExternalAccount} (or {@code null}
     * when the provider is unavailable — degraded state), and — when
     * available — a fresh external balance already used for the mandatory
     * drift-clamp so downstream callers can reuse it without a second RPC.
     */
    private static final class BoundView {
        final @NotNull BindingRow row;
        final @Nullable ExternalAccount external;
        final long externalBalance; // only meaningful when external != null
        final long scale;           // external.nativeScale() cached; 1 when external null
        final long dust;            // cached row.dustBalance() — never > scale-1

        private BoundView(@NotNull BindingRow row, @Nullable ExternalAccount external,
                          long externalBalance, long scale, long dust) {
            this.row = row;
            this.external = external;
            this.externalBalance = externalBalance;
            this.scale = scale;
            this.dust = dust;
        }

        boolean isAvailable() {
            return external != null;
        }

        /** Free pool = external free balance + sub-native-unit dust. */
        long freeTotal() {
            return externalBalance + dust;
        }
    }

    /**
     * Splits a free-pool change into an external-mod delta (a multiple of the
     * native scale) and a new sub-unit remainder. Positive
     * {@code externalDelta} means "deposit that much to external"; negative means
     * "withdraw {@code -externalDelta} from external". Post-condition:
     * {@code newExt + newDust == ext + dust + changeInFree}, with
     * {@code 0 <= newDust < scale} and {@code newExt} being a multiple of {@code scale}.
     */
    private static long[] splitBoundDelta(long ext, long dust, long changeInFree, long scale) {
        long newFree = ext + dust + changeInFree;
        // Correct modulo for possibly-negative newFree.
        long newDust = ((newFree % scale) + scale) % scale;
        long newExt = newFree - newDust;
        return new long[] { newExt - ext, newDust };
    }

    /**
     * Resolves the bound view for the current slot: returns {@code null} when the
     * slot is unbound (callers fall through to the original local path); returns
     * a {@link BoundView} otherwise. Applies the umbrella spec's drift-clamp on
     * every bound read: if the external balance dropped below our tracked locked
     * amount (player used the external mod's own UI behind our back), the locked
     * amount is clamped down to match and an ERROR is logged (now a real problem
     * under the transactional-lock protocol).
     */
    private @Nullable BoundView resolveBound() {
        BindingRow row = lookupBindingRow();
        if (row == null) return null;
        ExternalCurrencyProvider provider =
                BankSystemMod.getAPI().getCurrencyProvider(row.ref().providerId());
        ExternalAccount external =
                (provider != null && provider.isAvailable()) ? provider.open(row.ref()) : null;
        if (external == null) {
            maybeWarnProviderUnavailable(row);
            return new BoundView(row, null, 0L, 1L, row.dustBalance());
        }
        // Under the transactional-lock protocol (Task #33 v2.0.5+), locked funds
        // have ALREADY been withdrawn from the external mod at lock time and are
        // tracked separately in row.lockedBalance. External balance = free funds
        // (multiple of nativeScale), row.dustBalance = sub-native-unit remainder
        // that keeps money conservation exact. locked balance = held-back funds.
        // All three are independent axes — no drift-clamp.
        long extBal = external.getBalance();
        long scale = Math.max(1L, external.nativeScale());
        return new BoundView(row, external, extBal, scale, row.dustBalance());
    }

    /**
     * One-shot WARN dedup for a bound slot whose provider is not available.
     * Reads on such a slot happen every render frame in some UIs; logging on each
     * call would drown the log. {@link BankAccountBindings#shouldWarnUnavailable}
     * returns true the first time per (account, item, session).
     */
    private void maybeWarnProviderUnavailable(@NotNull BindingRow row) {
        BankAccountBindings bindings = BankAccountBindings.get();
        if (bindings == null) return;
        if (!bindings.shouldWarnUnavailable(accountId, itemID)) return;
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
            BACKEND_INSTANCES.LOGGER.warn("[ServerBank] Bound slot " + accountId + "/"
                    + itemID + " references external provider '" + row.ref().providerId()
                    + "' which is not currently available (mod uninstalled or adapter not "
                    + "loaded). Reads return degraded values (free=0, locked=" + row.lockedBalance()
                    + "); writes fail with FAILED_EXTERNAL_UNAVAILABLE. The binding row is "
                    + "preserved — reinstall the mod to resume operation. "
                    + "This warning fires once per slot per session.");
        }
    }

    public static @Nullable ServerBank create(ItemID itemID, long balance) {
        if (itemID == null || !itemID.isValid() || balance < 0) {
            return null; // Invalid parameters
        }
        IServerBankManager bankManager = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync();
        if(!bankManager.isItemIDAllowed(itemID)) {
            return null; // Item not allowed in bank
        }

        return new ServerBank(itemID, balance);
    }
    public static @Nullable ServerBank createFromTag(CompoundTag tag)
    {
        ServerBank bank = new ServerBank();
        if(bank.load(tag)) {
            return bank;
        }
        return null; // Invalid data
    }

    @Override
    public boolean hasChanges()
    {
        return changeFlag;
    }

    @Override
    public void clearChangeFlag()
    {
        changeFlag = false;
    }

    @Override
    public BankData getMinimalData() {
        // Route through the delegating getters so client snapshots see the bound
        // (or unbound) values correctly. Behavior unchanged for unbound slots.
        return new BankData(
                itemID,
                getBalance(),
                getLockedBalance(),
                net.kroia.banksystem.banking.binding.BankAccountBindings.getRawUnitsPerItem(accountId, itemID)
        );
    }
    @Override
    public CompletableFuture<BankData> getMinimalDataAsync() {
        return CompletableFuture.completedFuture(getMinimalData());
    }


    @Override
    public long getBalance() {
        // Task #33: bound-branch guard. Free balance = external balance + tracked
        // sub-native-unit dust. Unbound path below is byte-for-byte unchanged.
        BoundView bv = resolveBound();
        if (bv != null) {
            if (!bv.isAvailable()) return 0L; // degraded — free reads as 0
            return bv.freeTotal();
        }
        return balance;
    }
    @Override
    public CompletableFuture<Long> getBalanceAsync() {
        return CompletableFuture.completedFuture(getBalance());
    }



    @Override
    public long getLockedBalance() {
        // Task #33: locked balance for a bound slot lives in the binding row; the
        // provider being unavailable does NOT hide the tracked lock (players/StockMarket
        // still need to see the reservation). Unbound path unchanged.
        BindingRow row = lookupBindingRow();
        if (row != null) return row.lockedBalance();
        return lockedBalance;
    }
    @Override
    public CompletableFuture<Long> getLockedBalanceAsync() {
        return CompletableFuture.completedFuture(getLockedBalance());
    }



    @Override
    public long getTotalBalance() {
        // Task #33: total = external + dust + locked (money the user owns).
        // Unbound path unchanged.
        BoundView bv = resolveBound();
        if (bv != null) {
            if (!bv.isAvailable()) return bv.row.lockedBalance() + bv.dust;
            return bv.freeTotal() + bv.row.lockedBalance();
        }
        return balance + lockedBalance;
    }
    @Override
    public CompletableFuture<Long> getTotalBalanceAsync() {
        return CompletableFuture.completedFuture(getTotalBalance());
    }



    @Override
    public double getRealBalance() {
        // Route through the delegating getter so bound slots produce a correct value.
        // Unbound: getBalance() returns the balance field — identical to the pre-Task-#33 read.
        return convertToRealAmount(getBalance());
    }
    @Override
    public CompletableFuture<Double> getRealBalanceAsync() {
        return CompletableFuture.completedFuture(getRealBalance());
    }



    @Override
    public double getRealLockedBalance() {
        return convertToRealAmount(getLockedBalance());
    }
    @Override
    public CompletableFuture<Double> getRealLockedBalanceAsync() {
        return CompletableFuture.completedFuture(getRealLockedBalance());
    }



    @Override
    public double getRealTotalBalance() {
        return convertToRealAmount(getTotalBalance());
    }
    @Override
    public CompletableFuture<Double> getRealTotalBalanceAsync() {
        return CompletableFuture.completedFuture(getRealTotalBalance());
    }


    @Override
    public ItemID getItemID() {
        return itemID;
    }
    @Override
    public ItemID getItemIDAsync() {
        return itemID;
    }




    @Override
    public String getItemName()
    {
        String name = itemID.getName();
        if(name == null)
            return "unknown";
        if(name.contains(":"))
            return name.substring(name.lastIndexOf(":")+1);
        return name;
    }
    @Override
    public CompletableFuture<String> getItemNameAsync()
    {
        return CompletableFuture.completedFuture(getItemName());
    }



    // Weak-lock semantics — lockedBalance is advisory; callers verify before withdrawing locked funds
    @Override
    public boolean setBalance(long balance) {
        // Task #33: bound branch. Set the free pool (external + dust) to `balance`
        // — locked stays untouched (it's a separate axis under the transactional
        // model). Unbound path below is byte-for-byte unchanged.
        BoundView bv = resolveBound();
        if (bv != null) {
            if (balance < 0) return false;
            if (!bv.isAvailable()) return false;
            long delta = balance - bv.freeTotal();
            if (delta == 0) return true;
            long[] split = splitBoundDelta(bv.externalBalance, bv.dust, delta, bv.scale);
            long externalDelta = split[0];
            long newDust = split[1];
            if (externalDelta > 0) {
                if (!bv.external.deposit(externalDelta)) return false;
            } else if (externalDelta < 0) {
                if (!bv.external.withdraw(-externalDelta)) return false;
            }
            BankAccountBindings bindings = BankAccountBindings.get();
            if (bindings != null) bindings.setDust(accountId, itemID, newDust);
            // Issue #67 (v2.0.6): the bound branch mutates external + dust but the
            // local balance/lockedBalance fields stay 0, so setBalanceInternal is not
            // called and nothing flipped changeFlag. Without this, the
            // BANKSYSTEM_ACCOUNT_CHANGE_STREAM never fires and open BankTerminalScreens
            // stay stale.
            changeFlag = true;
            return true;
        }
        if(balance < 0)
            return false;
        long newBalance = balance - this.lockedBalance;
        if(newBalance < 0)
        {
            lockedBalance = balance;
            setBalanceInternal(0);
            return false;
        }
        setBalanceInternal(newBalance);
        return true;
    }
    @Override
    public CompletableFuture<Boolean> setBalanceAsync(long balance) {
        return CompletableFuture.completedFuture(setBalance(balance));
    }



    @Override
    public boolean setRealBalance(double balance) {
        return setBalance(convertToRawAmount(balance));
    }
    @Override
    public CompletableFuture<Boolean> setRealBalanceAsync(double balance) {
        return CompletableFuture.completedFuture(setRealBalance(balance));
    }



    @Override
    public BankStatus deposit(long amount) {
        // Task #33: bound branch. Dust-aware add to the free pool. The whole-native
        // portion goes through external.deposit; sub-native remainder is persisted
        // to row.dustBalance so no fraction is silently dropped. Unbound path unchanged.
        BoundView bv = resolveBound();
        if (bv != null) {
            if (amount < 0) return BankStatus.FAILED_NEGATIVE_VALUE;
            if (amount == 0) return BankStatus.SUCCESS;
            if (!bv.isAvailable()) return BankStatus.FAILED_EXTERNAL_UNAVAILABLE;
            long[] split = splitBoundDelta(bv.externalBalance, bv.dust, amount, bv.scale);
            long externalDelta = split[0];
            long newDust = split[1];
            if (externalDelta > 0 && !bv.external.deposit(externalDelta)) {
                return BankStatus.FAILED_OVERFLOW;
            }
            BankAccountBindings bindings = BankAccountBindings.get();
            if (bindings != null) bindings.setDust(accountId, itemID, newDust);
            // Issue #67 (v2.0.6): mark dirty so the change stream publishes to
            // subscribed BankTerminalScreens. The local balance/lockedBalance
            // fields are not touched on the bound path, so nothing else flips
            // changeFlag automatically.
            changeFlag = true;
            return BankStatus.SUCCESS;
        }
        if(amount < 0)
            return BankStatus.FAILED_NEGATIVE_VALUE;
        if(willOverflow(amount))
            return BankStatus.FAILED_OVERFLOW;
        addBalanceInternal(amount);
        return BankStatus.SUCCESS;
    }
    @Override
    public CompletableFuture<BankStatus> depositAsync(long amount) {
        return CompletableFuture.completedFuture(deposit(amount));
    }



    @Override
    public BankStatus depositReal(double amount) {
        return deposit(convertToRawAmount(amount));
    }
    @Override
    public CompletableFuture<BankStatus> depositRealAsync(double amount) {
        return CompletableFuture.completedFuture(depositReal(amount));
    }



    @Override
    public boolean hasSufficientFunds(long amount) {
        // Task #33: bound branch. Free pool = external + dust. Unbound path unchanged.
        BoundView bv = resolveBound();
        if (bv != null) {
            if (!bv.isAvailable()) return false;
            return bv.freeTotal() >= amount;
        }
        return balance >= amount;
    }
    @Override
    public CompletableFuture<Boolean> hasSufficientFundsAsync(long amount) {
        return CompletableFuture.completedFuture(hasSufficientFunds(amount));
    }



    @Override
    public BankStatus withdraw(long amount) {
        // Task #33: bound branch. Dust-aware subtract from the free pool.
        // Unbound path unchanged.
        BoundView bv = resolveBound();
        if (bv != null) {
            if (amount < 0) return BankStatus.FAILED_NEGATIVE_VALUE;
            if (amount == 0) return BankStatus.SUCCESS;
            if (!bv.isAvailable()) return BankStatus.FAILED_EXTERNAL_UNAVAILABLE;
            if (bv.freeTotal() < amount) return BankStatus.FAILED_NOT_ENOUGH_FUNDS;
            long[] split = splitBoundDelta(bv.externalBalance, bv.dust, -amount, bv.scale);
            long externalDelta = split[0];
            long newDust = split[1];
            if (externalDelta < 0 && !bv.external.withdraw(-externalDelta)) {
                return BankStatus.FAILED_NOT_ENOUGH_FUNDS;
            } else if (externalDelta > 0 && !bv.external.deposit(externalDelta)) {
                // Rare: dust rebalancing requires a deposit-side move; if it fails
                // the caller sees FAILED_OVERFLOW and no state changes.
                return BankStatus.FAILED_OVERFLOW;
            }
            BankAccountBindings bindings = BankAccountBindings.get();
            if (bindings != null) bindings.setDust(accountId, itemID, newDust);
            // Issue #67 (v2.0.6): mark dirty so the change stream publishes.
            changeFlag = true;
            return BankStatus.SUCCESS;
        }
        if(amount < 0)
            return BankStatus.FAILED_NEGATIVE_VALUE;
        if (balance < amount) {
            return BankStatus.FAILED_NOT_ENOUGH_FUNDS;
        }
        addBalanceInternal(-amount);
        return BankStatus.SUCCESS;
    }
    @Override
    public CompletableFuture<BankStatus> withdrawAsync(long amount) {
        return CompletableFuture.completedFuture(withdraw(amount));
    }



    @Override
    public BankStatus withdrawReal(double amount) {
        return withdraw(convertToRawAmount(amount));
    }
    @Override
    public CompletableFuture<BankStatus> withdrawRealAsync(double amount) {
        return CompletableFuture.completedFuture(withdrawReal(amount));
    }



    @Override
    public BankStatus withdrawLocked(long amount) {
        // Task #33: bound branch. External balance was already decremented on lock —
        // just decrement local locked. Unbound path unchanged.
        BoundView bv = resolveBound();
        if (bv != null) {
            if (amount < 0) return BankStatus.FAILED_NEGATIVE_VALUE;
            if (!bv.isAvailable()) return BankStatus.FAILED_EXTERNAL_UNAVAILABLE;
            long clamped = Math.min(amount, bv.row.lockedBalance());
            if (clamped < amount) return BankStatus.FAILED_NOT_ENOUGH_FUNDS;
            BankAccountBindings bindings = BankAccountBindings.get();
            if (bindings != null) {
                bindings.setLocked(accountId, itemID, bv.row.lockedBalance() - clamped);
            }
            // Issue #67 (v2.0.6): mark dirty so the change stream publishes.
            // amount == 0 stays a no-op above (falls through with SUCCESS below is unreachable
            // — the amount == 0 case reaches here with clamped == 0). Setting the flag on a
            // zero-amount call is technically harmless but avoids over-firing: guard on
            // clamped > 0 to keep no-op semantics.
            if (clamped > 0) changeFlag = true;
            return BankStatus.SUCCESS;
        }
        if(amount < 0)
            return BankStatus.FAILED_NEGATIVE_VALUE;
        if (lockedBalance < amount) {
            return BankStatus.FAILED_NOT_ENOUGH_FUNDS;
        }
        lockedBalance -= amount;
        changeFlag = true;
        return BankStatus.SUCCESS;
    }
    @Override
    public CompletableFuture<BankStatus> withdrawLockedAsync(long amount) {
        return CompletableFuture.completedFuture(withdrawLocked(amount));
    }




    @Override
    public BankStatus withdrawLockedReal(double amount) {
        return withdrawLocked(convertToRawAmount(amount));
    }
    @Override
    public CompletableFuture<BankStatus> withdrawLockedRealAsync(double amount) {
        return CompletableFuture.completedFuture(withdrawLockedReal(amount));
    }


    @Override
    public BankStatus withdrawLockedPrefered(long amount) {
        // Task #33: bound branch. Discount from locked first (no external op — the
        // funds were already deducted at lock time), then fall back to the free
        // pool via the dust-aware split. Unbound path unchanged.
        BoundView bv = resolveBound();
        if (bv != null) {
            if (amount < 0) return BankStatus.FAILED_NEGATIVE_VALUE;
            if (amount == 0) return BankStatus.SUCCESS;
            if (!bv.isAvailable()) return BankStatus.FAILED_EXTERNAL_UNAVAILABLE;
            long totalAvail = bv.row.lockedBalance() + bv.freeTotal();
            if (totalAvail < amount) return BankStatus.FAILED_NOT_ENOUGH_FUNDS;
            long fromLocked = Math.min(amount, bv.row.lockedBalance());
            long fromFree = amount - fromLocked;
            long newDust = bv.dust;
            if (fromFree > 0) {
                long[] split = splitBoundDelta(bv.externalBalance, bv.dust, -fromFree, bv.scale);
                long externalDelta = split[0];
                newDust = split[1];
                if (externalDelta < 0 && !bv.external.withdraw(-externalDelta)) {
                    return BankStatus.FAILED_NOT_ENOUGH_FUNDS;
                } else if (externalDelta > 0 && !bv.external.deposit(externalDelta)) {
                    return BankStatus.FAILED_OVERFLOW;
                }
            }
            BankAccountBindings bindings = BankAccountBindings.get();
            if (bindings != null) {
                bindings.setLocked(accountId, itemID, bv.row.lockedBalance() - fromLocked);
                if (fromFree > 0) bindings.setDust(accountId, itemID, newDust);
            }
            // Issue #67 (v2.0.6): mark dirty so the change stream publishes.
            changeFlag = true;
            return BankStatus.SUCCESS;
        }
        if(amount < 0)
            return BankStatus.FAILED_NEGATIVE_VALUE;
        if (lockedBalance < amount) {
            if (balance+lockedBalance < amount) {
                return BankStatus.FAILED_NOT_ENOUGH_FUNDS;
            }
            amount -= lockedBalance;
            lockedBalance = 0;
            addBalanceInternal(-amount);
            return BankStatus.SUCCESS;
        }

        lockedBalance -= amount;
        changeFlag = true;
        return BankStatus.SUCCESS;
    }
    @Override
    public CompletableFuture<BankStatus> withdrawLockedPreferedAsync(long amount) {
        return CompletableFuture.completedFuture(withdrawLockedPrefered(amount));
    }



    @Override
    public BankStatus withdrawLockedPreferedReal(double amount) {
        return withdrawLockedPrefered(convertToRawAmount(amount));
    }
    @Override
    public CompletableFuture<BankStatus> withdrawLockedPreferedRealAsync(double amount) {
        return CompletableFuture.completedFuture(withdrawLockedPreferedReal(amount));
    }



    @Override
    public BankStatus transfer(long amount, @NotNull ISyncServerBank other) {
        // Task #33: bound branch. Decompose into primitives (withdraw + deposit) so we
        // never have to special-case bound-source-to-bound-target. Unbound path unchanged.
        BindingRow boundRow = lookupBindingRow();
        if (boundRow != null) {
            if (amount < 0) return BankStatus.FAILED_NEGATIVE_VALUE;
            if (other == this) return BankStatus.SUCCESS;
            BankStatus w = this.withdraw(amount);
            if (w != BankStatus.SUCCESS) return w;
            BankStatus d = other.deposit(amount);
            if (d == BankStatus.SUCCESS) {
                logTransferLedger(other, amount);
                return BankStatus.SUCCESS;
            }
            // Best-effort rollback: deposit back what we just withdrew.
            this.deposit(amount);
            return d;
        }
        if(amount < 0)
            return BankStatus.FAILED_NEGATIVE_VALUE;
        if (balance < amount) {
            return BankStatus.FAILED_NOT_ENOUGH_FUNDS;
        }
        if(other == this)
            return BankStatus.SUCCESS;

        addBalanceInternal(-amount);
        BankStatus otherBankStatus = other.deposit(amount);
        if(otherBankStatus == BankStatus.SUCCESS) {
            logTransferLedger(other, amount);
            return BankStatus.SUCCESS;
        }
        addBalanceInternal(amount);
        return otherBankStatus;
    }

    /**
     * Task #44 (v2.0.8) — Transaction Ledger write for a successful transfer. Two rows:
     * one TRANSFER_OUT on the source account and one TRANSFER_IN on the destination.
     * Actor is unknown at this layer (the transfer primitive carries no player context),
     * so both rows are actor-null. Best-effort: manager missing (slave / shutdown) or
     * counterparty not a {@code ServerBank} (unknown accountId) simply skips the write.
     */
    private void logTransferLedger(ISyncServerBank other, long amount) {
        if (amount <= 0) return;
        net.kroia.banksystem.data.table.TransactionLogManager mgr =
                BankSystemModBackend.getTransactionLogManager();
        if (mgr == null) return;
        if (!(other instanceof ServerBank otherBank)) return;
        int srcAccount = this.accountId;
        int dstAccount = otherBank.accountId;
        if (srcAccount == UNATTACHED_ACCOUNT_ID || dstAccount == UNATTACHED_ACCOUNT_ID) return;
        long now = System.currentTimeMillis();
        short srcItem = this.itemID == null ? 0 : this.itemID.getShort();
        short dstItem = otherBank.itemID == null ? srcItem : otherBank.itemID.getShort();
        try {
            mgr.save(net.kroia.banksystem.data.table.record.TransactionLogRecord.transfer(
                    srcAccount, null,
                    net.kroia.banksystem.data.table.record.TransactionLogRecord.Kind.TRANSFER_OUT,
                    srcItem, amount, dstAccount, now));
            mgr.save(net.kroia.banksystem.data.table.record.TransactionLogRecord.transfer(
                    dstAccount, null,
                    net.kroia.banksystem.data.table.record.TransactionLogRecord.Kind.TRANSFER_IN,
                    dstItem, amount, srcAccount, now));
        } catch (RuntimeException ignored) { }
    }
    @Override
    public BankStatus transfer(long amount, int toAccount)
    {
        IServerBankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().getBankAccount(toAccount);
        if(account == null)
            return BankStatus.FAILED_NO_BANK;
        ISyncServerBank bank = account.getBank(itemID);
        if(bank == null)
            return BankStatus.FAILED_NO_BANK;
        return transfer(amount, bank);
    }
    //@Override
    //public CompletableFuture<BankStatus> transferAsync(long amount, @NotNull IAsyncBank other) {
    //    return CompletableFuture.completedFuture(transfer(amount, other));
    //}
    @Override
    public CompletableFuture<BankStatus> transferAsync(long amount, int toAccount) {
        return CompletableFuture.completedFuture(transfer(amount, toAccount));
    }





    @Override
    public BankStatus transferReal(double amount, @NotNull ISyncServerBank other) {
        return transfer(convertToRawAmount(amount), other);
    }
    @Override
    public BankStatus transferReal(double amount, int toAccount)
    {
        return transfer(convertToRawAmount(amount), toAccount);
    }
    @Override
    public CompletableFuture<BankStatus> transferRealAsync(double amount, int toAccount) {
        IServerBankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().getBankAccount(toAccount);
        if(account == null)
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_BANK);
        ISyncServerBank bank = account.getBank(itemID);
        if(bank == null)
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_BANK);
        return CompletableFuture.completedFuture(transferReal(amount, bank));
    }


    @Override
    public BankStatus transferFromLocked(long amount, @NotNull ISyncServerBank other) {
        // Task #33: bound branch. Decompose into withdrawLocked + deposit primitives.
        // Unbound path unchanged.
        BindingRow boundRow = lookupBindingRow();
        if (boundRow != null) {
            if (amount < 0) return BankStatus.FAILED_NEGATIVE_VALUE;
            BankStatus w = this.withdrawLocked(amount);
            if (w != BankStatus.SUCCESS) return w;
            BankStatus d = other.deposit(amount);
            if (d == BankStatus.SUCCESS) return BankStatus.SUCCESS;
            // Rollback: put the funds back on the external side and re-lock them.
            if (!this.deposit(amount).equals(BankStatus.SUCCESS)) {
                if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
                    BACKEND_INSTANCES.LOGGER.error("[ServerBank] transferFromLocked rollback "
                            + "deposit failed on bound slot " + accountId + "/" + itemID
                            + " after other.deposit failed with " + d
                            + " — external funds may be off by " + amount);
                }
            } else {
                // Restore the lock we consumed via withdrawLocked.
                BankAccountBindings bindings = BankAccountBindings.get();
                if (bindings != null) {
                    bindings.setLocked(accountId, itemID, boundRow.lockedBalance() + amount);
                }
            }
            return d;
        }
        if(amount < 0)
            return BankStatus.FAILED_NEGATIVE_VALUE;
        if (lockedBalance < amount) {
            return BankStatus.FAILED_NOT_ENOUGH_FUNDS;
        }
        lockedBalance -= amount;
        BankStatus otherBankStatus = other.deposit(amount);
        if(otherBankStatus == BankStatus.SUCCESS) {
            changeFlag = true;
            return BankStatus.SUCCESS;
        }
        lockedBalance += amount;
        return otherBankStatus;
    }
    @Override
    public BankStatus transferFromLocked(long amount, int toAccount)
    {
        ISyncServerBankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().getBankAccount(toAccount);
        if(account == null)
            return BankStatus.FAILED_NO_BANK;
        ISyncServerBank bank = account.getBank(itemID);
        if(bank == null)
            return BankStatus.FAILED_NO_BANK;
        return transferFromLocked(amount, bank);
    }
    @Override
    public CompletableFuture<BankStatus> transferFromLockedAsync(long amount, int toAccount)
    {
        return CompletableFuture.completedFuture(transferFromLocked(amount, toAccount));
    }



    @Override
    public BankStatus transferFromLockedReal(double amount, @NotNull ISyncServerBank other) {
        return transferFromLocked(convertToRawAmount(amount), other);
    }
    @Override
    public BankStatus transferFromLockedReal(double amount, int toAccount)
    {
        return transferFromLocked(convertToRawAmount(amount), toAccount);
    }
    @Override
    public CompletableFuture<BankStatus> transferFromLockedRealAsync(double amount, int toAccount)
    {
        ISyncServerBankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().getBankAccount(toAccount);
        if(account == null)
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_BANK);
        ISyncServerBank bank = account.getBank(itemID);
        if(bank == null)
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_BANK);
        return CompletableFuture.completedFuture(transferFromLockedReal(amount, bank));
    }




    @Override
    public BankStatus transferFromLockedPrefered(long amount, @NotNull ISyncServerBank other) {
        // Task #33: bound branch. Decompose into withdrawLockedPrefered + deposit.
        // Unbound path unchanged.
        BindingRow boundRow = lookupBindingRow();
        if (boundRow != null) {
            if (amount < 0) return BankStatus.FAILED_NEGATIVE_VALUE;
            long lockedBefore = boundRow.lockedBalance();
            BankStatus w = this.withdrawLockedPrefered(amount);
            if (w != BankStatus.SUCCESS) return w;
            BankStatus d = other.deposit(amount);
            if (d == BankStatus.SUCCESS) return BankStatus.SUCCESS;
            // Rollback: deposit back into the external side and restore the pre-op lock.
            if (!this.deposit(amount).equals(BankStatus.SUCCESS)) {
                if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
                    BACKEND_INSTANCES.LOGGER.error("[ServerBank] transferFromLockedPrefered "
                            + "rollback deposit failed on bound slot " + accountId + "/" + itemID
                            + " after other.deposit failed with " + d
                            + " — external funds may be off by " + amount);
                }
            } else {
                BankAccountBindings bindings = BankAccountBindings.get();
                if (bindings != null) bindings.setLocked(accountId, itemID, lockedBefore);
            }
            return d;
        }
        if(amount < 0)
            return BankStatus.FAILED_NEGATIVE_VALUE;
        long origAmount = amount;
        long lastLocked = lockedBalance;
        if (lockedBalance < amount) {
            if (balance+lockedBalance < amount) {
                return BankStatus.FAILED_NOT_ENOUGH_FUNDS;
            }
            amount -= lockedBalance;
            lockedBalance = 0;
            BankStatus otherBankStatus = other.deposit(origAmount);
            if(otherBankStatus == BankStatus.SUCCESS) {
                addBalanceInternal(-amount);
                return BankStatus.SUCCESS;
            }
            lockedBalance = lastLocked;
            return otherBankStatus;
        }

        lockedBalance -= amount;
        BankStatus otherBankStatus = other.deposit(amount);
        if(otherBankStatus == BankStatus.SUCCESS) {
            changeFlag = true;
            return BankStatus.SUCCESS;
        }
        lockedBalance = lastLocked;
        return otherBankStatus;
    }
    @Override
    public BankStatus transferFromLockedPrefered(long amount, int toAccount)
    {
        ISyncServerBankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().getBankAccount(toAccount);
        if(account == null)
            return BankStatus.FAILED_NO_BANK;
        ISyncServerBank bank = account.getBank(itemID);
        if(bank == null)
            return BankStatus.FAILED_NO_BANK;
        return transferFromLockedPrefered(amount, bank);
    }
    @Override
    public CompletableFuture<BankStatus> transferFromLockedPreferedAsync(long amount, int toAccount)
    {
        ISyncServerBankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().getBankAccount(toAccount);
        if(account == null)
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_BANK);
        ISyncServerBank bank = account.getBank(itemID);
        if(bank == null)
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_BANK);
        return CompletableFuture.completedFuture(transferFromLockedPrefered(amount, bank));
    }



    @Override
    public BankStatus transferFromLockedPreferedReal(double amount, @NotNull ISyncServerBank other) {
        return transferFromLockedPrefered(convertToRawAmount(amount), other);
    }
    @Override
    public BankStatus transferFromLockedPreferedReal(double amount, int toAccount)
    {
        return transferFromLockedPrefered(convertToRawAmount(amount), toAccount);
    }
    @Override
    public CompletableFuture<BankStatus> transferFromLockedPreferedRealAsync(double amount, int toAccount)
    {
        ISyncServerBankAccount account = BACKEND_INSTANCES.SERVER_BANK_MANAGER.getSync().getBankAccount(toAccount);
        if(account == null)
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_BANK);
        ISyncServerBank bank = account.getBank(itemID);
        if(bank == null)
            return CompletableFuture.completedFuture(BankStatus.FAILED_NO_BANK);
        return CompletableFuture.completedFuture(transferFromLockedPreferedReal(amount, bank));
    }



    @Override
    public BankStatus lockAmount(long amount) {
        // Task #33: bound branch. Dust-aware transactional move from free pool to
        // locked. Whole-native-unit portion physically withdraws from external;
        // sub-native-unit remainder stays as dust so no fraction is silently dropped.
        // Unbound path unchanged.
        BoundView bv = resolveBound();
        if (bv != null) {
            if (amount < 0) return BankStatus.FAILED_NEGATIVE_VALUE;
            if (amount == 0) return BankStatus.SUCCESS;
            if (!bv.isAvailable()) return BankStatus.FAILED_EXTERNAL_UNAVAILABLE;
            if (bv.freeTotal() < amount) return BankStatus.FAILED_NOT_ENOUGH_FUNDS;
            long[] split = splitBoundDelta(bv.externalBalance, bv.dust, -amount, bv.scale);
            long externalDelta = split[0];
            long newDust = split[1];
            if (externalDelta < 0 && !bv.external.withdraw(-externalDelta)) {
                return BankStatus.FAILED_NOT_ENOUGH_FUNDS;
            } else if (externalDelta > 0 && !bv.external.deposit(externalDelta)) {
                return BankStatus.FAILED_OVERFLOW;
            }
            BankAccountBindings bindings = BankAccountBindings.get();
            if (bindings != null) {
                bindings.setLocked(accountId, itemID, bv.row.lockedBalance() + amount);
                bindings.setDust(accountId, itemID, newDust);
            }
            // Issue #67 (v2.0.6): mark dirty so the change stream publishes.
            changeFlag = true;
            return BankStatus.SUCCESS;
        }
        if(amount < 0)
            return BankStatus.FAILED_NEGATIVE_VALUE;
        if (balance < amount) {
            return BankStatus.FAILED_NOT_ENOUGH_FUNDS;
        }
        addBalanceInternal(-amount);
        lockedBalance += amount;
        return BankStatus.SUCCESS;
    }
    @Override
    public CompletableFuture<BankStatus> lockAmountAsync(long amount) {
        return CompletableFuture.completedFuture(lockAmount(amount));
    }




    @Override
    public BankStatus lockAmountReal(double amount) {
        return lockAmount(convertToRawAmount(amount));
    }
    @Override
    public CompletableFuture<BankStatus> lockAmountRealAsync(double amount) {
        return CompletableFuture.completedFuture(lockAmountReal(amount));
    }




    @Override
    public BankStatus unlockAmount(long amount) {
        // Task #33: bound branch. Dust-aware transactional move from locked back to
        // free pool. External deposit only for the whole-native-unit portion of the
        // change; sub-native remainder shifts into dust. Unbound path unchanged.
        BoundView bv = resolveBound();
        if (bv != null) {
            if (amount < 0) return BankStatus.FAILED_NEGATIVE_VALUE;
            long clamped = Math.min(amount, bv.row.lockedBalance());
            if (clamped == 0) return BankStatus.SUCCESS;
            if (!bv.isAvailable()) return BankStatus.FAILED_EXTERNAL_UNAVAILABLE;
            long[] split = splitBoundDelta(bv.externalBalance, bv.dust, clamped, bv.scale);
            long externalDelta = split[0];
            long newDust = split[1];
            if (externalDelta > 0 && !bv.external.deposit(externalDelta)) {
                if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
                    BACKEND_INSTANCES.LOGGER.error("[ServerBank] unlockAmount deposit of " + externalDelta
                            + " to external provider failed on bound slot " + accountId + "/" + itemID
                            + " — external overflow or unavailable. Locked funds (" + clamped
                            + ") remain stuck locally. Manual intervention or retry on next unlock required.");
                }
                return BankStatus.FAILED_EXTERNAL_UNAVAILABLE;
            } else if (externalDelta < 0 && !bv.external.withdraw(-externalDelta)) {
                // Rare dust-rebalance withdraw during unlock — treat like the above.
                return BankStatus.FAILED_NOT_ENOUGH_FUNDS;
            }
            BankAccountBindings bindings = BankAccountBindings.get();
            if (bindings != null) {
                bindings.setLocked(accountId, itemID, bv.row.lockedBalance() - clamped);
                bindings.setDust(accountId, itemID, newDust);
            }
            // Issue #67 (v2.0.6): mark dirty so the change stream publishes.
            changeFlag = true;
            return BankStatus.SUCCESS;
        }
        if(amount < 0)
            return BankStatus.FAILED_NEGATIVE_VALUE;
        if (lockedBalance < amount) {
            return BankStatus.FAILED_NOT_ENOUGH_FUNDS;
        }
        addBalanceInternal(amount);
        lockedBalance -= amount;
        return BankStatus.SUCCESS;
    }
    @Override
    public CompletableFuture<BankStatus> unlockAmountAsync(long amount) {
        return CompletableFuture.completedFuture(unlockAmount(amount));
    }




    @Override
    public BankStatus unlockAmountReal(double amount) {
        return unlockAmount(convertToRawAmount(amount));
    }
    @Override
    public CompletableFuture<BankStatus> unlockAmountRealAsync(double amount) {
        return CompletableFuture.completedFuture(unlockAmountReal(amount));
    }



    @Override
    public void unlockAll()
    {
        // Task #33: bound branch — deposit locked funds back to external via the
        // dust-aware split, then clear the local locked field. If the external op
        // fails, log an ERROR and leave locked untouched so the funds stay tracked
        // and can be retried on a later unlock. Unbound path unchanged.
        BoundView bv = resolveBound();
        if (bv != null) {
            long lockedLocal = bv.row.lockedBalance();
            if (lockedLocal <= 0) return;
            if (!bv.isAvailable()) {
                if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
                    BACKEND_INSTANCES.LOGGER.error("[ServerBank] unlockAll on bound slot "
                            + accountId + "/" + itemID + " skipped — external provider unavailable. "
                            + "Locked funds (" + lockedLocal + ") stay tracked locally; retry on next unlock.");
                }
                return;
            }
            long[] split = splitBoundDelta(bv.externalBalance, bv.dust, lockedLocal, bv.scale);
            long externalDelta = split[0];
            long newDust = split[1];
            if (externalDelta > 0 && !bv.external.deposit(externalDelta)) {
                if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
                    BACKEND_INSTANCES.LOGGER.error("[ServerBank] unlockAll deposit of " + externalDelta
                            + " to external provider failed on bound slot " + accountId + "/" + itemID
                            + " — external overflow or unavailable. Locked funds stay tracked locally; manual intervention or retry required.");
                }
                return;
            } else if (externalDelta < 0 && !bv.external.withdraw(-externalDelta)) {
                if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
                    BACKEND_INSTANCES.LOGGER.error("[ServerBank] unlockAll dust-rebalance withdraw of " + (-externalDelta)
                            + " failed on bound slot " + accountId + "/" + itemID + ". Locked funds stay tracked locally.");
                }
                return;
            }
            BankAccountBindings bindings = BankAccountBindings.get();
            if (bindings != null) {
                bindings.setLocked(accountId, itemID, 0L);
                bindings.setDust(accountId, itemID, newDust);
            }
            // Issue #67 (v2.0.6): mark dirty so the change stream publishes.
            changeFlag = true;
            return;
        }
        addBalanceInternal(lockedBalance);
        lockedBalance = 0;
    }
    @Override
    public void unlockAllAsync()
    {
        unlockAll();
    }



    @Override
    public long convertToRawAmount(double realAmount)
    {
        return BankManager.convertToRawAmountStatic(realAmount);
    }
    @Override
    public CompletableFuture<Long> convertToRawAmountAsync(double realAmount)
    {
        return CompletableFuture.completedFuture(convertToRawAmount(realAmount));
    }




    @Override
    public double convertToRealAmount(long rawAmount)
    {
        return BankManager.convertToRealAmountStatic(rawAmount);
    }
    @Override
    public CompletableFuture<Double> convertToRealAmountAsync(long rawAmount)
    {
        return CompletableFuture.completedFuture(BankManager.convertToRealAmountStatic(rawAmount));
    }








    @Override
    public String getNormalizedBalance()
    {
        // Route through getBalance so bound-slot displays are correct.
        return getNormalizedAmount(getBalance());
    }
    @Override
    public CompletableFuture<String> getNormalizedBalanceAsync()
    {
        return CompletableFuture.completedFuture(getNormalizedAmount(getBalance()));
    }




    @Override
    public String getNormalizedLockedBalance()
    {
        return getNormalizedAmount(getLockedBalance());
    }
    @Override
    public CompletableFuture<String> getNormalizedLockedBalanceAsync()
    {
        return CompletableFuture.completedFuture(getNormalizedAmount(getLockedBalance()));
    }




    @Override
    public String getNormalizedTotalBalance()
    {
        return getNormalizedAmount(getTotalBalance());
    }
    @Override
    public CompletableFuture<String> getNormalizedTotalBalanceAsync()
    {
        return CompletableFuture.completedFuture(getNormalizedAmount(getTotalBalance()));
    }





    @Override
    public String getFormattedBalance()
    {
        return getFormattedAmount(getBalance());
    }
    @Override
    public CompletableFuture<String> getFormattedBalanceAsync()
    {
        return CompletableFuture.completedFuture(getFormattedAmount(getBalance()));
    }




    @Override
    public String getFormattedLockedBalance()
    {
        return getFormattedAmount(getLockedBalance());
    }
    @Override
    public CompletableFuture<String> getFormattedLockedBalanceAsync()
    {
        return CompletableFuture.completedFuture(getFormattedAmount(getLockedBalance()));
    }



    @Override
    public String getFormattedTotalBalance()
    {
        return getFormattedAmount(getTotalBalance());
    }
    @Override
    public CompletableFuture<String> getFormattedTotalBalanceAsync()
    {
        return CompletableFuture.completedFuture(getFormattedAmount(getTotalBalance()));
    }



    @Override
    public String getNormalizedAmount(double realAmount)
    {
        long amount = BankManager.convertToRawAmountStatic(realAmount);
        return getNormalizedAmountStatic(amount);
    }
    @Override
    public CompletableFuture<String> getNormalizedAmountAsync(double realAmount)
    {
        return CompletableFuture.completedFuture(getNormalizedAmountStatic(realAmount));
    }




    @Override
    public String getNormalizedAmount(long rawAmount)
    {
        return getNormalizedAmountStatic(rawAmount);
    }
    @Override
    public CompletableFuture<String> getNormalizedAmountAsync(long rawAmount)
    {
        return CompletableFuture.completedFuture(getNormalizedAmountStatic(rawAmount));
    }




    @Override
    public String getFormattedAmount(double realAmount)
    {
        long amount = BankManager.convertToRawAmountStatic(realAmount);
        return getFormattedAmountStatic(amount);
    }
    @Override
    public CompletableFuture<String> getFormattedAmountAsync(double realAmount)
    {
        return CompletableFuture.completedFuture(getFormattedAmountStatic(realAmount));
    }




    @Override
    public String getFormattedAmount(long rawAmount)
    {
        return getFormattedAmountStatic(rawAmount);
    }
    @Override
    public CompletableFuture<String> getFormattedAmountAsync(long rawAmount)
    {
        return CompletableFuture.completedFuture(getFormattedAmountStatic(rawAmount));
    }





    @Override
    public String toString()
    {
        return toJsonString();
    }
    @Override
    public CompletableFuture<String> toStringAsync()
    {
        return CompletableFuture.completedFuture(toJsonString());
    }

    @Override
    public String toStringNoOwner()
    {
        // Route through the delegating getters so bound slots show correctly.
        long free = getBalance();
        long locked = getLockedBalance();
        StringBuilder content = new StringBuilder(getItemName() + getFormattedTotalBalance());
        if(locked > 0) {
            content.append("(").append(BankSystemTextMessages.getBalanceDetailedMessage(
                    getFormattedAmount(free),
                    getFormattedAmount(locked))).append(")");
        }

        return content.toString();
    }
    @Override
    public CompletableFuture<String> toStringNoOwnerAsync()
    {
        return CompletableFuture.completedFuture(toStringNoOwner());
    }




    @Override
    public JsonElement toJson()
    {
        JsonObject json = new JsonObject();
        json.add("itemID", itemID.toJson());
        json.addProperty("balance", getNormalizedBalance());
        json.addProperty("lockedBalance", getNormalizedLockedBalance());
        return json;
    }
    @Override
    public CompletableFuture<JsonElement> toJsonAsync()
    {
        return CompletableFuture.completedFuture(toJson());
    }




    @Override
    public String toJsonString()
    {
        return JsonUtilities.toPrettyString(toJson());
    }
    @Override
    public CompletableFuture<String> toJsonStringAsync()
    {
        return CompletableFuture.completedFuture(JsonUtilities.toPrettyString(toJson()));
    }




    @Override
    public boolean save(CompoundTag tag) {
        CompoundTag itemTag = new CompoundTag();
        itemID.save(itemTag);
        tag.put("itemID", itemTag);
        // Task #33 (v2.0.5): for a currently-bound slot the local balance/lockedBalance
        // are NOT authoritative — the external mod is. Force-zero on save so a later
        // unbind path (which explicitly writes back the external balance) is the only
        // way values re-enter local storage. If the slot is not bound, save the usual
        // local values byte-for-byte identically to pre-Task-#33 behavior.
        boolean bound = lookupBindingRow() != null;
        tag.putLong("balance", bound ? 0L : balance);
        tag.putLong("lockedBalance", bound ? 0L : lockedBalance);
        return true;
    }

    @Override
    public boolean load(CompoundTag tag) {
        if(tag == null)
            return false;
        if(     !tag.contains("itemID") ||
                !tag.contains("balance") ||
                !tag.contains("lockedBalance"))
            return false;

        itemID = ItemID.INVALID_ID;
        if(tag.contains("itemID",Tag.TAG_STRING))
        {
            String itemIDStr = tag.getString("itemID");
            if(itemIDStr.equals("$") || itemIDStr.equals("money")) {
                itemID = ItemID.getFromItemStack(BankSystemItems.MONEY.get().getDefaultInstance());
            }
            else {
                ItemStack itemStack = ItemUtilities.createItemStackFromId(itemIDStr);
                if (itemStack != null && itemStack != ItemStack.EMPTY && !itemStack.is(Items.AIR))
                    itemID = ItemID.getFromItemStack(itemStack);
            }
        }
        else
        {
            CompoundTag itemTag = tag.getCompound("itemID");
            itemID = ItemID.createFromTag(itemTag);
            // Canonicalize at load time: the saved ID may have been merged into another ID
            // (alias) by a volatile-component merge — possibly in an EARLIER session, before
            // consolidation existed. Resolving here keys every freshly loaded bank by its
            // canonical ID; ServerBankAccount.load() then merges banks that collapse onto
            // the same canonical ID instead of dropping one. This also heals worlds that
            // were merged before this fix.
            itemID = ItemIDManager.resolveAlias(itemID);
        }


        long balance = tag.getLong("balance");
        lockedBalance = tag.getLong("lockedBalance");
        setBalanceInternal(balance);
        // Issue #61: when the item this bank held can no longer be resolved (its mod was
        // removed, or a removed mod's data component broke the saved template's parse), the
        // bank is dropped from the account and the balance is lost on the next save. That
        // matches vanilla, which deletes unknown items on load and never restores them if the
        // mod returns — but the loss must never be SILENT. Log it loudly with the item and the
        // amounts so an admin sees exactly what was lost and why.
        if (!itemID.isValid() && (balance > 0 || lockedBalance > 0)
                && BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null) {
            BACKEND_INSTANCES.LOGGER.warn("[ServerBank] Dropping a bank balance for an unresolvable item '"
                    + itemID.getName() + "' (balance=" + balance + ", locked=" + lockedBalance + "). "
                    + "Its mod is not installed on this server, so the balance cannot be restored and "
                    + "will be gone on the next save — this matches vanilla's handling of unknown items. "
                    + "Re-install the mod BEFORE loading/saving this world if you need to recover it.");
        }
        return balance >= 0 && lockedBalance >= 0 && itemID.isValid();
    }




    public static BankStatus exchangeFromLockedPrefered(@NotNull ISyncServerBank from1, @NotNull ISyncServerBank to1, long amount1,
                                                        @NotNull ISyncServerBank from2, @NotNull ISyncServerBank to2, long amount2)
    {
        if(amount1 < 0 || amount2 < 0)
            return BankStatus.FAILED_NEGATIVE_VALUE;

        if ((from1 instanceof  ServerBank castedFrom1) &&
                (to1 instanceof  ServerBank castedTo1) &&
                (from2 instanceof  ServerBank castedFrom2) &&
                (to2 instanceof  ServerBank castedTo2))
        {
            // Both transactions must be possible, otherwise no transaction is done
            // Copy original data
            long origFrom1LockedBalance1 = castedFrom1.lockedBalance;
            long origFrom2LockedBalance2 = castedFrom2.lockedBalance;
            long origFrom1Balance1 = castedFrom1.balance;
            long origFrom2Balance2 = castedFrom2.balance;
            long origTo1LockedBalance1 = castedTo1.lockedBalance;
            long origTo2LockedBalance2 = castedTo2.lockedBalance;
            long origTo1Balance1 = castedTo1.balance;
            long origTo2Balance2 = castedTo2.balance;
            boolean origFrom1ChangeFlag = castedFrom1.changeFlag;
            boolean origFrom2ChangeFlag = castedFrom2.changeFlag;
            boolean origTo1ChangeFlag = castedTo1.changeFlag;
            boolean origTo2ChangeFlag = castedTo2.changeFlag;

            // Try to transfer from locked balance
            BankStatus BankStatus1 = from1.transferFromLockedPrefered(amount1, to1);
            BankStatus BankStatus2 = from2.transferFromLockedPrefered(amount2, to2);
            if(BankStatus1 == BankStatus.SUCCESS && BankStatus2 == BankStatus.SUCCESS)
            {
                return BankStatus.SUCCESS;
            }
            // If not possible, revert changes
            castedFrom1.lockedBalance = origFrom1LockedBalance1;
            castedFrom2.lockedBalance = origFrom2LockedBalance2;
            castedFrom1.balance = origFrom1Balance1;
            castedFrom2.balance = origFrom2Balance2;
            castedTo1.lockedBalance = origTo1LockedBalance1;
            castedTo2.lockedBalance = origTo2LockedBalance2;
            castedTo1.balance = origTo1Balance1;
            castedTo2.balance = origTo2Balance2;
            castedTo2.changeFlag = origTo2ChangeFlag;
            castedTo1.changeFlag = origTo1ChangeFlag;
            castedFrom1.changeFlag = origFrom1ChangeFlag;
            castedFrom2.changeFlag = origFrom2ChangeFlag;

            if(BankStatus1 == BankStatus.SUCCESS)
                return BankStatus2;
            return BankStatus1;
        }
        return BankStatus.FAILED_WRONG_INSTANCE_TYPE;
    }

    /**
     * Absorbs the balances of {@code other} into this bank and empties {@code other}.
     * Used exclusively when two banks collapse onto the same canonical ItemID after an
     * ItemID alias merge (volatile-component merge): both the free and the locked balance
     * are preserved, so the sum over the pair is identical before and after (unless a
     * — practically impossible — long overflow forces a clamp, which is logged as an error).
     *
     * @param other the bank whose balances are merged into this one (emptied afterwards)
     */
    public void absorb(@NotNull ServerBank other) {
        this.balance = addClamped(this.balance, other.balance, "balance");
        this.lockedBalance = addClamped(this.lockedBalance, other.lockedBalance, "lockedBalance");
        other.balance = 0;
        other.lockedBalance = 0;
        this.changeFlag = true;
    }

    /**
     * <b>Internal — ItemID alias-merge consolidation only.</b>
     * Rewrites this bank's ItemID to the given canonical ID after its previous ID was
     * merged away as an alias. Deliberately bypasses {@link #create(ItemID, long)}'s
     * allowed-item check: an existing balance must never be droppable by a failed
     * re-creation. Do not use for anything else — a bank's ItemID is otherwise immutable.
     *
     * @param canonical the canonical ItemID that replaced this bank's aliased ID
     */
    public void rekeyForAliasMerge_internal(@NotNull ItemID canonical) {
        this.itemID = canonical;
        this.changeFlag = true;
    }

    /**
     * <b>Internal — external-currency unbind service only (Task #33, v2.0.5).</b>
     * Writes the local {@code balance} / {@code lockedBalance} fields directly,
     * bypassing every check the public API performs. Called by the unbind path in
     * {@code ServerBankManager} after a final external balance read: the freshly
     * unbound slot re-materializes into native storage without routing through
     * the still-bound public API (which would still delegate to the external mod
     * that just got unbound).
     *
     * @param balance       new free balance in raw units (clamped to {@code >= 0})
     * @param lockedBalance new locked balance in raw units (clamped to {@code >= 0})
     */
    public void writeLocalFieldsForUnbind_internal(long balance, long lockedBalance) {
        this.balance = Math.max(0L, balance);
        this.lockedBalance = Math.max(0L, lockedBalance);
        this.changeFlag = true;
    }

    /**
     * <b>Internal — external-currency bind service only (Issue #67, v2.0.6).</b>
     * Seeds {@link #lastSeenExternalBalance} with the external balance observed
     * at bind time so the very first {@link #pollExternalDrift()} tick after bind
     * does not fire a spurious flag flip (the cache already matches external).
     * Called by {@code ServerBankManager.bindExternalAccount} right after commit.
     *
     * @param externalBalance the balance {@code external.getBalance()} returned
     *                        at bind time
     */
    public void primeDriftCache(long externalBalance) {
        this.lastSeenExternalBalance = externalBalance;
    }

    /**
     * Watchdog poll (Issue #67, v2.0.6). Detects out-of-band mutations of the
     * external balance — the player used the third-party mod's own UI
     * (Numismatics Bank Terminal, Lightman's ATM, ...) while a BankSystem
     * terminal was open elsewhere. Every 1 Hz {@code ServerBankManager} tick:
     * compare {@code external.getBalance()} to {@link #lastSeenExternalBalance};
     * on mismatch, flip {@link #changeFlag} so the ARRS change stream fires.
     * <p>
     * Silent short-circuits (no allocation, no logging, no cache mutation):
     * <ul>
     *   <li>slot is not bound — nothing to watch;</li>
     *   <li>provider is not registered or reports unavailable — no reliable read;</li>
     *   <li>{@code provider.open(ref)} returns {@code null} — same as above.</li>
     * </ul>
     * The cache is updated ONLY when a real drift is detected — a matching poll
     * touches nothing.
     */
    public void pollExternalDrift() {
        BindingRow row = lookupBindingRow();
        if (row == null) return;
        ExternalCurrencyProvider provider =
                BankSystemMod.getAPI().getCurrencyProvider(row.ref().providerId());
        if (provider == null || !provider.isAvailable()) return;
        ExternalAccount external = provider.open(row.ref());
        if (external == null) return;
        long current = external.getBalance();
        if (current != lastSeenExternalBalance) {
            lastSeenExternalBalance = current;
            changeFlag = true;
        }
    }

    /**
     * Overflow-safe addition of two non-negative balances; clamps to {@link Long#MAX_VALUE}
     * and logs an error when the (practically impossible) overflow occurs.
     */
    private long addClamped(long a, long b, String what) {
        if (willAdditionOverflow(a, b)) {
            if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null)
                BACKEND_INSTANCES.LOGGER.error("[ServerBank] Overflow while merging " + what +
                        " of ItemID " + itemID + " during alias consolidation — clamping to Long.MAX_VALUE.");
            return Long.MAX_VALUE;
        }
        return a + b;
    }

    private void addBalanceInternal(long balance) {
        setBalanceInternal(this.balance + balance);
    }
    private void setBalanceInternal(long balance) {
        if(balance != this.balance) {
            this.balance = balance;
            changeFlag = true;
        }
    }



    public static long convertToRawAmountStatic(String realTextboxText) // 1864165.05
    {
        if(realTextboxText == null)
            return 0;
        if(realTextboxText.isEmpty())
            return 0;
        int decimalPlaces = realTextboxText.lastIndexOf(".");
        if(decimalPlaces == -1)
            decimalPlaces =  realTextboxText.lastIndexOf(",");
        try {
            if (decimalPlaces == -1)
                return Long.parseLong(realTextboxText) * BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
        }catch (NumberFormatException e) {
            return 0;
        }
        long A = 0;
        long B = 0;
        try{
            A = Long.parseLong(realTextboxText.substring(0, decimalPlaces)) * BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
        }catch (NumberFormatException ignored) {
            if (!realTextboxText.substring(0, decimalPlaces).isEmpty()) {
                return 0;
            }
        }
        try {
            String fracStr = realTextboxText.substring(decimalPlaces + 1);
            int scaleDigits = String.valueOf(BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR).length() - 1;
            if (fracStr.length() > scaleDigits)
                fracStr = fracStr.substring(0, scaleDigits);
            while (fracStr.length() < scaleDigits)
                fracStr = fracStr + "0";
            B = Long.parseLong(fracStr);
        }catch (NumberFormatException ignored) {

        }
        if (A < 0)
            return A - B;
        return A + B;
    }


    // (1000 means 10.00 currency units)
    public static String getNormalizedAmountStatic(long amount)
    {
        // depending on the exponent of the amount add a "k", "M", "G", "T", "P", "E", "Z", "Y"
        // 1.0e3 = 1k
        // 1.0e6 = 1M
        // 1.0e9 = 1G
        // 1.0e12 = 1T
        // 1.0e15 = 1P
        // 1.0e18 = 1E
        String exponents = "kMGTPEZY";

        if(amount < 0) {
            BACKEND_INSTANCES.LOGGER.warn("[ServerBank] getNormalizedAmountStatic called with negative amount: " + amount);
            return "0";
        }
        if(amount == 0)
            return "0";

        long wholeUnits = amount / BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;

        String amountString = String.valueOf(wholeUnits);
        if(wholeUnits <= 0)
        {
            double cents = (amount % BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR) / (double)BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
            String centsString = String.valueOf(cents);
            if(centsString.startsWith("0."))
                centsString = centsString.substring(2);
            if(BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR > 1)
                return "0." + centsString;
            return "0";
        }

        double logResult = Math.log10(wholeUnits);
        int exponent = (int)logResult;
        int exponent3 = exponent/3;
        if(exponent3 > 0)
        {
            int modValue = (exponent)%3+1;
            String firstPart = amountString.substring(0, modValue);
            if(firstPart.isEmpty())
                firstPart = "0";
            int endIdx = Math.min(modValue+2, amountString.length());
            String secondPart = amountString.substring(modValue, endIdx);

            amountString = firstPart+"."+secondPart + exponents.charAt(exponent3-1);
        }
        else
        {
            double cents = (amount % BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR) / (double)BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;

            String centsString = String.valueOf(cents);

            if(centsString.startsWith("0.")) {
                centsString = centsString.substring(2);
            }
            if(BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR > 1)
                amountString = amountString + "." + centsString;
        }
        return amountString;
    }
    public static String getNormalizedAmountStatic(double realAmount)
    {
        long amount = BankManager.convertToRawAmountStatic(realAmount);
        return getNormalizedAmountStatic(amount);
    }

    public static String getFormattedAmountStatic(double realAmount)
    {
        long amount = BankManager.convertToRawAmountStatic(realAmount);
        return getFormattedAmountStatic(amount);
    }

    public static String getFormattedAmountStatic(long rawAmount)
    {
        return getFormattedAmountStatic(rawAmount, BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR);
    }

    /**
     * Ratio-aware overload (Task #38b, v2.0.5). {@code itemFractionScaleFactor}
     * defaults to {@link BankSystemModSettings#ITEM_FRACTION_SCALE_FACTOR} (100);
     * bound slots pass the provider's per-item ratio (e.g. 81 for a Lightman's
     * gold slot) so the formatted string reports the correct physical-coin
     * count.
     */
    public static String getFormattedAmountStatic(long rawAmount, int itemFractionScaleFactor)
    {
        if (itemFractionScaleFactor <= 0) itemFractionScaleFactor = BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
        String nr = String.valueOf(rawAmount/ itemFractionScaleFactor);
        // add ' for every 3 digits
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for(int j = nr.length()-1; j >= 0; j--)
        {
            sb.append(nr.charAt(j));
            i++;
            if(i % 3 == 0 && j > 0)
                sb.append('\'');
        }
        sb.reverse();
        if(rawAmount % itemFractionScaleFactor != 0)
        {

            double cents = (rawAmount % itemFractionScaleFactor) / (double)itemFractionScaleFactor; // Convert to cents

            String centsString = String.valueOf(cents);

            // Remove "0." prefix if cents are zero
            if(centsString.startsWith("0.")) {
                centsString = centsString.substring(1);
            }
            sb.append(centsString);
        }
        return sb.toString();
    }
    public static String getTextFieldString(long amount)
    {
        long A = amount / BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;
        long B = amount % BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;

        StringBuilder sb = new StringBuilder();
        sb.append(A);
        sb.append(".");

        // Add filling zeros if needed
        int digitsCount = (int)Math.log10(BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR);
        String BStr = String.valueOf(B);
        int addZeroCount = digitsCount - BStr.length();
        sb.append("0".repeat(Math.max(0, addZeroCount)));
        sb.append(BStr);
        return sb.toString();
    }

    public static int getMaxDecimalDigitsCount()
    {
        return (int)Math.log10(BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR);
    }

    private boolean willOverflow(long tryToAddAmount)
    {
        if (willAdditionOverflow(balance, lockedBalance)) return true;
        return willAdditionOverflow(balance + lockedBalance, tryToAddAmount);
    }
    private static boolean willAdditionOverflow(long a, long b) {
        return Long.MAX_VALUE - a < b;
    }
}
