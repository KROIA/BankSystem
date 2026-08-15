package net.kroia.banksystem.banking.company;

import org.jetbrains.annotations.Nullable;

/**
 * Task #52 (v2.1.0) — immutable snapshot of one dividend distribution event,
 * persisted by {@link DividendHistoryStore}.
 */
public record DividendEvent(
        int companyId,
        @Nullable Integer scheduleId,
        long timestampMs,
        short currencyShort,
        long perShareRaw,
        long totalRaw,
        int holderCount,
        String sourceKind) {
}
