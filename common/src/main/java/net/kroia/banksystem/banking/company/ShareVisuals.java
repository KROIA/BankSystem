package net.kroia.banksystem.banking.company;

import net.minecraft.nbt.CompoundTag;

/**
 * Value type for a Company's share visuals (icon preset id, tint, display name, description).
 * Populated by Task #46 (ShareVisualEditorScreen) — Phase 1 (Task #43) only carries the
 * empty default so future tasks don't need to migrate the NBT schema.
 */
public final class ShareVisuals {

    public static final ShareVisuals EMPTY = new ShareVisuals("", 0xFFFFFFFF, "", "");

    private final String iconPresetId;
    private final int tint;
    private final String displayName;
    private final String description;

    public ShareVisuals(String iconPresetId, int tint, String displayName, String description) {
        this.iconPresetId = iconPresetId == null ? "" : iconPresetId;
        this.tint = tint;
        this.displayName = displayName == null ? "" : displayName;
        this.description = description == null ? "" : description;
    }

    public String getIconPresetId() { return iconPresetId; }
    public int getTint() { return tint; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }

    public void save(CompoundTag tag) {
        tag.putString("iconPresetId", iconPresetId);
        tag.putInt("tint", tint);
        tag.putString("displayName", displayName);
        tag.putString("description", description);
    }

    public static ShareVisuals load(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return EMPTY;
        }
        return new ShareVisuals(
                tag.contains("iconPresetId") ? tag.getString("iconPresetId") : "",
                tag.contains("tint") ? tag.getInt("tint") : 0xFFFFFFFF,
                tag.contains("displayName") ? tag.getString("displayName") : "",
                tag.contains("description") ? tag.getString("description") : ""
        );
    }
}
