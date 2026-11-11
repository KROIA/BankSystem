package net.kroia.banksystem.api.company;

import net.minecraft.network.chat.Component;

/**
 * Task #50 (v2.1.0) / v2.1.0 two-layer — public read-only value type for per-company
 * share visuals + supply. Consumed by external mods (e.g. StockMarket) via
 * {@link IBankSystemVisualLookup#getShareVisuals(net.kroia.banksystem.util.ItemID)}.
 *
 * <p>{@link #bgSymbolId()}/{@link #bgTint()} describe the background layer,
 * {@link #fgSymbolId()}/{@link #fgTint()} the foreground layer.
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

    /** Background layer symbol id; empty when unset. */
    public String bgSymbolId() { return bgSymbolId; }
    /** Background layer ARGB tint. */
    public int bgTint() { return bgTint; }
    /** Foreground layer symbol id; empty when unset. */
    public String fgSymbolId() { return fgSymbolId; }
    /** Foreground layer ARGB tint. */
    public int fgTint() { return fgTint; }

    /** Human-readable company name; {@link Component#empty()} when unset. */
    public Component displayName() { return displayName; }
    /** Company description; {@link Component#empty()} when unset. */
    public Component description() { return description; }
    /** Current outstanding share count. */
    public long totalSharesIssued() { return totalSharesIssued; }
    /** Immutable cap set at company creation; {@code 0} means unlimited. */
    public long maxSupply() { return maxSupply; }
}
