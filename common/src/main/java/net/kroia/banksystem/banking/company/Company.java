package net.kroia.banksystem.banking.company;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
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

        return new Company(companyId, name, bankAccountNr, maxSupply, createdAt,
                description, founders, totalSharesIssued, visuals, schedules);
    }
}
