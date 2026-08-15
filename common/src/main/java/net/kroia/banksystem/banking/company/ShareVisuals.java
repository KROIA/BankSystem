package net.kroia.banksystem.banking.company;

import net.minecraft.nbt.CompoundTag;

/**
 * Value type for a Company's share visuals. Two-layer model (v2.1.0):
 * {@link #bgLayer} defines the background fill color/symbol;
 * {@link #fgLayer} defines the foreground icon preset and its tint.
 *
 * <p>Legacy getters {@link #getIconPresetId()} and {@link #getTint()} are kept
 * for source compatibility — they delegate to fgLayer.symbolId() and bgLayer.tint().
 */
public final class ShareVisuals {

    /** A single visual layer: a symbol preset id (may be empty) and an ARGB tint. */
    public record ShareLayer(String symbolId, int tint) {
        public static final ShareLayer EMPTY = new ShareLayer("", 0xFFFFFFFF);
    }

    public static final ShareVisuals EMPTY = new ShareVisuals(
            ShareLayer.EMPTY, ShareLayer.EMPTY, "", "");

    private final ShareLayer bgLayer;
    private final ShareLayer fgLayer;
    /** ARGB base card color; multiplied under both layers. Default white (no tint). */
    private final int baseTint;
    private final String displayName;
    private final String description;

    public ShareVisuals(ShareLayer bgLayer, ShareLayer fgLayer, int baseTint,
                        String displayName, String description) {
        this.bgLayer = bgLayer != null ? bgLayer : ShareLayer.EMPTY;
        this.fgLayer = fgLayer != null ? fgLayer : ShareLayer.EMPTY;
        // Zero-alpha would render the card invisible — normalize to opaque.
        this.baseTint = (baseTint & 0xFF000000) == 0 ? baseTint | 0xFF000000 : baseTint;
        this.displayName = displayName == null ? "" : displayName;
        this.description = description == null ? "" : description;
    }

    public ShareVisuals(ShareLayer bgLayer, ShareLayer fgLayer,
                        String displayName, String description) {
        this(bgLayer, fgLayer, 0xFFFFFFFF, displayName, description);
    }

    public ShareLayer getBgLayer() { return bgLayer; }
    public ShareLayer getFgLayer() { return fgLayer; }
    public int getBaseTint() { return baseTint; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }

    /** Legacy compat: returns the foreground layer's symbol id. */
    public String getIconPresetId() { return fgLayer.symbolId(); }
    /** Legacy compat: returns the background layer's tint. */
    public int getTint() { return bgLayer.tint(); }

    public void save(CompoundTag tag) {
        tag.putString("bgSymbolId", bgLayer.symbolId());
        tag.putInt("bgTint", bgLayer.tint());
        tag.putString("fgSymbolId", fgLayer.symbolId());
        tag.putInt("fgTint", fgLayer.tint());
        tag.putInt("baseTint", baseTint);
        tag.putString("displayName", displayName);
        tag.putString("description", description);
    }

    public static ShareVisuals load(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return EMPTY;
        }
        if (tag.contains("bgSymbolId")) {
            // New two-layer format.
            ShareLayer bg = new ShareLayer(
                    tag.getString("bgSymbolId"),
                    tag.contains("bgTint") ? tag.getInt("bgTint") : 0xFFFFFFFF);
            ShareLayer fg = new ShareLayer(
                    tag.contains("fgSymbolId") ? tag.getString("fgSymbolId") : "",
                    tag.contains("fgTint") ? tag.getInt("fgTint") : 0xFFFFFFFF);
            return new ShareVisuals(bg, fg,
                    tag.contains("baseTint") ? tag.getInt("baseTint") : 0xFFFFFFFF,
                    tag.contains("displayName") ? tag.getString("displayName") : "",
                    tag.contains("description") ? tag.getString("description") : "");
        }
        // Legacy format: iconPresetId/tint → fgLayer; bgLayer = EMPTY.
        ShareLayer fg = new ShareLayer(
                tag.contains("iconPresetId") ? tag.getString("iconPresetId") : "",
                tag.contains("tint") ? tag.getInt("tint") : 0xFFFFFFFF);
        return new ShareVisuals(ShareLayer.EMPTY, fg,
                tag.contains("baseTint") ? tag.getInt("baseTint") : 0xFFFFFFFF,
                tag.contains("displayName") ? tag.getString("displayName") : "",
                tag.contains("description") ? tag.getString("description") : "");
    }
}
