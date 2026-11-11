package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.ItemID;
import net.kroia.banksystem.util.ItemIDManager;
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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Task #156: a slave registration request that master never answered (timeout / not sent)
 * must not be cached as a rejection. The entry leaves the negative cache, and lookups inside
 * the retry backoff window return INVALID_ID without a new request.
 */
public class ItemIDRegistrationRetryTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.ITEM_ID;
    }

    @Override
    public void registerTests() {
        addTest("registration_timeout_is_not_cached_as_rejection", this::testTimeoutNotCached);
    }

    @SuppressWarnings("unchecked")
    private TestResult testTimeoutNotCached() {
        if (UtilitiesPlatform.getRegistryAccessServerSide() == null)
            return pass("skipped: no server-side RegistryAccess");

        ItemStack raw = new ItemStack(Items.PAPER);
        CompoundTag nbt = new CompoundTag();
        nbt.putString("banksystem_retry_test", "timeout");
        raw.set(DataComponents.CUSTOM_DATA, CustomData.of(nbt));

        boolean savedLatch = ItemIDManager.isRegistrationLatchArmed_forTesting();
        BankSystemModBackend.Instances instances = BankSystemModBackend.getInstances_forTesting();
        boolean savedSlave = instances.isSlaveServer;
        try {
            ItemIDManager.markRegistryReady();
            ItemIDManager.clearSlaveNegativeCache_forTesting();
            ItemIDManager.resetSlaveArrsCounter_forTesting();

            ItemStack normalized = VolatileItemComponents.normalize(raw);
            normalized.setCount(1);
            Class<?> keyClass = Class.forName("net.kroia.banksystem.util.ItemIDManager$NormalizedStackKey");
            Constructor<?> ctor = keyClass.getDeclaredConstructor(ItemStack.class);
            ctor.setAccessible(true);
            Object key = ctor.newInstance(normalized);

            // Simulate the in-flight mark the register path sets before sending.
            Field cacheField = ItemIDManager.class.getDeclaredField("slaveNegativeOrPendingCache");
            cacheField.setAccessible(true);
            ((Map<Object, Boolean>) cacheField.get(null)).put(key, Boolean.TRUE);

            Method populate = ItemIDManager.class.getDeclaredMethod("populateSlaveCacheFromResponse",
                    List.class, List.class, List.class, Throwable.class);
            populate.setAccessible(true);
            populate.invoke(null, List.of(normalized), List.of(key), null, new TimeoutException("simulated"));

            TestResult r = assertFalse("timeout must not leave the stack in the negative cache",
                    ItemIDManager.isInSlaveNegativeCache_forTesting(raw));
            if (!r.passed()) return r;

            // Inside the backoff window: lookup returns INVALID_ID and sends nothing.
            instances.isSlaveServer = true;
            ItemID id = ItemIDManager.registerItemStackServerSide_direct(raw);
            instances.isSlaveServer = savedSlave;
            r = assertTrue("lookup inside backoff returns INVALID_ID",
                    id == null || id.getShort() == ItemID.INVALID_ID.getShort());
            if (!r.passed()) return r;
            return assertEquals("no request fired inside the backoff window",
                    0, ItemIDManager.getSlaveArrsRequestsFired_forTesting());
        } catch (Throwable t) {
            return fail("threw " + t.getClass().getSimpleName() + " — " + t.getMessage());
        } finally {
            instances.isSlaveServer = savedSlave;
            ItemIDManager.clearSlaveNegativeCache_forTesting();
            ItemIDManager.resetSlaveArrsCounter_forTesting();
            if (!savedLatch)
                ItemIDManager.markRegistryReady();
        }
    }
}
