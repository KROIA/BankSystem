package net.kroia.banksystem.api.event;

import org.jetbrains.annotations.Nullable;

/**
 * Payload of {@link net.kroia.banksystem.api.IBankSystemEvents#getSuspicionFlaggedEvent()}.
 * <p>
 * One suspicion flag raised on the <b>master JVM only</b>, fired after the flag row was queued
 * for saving and the audit line / admin alert were issued.
 *
 * @param accountNumber flagged account ({@code 0} for server-wide flags such as money-supply growth)
 * @param flagType      append-only flag code
 * @param flagName      constant name of {@code flagType} (e.g. {@code "WEALTH_JUMP"}), or {@code "FLAG_<code>"}
 * @param severity      free-form, higher = worse
 * @param amount        raw amount that triggered the flag ({@code 0} if none)
 * @param ref           optional reference (e.g. ledger or order ref), or {@code null}
 * @param details       human-readable reason, or {@code null}
 * @param timeMs        flag time, epoch milliseconds
 * @since 2.2.0
 */
public record SuspicionFlaggedEvent(int accountNumber, int flagType, String flagName, int severity,
                                    long amount, @Nullable String ref, @Nullable String details, long timeMs) {
}
