package net.kroia.banksystem.client.company;

import net.kroia.banksystem.BankSystemMod;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Task #46 (v2.0.8) — shared client + server preset icon registry for company share visuals.
 * Server uses {@link #isValidPresetId(String)} to validate the client's editor submission;
 * client uses {@link #getTexture(String)} to draw the icon overlay.
 *
 * <p>All 30 preset ids currently point to a single placeholder texture
 * ({@code assets/banksystem/textures/company/share_preset/_placeholder.png}) — art is
 * deferred per Task #46 deliverable notes. Registration structure (id → RL) is production
 * shape so filling in real art later is a data-only change.
 */
public final class SharePresetRegistry {

    private static final ResourceLocation PLACEHOLDER = ResourceLocation.fromNamespaceAndPath(
            BankSystemMod.MOD_ID, "textures/company/share_preset/_placeholder.png");

    private static final Map<String, ResourceLocation> PRESETS = new LinkedHashMap<>();

    static {
        String[] ids = {
                "leaf", "gear", "anvil", "pickaxe", "sword", "shield", "crown", "star",
                "diamond", "emerald", "gold_ingot", "iron_ingot", "wheat", "apple", "fish",
                "boat", "bow", "potion", "book", "map", "compass", "clock", "key", "lantern",
                "torch", "bell", "heart", "skull", "flame", "snowflake"
        };
        for (String id : ids) {
            // NOTE: all ids resolve to the placeholder texture in this ship (art deferred).
            // Swap the RHS to `textures/company/share_preset/<id>.png` when art lands.
            PRESETS.put(id, PLACEHOLDER);
        }
    }

    private SharePresetRegistry() {}

    /** Ordered list of preset ids as displayed in the editor grid. */
    public static List<String> orderedIds() {
        return List.copyOf(PRESETS.keySet());
    }

    public static Set<String> allIds() {
        return Collections.unmodifiableSet(PRESETS.keySet());
    }

    public static boolean isValidPresetId(String id) {
        return id != null && PRESETS.containsKey(id);
    }

    public static ResourceLocation getTexture(String id) {
        ResourceLocation rl = PRESETS.get(id);
        return rl != null ? rl : PLACEHOLDER;
    }

    public static ResourceLocation placeholder() { return PLACEHOLDER; }

    public static int size() { return PRESETS.size(); }
}
