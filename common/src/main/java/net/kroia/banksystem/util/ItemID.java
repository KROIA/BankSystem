package net.kroia.banksystem.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.JsonUtilities;
import net.kroia.modutilities.persistence.ServerSaveable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ItemID implements ServerSaveable {

    private static BankSystemModBackend.Instances BACKEND_INSTANCES;
    public static void setBackend(BankSystemModBackend.Instances backend) {
        BACKEND_INSTANCES = backend;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, ItemID> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.SHORT, p -> p.id,
            ItemID::new
    );

    private static final String compoundTagKey_ID = "id";
    public static final ItemID INVALID_ID = new ItemID((short)0);

    private short id;
    private @Nullable String name_cache = null;

    /**
     * True while {@link #name_cache} holds the numeric fallback ({@code String.valueOf(id)})
     * instead of a real item registry name.
     * <p>
     * Why this exists: NBT {@link #load(CompoundTag)} can run before the item registry /
     * {@link ItemIDManager} is populated (e.g. world data referencing ItemIDs is loaded before
     * or while the ItemID table itself loads). In that situation the numeric name is only a
     * <b>provisional placeholder</b> and must be replaced as soon as resolution becomes
     * possible — {@link #getName()} retries the (cheap) resolution while this flag is set.
     * Previously the placeholder was cached forever, which misled server-side consumers
     * (e.g. command tab suggestions) into showing bare numeric IDs instead of item names.
     * <p>
     * This flag is intentionally <b>not persisted</b> — the serialization format only contains
     * the id, and the flag is re-derived on load from whether resolution succeeds.
     */
    private boolean nameIsPlaceholder = false;


    public ItemID(short id)
    {
        this.id = id;
        //this.name_cache = String.valueOf(id);
        tryUpdateNameCache();
    }
    public ItemID(short id, @Nullable String name_cache)
    {
        this.id = id;
        // The caller supplies a trusted, real registry name (or null, in which case
        // getName() resolves lazily) — never a numeric placeholder.
        this.name_cache = name_cache;
    }
    public ItemID(ItemID other)
    {
        this.id = other.id;
        this.name_cache = other.name_cache;
        this.nameIsPlaceholder = other.nameIsPlaceholder;
    }

    /**
     * Overwrites the cached name with a real registry name (or null to force lazy
     * re-resolution in {@link #getName()}). Must never be called with a numeric placeholder —
     * placeholders are only ever written internally together with {@link #nameIsPlaceholder}.
     *
     * @param name_cache the real item registry name, or null
     */
    public void setNameCache_internal(String name_cache)
    {
        this.name_cache = name_cache;
        this.nameIsPlaceholder = false;
    }
    public void tryUpdateNameCache()
    {
        // Read-only access: the template is only inspected, never mutated,
        // so the defensive copy of getStack() is not needed here.
        ItemStack stack = getStackTemplate();
        if(stack.isEmpty() || stack.getItem() == Items.AIR) {
            // Not resolvable (yet): registry/manager may simply not be populated.
            // Cache the numeric id as a marked placeholder so getName() retries later.
            name_cache = String.valueOf(id);
            nameIsPlaceholder = true;
            return;
        }
        String name = ItemUtilities.getItemIDStr(stack.getItem());
        if(name == null) {
            // The platform registry can't name the item (yet) — same provisional fallback.
            name_cache = String.valueOf(id);
            nameIsPlaceholder = true;
            return;
        }
        name_cache = name;
        nameIsPlaceholder = false;
    }
    public static @NotNull ItemID fromJson(JsonElement jsonElement)
    {
        if(!jsonElement.isJsonObject())
            return INVALID_ID;

        JsonObject jsonObject = jsonElement.getAsJsonObject();
        if(!jsonObject.has(compoundTagKey_ID))
            return INVALID_ID;
        short id = jsonObject.get(compoundTagKey_ID).getAsShort();
        return new ItemID(id);
    }

    public static @NotNull ItemID createFromTag(CompoundTag tag) {
        ItemID itemID = new ItemID((short) 0);
        if (!itemID.load(tag)) {
            return INVALID_ID; // Invalid data
        }
        return itemID;
    }

    public static @NotNull ItemID getFromItemStack(ItemStack itemStack)
    {
        return ItemIDManager.getItemID(itemStack);
    }

    /**
     * Do not call this from the client side!
     * @param itemStack
     * @return the itemID associated with the itemStack
     */
    public static @NotNull CompletableFuture<ItemID> getOrRegisterFromItemStackServerSide(@NotNull ItemStack itemStack)
    {
        return ItemIDManager.registerItemStackServerSide(itemStack);
    }
    //public static @NotNull CompletableFuture<List<ItemID>> getOrRegisterFromItemStackServerSide(@NotNull List<ItemStack> itemStacks)
    //{
    //    return ItemIDManager.registerItemStackServerSide(itemStacks);
    //}

    /**
     * Do not call this from the server side
     * @param itemStack
     * @return the itemID associated with the itemStack
     */
    public static @NotNull CompletableFuture<ItemID> getOrRegisterFromItemStackClientSide(@NotNull ItemStack itemStack)
    {
        return ItemIDManager.registerItemStackClientSide(itemStack);
    }
    public static @NotNull CompletableFuture<List<ItemID>> getOrRegisterFromItemStackClientSide(@NotNull List<ItemStack> itemStacks)
    {
        return ItemIDManager.registerItemStackClientSide(itemStacks);
    }


    /**
     * This function is only save to call from the master server
     * @param itemStack
     * @return the itemID associated with the itemStack
     */
    public static @NotNull ItemID getOrRegisterFromItemStackServerSide_direct(@NotNull ItemStack itemStack)
    {
        // Note: getFromItemStack (→ ItemIDManager.getItemID) returns INVALID_ID (not null) for
        // unknown stacks. Prior to Task #23 the slave-side createDefaultItemIDs pre-registered
        // items so unknown stacks were rare on the register-flow path; with Task #23 removing
        // that pre-registration, an unknown stack on the slave must fall through to the
        // registration path (which on the slave delegates to master via ARRS). Gating on
        // `== null` alone silently returned INVALID_ID without ever calling the delegation.
        ItemID id = getFromItemStack(itemStack);
        if (id == null || !id.isValid())
        {
            id = ItemIDManager.registerItemStackServerSide_direct(itemStack);
        }
        return id;
    }

    public static ItemID of(ItemStack defaultInstance) {
        ItemID id = ItemIDManager.getItemID(defaultInstance);
        if(id == null)
        {
            warn("Item not registered: "+ defaultInstance);
            return INVALID_ID;
        }
        return id;
    }




    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ItemID other)) return false;
        return id == other.id;
    }

    @Override
    public int hashCode() {
        return id;
    }

    /**
     * Returns a <b>defensive copy</b> of the template stack registered for this ItemID.
     * Callers may freely mutate the result (set counts, apply components, ...).
     * Aliased IDs (merged during volatile-component migration) resolve to their canonical entry.
     *
     * @return a copy of the registered template, or {@link ItemStack#EMPTY} if unknown
     */
    public @NotNull ItemStack getStack() {
        return ItemIDManager.getItemStack(this);
    }

    /**
     * Returns the <b>live template stack</b> for this ItemID without copying.
     * <p>
     * <b>Read-only!</b> Never mutate the returned stack — it is the registry's internal
     * template. Only use this on hot paths (e.g. per-frame rendering) where the defensive
     * copy of {@link #getStack()} would cause needless allocations.
     *
     * @return the live template stack, or {@link ItemStack#EMPTY} if unknown
     */
    public @NotNull ItemStack getStackTemplate() {
        return ItemIDManager.getItemStackTemplate(this);
    }

    /**
     * Returns the item registry name (e.g. {@code "minecraft:paper"}) for this ItemID, or the
     * numeric id as a string while the name cannot be resolved.
     * <p>
     * <b>Lazy self-heal:</b> when the cache only holds the numeric placeholder (see
     * {@link #nameIsPlaceholder}), every call retries the real resolution before returning.
     * The retry is cheap — a single map lookup in {@link ItemIDManager} (including alias
     * resolution for merged IDs) — so it is safe on hot paths. On success the real name is
     * cached and the flag cleared; on failure the placeholder is returned as before. A failed
     * retry is <i>normal</i> while the registry/manager is not yet populated, so it is silent
     * by design: no exceptions, no log spam.
     *
     * @return the resolved item name, or the numeric id as a provisional fallback
     */
    public @NotNull String getName() {
        // Fast path: a real (non-placeholder) cached name never changes.
        if(name_cache != null && !nameIsPlaceholder)
            return name_cache;

        // Resolve, or retry a provisional placeholder. Read-only access, no copy needed.
        ItemStack stack = getStackTemplate();
        if(stack.isEmpty()) {
            name_cache = String.valueOf(id);
            nameIsPlaceholder = true;
            return name_cache;
        }
        String name = ItemUtilities.getItemIDStr(stack.getItem());
        if(name == null) {
            name_cache = String.valueOf(id);
            nameIsPlaceholder = true;
            return name_cache;
        }
        name_cache = name;
        nameIsPlaceholder = false;
        return name_cache;
    }
    public boolean isAir() {
        // Read-only access, no copy needed.
        ItemStack stack = getStackTemplate();
        return stack.isEmpty() || stack.is(Items.AIR);
    }

    @Override
    public boolean save(CompoundTag tag) {
        tag.putShort(compoundTagKey_ID, id);
        return true;
    }

    @Override
    public boolean load(CompoundTag tag) {
        if(!tag.contains(compoundTagKey_ID))
            return false;
        id = tag.getShort(compoundTagKey_ID);
        // Resolve the real name immediately if possible. NBT load can run before the item
        // registry / ItemIDManager is populated — in that case tryUpdateNameCache() stores the
        // numeric id as a marked placeholder (see nameIsPlaceholder) and getName() self-heals
        // once resolution becomes possible. Previously the placeholder was written
        // unconditionally and cached forever, so server-side consumers (e.g. command tab
        // suggestions) showed bare numbers instead of item names for every loaded ItemID.
        tryUpdateNameCache();
        return true;
    }

    public JsonElement toJson() {
        JsonObject jsonElement = new JsonObject();
        jsonElement.addProperty(compoundTagKey_ID, id);
        return jsonElement;
    }

    public String toJsonString()
    {
        return JsonUtilities.toPrettyString(toJson());
    }


    public boolean isValid() {
        if(id == INVALID_ID.id)
            return false;
        // Read-only access, no copy needed.
        ItemStack stack = getStackTemplate();
        return !stack.isEmpty();
    }

    @Override
    public String toString() {
        return getName()+"="+getShort();
    }

    public short getShort() {
        return id;
    }

    private static void info(String msg)
    {
        BACKEND_INSTANCES.LOGGER.info("[ItemID] " + msg);
    }
    private static void error(String msg)
    {
        BACKEND_INSTANCES.LOGGER.error("[ItemID] " + msg);
    }
    private static void error(String msg, Throwable e)
    {
        BACKEND_INSTANCES.LOGGER.error("[ItemID] " + msg, e);
    }
    private static void warn(String msg)
    {
        BACKEND_INSTANCES.LOGGER.warn("[ItemID] " + msg);
    }
    private static void debug(String msg)
    {
        BACKEND_INSTANCES.LOGGER.debug("[ItemID] " + msg);
    }
}
