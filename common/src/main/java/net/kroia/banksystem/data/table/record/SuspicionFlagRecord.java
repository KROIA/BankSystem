package net.kroia.banksystem.data.table.record;

import org.jetbrains.annotations.Nullable;

/**
 * Task #110 (v2.2.0) — one row of the {@code SuspicionFlag} table, shared by the flag tasks
 * (#111, #114, #115). {@code flagType} is an append-only int code owned by the task that
 * raises it; {@code severity} is free-form (higher = worse).
 */
public record SuspicionFlagRecord(long id, long time, int accountNumber, int flagType, int severity,
                                  long amount, @Nullable String ref, @Nullable String details,
                                  boolean acknowledged) {
    public static final long UNSAVED_ID = -1L;
}
