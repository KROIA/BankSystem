package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.api.bank.IAsyncBank;
import net.kroia.banksystem.minecraft.block.BankSystemBlocks;
import net.kroia.banksystem.minecraft.entity.custom.BankDownloadBlockEntity;
import net.kroia.banksystem.minecraft.item.custom.money.MoneyItem;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.BankSystemLogger;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.core.BlockPos;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Issue #87: a failed lock request in the bank download block completes the block's result with 0
 * and logs the unknown lock outcome for the admin.
 */
public class DownloadLockFailureTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.ITEM_BANK;
    }

    @Override
    public void registerTests() {
        addTest("download_lock_failure_completes_and_logs", this::testLockFailure);
    }

    private TestResult testLockFailure() {
        BankDownloadBlockEntity block = new BankDownloadBlockEntity(BlockPos.ZERO,
                BankSystemBlocks.BANK_DOWNLOAD_BLOCK.get().defaultBlockState());
        ItemID money = MoneyItem.getItemID();
        // A bank with plenty of balance whose lock request fails (as ARRS does on a timeout).
        IAsyncBank bank = (IAsyncBank) Proxy.newProxyInstance(IAsyncBank.class.getClassLoader(),
                new Class<?>[]{IAsyncBank.class}, (proxy, m, args) -> switch (m.getName()) {
                    case "getBalanceAsync" -> CompletableFuture.completedFuture(1_000_000L);
                    case "lockAmountAsync" -> CompletableFuture.failedFuture(new RuntimeException("test: lock timed out"));
                    case "toString" -> "FailingLockBank";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });

        BankSystemModBackend.Instances instances = BankSystemModBackend.getInstances_forTesting();
        BankSystemLogger original = instances.LOGGER;
        AtomicReference<String> logged = new AtomicReference<>();
        instances.LOGGER = new BankSystemLogger(instances) {
            @Override
            public void error(String message, Throwable throwable) {
                if (message.contains("[BankDownloadBlockEntity] Lock of")) logged.set(message);
                original.error(message, throwable);
            }
        };
        try {
            Method fill = BankDownloadBlockEntity.class.getDeclaredMethod("withdrawAndFillInventory",
                    ItemID.class, int.class, IAsyncBank.class);
            fill.setAccessible(true);
            @SuppressWarnings("unchecked")
            CompletableFuture<Integer> result = (CompletableFuture<Integer>) fill.invoke(block, money, 5, bank);
            Integer filled = result.get(2, TimeUnit.SECONDS);
            if (filled != 0) return fail("Failed lock filled " + filled + " item(s)");
            if (logged.get() == null) return fail("Issue #87: failed lock not logged for the admin");
            return pass("Failed lock completes with 0 and logs: " + logged.get());
        } catch (Exception e) {
            return fail("Result did not complete: " + e);
        } finally {
            instances.LOGGER = original;
        }
    }
}
