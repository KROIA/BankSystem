package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.minecraft.block.BankSystemBlocks;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.testkit.TestCategory;
import net.kroia.testkit.TestResult;
import net.kroia.testkit.TestSuite;
import net.kroia.testkit.world.TestArea;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * TK-5: hopper item flow through a placed Bank Upload block that is not sending (no redstone, no owner), so it holds
 * items like a chest: hopper -> upload block -> hopper -> chest. All items must arrive in the chest, none lost.
 */
public class UploadHopperWorldTests extends TestSuite {

    private static final int ITEMS = 10;
    /** A hopper moves one item per 8 ticks; two hoppers in a row, plus slack for a loaded server. */
    private static final int MAX_TICKS = 600;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.WORLD;
    }

    @Override
    public Side side() {
        return Side.SCENARIO;
    }

    @Override
    public void registerTests() {
        addTest("hopper_passes_items_through_idle_upload_block", this::passThrough);
    }

    private TestResult passThrough() {
        try (TestArea area = TestArea.open(getServer())) {
            BlockPos chest = area.place(0, 0, 0, Blocks.CHEST.defaultBlockState());
            BlockPos out = area.place(0, 1, 0, Blocks.HOPPER.defaultBlockState()); // default facing: down
            BlockPos upload = area.place(0, 2, 0, BankSystemBlocks.BANK_UPLOAD_BLOCK.get().defaultBlockState());
            BlockPos in = area.place(0, 3, 0, Blocks.HOPPER.defaultBlockState());
            ItemStack rest = area.insert(in, new ItemStack(Items.COBBLESTONE, ITEMS));
            if (!rest.isEmpty()) return fail("feeding hopper did not take " + rest);
            if (!area.waitUntil(() -> area.countNow(chest, Items.COBBLESTONE) == ITEMS, MAX_TICKS))
                return fail("after " + MAX_TICKS + " ticks: feed hopper " + area.count(in, Items.COBBLESTONE)
                        + ", upload block " + area.count(upload, Items.COBBLESTONE) + ", out hopper "
                        + area.count(out, Items.COBBLESTONE) + ", chest " + area.count(chest, Items.COBBLESTONE));
            int left = area.count(in, Items.COBBLESTONE) + area.count(upload, Items.COBBLESTONE) + area.count(out, Items.COBBLESTONE);
            return assertEquals(0, left);
        }
    }
}
