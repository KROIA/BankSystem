package net.kroia.banksystem.banking.company;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Company entity — Phase 1 (Task #43) foundation for the Company Feature program.
 * <p>
 * See {@code .claude/Features/CompanyFeature.md} §1 for the full spec. Fields listed in
 * spec §1 that are populated by later phases ({@link #totalSharesIssued},
 * {@link #shareVisuals}, {@link #payoutSchedules}) exist here with empty defaults so
 * future tasks (#45, #46, #47) do not need to migrate the NBT schema.
 * <p>
 * Mutation is restricted to package-private methods so only {@link CompanyManager} can
 * change state — enforces the "name immutable, monotonic id, index consistency" invariants.
 */
public final class Company {

    // ------------------------------------------------------------------
    // Immutable identity
    // ------------------------------------------------------------------
    private final int companyId;
    private final String name;
    private final int bankAccountNr;
    private final long maxSupply;
    private final long createdAt;

    // ------------------------------------------------------------------
    // Mutable state (guarded by CompanyManager)
    // ------------------------------------------------------------------
    private String description;
    private final Set<UUID> founders;
    private long totalSharesIssued;
    private ShareVisuals shareVisuals;
    private final List<PayoutSchedule> payoutSchedules;
    /** Task #45 — monotonic schedule id allocator, scoped per Company. */
    private long nextScheduleId = 1L;
    /** v2.1.0 — last-used timeframe index for the Statistics tab (0=24h, 1=7d, 2=30d, 3=90d, 4=all). */
    private int statisticsTimeframe = 1;
    /** v2.1.0 — ItemID short of the company's default currency (0 = money). */
    private short companyCurrency = 0;
    /** Persisted set of BlockPos positions of Share Stamper blocks bound to this company. */
    private final Set<BlockPos> boundStampers = new HashSet<>();

    Company(int companyId, String name, int bankAccountNr, long maxSupply, long createdAt,
            String description, Set<UUID> founders, long totalSharesIssued,
            ShareVisuals shareVisuals, List<PayoutSchedule> payoutSchedules) {
        this.companyId = companyId;
        this.name = name;
        this.bankAccountNr = bankAccountNr;
        this.maxSupply = maxSupply;
        this.createdAt = createdAt;
        this.description = description == null ? "" : description;
        this.founders = founders == null ? new HashSet<>() : new HashSet<>(founders);
        this.totalSharesIssued = totalSharesIssued;
        this.shareVisuals = shareVisuals == null ? ShareVisuals.EMPTY : shareVisuals;
        this.payoutSchedules = payoutSchedules == null ? new ArrayList<>() : new ArrayList<>(payoutSchedules);
    }

    // ------------------------------------------------------------------
    // Getters
    // ------------------------------------------------------------------
    public int getCompanyId() { return companyId; }
    public String getName() { return name; }
    public int getBankAccountNr() { return bankAccountNr; }
    public long getMaxSupply() { return maxSupply; }
    public long getCreatedAt() { return createdAt; }
    public String getDescription() { return description; }
    public Set<UUID> getFounders() { return java.util.Collections.unmodifiableSet(founders); }
    public long getTotalSharesIssued() { return totalSharesIssued; }
    public int getStatisticsTimeframe() { return statisticsTimeframe; }
    public void setStatisticsTimeframe(int tf) { this.statisticsTimeframe = (tf >= 0 && tf <= 4) ? tf : 1; }
    public short getCompanyCurrency() { return companyCurrency; }
    public void setCompanyCurrency(short c) { this.companyCurrency = c; }
    public ShareVisuals getShareVisuals() { return shareVisuals; }
    public List<PayoutSchedule> getPayoutSchedules() { return java.util.Collections.unmodifiableList(payoutSchedules); }

    public boolean isFounder(UUID uuid) {
        return uuid != null && founders.contains(uuid);
    }

    // ------------------------------------------------------------------
    // Manager-only mutation
    // ------------------------------------------------------------------
    void setDescription(String description) {
        this.description = description == null ? "" : description;
    }

    boolean addFounder(UUID uuid) {
        if (uuid == null) return false;
        return founders.add(uuid);
    }

    boolean removeFounder(UUID uuid) {
        if (uuid == null) return false;
        return founders.remove(uuid);
    }

    void setShareVisuals(ShareVisuals visuals) {
        this.shareVisuals = visuals == null ? ShareVisuals.EMPTY : visuals;
    }

    void setTotalSharesIssued(long value) {
        this.totalSharesIssued = value;
    }

    // ------------------------------------------------------------------
    // Task #45 (v2.1.0) — payout schedule mutation (package-private; only
    // CompanyManager / PayoutExecutor may call these).
    // ------------------------------------------------------------------

    /** Allocate a new monotonic schedule id for this company. */
    long allocateScheduleId() {
        long id = nextScheduleId++;
        return id;
    }

    /**
     * BUG batch 4 (v2.1.0) — renormalize every schedule's {@code nextRunTick} to
     * {@code baseTick + intervalTicks}. Called from {@link CompanyManager#load}
     * after world load because {@code payoutTickCounter} is a per-session counter
     * that resets to {@code 0} on every server start
     * ({@code BankSystemModBackend.onServerStart}); a persisted {@code nextRunTick}
     * from the previous session is meaningless in the new session's clock and
     * would show as a stale multi-minute countdown until the next fire (which
     * itself would never happen if the persisted value was very large).
     * <p>
     * Semantics: after reload, every schedule's next run is exactly one full
     * interval away from load time. Sub-interval progress is discarded — acceptable
     * for a reload boundary.
     */
    void renormalizeSchedulesAfterLoad(long baseTick) {
        for (int i = 0; i < payoutSchedules.size(); i++) {
            PayoutSchedule s = payoutSchedules.get(i);
            payoutSchedules.set(i, s.withNextRunTick(baseTick + s.getIntervalTicks()));
        }
    }

    void addSchedule(PayoutSchedule schedule) {
        if (schedule == null) return;
        payoutSchedules.add(schedule);
        if (schedule.getScheduleId() >= nextScheduleId) nextScheduleId = schedule.getScheduleId() + 1;
    }

    /** Returns {@code true} iff a schedule with {@code scheduleId} was found and swapped. */
    boolean replaceSchedule(long scheduleId, PayoutSchedule updated) {
        for (int i = 0; i < payoutSchedules.size(); i++) {
            if (payoutSchedules.get(i).getScheduleId() == scheduleId) {
                payoutSchedules.set(i, updated);
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Stamper binding persistence helpers (package-private)
    // ------------------------------------------------------------------
    void addBoundStamper(BlockPos pos) {
        if (pos != null) boundStampers.add(pos.immutable());
    }

    void removeBoundStamper(BlockPos pos) {
        if (pos != null) boundStampers.remove(pos.immutable());
    }

    public Set<BlockPos> getBoundStampers() {
        return Collections.unmodifiableSet(boundStampers);
    }

    boolean removeSchedule(long scheduleId) {
        return payoutSchedules.removeIf(s -> s.getScheduleId() == scheduleId);
    }

    @Nullable
    public PayoutSchedule findSchedule(long scheduleId) {
        for (PayoutSchedule s : payoutSchedules) {
            if (s.getScheduleId() == scheduleId) return s;
        }
        return null;
    }

    /**
     * Cascade-strip: removes every schedule whose {@code targetUUID} equals {@code user}.
     * Returns the count removed.
     */
    int stripSchedulesForUser(UUID user) {
        if (user == null) return 0;
        int before = payoutSchedules.size();
        payoutSchedules.removeIf(s -> user.equals(s.getTargetUUID()));
        return before - payoutSchedules.size();
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------
    public void save(CompoundTag tag) {
        tag.putInt("companyId", companyId);
        tag.putString("name", name);
        tag.putInt("bankAccountNr", bankAccountNr);
        tag.putLong("maxSupply", maxSupply);
        tag.putLong("createdAt", createdAt);
        tag.putString("description", description);
        tag.putLong("totalSharesIssued", totalSharesIssued);
        tag.putLong("nextScheduleId", nextScheduleId);
        tag.putInt("statisticsTimeframe", statisticsTimeframe);
        tag.putShort("companyCurrency", companyCurrency);

        ListTag foundersTag = new ListTag();
        for (UUID founder : founders) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("uuid", founder);
            foundersTag.add(entry);
        }
        tag.put("founders", foundersTag);

        CompoundTag visualsTag = new CompoundTag();
        shareVisuals.save(visualsTag);
        tag.put("shareVisuals", visualsTag);

        ListTag schedulesTag = new ListTag();
        for (PayoutSchedule schedule : payoutSchedules) {
            CompoundTag entry = new CompoundTag();
            schedule.save(entry);
            schedulesTag.add(entry);
        }
        tag.put("payoutSchedules", schedulesTag);

        ListTag stampersTag = new ListTag();
        for (BlockPos p : boundStampers) {
            CompoundTag se = new CompoundTag();
            se.putInt("x", p.getX());
            se.putInt("y", p.getY());
            se.putInt("z", p.getZ());
            stampersTag.add(se);
        }
        tag.put("boundStampers", stampersTag);
    }

    /**
     * Tolerant load — {@code shareVisuals} and {@code payoutSchedules} default to empty
     * when absent so Phase 2+ can extend the schema without a migration.
     */
    public static Company load(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) return null;
        if (!tag.contains("companyId") || !tag.contains("name") || !tag.contains("bankAccountNr")) {
            return null;
        }
        int companyId = tag.getInt("companyId");
        String name = tag.getString("name");
        int bankAccountNr = tag.getInt("bankAccountNr");
        long maxSupply = tag.getLong("maxSupply");
        long createdAt = tag.getLong("createdAt");
        String description = tag.contains("description") ? tag.getString("description") : "";
        long totalSharesIssued = tag.contains("totalSharesIssued") ? tag.getLong("totalSharesIssued") : 0L;

        Set<UUID> founders = new HashSet<>();
        if (tag.contains("founders", Tag.TAG_LIST)) {
            ListTag foundersTag = tag.getList("founders", Tag.TAG_COMPOUND);
            for (int i = 0; i < foundersTag.size(); i++) {
                CompoundTag entry = foundersTag.getCompound(i);
                if (entry.hasUUID("uuid")) founders.add(entry.getUUID("uuid"));
            }
        }

        ShareVisuals visuals = ShareVisuals.EMPTY;
        if (tag.contains("shareVisuals", Tag.TAG_COMPOUND)) {
            visuals = ShareVisuals.load(tag.getCompound("shareVisuals"));
        }

        List<PayoutSchedule> schedules = new ArrayList<>();
        if (tag.contains("payoutSchedules", Tag.TAG_LIST)) {
            ListTag schedulesTag = tag.getList("payoutSchedules", Tag.TAG_COMPOUND);
            for (int i = 0; i < schedulesTag.size(); i++) {
                PayoutSchedule schedule = PayoutSchedule.load(schedulesTag.getCompound(i));
                if (schedule != null) schedules.add(schedule);
            }
        }

        Company loaded = new Company(companyId, name, bankAccountNr, maxSupply, createdAt,
                description, founders, totalSharesIssued, visuals, schedules);
        // Task #45 — tolerant load of nextScheduleId. Fall back to max(scheduleId)+1
        // when the tag is absent (older Company NBT).
        long nextSchedId = 1L;
        if (tag.contains("nextScheduleId")) nextSchedId = tag.getLong("nextScheduleId");
        for (PayoutSchedule s : schedules) {
            if (s.getScheduleId() >= nextSchedId) nextSchedId = s.getScheduleId() + 1;
        }
        loaded.nextScheduleId = nextSchedId;
        if (tag.contains("statisticsTimeframe")) loaded.statisticsTimeframe = tag.getInt("statisticsTimeframe");
        if (tag.contains("companyCurrency")) loaded.companyCurrency = tag.getShort("companyCurrency");
        if (tag.contains("boundStampers", Tag.TAG_LIST)) {
            ListTag sl = tag.getList("boundStampers", Tag.TAG_COMPOUND);
            for (int i = 0; i < sl.size(); i++) {
                CompoundTag se = sl.getCompound(i);
                loaded.boundStampers.add(new BlockPos(se.getInt("x"), se.getInt("y"), se.getInt("z")));
            }
        }
        return loaded;
    }
}
