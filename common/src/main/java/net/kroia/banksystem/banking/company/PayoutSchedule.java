package net.kroia.banksystem.banking.company;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * Value type for a recurring payout schedule attached to a Company. Actual scheduler
 * comes in Task #45 (Phase 3) — Phase 1 (Task #43) only carries the empty default and
 * the NBT save/load so future tasks don't need to migrate the schema.
 */
public final class PayoutSchedule {

    private final long scheduleId;
    private final UUID targetUUID;
    private final long amount;
    private final long intervalTicks;
    private final long nextRunTick;
    private final boolean paused;
    private final long createdAt;
    private final UUID createdBy;

    public PayoutSchedule(long scheduleId, UUID targetUUID, long amount, long intervalTicks,
                          long nextRunTick, boolean paused, long createdAt, UUID createdBy) {
        this.scheduleId = scheduleId;
        this.targetUUID = targetUUID;
        this.amount = amount;
        this.intervalTicks = intervalTicks;
        this.nextRunTick = nextRunTick;
        this.paused = paused;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    public long getScheduleId() { return scheduleId; }
    public UUID getTargetUUID() { return targetUUID; }
    public long getAmount() { return amount; }
    public long getIntervalTicks() { return intervalTicks; }
    public long getNextRunTick() { return nextRunTick; }
    public boolean isPaused() { return paused; }
    public long getCreatedAt() { return createdAt; }
    public UUID getCreatedBy() { return createdBy; }

    // ------------------------------------------------------------------
    // Task #45 (v2.0.8) — copy-with helpers. PayoutSchedule stays immutable;
    // Company keeps the schedule list mutable via replaceById(...).
    // ------------------------------------------------------------------
    public PayoutSchedule withNextRunTick(long newNextRunTick) {
        return new PayoutSchedule(scheduleId, targetUUID, amount, intervalTicks,
                newNextRunTick, paused, createdAt, createdBy);
    }

    public PayoutSchedule withPaused(boolean newPaused) {
        return new PayoutSchedule(scheduleId, targetUUID, amount, intervalTicks,
                nextRunTick, newPaused, createdAt, createdBy);
    }

    public PayoutSchedule withAmountAndInterval(long newAmount, long newIntervalTicks) {
        return new PayoutSchedule(scheduleId, targetUUID, newAmount, newIntervalTicks,
                nextRunTick, paused, createdAt, createdBy);
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
    }

    public static PayoutSchedule load(CompoundTag tag) {
        if (tag == null) return null;
        return new PayoutSchedule(
                tag.getLong("scheduleId"),
                tag.hasUUID("targetUUID") ? tag.getUUID("targetUUID") : null,
                tag.getLong("amount"),
                tag.getLong("intervalTicks"),
                tag.getLong("nextRunTick"),
                tag.getBoolean("paused"),
                tag.getLong("createdAt"),
                tag.hasUUID("createdBy") ? tag.getUUID("createdBy") : null
        );
    }
}
