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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
    private static final String NS2 = "bs_missing_other";

    private enum Mode { REFUSE, REFUSE_MESSAGE, RECOVER }

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.ITEM_ID;
    }

    @Override
    public void registerTests() {
        // BS-208: first, before any fixture touches the ItemID state.
        addTest("healthy_startup_reports_no_missing_mods", this::healthyStartup);
        addTest("missing_mod_entries_refuse_load_and_survive_save", () -> run(Mode.REFUSE));
        addTest("missing_mod_refusal_lists_namespaces_with_counts", () -> run(Mode.REFUSE_MESSAGE));
        addTest("missing_mod_recovery_removes_entries_and_balances", () -> run(Mode.RECOVER));
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

    /**
     * BS-208: a normal start of this world kept nothing unresolved, removed nothing, refused nothing, and every
     * entry of the ItemIDs.nbt on disk (and every live template) decodes strictly, so the next start will not
     * refuse either.
     */
    private TestResult healthyStartup() {
        var reg = UtilitiesPlatform.getRegistryAccessServerSide();
        if (reg == null)
            return pass("skipped: no server-side RegistryAccess");
        BankSystemModBackend.Instances instances = BankSystemModBackend.getInstances_forTesting();
        if (instances == null || instances.isSlaveServer)
            return pass("skipped: not a master server — slaves never load ItemIDs from disk");
        TestResult r = assertEquals("unresolved (missing-mod) entries after startup",
                Map.of(), ItemIDManager.getUnresolvedEntries_forTesting());
        if (!r.passed()) return r;
        r = assertEquals("ItemIDs removed as missing at startup", Set.of(), ItemIDManager.getLastRemovedMissing());
        if (!r.passed()) return r;
        r = assertNull("startup refusal recorded", BankSystemModBackend.getSingleplayerRefusal());
        if (!r.passed()) return r;
        List<String> bad = new ArrayList<>();
        for (Map.Entry<ItemID, ItemStack> e : ItemIDManager.getItemIDMap().entrySet()) {
            if (e.getValue().isEmpty()) continue;
            if (ItemStackNbt.parseStrict(reg, ItemStackNbt.save(e.getValue(), reg)).isEmpty())
                bad.add(e.getKey().getShort() + "=" + e.getValue());
        }
        r = assertTrue("live templates that do not decode strictly: " + bad, bad.isEmpty());
        if (!r.passed()) return r;
        if (instances.SERVER_DATA_HANDLER == null)
            return pass("no data handler; live state checked only");
        Path onDisk = instances.SERVER_DATA_HANDLER.getMetaDataFilePath().resolveSibling("ItemIDs.nbt");
        if (!Files.isRegularFile(onDisk))
            return pass("ItemIDs.nbt not written yet; live state checked only");
        CompoundTag file = instances.SERVER_DATA_HANDLER.readDataCompound(onDisk);
        if (file == null)
            return fail("ItemIDs.nbt exists but does not read");
        List<CompoundTag> undecodable = new ArrayList<>();
        int n = 0;
        for (Tag t : NbtCompat.getList(file, "itemIDs", Tag.TAG_COMPOUND)) {
            n++;
            if (ItemStackNbt.parseStrict(reg, NbtCompat.getCompound((CompoundTag) t, "itemStack")).isEmpty())
                undecodable.add((CompoundTag) t);
        }
        r = assertTrue("ItemIDs.nbt entries that would refuse the next start: "
                        + ItemIDManager.countMissingNamespaces(undecodable, dev.architectury.platform.Platform::isModLoaded),
                undecodable.isEmpty());
        if (!r.passed()) return r;
        return pass(n + " saved ItemIDs decode; no missing mods, no refusal");
    }

    private static List<Path> backupsOf(Path folder) throws IOException {
        String prefix = folder.getFileName() + "_backup-remove-missing-";
        try (var s = Files.list(folder.getParent())) {
            return s.filter(p -> p.getFileName().toString().startsWith(prefix)).toList();
        }
    }

    private static void deleteTree(Path root) {
        try (var s = Files.walk(root)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private TestResult run(Mode mode) {
        boolean confirmRemoval = mode == Mode.RECOVER;
        List<Path> newBackups = new ArrayList<>();
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
            if (base + 6 >= Short.MAX_VALUE)
                return fail("No free short range available");
            int missingItem = base + 1, bookA = base + 2, bookB = base + 3, otherItem = base + 4;

            Map<ItemID, ItemStack> fixture = new ConcurrentHashMap<>();
            fixture.put(new ItemID((short) base), new ItemStack(Items.PAPER));
            ItemIDManager.replaceState_forTesting(fixture, Collections.emptyMap(), base + 5);
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
            if (mode == Mode.REFUSE_MESSAGE)
                list.add(pair(otherItem, stack(NS2 + ":gone")));
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
                if (mode == Mode.REFUSE_MESSAGE) {
                    String msg = refused.getMessage();
                    r = assertEquals("missing count", 4, refused.missingCount);
                    if (!r.passed()) return r;
                    r = assertEquals("count per namespace", Map.of(NS, 3, NS2, 1), refused.missingNamespaces);
                    if (!r.passed()) return r;
                    r = assertTrue("message lists '" + NS + ": 3 item(s)': " + msg, msg.contains(NS + ": 3 item(s)"));
                    if (!r.passed()) return r;
                    r = assertTrue("message lists '" + NS2 + ": 1 item(s)': " + msg, msg.contains(NS2 + ": 1 item(s)"));
                    if (!r.passed()) return r;
                    return pass("refusal names every missing namespace with its count");
                }
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

            // BS-208: the confirmed recovery backs up the data folder before it removes anything.
            if (instances.SERVER_DATA_HANDLER == null)
                return fail("no data handler; the recovery backup cannot be checked");
            Path folder = instances.SERVER_DATA_HANDLER.getMetaDataFilePath().getParent();
            Path itemIdsFile = folder.resolve("ItemIDs.nbt");
            List<Path> backupsBefore = backupsOf(folder);
            List<String> filesBefore;
            try (var s = Files.list(folder)) {
                filesBefore = s.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).toList();
            }
            byte[] itemIdsBefore = Files.isRegularFile(itemIdsFile) ? Files.readAllBytes(itemIdsFile) : null;

            r = assertTrue("confirmed load() succeeds", new ItemIDManager().load(file));
            if (!r.passed()) return r;
            for (Path p : backupsOf(folder))
                if (!backupsBefore.contains(p)) newBackups.add(p);
            r = assertEquals("one backup folder created next to the bank data", 1, newBackups.size());
            if (!r.passed()) return r;
            Path backup = newBackups.get(0);
            List<String> notCopied = new ArrayList<>();
            for (String name : filesBefore)
                if (!Files.isRegularFile(backup.resolve(name))) notCopied.add(name);
            r = assertTrue("files missing from the backup: " + notCopied, notCopied.isEmpty());
            if (!r.passed()) return r;
            if (itemIdsBefore != null) {
                r = assertTrue("backup holds the old ItemIDs.nbt byte for byte",
                        java.util.Arrays.equals(itemIdsBefore, Files.readAllBytes(backup.resolve("ItemIDs.nbt"))));
                if (!r.passed()) return r;
            }
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
            newBackups.forEach(ItemIDMissingModTests::deleteTree); // test-made backups only
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
