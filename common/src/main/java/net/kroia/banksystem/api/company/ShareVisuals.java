package net.kroia.banksystem.api.company;

import net.minecraft.network.chat.Component;

/**
 * Task #50 (v2.0.8) — public read-only value type for per-company share visuals + supply.
 * Consumed by external mods (e.g. StockMarket) via
 * {@link IBankSystemVisualLookup#getShareVisuals(net.kroia.banksystem.util.ItemID)}.
 *
 * <p>Distinct from the internal {@link net.kroia.banksystem.banking.company.ShareVisuals}
 * NBT value type — this API surface wraps it and adds current + max supply so downstream
 * mods can render "issued / max" without additional lookups.
 */
public final class ShareVisuals {

    private final String iconPresetId;
    private final int tint;
    private final Component displayName;
    private final Component description;
    private final long totalSharesIssued;
    private final long maxSupply;

    public ShareVisuals(String iconPresetId,
                        int tint,
                        Component displayName,
                        Component description,
                        long totalSharesIssued,
                        long maxSupply) {
        this.iconPresetId = iconPresetId == null ? "" : iconPresetId;
        this.tint = tint;
        this.displayName = displayName == null ? Component.empty() : displayName;
        this.description = description == null ? Component.empty() : description;
        this.totalSharesIssued = totalSharesIssued;
        this.maxSupply = maxSupply;
    }

    /** Stable preset id (see {@code SharePresetRegistry}); empty when unset. */
    public String iconPresetId() { return iconPresetId; }

    /** ARGB tint applied to the preset icon. */
    public int tint() { return tint; }

    /** Human-readable company name; {@link Component#empty()} when unset. */
    public Component displayName() { return displayName; }

    /** Company description; {@link Component#empty()} when unset. */
    public Component description() { return description; }

    /** Current outstanding share count. */
    public long totalSharesIssued() { return totalSharesIssued; }

    /** Immutable cap set at company creation; {@code 0} means unlimited. */
    public long maxSupply() { return maxSupply; }
}
