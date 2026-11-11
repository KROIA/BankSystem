package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.BankSystemDataHandler;
import net.kroia.banksystem.util.ItemIDManager;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Issue #60: a compatibility-mode load must not mask an unreadable ItemIDs.nbt. The compat
 * branch of loadAll() calls recoverUnreadableItemIDsForCompatMode(); it must move the file
 * aside and release the registration latch, like the regular load-failure recovery.
 */
public class CompatItemIDsRecoveryTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.DATA_PERSISTENCE;
    }

    @Override
    public void registerTests() {
        addTest("compat_unreadable_itemids_backed_up", this::testUnreadableItemIDsRecovered);
    }

    private TestResult testUnreadableItemIDsRecovered() {
        BankSystemModBackend.Instances inst = BankSystemModBackend.getInstances_forTesting();
        if (inst == null || inst.isSlaveServer || inst.ITEM_ID_MANAGER == null || inst.SERVER_DATA_HANDLER == null)
            return skip("needs a master server");

        // The live handler, pointed at a temp folder (absolute relative path, as in
        // SaveDirtyFlagTests): the real save files are never touched. A fresh handler is not
        // used because its constructor resets the static bank-data-loaded flag.
        // Side effect: the live ItemIDs load state ends FRESH instead of LOADED (both saveable).
        BankSystemDataHandler handler = inst.SERVER_DATA_HANDLER;
        Path savedRelative = handler.getRelativeSavePath();
        Path root = null;
        try {
            root = Files.createTempDirectory("bs_issue60_");
            handler.setRelativeSavePath(root);
            Path itemIDs = handler.getAbsoluteSavePath("ItemIDs.nbt");
            Files.write(itemIDs, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});

            if (handler.load_itemIDs())
                return fail("setup: the garbage ItemIDs.nbt loaded");

            TestResult r = assertTrue("compat recovery reports a usable ItemID registry",
                    handler.recoverUnreadableItemIDsForCompatMode());
            if (!r.passed()) return r;
            r = assertFalse("the unreadable ItemIDs.nbt was moved aside", Files.exists(itemIDs));
            if (!r.passed()) return r;
            boolean backedUp;
            try (Stream<Path> files = Files.list(itemIDs.getParent())) {
                backedUp = files.anyMatch(p -> p.getFileName().toString().startsWith("ItemIDs.nbt.corrupt-"));
            }
            r = assertTrue("a ItemIDs.nbt.corrupt-<time> backup exists", backedUp);
            if (!r.passed()) return r;
            return assertTrue("the registration latch is released", ItemIDManager.isRegistrationReady());
        } catch (IOException e) {
            return fail("I/O error: " + e.getMessage());
        } finally {
            handler.setRelativeSavePath(savedRelative);
            if (root != null) {
                try (Stream<Path> walk = Files.walk(root)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                } catch (IOException ignored) {
                }
            }
        }
    }
}
