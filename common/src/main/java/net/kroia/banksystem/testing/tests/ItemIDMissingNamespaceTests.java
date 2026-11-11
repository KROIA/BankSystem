package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** BS-195: which mod a saved ItemID entry is missing is found from its raw NBT. Offline. */
public class ItemIDMissingNamespaceTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.ITEM_ID;
    }

    @Override
    public boolean needsGame() {
        return false;
    }

    @Override
    public void registerTests() {
        addTest("missing_namespaces_counted_per_mod", this::countsPerMod);
    }

    private TestResult countsPerMod() {
        Set<String> loaded = Set.of("minecraft", "banksystem");
        CompoundTag item = new CompoundTag();
        item.putString("id", "create:wrench");
        CompoundTag book = new CompoundTag();
        book.putString("id", "minecraft:enchanted_book");
        CompoundTag levels = new CompoundTag();
        levels.putInt("create:capacity", 1);
        levels.putInt("minecraft:mending", 1);
        book.put("levels", levels);
        CompoundTag listed = new CompoundTag();
        listed.putString("id", "minecraft:paper");
        ListTag lore = new ListTag();
        lore.add(StringTag.valueOf("othermod:thing"));
        listed.put("lore", lore);
        CompoundTag plain = new CompoundTag();
        plain.putString("id", "minecraft:paper");
        plain.putString("note", "not an id: just text");

        Map<String, Integer> counts = ItemIDManager.countMissingNamespaces(List.of(item, book, listed, plain), loaded::contains);
        return assertEquals("create counted twice (item + enchantment), othermod once, unexplained entry as unknown",
                Map.of("create", 2, "othermod", 1, "unknown", 1), counts);
    }
}
