package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.networking.general.BankAccountChangeStream;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.async_function_forwarding.AsyncFunctionInputData;
import net.kroia.banksystem.util.async_function_forwarding.AsyncFunctionOutputData;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class LifecycleTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.LIFECYCLE;
    }

    @Override
    public void registerTests() {
        addTest("bankAccountChangeStream_pendingPackets_is_thread_safe", this::testPendingPacketsIsThreadSafe);
        addTest("bankSystemMod_init_is_thread_safe", this::testInitIsThreadSafe);
        addTest("asyncFunctionData_does_not_leak_buffers", this::testAsyncFunctionDataDoesNotLeakBuffers);
    }

    @Override
    public void setup() {}

    @Override
    public void teardown() {}

    private TestResult testPendingPacketsIsThreadSafe() {
        try {
            Field field = BankAccountChangeStream.class.getDeclaredField("pendingPackets");
            Class<?> type = field.getType();
            if (!java.util.concurrent.ConcurrentLinkedQueue.class.isAssignableFrom(type)) {
                return fail("BankAccountChangeStream.pendingPackets is " + type.getSimpleName() +
                        ", expected ConcurrentLinkedQueue for thread-safe cross-thread access");
            }
            return pass("BankAccountChangeStream.pendingPackets is ConcurrentLinkedQueue — thread-safe");
        } catch (NoSuchFieldException e) {
            return fail("Field 'pendingPackets' not found on BankAccountChangeStream: " + e.getMessage());
        }
    }

    /**
     * Regression for Issue #50: BankSystemMod.init() must be synchronized and
     * the backend field must be volatile to prevent duplicate BankSystemModBackend
     * instances under concurrent initialization.
     *
     * Uses reflection checks rather than actually resetting the live backend,
     * which would disturb the running mod.
     */
    private TestResult testInitIsThreadSafe() {
        try {
            Method initMethod = BankSystemMod.class.getDeclaredMethod("init");
            if (!Modifier.isSynchronized(initMethod.getModifiers())) {
                return fail("BankSystemMod.init() is not synchronized — " +
                        "concurrent calls can create duplicate backends (Issue #50)");
            }
        } catch (NoSuchMethodException e) {
            return fail("Method 'init()' not found on BankSystemMod: " + e.getMessage());
        }

        try {
            Field backendField = BankSystemMod.class.getDeclaredField("backend");
            if (!Modifier.isVolatile(backendField.getModifiers())) {
                return fail("BankSystemMod.backend is not volatile — " +
                        "other threads may see a stale null reference (Issue #50)");
            }
        } catch (NoSuchFieldException e) {
            return fail("Field 'backend' not found on BankSystemMod: " + e.getMessage());
        }

        // Verify getAPI() returns a consistent, non-null instance
        Object api1 = BankSystemMod.getAPI();
        Object api2 = BankSystemMod.getAPI();
        if (api1 == null) {
            return fail("BankSystemMod.getAPI() returned null");
        }
        if (api1 != api2) {
            return fail("BankSystemMod.getAPI() returned different instances on consecutive calls");
        }

        return pass("BankSystemMod.init() is synchronized, backend is volatile, " +
                "getAPI() returns consistent non-null instance");
    }

    /**
     * Regression for Issue #27: AsyncFunctionInputData.of and AsyncFunctionOutputData.of
     * allocate Unpooled.buffer() internally. The fix wraps both in try/finally with release().
     * Run a batch of calls and verify none throw — a missing release() would eventually
     * cause resource exhaustion or Netty leak-detector warnings.
     */
    private TestResult testAsyncFunctionDataDoesNotLeakBuffers() {
        @SuppressWarnings("unchecked")
        StreamCodec codec = ByteBufCodecs.VAR_INT.cast();
        int iterations = 200;

        try {
            for (int i = 0; i < iterations; i++) {
                AsyncFunctionInputData.of(
                        codec, DummyFn.TEST, i,
                        (fn, bytes) -> new AsyncFunctionInputData<>(fn, codec, bytes));
            }
        } catch (Exception e) {
            return fail("AsyncFunctionInputData.of threw after repeated calls (possible buffer leak): " + e.getMessage());
        }

        try {
            for (int i = 0; i < iterations; i++) {
                AsyncFunctionOutputData.of(
                        codec, DummyFn.TEST, i,
                        (fn, bytes) -> new AsyncFunctionOutputData<>(fn, codec, bytes));
            }
        } catch (Exception e) {
            return fail("AsyncFunctionOutputData.of threw after repeated calls (possible buffer leak): " + e.getMessage());
        }

        return pass(iterations + " iterations of InputData.of + OutputData.of completed without error");
    }

    private enum DummyFn { TEST }
}
