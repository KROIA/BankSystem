package net.kroia.banksystem.banking.share;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.PayDividendResult;
import net.kroia.banksystem.api.bank.BankStatus;
import net.kroia.banksystem.api.bank.ISyncServerBank;
import net.kroia.banksystem.api.bankaccount.IServerBankAccount;
import net.kroia.banksystem.api.bankmanager.ISyncServerBankManager;
import net.kroia.banksystem.api.dividend.IDividendPayer;
import net.kroia.banksystem.api.event.DividendPaidEvent;
import net.kroia.banksystem.banking.company.Company;
import net.kroia.banksystem.banking.company.CompanyManager;
import net.kroia.banksystem.data.table.record.TransactionLogRecord;
import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem;
import net.kroia.banksystem.util.BankSystemEvents;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Task #49 (v2.0.8) — Company Feature Phase 5: one-shot dividend distributor. Master-only.
 * <p>
 * Resolves the company's stamped-share {@link ItemID}, snapshots the balance of every
 * account holding that share via {@link ISyncServerBankManager#listAccountsHolding(ItemID)},
 * precomputes {@code total = sum(holderShares) * amountPerShare}, verifies the company
 * money bank can afford it, then transfers atomically from the company's money bank to
 * each holder's money bank. Refuses the whole run on insufficient funds (no state change).
 * <p>
 * Writes {@code TransactionLog.DIVIDEND} rows (one out on the company account, one in on
 * each holder). Fires {@link DividendPaidEvent} once per successful run.
 * <p>
 * Runs on the server thread — the caller is responsible for dispatching there (the
 * ARRS master handler and the BankSystemAPI shim both already run on the server thread).
 */
public final class DividendPayer implements IDividendPayer {

    private final BankSystemModBackend.Instances instances;

    public DividendPayer(BankSystemModBackend.Instances instances) {
        this.instances = instances;
    }

    @Override
    public PayDividendResult payDividend(int companyId, long amountPerShare,
                                         boolean includeCompanyAccount,
                                         @Nullable UUID actor,
                                         short currencyItem) {
        if (instances == null || instances.isSlaveServer) return PayDividendResult.of(PayDividendResult.Reason.NOT_MASTER);
        if (amountPerShare <= 0L) return PayDividendResult.of(PayDividendResult.Reason.INVALID_INPUT);

        CompanyManager cm = CompanyManager.get();
        if (cm == null) return PayDividendResult.of(PayDividendResult.Reason.NOT_MASTER);
        Company company = cm.getById(companyId);
        if (company == null) return PayDividendResult.of(PayDividendResult.Reason.COMPANY_MISSING);

        ISyncServerBankManager bm = instances.SERVER_BANK_MANAGER != null
                ? instances.SERVER_BANK_MANAGER.getSync() : null;
        if (bm == null) return PayDividendResult.of(PayDividendResult.Reason.INTERNAL);

        // Resolve the stamped-share ItemID for this company. If it has never been
        // registered (no shares stamped yet), listAccountsHolding will return empty.
        ItemStack template = StampedShareItem.ofCompany(BankSystemItems.STAMPED_SHARE.get(), companyId);
        ItemID shareItemId = ItemIDManager.getItemID(template);
        if (shareItemId == null || !shareItemId.isValid()) {
            return PayDividendResult.of(PayDividendResult.Reason.NO_SHARES);
        }

        Set<Integer> holderAccounts = bm.listAccountsHolding(shareItemId);
        int companyAccountNr = company.getBankAccountNr();

        // BUG batch 4 (v2.0.8) — item banks store balances in RAW fixed-point units
        // (physical count * ITEM_FRACTION_SCALE_FACTOR); ShareStamper / BankUpload
        // deposits go through {@code depositRealAsync(count)} which multiplies by
        // SCALE. Previously this code treated {@code getTotalBalance()} as a plain
        // share count and produced dividend payouts inflated by exactly 100×.
        // Convert to physical share count up front so {@code amountPerShare * shares}
        // yields the correct raw money total.
        final long SCALE = (long) net.kroia.banksystem.BankSystemModSettings.ITEM_FRACTION_SCALE_FACTOR;

        // Upfront snapshot — iterated even if a subsequent market match mutates a
        // holder's balance mid-run (spec §5 concurrency requirement).
        List<HolderSnap> snapshots = new ArrayList<>();
        long totalShares = 0L;
        // Bug batch 3 #5 (v2.0.8) — the company's own account is ALWAYS excluded
        // from dividend distribution regardless of the includeCompanyAccount flag.
        // Paying the company its own money is a no-op and only confuses the ledger.
        // The parameter is retained for API/wire compatibility.
        for (int accountNr : holderAccounts) {
            if (accountNr == companyAccountNr) continue;
            IServerBankAccount account = bm.getBankAccount(accountNr);
            if (account == null) continue;
            ISyncServerBank shareBank = account.getBank(shareItemId);
            if (shareBank == null) continue;
            long rawShares = shareBank.getTotalBalance();
            long shares = rawShares / SCALE; // convert raw storage → physical share count
            if (shares <= 0L) continue;
            snapshots.add(new HolderSnap(accountNr, shares));
            totalShares += shares;
        }
        if (instances.LOGGER != null) {
            instances.LOGGER.info("[DividendPayer] payDividend company=" + companyId
                    + " amountPerShareRaw=" + amountPerShare + " totalHolders=" + snapshots.size()
                    + " totalPhysicalShares=" + totalShares);
        }
        if (totalShares == 0L || snapshots.isEmpty()) {
            return PayDividendResult.of(PayDividendResult.Reason.NO_SHARES);
        }

        // Overflow-safe total. If per-share * shares would overflow, refuse.
        long total;
        try {
            total = Math.multiplyExact(totalShares, amountPerShare);
        } catch (ArithmeticException e) {
            return PayDividendResult.of(PayDividendResult.Reason.INVALID_INPUT);
        }

        IServerBankAccount sourceAccount = bm.getBankAccount(companyAccountNr);
        if (sourceAccount == null) return PayDividendResult.of(PayDividendResult.Reason.INTERNAL);

        // Resolve the payout currency: sentinel 0 → money, else the registered item.
        boolean isMoney = (currencyItem == net.kroia.banksystem.banking.company.PayoutSchedule.MONEY_CURRENCY);
        ItemID payoutId;
        if (isMoney) {
            payoutId = MoneyItem.getItemID();
            if (payoutId == null || !payoutId.isValid()) return PayDividendResult.of(PayDividendResult.Reason.INTERNAL);
        } else {
            payoutId = new ItemID(currencyItem);
        }

        ISyncServerBank sourceMoneyBank = sourceAccount.getBank(payoutId);
        if (sourceMoneyBank == null) {
            return PayDividendResult.of(isMoney
                    ? PayDividendResult.Reason.INTERNAL
                    : PayDividendResult.Reason.CURRENCY_ITEM_MISSING);
        }

        // All-or-nothing precheck — free (unlocked) balance only. Locked funds do
        // not settle dividends. Consistent with PayoutExecutor.transfer semantics
        // (transfer moves from the free balance).
        if (sourceMoneyBank.getBalance() < total) {
            return PayDividendResult.of(PayDividendResult.Reason.INSUFFICIENT_FUNDS);
        }

        long nowMs = System.currentTimeMillis();
        short payoutShort = payoutId.getShort();
        List<TransactionLogRecord> ledger = new ArrayList<>(snapshots.size() * 2);
        long paid = 0L;
        int paidHolders = 0;

        for (HolderSnap snap : snapshots) {
            long payAmount;
            try {
                payAmount = Math.multiplyExact(snap.shares, amountPerShare);
            } catch (ArithmeticException e) {
                // Should not happen — the aggregate multiplication above already
                // caught overflow. Skip defensively.
                continue;
            }
            IServerBankAccount targetAccount = bm.getBankAccount(snap.accountNr);
            if (targetAccount == null) continue;
            ISyncServerBank targetBank = targetAccount.getBank(payoutId);
            if (targetBank == null) {
                targetBank = targetAccount.getOrCreateBank(payoutId);
                if (targetBank == null) continue;
            }
            BankStatus status = sourceMoneyBank.transfer(payAmount, targetBank);
            if (status != BankStatus.SUCCESS) {
                // Precheck should have guaranteed sufficient funds; log and skip.
                if (instances.LOGGER != null) {
                    instances.LOGGER.warn("[DividendPayer] transfer failed post-precheck for company "
                            + companyId + " holder account " + snap.accountNr + " status=" + status);
                }
                continue;
            }
            paid += payAmount;
            paidHolders++;
            if (instances.LOGGER != null) {
                instances.LOGGER.debug("[DividendPayer] company=" + companyId
                        + " holder account=" + snap.accountNr + " physicalShares=" + snap.shares
                        + " payAmountRaw=" + payAmount);
            }
            // Outbound row on company account, inbound row on holder.
            ledger.add(new TransactionLogRecord(TransactionLogRecord.UNSAVED_ID,
                    companyAccountNr, actor, TransactionLogRecord.Kind.DIVIDEND, payoutShort,
                    payAmount, snap.accountNr, companyId, nowMs, null));
            ledger.add(new TransactionLogRecord(TransactionLogRecord.UNSAVED_ID,
                    snap.accountNr, actor, TransactionLogRecord.Kind.DIVIDEND, payoutShort,
                    payAmount, companyAccountNr, companyId, nowMs, null));
        }

        if (instances.TRANSACTION_LOG_MANAGER != null && !ledger.isEmpty()) {
            instances.TRANSACTION_LOG_MANAGER.save(ledger);
        }

        if (instances.SERVER_EVENTS instanceof BankSystemEvents bse) {
            bse.DIVIDEND_PAID.notifyListeners(
                    new DividendPaidEvent(companyId, amountPerShare, paid, paidHolders, nowMs));
        }

        return PayDividendResult.ok(paid, paidHolders);
    }

    private record HolderSnap(int accountNr, long shares) {}
}
