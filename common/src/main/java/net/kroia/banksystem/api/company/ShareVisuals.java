package net.kroia.banksystem.api.company;

import net.minecraft.network.chat.Component;

/**
 * Task #50 (v2.0.8) / v2.0.9 two-layer — public read-only value type for per-company
 * share visuals + supply. Consumed by external mods (e.g. StockMarket) via
 * {@link IBankSystemVisualLookup#getShareVisuals(net.kroia.banksystem.util.ItemID)}.
 *
 * <p>v2.0.9 adds {@link #bgSymbolId()}/{@link #bgTint()} for the background layer and
 * {@link #fgSymbolId()}/{@link #fgTint()} for the foreground layer. Legacy accessors
 * {@link #iconPresetId()} and {@link #tint()} are kept as compat shims:
 * {@code iconPresetId()} → {@code fgSymbolId()}, {@code tint()} → {@code bgTint()}.
 */
public final class ShareVisuals {

    private final String bgSymbolId;
    private final int bgTint;
    private final String fgSymbolId;
    private final int fgTint;
    private final Component displayName;
    private final Component description;
    private final long totalSharesIssued;
    private final long maxSupply;

    /** Full two-layer constructor. */
    public ShareVisuals(String bgSymbolId, int bgTint,
                        String fgSymbolId, int fgTint,
                        Component displayName,
                        Component description,
                        long totalSharesIssued,
                        long maxSupply) {
        this.bgSymbolId = bgSymbolId == null ? "" : bgSymbolId;
        this.bgTint = bgTint;
        this.fgSymbolId = fgSymbolId == null ? "" : fgSymbolId;
        this.fgTint = fgTint;
        this.displayName = displayName == null ? Component.empty() : displayName;
        this.description = description == null ? Component.empty() : description;
        this.totalSharesIssued = totalSharesIssued;
        this.maxSupply = maxSupply;
    }

    /**
     * Legacy 6-arg compat constructor — maps {@code iconPresetId} → fgSymbolId,
     * {@code tint} → bgTint; bgSymbolId = "" and fgTint = 0xFFFFFFFF.
     */
    public ShareVisuals(String iconPresetId,
                        int tint,
                        Component displayName,
                        Component description,
                        long totalSharesIssued,
                        long maxSupply) {
        this("", tint, iconPresetId == null ? "" : iconPresetId, 0xFFFFFFFF,
                displayName, description, totalSharesIssued, maxSupply);
    }

    /** Background layer symbol id; empty when unset. */
    public String bgSymbolId() { return bgSymbolId; }
    /** Background layer ARGB tint. */
    public int bgTint() { return bgTint; }
    /** Foreground layer symbol id; empty when unset. */
    public String fgSymbolId() { return fgSymbolId; }
    /** Foreground layer ARGB tint. */
    public int fgTint() { return fgTint; }

    /** Legacy compat: returns {@link #fgSymbolId()}. */
    public String iconPresetId() { return fgSymbolId; }
    /** Legacy compat: returns {@link #bgTint()}. */
    public int tint() { return bgTint; }

    /** Human-readable company name; {@link Component#empty()} when unset. */
    public Component displayName() { return displayName; }
    /** Company description; {@link Component#empty()} when unset. */
    public Component description() { return description; }
    /** Current outstanding share count. */
    public long totalSharesIssued() { return totalSharesIssued; }
    /** Immutable cap set at company creation; {@code 0} means unlimited. */
    public long maxSupply() { return maxSupply; }
}
