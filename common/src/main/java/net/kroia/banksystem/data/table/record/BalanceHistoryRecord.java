package net.kroia.banksystem.data.table.record;

public record BalanceHistoryRecord(
    int accountNumber,
    short itemId,
    long balance,
    long lockedBalance,
    long time
) {}
