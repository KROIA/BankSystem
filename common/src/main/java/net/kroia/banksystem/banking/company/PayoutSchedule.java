package net.kroia.banksystem.banking.company;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * Value type for a recurring payout schedule attached to a Company.
 * <p>
 * Spec B.1–B.4 (v2.1.0) schema extensions — all fields are load-compatible with
 * pre-B NBT saves (missing tags default):
 * <ul>
 *   <li>{@code targetAccountNr} — explicit target bank account (spec B.1 split
 *       picker); {@code -1} → legacy behavior (target's personal account).</li>
 *   <li>{@code targetPlayerName} / {@code targetAccountName} — display snapshots
 *       captured at write time (spec A.9); default {@code ""}.</li>
 *   <li>{@code mode} — {@link Mode#FIXED_PAYOUT} (default for existing rows)
 *       or {@link Mode#DIVIDEND} (spec B.2: amount is a total split across all
 *       shareholders proportional to holdings at fire time).</li>
 *   <li>{@code currencyItem} — ItemID short of the payout currency (spec B.3);
 *       {@code 0} → money (default).</li>
 *   <li>{@code missedAmount} / {@code missedCount} — missed-payout accumulator
 *       (spec B.4); grow on failed executions, shrink via manual catch-up.</li>
 * </ul>
 */
public final class PayoutSchedule {

    /** Spec B.2 — payout mode. Persisted by name; missing tag → FIXED_PAYOUT. */
    public enum Mode {
        FIXED_PAYOUT,
        DIVIDEND,
        /** Feature C — executes once at the scheduled time, then self-removes. */
        ONE_TIME
    }

    /** @return {@code true} when this schedule removes itself after one successful execution. */
    public boolean isOneTime() { return mode == Mode.ONE_TIME; }

    /** Sentinel for "no explicit target account — use the target's personal account". */
    public static final int NO_TARGET_ACCOUNT = -1;
    /** Sentinel currency short for "money" (the default currency). */
    public static final short MONEY_CURRENCY = 0;

    private final long scheduleId;
    private final UUID targetUUID;
    private final long amount;
    private final long intervalTicks;
    private final long nextRunTick;
    private final boolean paused;
    private final long createdAt;
    private final UUID createdBy;
    private final int targetAccountNr;
    private final String targetPlayerName;
    private final String targetAccountName;
    private final Mode mode;
    private final short currencyItem;
    private final long missedAmount;
    private final int missedCount;
    /**
     * Task #57b — {@code true} when this schedule was auto-paused because its currency item
     * was blacklisted (server-internal marker, persisted). Distinguishes a currency-ban pause
     * from a user-initiated pause so {@code allowItemID} auto-resumes ONLY ban-paused schedules
     * and never un-pauses a user pause. Not carried on the client wire (client only sees paused).
     */
    private final boolean pausedByCurrencyBan;

    public PayoutSchedule(long scheduleId, UUID targetUUID, long amount, long intervalTicks,
                          long nextRunTick, boolean paused, long createdAt, UUID createdBy,
                          int targetAccountNr, String targetPlayerName, String targetAccountName,
                          Mode mode, short currencyItem, long missedAmount, int missedCount,
                          boolean pausedByCurrencyBan) {
        this.scheduleId = scheduleId;
        this.targetUUID = targetUUID;
        this.amount = amount;
        this.intervalTicks = intervalTicks;
        this.nextRunTick = nextRunTick;
        this.paused = paused;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        this.targetAccountNr = targetAccountNr;
        this.targetPlayerName = targetPlayerName == null ? "" : targetPlayerName;
        this.targetAccountName = targetAccountName == null ? "" : targetAccountName;
        this.mode = mode == null ? Mode.FIXED_PAYOUT : mode;
        this.currencyItem = currencyItem;
        this.missedAmount = missedAmount;
        this.missedCount = missedCount;
        this.pausedByCurrencyBan = pausedByCurrencyBan;
    }

    /** 15-arg constructor — no currency-ban marker (defaults false). */
    public PayoutSchedule(long scheduleId, UUID targetUUID, long amount, long intervalTicks,
                          long nextRunTick, boolean paused, long createdAt, UUID createdBy,
                          int targetAccountNr, String targetPlayerName, String targetAccountName,
                          Mode mode, short currencyItem, long missedAmount, int missedCount) {
        this(scheduleId, targetUUID, amount, intervalTicks, nextRunTick, paused, createdAt,
                createdBy, targetAccountNr, targetPlayerName, targetAccountName, mode, currencyItem,
                missedAmount, missedCount, false);
    }

    /** Legacy-shape constructor — personal-account money payout, FIXED mode, no misses. */
    public PayoutSchedule(long scheduleId, UUID targetUUID, long amount, long intervalTicks,
                          long nextRunTick, boolean paused, long createdAt, UUID createdBy) {
        this(scheduleId, targetUUID, amount, intervalTicks, nextRunTick, paused, createdAt,
                createdBy, NO_TARGET_ACCOUNT, "", "", Mode.FIXED_PAYOUT, MONEY_CURRENCY, 0L, 0, false);
    }

    public long getScheduleId() { return scheduleId; }
    public UUID getTargetUUID() { return targetUUID; }
    public long getAmount() { return amount; }
    public long getIntervalTicks() { return intervalTicks; }
    public long getNextRunTick() { return nextRunTick; }
    public boolean isPaused() { return paused; }
    public long getCreatedAt() { return createdAt; }
    public UUID getCreatedBy() { return createdBy; }
    public int getTargetAccountNr() { return targetAccountNr; }
    public String getTargetPlayerName() { return targetPlayerName; }
    public String getTargetAccountName() { return targetAccountName; }
    public Mode getMode() { return mode; }
    public short getCurrencyItem() { return currencyItem; }
    /**
     * Bug B fix (v2.1.0) — treat both the canonical sentinel {@code 0} AND the
     * live MoneyItem short as money. Prevents UI unscaled-display when a schedule
     * was created/persisted with the raw MoneyItem short rather than the sentinel.
     */
    public boolean isMoneyCurrency() {
        if (currencyItem == MONEY_CURRENCY) return true;
        try {
            net.kroia.banksystem.util.ItemID mid =
                    net.kroia.banksystem.minecraft.item.custom.money.MoneyItem.getItemID();
            return mid != null && mid.getShort() == currencyItem;
        } catch (Throwable t) { return false; }
    }
    public long getMissedAmount() { return missedAmount; }
    public int getMissedCount() { return missedCount; }
    /** Task #57b — server-internal: paused because the currency item was blacklisted. */
    public boolean isPausedByCurrencyBan() { return pausedByCurrencyBan; }

    // ------------------------------------------------------------------
    // Copy-with helpers. PayoutSchedule stays immutable; Company keeps the
    // schedule list mutable via replaceSchedule(...).
    // ------------------------------------------------------------------
    public PayoutSchedule withNextRunTick(long newNextRunTick) {
        return new PayoutSchedule(scheduleId, targetUUID, amount, intervalTicks,
                newNextRunTick, paused, createdAt, createdBy, targetAccountNr,
                targetPlayerName, targetAccountName, mode, currencyItem, missedAmount, missedCount,
                pausedByCurrencyBan);
    }

    public PayoutSchedule withPaused(boolean newPaused) {
        return new PayoutSchedule(scheduleId, targetUUID, amount, intervalTicks,
                nextRunTick, newPaused, createdAt, createdBy, targetAccountNr,
                targetPlayerName, targetAccountName, mode, currencyItem, missedAmount, missedCount,
                pausedByCurrencyBan);
    }

    /** Task #57b — set the currency-ban pause marker (also drives the paused flag). */
    public PayoutSchedule withPausedByCurrencyBan(boolean newPaused, boolean newMarker) {
        return new PayoutSchedule(scheduleId, targetUUID, amount, intervalTicks,
                nextRunTick, newPaused, createdAt, createdBy, targetAccountNr,
                targetPlayerName, targetAccountName, mode, currencyItem, missedAmount, missedCount,
                newMarker);
    }

    public PayoutSchedule withAmountAndInterval(long newAmount, long newIntervalTicks) {
        return new PayoutSchedule(scheduleId, targetUUID, newAmount, newIntervalTicks,
                nextRunTick, paused, createdAt, createdBy, targetAccountNr,
                targetPlayerName, targetAccountName, mode, currencyItem, missedAmount, missedCount,
                pausedByCurrencyBan);
    }

    /** Spec B.1/B.2/B.3 — full editable-fields replacement (target, mode, currency). */
    public PayoutSchedule withEditableFields(long newAmount, long newIntervalTicks, long newNextRunTick,
                                             UUID newTarget, int newTargetAccountNr,
                                             String newTargetPlayerName, String newTargetAccountName,
                                             Mode newMode, short newCurrencyItem) {
        return new PayoutSchedule(scheduleId, newTarget, newAmount, newIntervalTicks,
                newNextRunTick, paused, createdAt, createdBy, newTargetAccountNr,
                newTargetPlayerName, newTargetAccountName, newMode, newCurrencyItem,
                missedAmount, missedCount, pausedByCurrencyBan);
    }

    /** Spec B.4 — set the missed-payout accumulator. */
    public PayoutSchedule withMissed(long newMissedAmount, int newMissedCount) {
        return new PayoutSchedule(scheduleId, targetUUID, amount, intervalTicks,
                nextRunTick, paused, createdAt, createdBy, targetAccountNr,
                targetPlayerName, targetAccountName, mode, currencyItem,
                Math.max(0L, newMissedAmount), Math.max(0, newMissedCount), pausedByCurrencyBan);
    }

    public void save(CompoundTag tag) {
        tag.putLong("scheduleId", scheduleId);
        if (targetUUID != null) tag.putUUID("targetUUID", targetUUID);
        tag.putLong("amount", amount);
        tag.putLong("intervalTicks", intervalTicks);
        tag.putLong("nextRunTick", nextRunTick);
        tag.putBoolean("paused", paused);
        tag.putLong("createdAt", createdAt);
        if (createdBy != null) tag.putUUID("createdBy", createdBy);
        tag.putInt("targetAccountNr", targetAccountNr);
        tag.putString("targetPlayerName", targetPlayerName);
        tag.putString("targetAccountName", targetAccountName);
        tag.putString("mode", mode.name());
        tag.putShort("currencyItem", currencyItem);
        tag.putLong("missedAmount", missedAmount);
        tag.putInt("missedCount", missedCount);
        tag.putBoolean("pausedByCurrencyBan", pausedByCurrencyBan);
    }

    public static PayoutSchedule load(CompoundTag tag) {
        if (tag == null) return null;
        Mode mode = Mode.FIXED_PAYOUT;
        if (tag.contains("mode")) {
            try { mode = Mode.valueOf(tag.getString("mode")); }
            catch (IllegalArgumentException ignored) { /* keep default */ }
        }
        return new PayoutSchedule(
                tag.getLong("scheduleId"),
                tag.hasUUID("targetUUID") ? tag.getUUID("targetUUID") : null,
                tag.getLong("amount"),
                tag.getLong("intervalTicks"),
                tag.getLong("nextRunTick"),
                tag.getBoolean("paused"),
                tag.getLong("createdAt"),
                tag.hasUUID("createdBy") ? tag.getUUID("createdBy") : null,
                tag.contains("targetAccountNr") ? tag.getInt("targetAccountNr") : NO_TARGET_ACCOUNT,
                tag.getString("targetPlayerName"),
                tag.getString("targetAccountName"),
                mode,
                tag.getShort("currencyItem"),
                tag.getLong("missedAmount"),
                tag.getInt("missedCount"),
                tag.getBoolean("pausedByCurrencyBan") // missing → false (older NBT)
        );
    }
}
