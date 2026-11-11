package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.banking.bank.ServerBank;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.banksystem.util.ItemIDMissingModsException;
import net.kroia.banksystem.util.ItemStackNbt;
import net.kroia.modutilities.NbtCompat;
import net.kroia.modutilities.UtilitiesPlatform;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BS-194 / BS-195: saved ItemIDs whose mod is missing (or that carry data of a missing mod)
 * stay unresolved, survive a save unchanged, never merge, and refuse the load until
 * {@code CONFIRM_ITEMID_REMOVE_MISSING} removes them (shorts stay reserved, balances dropped).
 */
public class ItemIDMissingModTests extends TestSuite {

    private static final String NS = "bs_missing_test";

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.ITEM_ID;
    }

    @Override
    public void registerTests() {
        addTest("missing_mod_entries_refuse_load_and_survive_save", () -> run(false));
        addTest("missing_mod_recovery_removes_entries_and_balances", () -> run(true));
    }

    private static CompoundTag pair(int s, CompoundTag stack) {
        CompoundTag idTag = new CompoundTag();
        new ItemID((short) s).save(idTag);
        CompoundTag pair = new CompoundTag();
        pair.put("itemID", idTag);
        pair.put("itemStack", stack);
        return pair;
    }

    private static CompoundTag stack(String id) {
        CompoundTag t = new CompoundTag();
        t.putString("id", id);
        t.putInt("count", 1);
        return t;
    }

    /** An enchanted book whose only stored enchantment comes from a missing mod. */
    private static CompoundTag book(String enchantment) {
        CompoundTag levels = new CompoundTag();
        levels.putInt(NS + ":" + enchantment, 1);
        CompoundTag stored = new CompoundTag();
        stored.put("levels", levels);
        CompoundTag components = new CompoundTag();
        components.put("minecraft:stored_enchantments", stored);
        CompoundTag t = stack("minecraft:enchanted_book");
        t.put("components", components);
        return t;
    }

    private static Map<Short, CompoundTag> entriesByShort(CompoundTag file) {
        Map<Short, CompoundTag> out = new HashMap<>();
        for (Tag t : NbtCompat.getList(file, "itemIDs", Tag.TAG_COMPOUND)) {
            CompoundTag p = (CompoundTag) t;
            out.put(NbtCompat.getShort(NbtCompat.getCompound(p, "itemID"), "id"), p);
        }
        return out;
    }

    private TestResult run(boolean confirmRemoval) {
        if (UtilitiesPlatform.getRegistryAccessServerSide() == null)
            return pass("skipped: no server-side RegistryAccess");
        BankSystemModBackend.Instances instances = BankSystemModBackend.getInstances_forTesting();
        if (instances == null || instances.isSlaveServer || instances.SERVER_SETTINGS == null)
            return pass("skipped: not a master server — slaves never load ItemIDs from disk");

        Map<ItemID, ItemStack> savedItemMap = new HashMap<>(ItemIDManager.getItemIDMap());
        Map<ItemID, ItemID> savedAliasMap = new HashMap<>(ItemIDManager.getItemIDAliasMap());
        int savedCounter = ItemIDManager.getNextShortCounter_forTesting();
        int savedRepairCount = ItemIDManager.getLastLoadRepairCount_forTesting();
        Map<ItemID, ItemID> savedQuarantine = ItemIDManager.getQuarantinedAliases_forTesting();
        Set<ItemID> savedPersisted = ItemIDManager.getPersistedShorts_forTesting();
        Boolean savedConfirm = instances.SERVER_SETTINGS.BANK.CONFIRM_ITEMID_REMOVE_MISSING.get();

        try {
            int max = savedCounter;
            for (ItemID id : savedItemMap.keySet()) max = Math.max(max, id.getShort());
            for (Map.Entry<ItemID, ItemID> e : savedAliasMap.entrySet())
                max = Math.max(max, Math.max(e.getKey().getShort(), e.getValue().getShort()));
            int base = max + 600;
            if (base + 5 >= Short.MAX_VALUE)
                return fail("No free short range available");
            int missingItem = base + 1, bookA = base + 2, bookB = base + 3;

            Map<ItemID, ItemStack> fixture = new ConcurrentHashMap<>();
            fixture.put(new ItemID((short) base), new ItemStack(Items.PAPER));
            ItemIDManager.replaceState_forTesting(fixture, Collections.emptyMap(), base + 4);
            CompoundTag file = new CompoundTag();
            if (!new ItemIDManager().save(file))
                return fail("Could not serialize the fixture");
            ListTag list = NbtCompat.getList(file, "itemIDs", Tag.TAG_COMPOUND);
            CompoundTag rawMissing = pair(missingItem, stack(NS + ":gone"));
            CompoundTag rawBookA = pair(bookA, book("a"));
            CompoundTag rawBookB = pair(bookB, book("b"));
            list.add(rawMissing.copy());
            list.add(rawBookA.copy());
            list.add(rawBookB.copy());
            file.put("itemIDs", list);

            TestResult r = assertTrue("strict decode refuses a book with an unknown enchantment",
                    ItemStackNbt.parseStrict(UtilitiesPlatform.getRegistryAccessServerSide(),
                            NbtCompat.getCompound(rawBookA, "itemStack")).isEmpty());
            if (!r.passed()) return r;

            instances.SERVER_SETTINGS.BANK.CONFIRM_ITEMID_REMOVE_MISSING.set(confirmRemoval);
            ItemIDManager.clear();

            if (!confirmRemoval) {
                ItemIDMissingModsException refused = null;
                try {
                    new ItemIDManager().load(file);
                } catch (ItemIDMissingModsException e) {
                    refused = e;
                }
                r = assertNotNull("load refused while entries of a missing mod exist", refused);
                if (!r.passed()) return r;
                r = assertEquals("all three entries counted under the missing namespace",
                        3, refused.missingNamespaces.getOrDefault(NS, 0));
                if (!r.passed()) return r;
                r = assertTrue("report names CONFIRM_ITEMID_REMOVE_MISSING",
                        refused.getMessage().contains("CONFIRM_ITEMID_REMOVE_MISSING"));
                if (!r.passed()) return r;
                Map<ItemID, CompoundTag> unresolved = ItemIDManager.getUnresolvedEntries_forTesting();
                r = assertEquals("three unresolved entries kept", 3, unresolved.size());
                if (!r.passed()) return r;
                r = assertTrue("unresolved entries stay out of the lookup map and alias table",
                        !ItemIDManager.getItemIDMap().containsKey(new ItemID((short) bookA))
                                && !ItemIDManager.getItemIDMap().containsKey(new ItemID((short) bookB))
                                && !ItemIDManager.getItemIDAliasMap().containsKey(new ItemID((short) bookA))
                                && !ItemIDManager.getItemIDAliasMap().containsKey(new ItemID((short) bookB)));
                if (!r.passed()) return r;

                CompoundTag resaved = new CompoundTag();
                if (!new ItemIDManager().save(resaved))
                    return fail("Could not re-save");
                Map<Short, CompoundTag> entries = entriesByShort(resaved);
                r = assertEquals("missing-mod item written back unchanged", rawMissing, entries.get((short) missingItem));
                if (!r.passed()) return r;
                r = assertEquals("book A written back unchanged", rawBookA, entries.get((short) bookA));
                if (!r.passed()) return r;
                r = assertEquals("book B written back unchanged", rawBookB, entries.get((short) bookB));
                if (!r.passed()) return r;
                return pass("missing-mod entries refuse the load and survive a save byte-identical");
            }

            r = assertTrue("confirmed load() succeeds", new ItemIDManager().load(file));
            if (!r.passed()) return r;
            r = assertTrue("no unresolved entries left", ItemIDManager.getUnresolvedEntries_forTesting().isEmpty());
            if (!r.passed()) return r;
            r = assertEquals("removed IDs reported", Set.of(new ItemID((short) missingItem),
                    new ItemID((short) bookA), new ItemID((short) bookB)), ItemIDManager.getLastRemovedMissing());
            if (!r.passed()) return r;
            r = assertFalse("CONFIRM_ITEMID_REMOVE_MISSING consumed",
                    Boolean.TRUE.equals(instances.SERVER_SETTINGS.BANK.CONFIRM_ITEMID_REMOVE_MISSING.get()));
            if (!r.passed()) return r;
            r = assertTrue("removed shorts stay reserved", ItemIDManager.getNextShortCounter_forTesting() > bookB);
            if (!r.passed()) return r;
            CompoundTag resaved = new CompoundTag();
            if (!new ItemIDManager().save(resaved))
                return fail("Could not re-save");
            Map<Short, CompoundTag> entries = entriesByShort(resaved);
            r = assertTrue("removed entries are gone from the save, the valid one stays",
                    !entries.containsKey((short) missingItem) && !entries.containsKey((short) bookA)
                            && !entries.containsKey((short) bookB) && entries.containsKey((short) base));
            if (!r.passed()) return r;

            // A saved balance of a removed item no longer loads (dropped from the account).
            CompoundTag bankTag = new CompoundTag();
            CompoundTag idTag = new CompoundTag();
            new ItemID((short) missingItem).save(idTag);
            bankTag.put("itemID", idTag);
            bankTag.putLong("balance", 5);
            bankTag.putLong("lockedBalance", 0);
            r = assertNull("bank of a removed item is dropped on load", ServerBank.createFromTag(bankTag));
            if (!r.passed()) return r;
            return pass("confirmed recovery removes the entries, keeps shorts reserved and drops their balances");
        } catch (Throwable t) {
            return fail("missing-mod test threw: " + t.getClass().getSimpleName() + " — " + t.getMessage());
        } finally {
            ItemIDManager.replaceState_forTesting(savedItemMap, savedAliasMap, savedCounter);
            ItemIDManager.restoreQuarantinedAliases_forTesting(savedQuarantine);
            ItemIDManager.restorePersistedShorts_forTesting(savedPersisted);
            ItemIDManager.markRegistryReady();
            if (savedRepairCount == 0)
                ItemIDManager.consumeLastLoadRepairCount();
            ItemIDManager.consumeLastLoadRequiresResave();
            instances.SERVER_SETTINGS.BANK.CONFIRM_ITEMID_REMOVE_MISSING.set(savedConfirm);
            if (instances.SERVER_DATA_HANDLER != null) {
                instances.SERVER_DATA_HANDLER.save_globalSettings();
                instances.SERVER_DATA_HANDLER.save_metadata();
            }
        }
    }
}
