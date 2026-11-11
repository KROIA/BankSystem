package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.banksystem.util.ItemIDMergeAbortedException;
import net.kroia.banksystem.util.VolatileItemComponents;
import net.kroia.modutilities.UtilitiesPlatform;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Task #157: under an UNCHANGED volatile-component set, two persisted ItemIDs that load as
 * the same item (e.g. a mod update dropped the component that told them apart) must not
 * merge silently. Startup aborts without {@code CONFIRM_ITEMID_MERGE}; with it the merge is
 * applied and the flag is consumed.
 */
public class ItemIDUnchangedSetMergeTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.ITEM_ID;
    }

    @Override
    public void registerTests() {
        addTest("unchanged_set_persisted_merge_needs_confirmation", this::testPersistedMergeNeedsConfirmation);
    }

    private static ItemStack paper(String value) {
        ItemStack stack = new ItemStack(Items.PAPER);
        CompoundTag nbt = new CompoundTag();
        nbt.putString("banksystem_unchanged_set_merge_test", value);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(nbt));
        return stack;
    }

    private TestResult testPersistedMergeNeedsConfirmation() {
        if (UtilitiesPlatform.getRegistryAccessServerSide() == null)
            return pass("skipped: no server-side RegistryAccess");
        BankSystemModBackend.Instances instances = BankSystemModBackend.getInstances_forTesting();
        if (instances == null || instances.isSlaveServer || instances.SERVER_SETTINGS == null)
            return pass("skipped: not a master server — slaves never load ItemIDs from disk");
        List<String> currentSet = VolatileItemComponents.getEffectiveComponentIds();
        List<String> storedSet = instances.SERVER_DATA_HANDLER != null
                ? instances.SERVER_DATA_HANDLER.getStoredEffectiveComponentSet() : null;
        if (storedSet != null && !storedSet.equals(currentSet))
            return pass("skipped: component set changed this session — the unchanged-set branch is not reachable");

        Map<ItemID, ItemStack> savedItemMap = new HashMap<>(ItemIDManager.getItemIDMap());
        Map<ItemID, ItemID> savedAliasMap = new HashMap<>(ItemIDManager.getItemIDAliasMap());
        int savedCounter = ItemIDManager.getNextShortCounter_forTesting();
        int savedRepairCount = ItemIDManager.getLastLoadRepairCount_forTesting();
        Map<ItemID, ItemID> savedQuarantine = ItemIDManager.getQuarantinedAliases_forTesting();
        Set<ItemID> savedPersisted = ItemIDManager.getPersistedShorts_forTesting();
        Boolean savedConfirm = instances.SERVER_SETTINGS.BANK.CONFIRM_ITEMID_MERGE.get();

        try {
            // Unused short range, so the confirmed merge's balance-history purge touches nothing real.
            int max = savedCounter;
            for (ItemID id : savedItemMap.keySet()) max = Math.max(max, id.getShort());
            for (Map.Entry<ItemID, ItemID> e : savedAliasMap.entrySet())
                max = Math.max(max, Math.max(e.getKey().getShort(), e.getValue().getShort()));
            int base = max + 500;
            if (base + 3 >= Short.MAX_VALUE)
                return fail("No free short range available");
            ItemID a = new ItemID((short) base);
            ItemID b = new ItemID((short) (base + 1));   // same item as a: the stale duplicate
            ItemID c = new ItemID((short) (base + 2));   // distinct item, must stay untouched

            // 3-item persisted set: a and b load identical (as after a mod dropped their component).
            Map<ItemID, ItemStack> fixture = new ConcurrentHashMap<>();
            fixture.put(a, paper("dup"));
            fixture.put(b, paper("dup"));
            fixture.put(c, paper("other"));
            ItemIDManager.replaceState_forTesting(fixture, Collections.emptyMap(), base + 3);
            CompoundTag tag = new CompoundTag();
            if (!new ItemIDManager().save(tag))
                return fail("Could not serialize the fixture");

            // ==== Unconfirmed: startup must abort, no merge ====
            instances.SERVER_SETTINGS.BANK.CONFIRM_ITEMID_MERGE.set(false);
            ItemIDManager.clear();
            boolean aborted = false;
            try {
                new ItemIDManager().load(tag);
            } catch (ItemIDMergeAbortedException e) {
                aborted = true;
                TestResult r = assertTrue("report names CONFIRM_ITEMID_MERGE", e.getMessage().contains("CONFIRM_ITEMID_MERGE"));
                if (!r.passed()) return r;
            }
            TestResult r = assertTrue("persisted-into-persisted merge under an unchanged set aborts without confirmation", aborted);
            if (!r.passed()) return r;
            r = assertFalse("no alias created for the duplicate", ItemIDManager.getItemIDAliasMap().containsKey(b));
            if (!r.passed()) return r;
            r = assertTrue("both persisted IDs still registered",
                    ItemIDManager.getItemIDMap().containsKey(a) && ItemIDManager.getItemIDMap().containsKey(b));
            if (!r.passed()) return r;

            // ==== Confirmed: merge applied, flag consumed ====
            instances.SERVER_SETTINGS.BANK.CONFIRM_ITEMID_MERGE.set(true);
            ItemIDManager.clear();
            r = assertTrue("confirmed load() returned true", new ItemIDManager().load(tag));
            if (!r.passed()) return r;
            r = assertEquals("duplicate resolves to the lowest persisted short", a, ItemIDManager.resolveAlias(b));
            if (!r.passed()) return r;
            r = assertTrue("distinct item untouched", ItemIDManager.getItemIDMap().containsKey(c)
                    && !ItemIDManager.getItemIDAliasMap().containsKey(c));
            if (!r.passed()) return r;
            r = assertFalse("CONFIRM_ITEMID_MERGE consumed",
                    Boolean.TRUE.equals(instances.SERVER_SETTINGS.BANK.CONFIRM_ITEMID_MERGE.get()));
            if (!r.passed()) return r;

            return pass("unchanged-set persisted merges abort without CONFIRM_ITEMID_MERGE and apply once confirmed");
        } catch (Throwable t) {
            return fail("unchanged-set merge test threw: " + t.getClass().getSimpleName() + " — " + t.getMessage());
        } finally {
            ItemIDManager.replaceState_forTesting(savedItemMap, savedAliasMap, savedCounter);
            ItemIDManager.restoreQuarantinedAliases_forTesting(savedQuarantine);
            ItemIDManager.restorePersistedShorts_forTesting(savedPersisted);
            ItemIDManager.markRegistryReady();
            if (savedRepairCount == 0)
                ItemIDManager.consumeLastLoadRepairCount();
            instances.SERVER_SETTINGS.BANK.CONFIRM_ITEMID_MERGE.set(savedConfirm);
            if (instances.SERVER_DATA_HANDLER != null) {
                // Fixture loads wrote fixture metadata/settings through the production path —
                // re-save the restored live state.
                instances.SERVER_DATA_HANDLER.save_globalSettings();
                instances.SERVER_DATA_HANDLER.save_metadata();
            }
        }
    }
}
