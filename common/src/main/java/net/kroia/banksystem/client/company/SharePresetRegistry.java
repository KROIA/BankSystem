package net.kroia.banksystem.client.company;

import net.kroia.banksystem.BankSystemMod;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

/**
 * Task #46 (v2.1.0) — shared client + server preset icon registry for company share visuals.
 * Task #54 (v2.1.0) — extended to include server-synced dynamic symbols via
 * {@link ClientSymbolRegistry}. The bundled 45 presets are the primary set; synced symbols
 * extend it at runtime.
 *
 * <p>Each bundled preset id maps to its texture at
 * {@code assets/banksystem/textures/item/share_symbol/<id>.png}. Dynamic (synced) symbols
 * are registered as {@link net.minecraft.client.renderer.texture.DynamicTexture} under
 * {@code banksystem:dynamic/share_symbol/<id>}.
 */
public final class SharePresetRegistry {

    private static final ResourceLocation PLACEHOLDER = ResourceLocation.fromNamespaceAndPath(
            BankSystemMod.MOD_ID, "textures/item/share_symbol/_placeholder.png");

    private static final Map<String, ResourceLocation> PRESETS = new LinkedHashMap<>();
    private static final List<String> ORDERED;

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
        ORDERED = List.copyOf(PRESETS.keySet());
    }

    private SharePresetRegistry() {}

    /**
     * Ordered list of all preset ids — bundled first, then server-synced dynamic ids.
     * Used by the editor grid.
     */
    public static List<String> orderedIds() {
        List<ClientSymbolRegistry.SymbolState> states = ClientSymbolRegistry.getStates();
        if (states.isEmpty()) return ORDERED;
        List<String> all = new ArrayList<>(ORDERED);
        for (ClientSymbolRegistry.SymbolState s : states) {
            if (!PRESETS.containsKey(s.id())) all.add(s.id());
        }
        return Collections.unmodifiableList(all);
    }

    public static Set<String> allIds() {
        List<ClientSymbolRegistry.SymbolState> states = ClientSymbolRegistry.getStates();
        if (states.isEmpty()) return Collections.unmodifiableSet(PRESETS.keySet());
        Set<String> all = new LinkedHashSet<>(PRESETS.keySet());
        states.forEach(s -> all.add(s.id()));
        return Collections.unmodifiableSet(all);
    }

    /**
     * Validates a symbol id. Accepts bundled presets AND server-synced dynamic ids.
     * Server-side validation uses {@link net.kroia.banksystem.banking.company.ShareSymbolStore#isValidSymbolId}
     * instead (see {@link net.kroia.banksystem.banking.company.AsyncCompanyManager}).
     */
    public static boolean isValidPresetId(String id) {
        return id != null && (PRESETS.containsKey(id) || ClientSymbolRegistry.isValidSymbolId(id));
    }

    /** {@code true} only for the 45 compile-time bundled presets. */
    public static boolean isBundledId(String id) {
        return PRESETS.containsKey(id);
    }

    /**
     * Returns the {@link ResourceLocation} to use for GUI rendering (editor grid,
     * badge painter). Bundled: file RL (auto-loaded by TextureManager). Dynamic:
     * DynamicTexture RL registered by {@link ClientSymbolRegistry}.
     */
    public static ResourceLocation getTexture(String id) {
        ResourceLocation bundled = PRESETS.get(id);
        if (bundled != null) return bundled;
        if (ClientSymbolRegistry.isValidSymbolId(id)) {
            return ClientSymbolRegistry.getDynamicRL(id);
        }
        return PLACEHOLDER;
    }

    public static ResourceLocation placeholder() { return PLACEHOLDER; }

    public static int size() {
        int dynamic = (int) ClientSymbolRegistry.getStates().stream()
                .filter(s -> !PRESETS.containsKey(s.id())).count();
        return PRESETS.size() + dynamic;
    }
}
