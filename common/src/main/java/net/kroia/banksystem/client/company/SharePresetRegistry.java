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
 * <p>Each preset id maps to its own texture at
 * {@code assets/banksystem/textures/item/share_symbol/<id>.png}. The RLs are full file
 * paths (with {@code textures/} prefix and {@code .png} extension) because they are used
 * for direct GUI/render blits ({@code RenderType.text(rl)}), not atlas sprite lookups.
 * Unknown ids fall back to {@link #placeholder()}.
 */
public final class SharePresetRegistry {

    private static final ResourceLocation PLACEHOLDER = ResourceLocation.fromNamespaceAndPath(
            BankSystemMod.MOD_ID, "textures/item/share_symbol/_placeholder.png");

    private static final Map<String, ResourceLocation> PRESETS = new LinkedHashMap<>();

    static {
        String[] ids = {
                "leaf", "gear", "anvil", "pickaxe", "sword", "shield", "crown", "star",
                "diamond", "emerald", "gold_ingot", "iron_ingot", "wheat", "apple", "fish",
                "boat", "bow", "potion", "book", "map", "compass", "clock", "key", "lantern",
                "torch", "bell", "heart", "skull", "flame", "snowflake",
                "coin", "scales", "chest", "barrel", "house", "tower", "tree", "mountain",
                "sun", "moon", "bolt", "anchor", "hammer", "axe", "minecart"
        };
        for (String id : ids) {
            PRESETS.put(id, ResourceLocation.fromNamespaceAndPath(
                    BankSystemMod.MOD_ID, "textures/item/share_symbol/" + id + ".png"));
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
