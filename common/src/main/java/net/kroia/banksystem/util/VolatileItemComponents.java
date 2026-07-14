package net.kroia.banksystem.util;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Registry of the two data-driven <b>item component sets</b> that control how ItemStack
 * components participate in {@link ItemID} identity and in deposits:
 * <p>
 * <b>1. Volatile components</b> — <i>ignored for identity</i>.
 * Some mods attach time-varying / interior-mutable data components to item stacks
 * (e.g. TerraFirmaCraft's {@code tfc:food} decay timestamp and {@code tfc:heat} temperature).
 * Such components change on their own over time — sometimes even while being compared or
 * serialized — which breaks any identity system that treats every component byte as identity
 * (spurious new ItemIDs per decay window, registry templates rotting in place, save failures).
 * BankSystem therefore removes these component types from all stacks at the ItemID identity
 * boundary (compare, register, store, save, sync) via {@link #normalize(ItemStack)}.
 * Mods like TFC re-attach a fresh component (with fresh state) in their {@code ItemStack}
 * constructor hooks, so stacks handed out to players from a normalized template automatically
 * receive up-to-date volatile state.
 * <p>
 * <b>2. Deposit-gated components</b> — <i>also ignored for identity, but checked at deposit
 * time</i>. Ignoring a state-carrying component for identity makes differently-aged items
 * fungible inside the bank, which opens a laundering exploit: deposit a rotten TFC food,
 * withdraw a fresh one. For component types in the gated set,
 * {@link #isDepositEquivalent(ItemStack, ItemID)} additionally verifies at every credit
 * boundary that the incoming stack is equivalent to the withdrawal-equivalent reconstruction
 * the bank would hand back; stacks that are not equivalent are rejected and stay with the
 * player. Items that carry no gated component are entirely unaffected.
 * <p>
 * Both sets are the union of two sources each:
 * <ol>
 *     <li><b>Datapack tags</b> over the {@code minecraft:data_component_type} registry:
 *         {@code banksystem:volatile_item_components} and
 *         {@code banksystem:deposit_gated_components}.
 *         To extend these tags, a datapack must place its tag file under BankSystem's
 *         namespace — {@code data/banksystem/tags/data_component_type/volatile_item_components.json}
 *         or {@code data/banksystem/tags/data_component_type/deposit_gated_components.json} —
 *         <b>not</b> under the pack's own namespace (a tag in another namespace defines a
 *         different tag that BankSystem never reads).
 *         BankSystem ships these tags with <b>optional</b> entries for known offenders;
 *         modpack authors extend them with their own datapacks. Vanilla tag syncing
 *         distributes the tags to clients automatically.</li>
 *     <li><b>Server config lists</b> {@code ADDITIONAL_VOLATILE_COMPONENTS} and
 *         {@code ADDITIONAL_DEPOSIT_GATED_COMPONENTS}
 *         (see {@link net.kroia.banksystem.BankSystemModSettings.Bank}) for admins without
 *         datapack access. Both lists are synced to clients and forwarded to slave servers
 *         inside {@link net.kroia.banksystem.networking.general.SyncItemIDsPacket}.</li>
 * </ol>
 */
public final class VolatileItemComponents {

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    /**
     * Datapack tag over the data-component-type registry listing volatile component types
     * (ignored for ItemID identity). Shipped by BankSystem with optional entries
     * ({@code tfc:food}, {@code tfc:heat}); extendable by modpack datapacks without any code.
     */
    public static final TagKey<DataComponentType<?>> VOLATILE_COMPONENTS_TAG = TagKey.create(
            Registries.DATA_COMPONENT_TYPE,
            ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "volatile_item_components"));

    /**
     * Datapack tag over the data-component-type registry listing deposit-gated component types
     * (ignored for ItemID identity, but must match a fresh reconstruction at deposit time —
     * see {@link #isDepositEquivalent(ItemStack, ItemID)}). Shipped by BankSystem with an
     * optional entry for {@code tfc:food} (food decay state must not be laundered);
     * extendable by modpack datapacks without any code.
     */
    public static final TagKey<DataComponentType<?>> DEPOSIT_GATED_COMPONENTS_TAG = TagKey.create(
            Registries.DATA_COMPONENT_TYPE,
            ResourceLocation.fromNamespaceAndPath(BankSystemMod.MOD_ID, "deposit_gated_components"));

    /**
     * Additional volatile component ids sourced from the server config
     * ({@code ADDITIONAL_VOLATILE_COMPONENTS}). On the master server this is filled from the
     * settings file; on clients and slave servers it is overwritten by the list carried in
     * {@link net.kroia.banksystem.networking.general.SyncItemIDsPacket} so that all sides
     * normalize identically. Replaced atomically (the referenced set is never mutated).
     */
    private static volatile Set<ResourceLocation> configComponentIds = Set.of();

    /**
     * Additional deposit-gated component ids sourced from the server config
     * ({@code ADDITIONAL_DEPOSIT_GATED_COMPONENTS}). Filled and synced exactly like
     * {@link #configComponentIds}. Replaced atomically (the referenced set is never mutated).
     */
    private static volatile Set<ResourceLocation> gatedConfigComponentIds = Set.of();

    /**
     * Snapshot of the ids contained in {@link #VOLATILE_COMPONENTS_TAG} at the moment the
     * world was loaded (or the client joined). {@code null} = not captured yet — all tag
     * lookups then fall back to the live registry tag.
     * <p>
     * <b>Why a snapshot:</b> datapack tags can be rebound at runtime ({@code /reload},
     * {@code /datapack enable}). If the tag contents were read live, a runtime tag change
     * would silently change ItemID identity on a running server, bypassing the startup
     * merge guard (see {@link ItemIDManager#detectCollapseCollisions}). By normalizing
     * against the snapshot, a runtime tag change is <b>rejected</b>: the server keeps using
     * the set it applied at world load, and the change is evaluated (and guarded) at the
     * next restart. {@link #onServerResourcesReloaded()} logs an error when it detects
     * such a rejected change.
     */
    private static volatile Set<ResourceLocation> tagVolatileSnapshot = null;

    /** Snapshot of {@link #DEPOSIT_GATED_COMPONENTS_TAG}; same semantics as {@link #tagVolatileSnapshot}. */
    private static volatile Set<ResourceLocation> tagGatedSnapshot = null;

    /**
     * Tick countdown started by {@link #onServerResourcesReloaded()}. Tag rebinding happens
     * a few scheduler tasks after the reload listeners run, so the comparison against the
     * snapshot is deferred by a couple of server ticks (see {@link #serverTick()}).
     */
    private static int pendingTagCheckTicks = 0;

    /** Last live tag set we already warned about, to avoid log spam on repeated reloads. */
    private static Set<ResourceLocation> lastRejectedTagUnion = null;

    private VolatileItemComponents() {}

    /**
     * Replaces the config-sourced part of the volatile component set.
     * Invalid resource locations are skipped with a warning. Unknown-but-valid ids are kept
     * (the owning mod may simply be absent — harmless, they just never match a component).
     *
     * @param componentIds component type ids as strings, e.g. {@code "tfc:food"}. May be null.
     * @return {@code true} if the effective set changed (callers should then re-normalize
     *         already-registered templates via {@link ItemIDManager#renormalizeAndMerge()}).
     */
    public static boolean setConfigComponentIds(@Nullable Collection<String> componentIds) {
        Set<ResourceLocation> newSet = parseComponentIds(componentIds, "volatile");
        if (newSet.equals(configComponentIds))
            return false;
        configComponentIds = Set.copyOf(newSet);
        return true;
    }

    /**
     * Replaces the config-sourced part of the deposit-gated component set.
     * Gated components are stripped by {@link #normalize(ItemStack)} as well (they are
     * invisible to identity), so a change here also changes ItemID identity — callers must
     * re-normalize registered templates via {@link ItemIDManager#renormalizeAndMerge()} when
     * this returns {@code true}, exactly like for {@link #setConfigComponentIds(Collection)}.
     *
     * @param componentIds component type ids as strings, e.g. {@code "tfc:food"}. May be null.
     * @return {@code true} if the effective set changed.
     */
    public static boolean setGatedConfigComponentIds(@Nullable Collection<String> componentIds) {
        Set<ResourceLocation> newSet = parseComponentIds(componentIds, "deposit-gated");
        if (newSet.equals(gatedConfigComponentIds))
            return false;
        gatedConfigComponentIds = Set.copyOf(newSet);
        return true;
    }

    /** Parses id strings into resource locations, skipping invalid entries with a warning. */
    private static Set<ResourceLocation> parseComponentIds(@Nullable Collection<String> componentIds, String setName) {
        Set<ResourceLocation> newSet = new LinkedHashSet<>();
        if (componentIds != null) {
            for (String idStr : componentIds) {
                if (idStr == null || idStr.isBlank())
                    continue;
                ResourceLocation id = ResourceLocation.tryParse(idStr.trim());
                if (id == null) {
                    warn("Ignoring invalid " + setName + " component id in config: '" + idStr + "'");
                    continue;
                }
                newSet.add(id);
            }
        }
        return newSet;
    }

    // ------------------------------------------------------------------------------------
    // Tag snapshot (world-load-time view of the datapack tags)
    // ------------------------------------------------------------------------------------

    /** Reads the ids currently bound to the given data-component-type tag from the live registry. */
    private static @NotNull Set<ResourceLocation> readTagIds(@NotNull TagKey<DataComponentType<?>> tag) {
        Set<ResourceLocation> ids = new LinkedHashSet<>();
        for (Holder<DataComponentType<?>> holder : BuiltInRegistries.DATA_COMPONENT_TYPE.getTagOrEmpty(tag)) {
            ResourceLocation id = holder.unwrapKey().map(k -> k.location()).orElse(null);
            if (id == null)
                id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(holder.value());
            if (id != null)
                ids.add(id);
        }
        return ids;
    }

    /**
     * Captures the current contents of the two datapack tags as the <b>applied</b> tag sets.
     * From this point on, all normalization uses the snapshot instead of the live tags, so
     * runtime tag rebinds ({@code /reload}) cannot change ItemID identity until the next
     * restart (see {@link #tagVolatileSnapshot}).
     * <p>
     * Called at world load on servers (master and slave, before the startup merge guard runs)
     * and — via {@link #captureTagSnapshotIfAbsent()} — on clients when the first
     * {@link net.kroia.banksystem.networking.general.SyncItemIDsPacket} arrives.
     */
    public static void captureTagSnapshot() {
        tagVolatileSnapshot = Set.copyOf(readTagIds(VOLATILE_COMPONENTS_TAG));
        tagGatedSnapshot = Set.copyOf(readTagIds(DEPOSIT_GATED_COMPONENTS_TAG));
        lastRejectedTagUnion = null;
    }

    /**
     * Captures the tag snapshot only if none was captured yet. Used on the client side:
     * the first sync after joining captures the (freshly synced) tags, while later syncs on
     * the same connection must NOT re-capture — if the server rejected a runtime tag change,
     * the client has to keep normalizing with the same (old) set as the server.
     */
    public static void captureTagSnapshotIfAbsent() {
        if (tagVolatileSnapshot == null || tagGatedSnapshot == null)
            captureTagSnapshot();
    }

    /**
     * Drops the tag snapshot (back to live tag reads until the next capture).
     * Called when the server stops and when the client disconnects, so the next
     * world/server uses its own freshly bound tags.
     */
    public static void resetTagSnapshot() {
        tagVolatileSnapshot = null;
        tagGatedSnapshot = null;
        lastRejectedTagUnion = null;
        pendingTagCheckTicks = 0;
    }

    /** @return the applied volatile tag ids: the snapshot if captured, otherwise the live tag. */
    private static @NotNull Set<ResourceLocation> effectiveVolatileTagIds() {
        Set<ResourceLocation> snapshot = tagVolatileSnapshot;
        return snapshot != null ? snapshot : readTagIds(VOLATILE_COMPONENTS_TAG);
    }

    /** @return the applied deposit-gated tag ids: the snapshot if captured, otherwise the live tag. */
    private static @NotNull Set<ResourceLocation> effectiveGatedTagIds() {
        Set<ResourceLocation> snapshot = tagGatedSnapshot;
        return snapshot != null ? snapshot : readTagIds(DEPOSIT_GATED_COMPONENTS_TAG);
    }

    /**
     * Returns the <b>effective component set</b> actually applied to ItemID identity:
     * the union of both datapack tags (snapshot view if captured) and both config lists,
     * as a <b>sorted</b> list of id strings.
     * <p>
     * This is the value the startup merge guard persists in {@code Meta_data.nbt} and
     * compares across restarts to distinguish healing merges (unchanged set) from
     * collapse merges (changed set) — see {@link ItemIDManager#detectCollapseCollisions}.
     * Config ids whose owning mod is absent are included on purpose: they describe the
     * configured intent and keep the comparison stable across mod-list changes.
     *
     * @return sorted, de-duplicated list of component type id strings. Never null.
     */
    public static @NotNull List<String> getEffectiveComponentIds() {
        TreeSet<String> ids = new TreeSet<>();
        for (ResourceLocation id : effectiveVolatileTagIds())
            ids.add(id.toString());
        for (ResourceLocation id : effectiveGatedTagIds())
            ids.add(id.toString());
        for (ResourceLocation id : configComponentIds)
            ids.add(id.toString());
        for (ResourceLocation id : gatedConfigComponentIds)
            ids.add(id.toString());
        return new ArrayList<>(ids);
    }

    /**
     * Notifies this registry that the server's datapack resources were (re)loaded.
     * During initial server startup the snapshot is not captured yet, so this is a no-op.
     * On a <b>running</b> server ({@code /reload}, {@code /datapack enable ...}) it schedules
     * a deferred comparison of the freshly bound tags against the applied snapshot
     * (deferred because vanilla rebinds registry tags a few scheduler tasks after the
     * reload listeners run). If the tags changed, {@link #serverTick()} logs an error —
     * the change itself is automatically rejected, because normalization keeps using the
     * snapshot until the next restart, where the startup merge guard evaluates it.
     */
    public static void onServerResourcesReloaded() {
        if (tagVolatileSnapshot != null || tagGatedSnapshot != null)
            pendingTagCheckTicks = 3;
    }

    /**
     * Per-server-tick hook (cheap: a single int check). Performs the deferred
     * tag-change detection scheduled by {@link #onServerResourcesReloaded()}.
     */
    public static void serverTick() {
        if (pendingTagCheckTicks <= 0)
            return;
        if (--pendingTagCheckTicks == 0)
            checkForRuntimeTagChange();
    }

    /**
     * Compares the live tag contents against the applied snapshot and logs an error if a
     * runtime tag change was detected (and therefore rejected). Never mutates the snapshot —
     * rejecting the change IS the point (see {@link #tagVolatileSnapshot}).
     */
    private static void checkForRuntimeTagChange() {
        Set<ResourceLocation> snapshotVolatile = tagVolatileSnapshot;
        Set<ResourceLocation> snapshotGated = tagGatedSnapshot;
        if (snapshotVolatile == null || snapshotGated == null)
            return;
        Set<ResourceLocation> liveVolatile = readTagIds(VOLATILE_COMPONENTS_TAG);
        Set<ResourceLocation> liveGated = readTagIds(DEPOSIT_GATED_COMPONENTS_TAG);
        if (liveVolatile.equals(snapshotVolatile) && liveGated.equals(snapshotGated))
            return;
        Set<ResourceLocation> liveUnion = new LinkedHashSet<>(liveVolatile);
        liveUnion.addAll(liveGated);
        if (liveUnion.equals(lastRejectedTagUnion))
            return; // already warned about exactly this change
        lastRejectedTagUnion = liveUnion;
        error("A datapack reload changed the contents of the '" + VOLATILE_COMPONENTS_TAG.location()
                + "' / '" + DEPOSIT_GATED_COMPONENTS_TAG.location() + "' tags on a RUNNING server. "
                + "The change was REJECTED: item identification keeps using the component set that was "
                + "applied when the world was loaded, so no items merge mid-session. "
                + "The new tag contents will be evaluated by the ItemID merge guard at the next server restart. "
                + "(Applied volatile set: " + snapshotVolatile + ", applied gated set: " + snapshotGated
                + "; new volatile set: " + liveVolatile + ", new gated set: " + liveGated + ")");
    }

    /**
     * @return the config-sourced volatile component ids as strings, for syncing to
     *         clients and slave servers. Never null.
     */
    public static @NotNull List<String> getConfigComponentIdStrings() {
        return toStringList(configComponentIds);
    }

    /**
     * @return the config-sourced deposit-gated component ids as strings, for syncing to
     *         clients and slave servers. Never null.
     */
    public static @NotNull List<String> getGatedConfigComponentIdStrings() {
        return toStringList(gatedConfigComponentIds);
    }

    private static @NotNull List<String> toStringList(Set<ResourceLocation> ids) {
        List<String> list = new ArrayList<>();
        for (ResourceLocation id : ids)
            list.add(id.toString());
        return list;
    }

    /**
     * Returns a copy of the given stack with all volatile <b>and</b> deposit-gated component
     * types removed. The input stack is <b>never mutated</b>.
     * <p>
     * Gated components are stripped too because they must be invisible to ItemID identity:
     * a rotten and a fresh food have to resolve to the same ItemID, otherwise every decay
     * step would mint a new spurious ID (the corruption the volatile set exists to prevent).
     * The state check for gated components happens separately, at deposit time, via
     * {@link #isDepositEquivalent(ItemStack, ItemID)}.
     * <p>
     * Note that {@link ItemStack#copy()} may itself trigger third-party constructor hooks
     * that (re-)attach volatile components (this is exactly TFC's behavior) — the removal
     * below runs after the copy, so the result is always free of volatile components
     * regardless of what such hooks do.
     * <p>
     * "Removed" means <i>reset to the item's prototype default</i>: component types that are
     * part of the item's default component map (e.g. {@code minecraft:repair_cost}) are set
     * back to their prototype value rather than removal-marked, so a normalized stack always
     * compares equal to a normalized default instance — see {@link #resetComponentToPrototype}.
     *
     * @param stack the stack to normalize (not mutated)
     * @return a normalized copy; {@link ItemStack#EMPTY} for empty inputs
     */
    public static @NotNull ItemStack normalize(@NotNull ItemStack stack) {
        if (stack.isEmpty())
            return ItemStack.EMPTY;
        ItemStack copy = stack.copy();
        // Strip everything listed in the datapack tags (volatile + deposit-gated).
        // Uses the world-load snapshot when captured, so runtime tag rebinds cannot
        // change identity mid-session (see tagVolatileSnapshot).
        removeByIds(copy, effectiveVolatileTagIds());
        removeByIds(copy, effectiveGatedTagIds());
        // Strip everything listed in the (synced) server config lists.
        removeByIds(copy, configComponentIds);
        removeByIds(copy, gatedConfigComponentIds);
        return copy;
    }

    /**
     * Returns a copy of the given stack with exactly the component types listed in
     * {@code ids} removed (unresolvable ids — owning mod absent — are skipped).
     * The input stack is never mutated.
     * <p>
     * Used by the startup merge guard to reconstruct an item's identity under a
     * <b>previous</b> effective component set (the set persisted in {@code Meta_data.nbt}),
     * which is how healing merges are told apart from collapse merges — see
     * {@link ItemIDManager#detectCollapseCollisions}.
     *
     * @param stack the stack to strip (not mutated)
     * @param ids   the component type ids to remove
     * @return a stripped copy; {@link ItemStack#EMPTY} for empty inputs
     */
    public static @NotNull ItemStack stripComponentsByIds(@NotNull ItemStack stack, @NotNull Set<ResourceLocation> ids) {
        if (stack.isEmpty())
            return ItemStack.EMPTY;
        // Note: copy() may re-attach volatile components via third-party constructor hooks
        // (TFC does this) — removal runs after the copy, exactly like in normalize().
        ItemStack copy = stack.copy();
        removeByIds(copy, ids);
        return copy;
    }

    private static void removeByIds(ItemStack stack, Set<ResourceLocation> ids) {
        for (ResourceLocation id : ids) {
            DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(id);
            if (type != null)
                resetComponentToPrototype(stack, type);
        }
    }

    /**
     * Strips a single component type from the stack <b>without ever poisoning the component
     * patch</b>.
     * <p>
     * Why not simply {@link ItemStack#remove}: every item carries a set of <i>prototype</i>
     * (default) components — e.g. {@code minecraft:repair_cost} is present with value 0 on
     * <b>every</b> item in the game. For such types {@code remove()} does not clear the patch,
     * it writes an explicit <i>removal marker</i> ({@code "!minecraft:repair_cost"}) into the
     * patch, which makes the stack compare <b>unequal</b> to a pristine default instance in
     * {@link ItemStack#isSameItemSameComponents}. A registry template normalized that way
     * would no longer match any real stack of the same item — every lookup would fail and
     * mint spurious new ItemIDs (this corrupted a live world when {@code minecraft:repair_cost}
     * was temporarily declared volatile).
     * <p>
     * Instead, the component is reset to its prototype value: for prototype-backed types,
     * {@code set(type, prototypeValue)} collapses the patch entry entirely (vanilla's
     * {@code PatchedDataComponentMap.set} removes the patch when the value equals the
     * prototype); for non-prototype types, {@code remove()} is safe and just clears the patch.
     * Either way the result is "this component no longer distinguishes the stack from a
     * default instance", which is exactly what volatile-component identity requires.
     *
     * @param stack the stack to strip (mutated in place — callers pass defensive copies)
     * @param type  the component type to reset
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void resetComponentToPrototype(ItemStack stack, DataComponentType<?> type) {
        Object prototypeValue = stack.getPrototype().get((DataComponentType) type);
        if (prototypeValue != null)
            stack.set((DataComponentType) type, prototypeValue);
        else
            stack.remove(type);
    }

    /**
     * Deposit gate: decides whether a physical ItemStack may be converted into a bank credit.
     * <p>
     * <b>Why this exists:</b> ItemID identity ignores volatile and gated components, so the
     * bank treats e.g. rotten and fresh TFC food as the same fungible item. Without this
     * check a player could deposit a rotten food and withdraw a fresh one ("state
     * laundering"). The rule closing that exploit: <i>an item may only be deposited if it is
     * equivalent to what the bank would hand back for it.</i>
     * <p>
     * <b>How:</b> the withdrawal-equivalent reconstruction {@code R} is a copy of the
     * normalized registry template ({@link ItemIDManager#getItemStack(ItemID)}) — third-party
     * constructor hooks stamp fresh state onto the copy, exactly like on a real withdrawal.
     * Both the incoming stack and {@code R} are then compared via
     * {@link ItemStack#isSameItemSameComponents} with all <i>non-gated</i> volatile components
     * stripped but the <i>gated</i> components kept on both sides.
     * <p>
     * <b>Why {@code isSameItemSameComponents} makes this mod-agnostic:</b> mods that care
     * about state equivalence mixin their own semantics into that comparison (TFC: creation
     * dates compare equal only within its decay-stacking window; rotten never equals fresh).
     * The source mod therefore decides what counts as "equivalent to fresh" — BankSystem
     * contains no mod-specific logic. Mods without such hooks are unaffected unless a pack
     * author explicitly gates one of their components.
     * <p>
     * Cheap short-circuits keep items without gated components completely unaffected.
     *
     * @param incoming the physical stack the player/block wants to deposit (never mutated)
     * @param itemID   the (already resolved) ItemID the credit would be booked under
     * @return {@code true} if the deposit is acceptable, {@code false} if it must be rejected
     */
    public static boolean isDepositEquivalent(@NotNull ItemStack incoming, @NotNull ItemID itemID) {
        if (incoming.isEmpty())
            return true;
        List<DataComponentType<?>> gatedTypes = effectiveGatedTypes();
        if (gatedTypes.isEmpty())
            return true; // No gated components configured — gate disabled, zero overhead.
        boolean hasGated = false;
        for (DataComponentType<?> type : gatedTypes) {
            if (incoming.has(type)) {
                hasGated = true;
                break;
            }
        }
        if (!hasGated)
            return true; // Item carries no gated component — completely unaffected.

        // Withdrawal-equivalent reconstruction: defensive copy of the normalized template;
        // mod constructor hooks stamp fresh gated/volatile state onto the copy.
        ItemStack reconstruction = ItemIDManager.getItemStack(itemID);
        if (reconstruction.isEmpty())
            return true; // Unknown ItemID — nothing to compare against; the deposit itself
                         // fails downstream ("item not registered"), so nothing is credited.

        // Compare with non-gated volatile components stripped from BOTH sides, gated
        // components KEPT on both sides. The gated state is exactly what we must compare.
        ItemStack s = stripNonGatedVolatile(incoming);
        ItemStack r = stripNonGatedVolatile(reconstruction);
        return ItemStack.isSameItemSameComponents(s, r);
    }

    /**
     * Convenience overload of {@link #isDepositEquivalent(ItemStack, ItemID)} that resolves
     * the ItemID itself. Prefer the two-argument form when the caller already has the ID
     * (avoids a second registry scan).
     */
    public static boolean isDepositEquivalent(@NotNull ItemStack incoming) {
        if (incoming.isEmpty())
            return true;
        if (effectiveGatedTypes().isEmpty())
            return true;
        return isDepositEquivalent(incoming, ItemIDManager.getItemID(incoming));
    }

    /**
     * @return the effective deposit-gated component types (datapack tag + config union),
     *         resolved against the component-type registry. Unresolvable config ids
     *         (owning mod absent) are skipped.
     */
    private static @NotNull List<DataComponentType<?>> effectiveGatedTypes() {
        List<DataComponentType<?>> types = new ArrayList<>();
        for (ResourceLocation id : effectiveGatedTagIds()) {
            DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(id);
            if (type != null && !types.contains(type))
                types.add(type);
        }
        for (ResourceLocation id : gatedConfigComponentIds) {
            DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(id);
            if (type != null && !types.contains(type))
                types.add(type);
        }
        return types;
    }

    /**
     * Returns a copy of the stack with all volatile components removed <b>except</b> those in
     * the deposit-gated set. Used only by the deposit gate: the gated state must survive into
     * the comparison, while irrelevant volatile data (e.g. {@code tfc:heat}) must not cause
     * false rejections.
     */
    private static @NotNull ItemStack stripNonGatedVolatile(@NotNull ItemStack stack) {
        List<DataComponentType<?>> gatedTypes = effectiveGatedTypes();
        ItemStack copy = stack.copy();
        // Reset instead of remove — see resetComponentToPrototype() for why plain remove()
        // would poison the patch of prototype-backed component types.
        for (ResourceLocation id : effectiveVolatileTagIds()) {
            DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(id);
            if (type != null && !gatedTypes.contains(type))
                resetComponentToPrototype(copy, type);
        }
        for (ResourceLocation id : configComponentIds) {
            DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(id);
            if (type != null && !gatedTypes.contains(type))
                resetComponentToPrototype(copy, type);
        }
        return copy;
    }

    private static void warn(String msg) {
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null)
            BACKEND_INSTANCES.LOGGER.warn("[VolatileItemComponents] " + msg);
    }

    private static void error(String msg) {
        if (BACKEND_INSTANCES != null && BACKEND_INSTANCES.LOGGER != null)
            BACKEND_INSTANCES.LOGGER.error("[VolatileItemComponents] " + msg);
    }
}
