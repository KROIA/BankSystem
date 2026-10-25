package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.minecraft.component.BankSystemDataComponents;
import net.kroia.banksystem.minecraft.item.BankSystemItems;
import net.kroia.banksystem.minecraft.item.custom.share.StampedShareItem;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.testing.LiveStateGuard;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.banksystem.util.VolatileItemComponents;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.world.item.ItemStack;

/**
 * Task #48 (v2.1.0) — verifies that {@code banksystem:company_id} is treated as an
 * identity-relevant data component (NOT volatile) so two stamped shares of different
 * companies produce distinct {@link ItemID}s while two of the same company collapse
 * to a single ItemID.
 * <p>
 * If these tests ever fail, the entire share-as-item premise breaks: dividends,
 * StockMarket keying, and holder-index queries would all confuse companies.
 */
public class CompanyShareItemIDTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.COMPANY;
    }

    @Override
    public void registerTests() {
        addTest("company_id_component_is_not_volatile", this::testCompanyIdNotVolatile);
        addTest("distinct_companies_get_distinct_item_ids", this::testDistinctCompaniesDistinctIds);
        addTest("same_company_shares_share_one_item_id", this::testSameCompanySharesShareId);
        addTest("unstamped_share_differs_from_stamped", this::testUnstampedDiffersFromStamped);
    }


    /**
     * Snapshot of the live ItemID registry, taken before the first synthetic template is
     * minted and reinstalled in {@link #teardown()}. Without it every run leaves one stray
     * row per scratch template in the bank manage screen, forever — the ItemID table has no
     * deregister. Only entries added after the snapshot are dropped; nothing that existed
     * before the suite ran is renumbered or removed.
     */
    private LiveStateGuard stateGuard;

    /**
     * Registers a synthetic test template through the suite's {@link LiveStateGuard}, so the
     * minted ItemID is discarded in {@link #teardown()} and residue left under the same
     * (deterministic) marker by an aborted earlier run is scrubbed on the way in.
     */
    private ItemID registerScratch(ItemStack stack) {
        return stateGuard != null
                ? stateGuard.register(stack)
                : ItemIDManager.registerItemStackServerSide_direct(stack);
    }

    @Override
    public void setup() {
        // Capture BEFORE the first stamped-share registration below.
        stateGuard = LiveStateGuard.capture();
    }

    @Override
    public void teardown() {
        // Drop the synthetic stamped-share templates again; the company ids used below are
        // fixed test values (1001/1002/2003/3004) that no real company owns, so leaving them
        // registered would add a permanent bogus row to the bank manage screen per run.
        LiveStateGuard.restore(stateGuard);
        stateGuard = null;
    }

    // ------------------------------------------------------------------

    /**
     * Guard against a downstream datapack / config move that would fold
     * {@code banksystem:company_id} into the volatile set — that would collapse every
     * stamped share of every company into a single ItemID (the whole feature breaks).
     */
    private TestResult testCompanyIdNotVolatile() {
        String key = BankSystemDataComponents.COMPANY_ID.getId().toString();
        for (String id : VolatileItemComponents.getEffectiveComponentIds()) {
            if (key.equalsIgnoreCase(id)) {
                return fail(key + " is currently in the volatile-components set; " +
                        "all stamped shares would collapse to one ItemID");
            }
        }
        return pass(key + " is NOT in the volatile-components set (as required)");
    }

    private TestResult testDistinctCompaniesDistinctIds() {
        if (BankSystemItems.STAMPED_SHARE.get() == null)
            return fail("stamped_share item is not registered");
        ItemStack shareA = StampedShareItem.ofCompany(BankSystemItems.STAMPED_SHARE.get(), 1001);
        ItemStack shareB = StampedShareItem.ofCompany(BankSystemItems.STAMPED_SHARE.get(), 1002);
        ItemID idA = registerScratch(shareA);
        ItemID idB = registerScratch(shareB);
        if (!idA.isValid() || !idB.isValid())
            return fail("Failed to register stamped-share templates (ids: " + idA + ", " + idB + ")");
        return assertTrue("shares of different companies must have different ItemIDs " +
                        "(companyId 1001 vs 1002 both got " + idA + ")",
                !idA.equals(idB));
    }

    private TestResult testSameCompanySharesShareId() {
        if (BankSystemItems.STAMPED_SHARE.get() == null)
            return fail("stamped_share item is not registered");
        ItemStack shareA = StampedShareItem.ofCompany(BankSystemItems.STAMPED_SHARE.get(), 2003);
        ItemStack shareB = StampedShareItem.ofCompany(BankSystemItems.STAMPED_SHARE.get(), 2003);
        // Different counts shouldn't affect identity either — ItemID keys on item + components only.
        shareB.setCount(64);
        ItemID idA = registerScratch(shareA);
        ItemID idB = ItemIDManager.getItemID(shareB);
        if (!idA.isValid())
            return fail("Failed to register stamped-share template (id: " + idA + ")");
        return assertEquals("two stamped shares of the same companyId (2003) must share one ItemID",
                idA, idB);
    }

    private TestResult testUnstampedDiffersFromStamped() {
        if (BankSystemItems.STAMPED_SHARE.get() == null)
            return fail("stamped_share item is not registered");
        ItemStack unstamped = new ItemStack(BankSystemItems.STAMPED_SHARE.get());
        ItemStack stamped = StampedShareItem.ofCompany(BankSystemItems.STAMPED_SHARE.get(), 3004);
        ItemID idUnstamped = registerScratch(unstamped);
        ItemID idStamped = registerScratch(stamped);
        if (!idUnstamped.isValid() || !idStamped.isValid())
            return fail("Registration failed (unstamped=" + idUnstamped + ", stamped=" + idStamped + ")");
        return assertTrue("an unstamped stamped_share (no company_id component) must not collide " +
                        "with a stamped one — both got " + idUnstamped,
                !idUnstamped.equals(idStamped));
    }
}
