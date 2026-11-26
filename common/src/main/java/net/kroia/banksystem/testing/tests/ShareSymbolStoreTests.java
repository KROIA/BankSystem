package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.banking.company.ShareSymbolStore;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.banksystem.util.BankSystemLogger;
import net.kroia.testkit.TestCategory;
import net.kroia.testkit.TestResult;
import net.kroia.testkit.TestSuite;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** BS-294: {@link ShareSymbolStore#adminReload} on its own temp folder (no live symbols). */
public class ShareSymbolStoreTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.COMPANY;
    }

    @Override
    public boolean needsGame() {
        return false;
    }

    @Override
    public void registerTests() {
        addTest("reload_adds_new_files_and_drops_deleted", this::reloadAddsAndDrops);
    }

    /** The mod backend sets LOGGER only when the mod starts; the offline JUnit run never starts it. */
    private static BankSystemLogger log() {
        BankSystemModBackend.Instances i = BankSystemModBackend.getInstances_forTesting();
        return i.LOGGER != null ? i.LOGGER : new BankSystemLogger(i);
    }

    /** Smallest bytes {@link ShareSymbolStore#validatePng} accepts: signature + IHDR of a square power-of-two image. */
    static byte[] png(int size) {
        ByteBuffer b = ByteBuffer.allocate(33);
        b.put(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        b.putInt(13).put("IHDR".getBytes()).putInt(size).putInt(size);
        b.put(new byte[]{8, 6, 0, 0, 0}).putInt(0);
        return b.array();
    }

    private TestResult reloadAddsAndDrops() {
        Path world = null;
        try {
            world = Files.createTempDirectory("banksystem-bs294");
            ShareSymbolStore store = new ShareSymbolStore(log());
            store.open(world, false);
            Path dir = world.resolve("data").resolve("BankSystem").resolve("share_symbols");
            Files.write(dir.resolve("inbox").resolve("aa_test.png"), png(16));
            Files.write(dir.resolve("inbox").resolve("bb_test.png"), png(32));
            if (store.adminAdd("aa_test") != null || store.adminAdd("bb_test") != null) return fail("adminAdd refused a valid PNG");
            int bbOrdinal = ordinal(store, "bb_test");

            Files.delete(dir.resolve("aa_test.png"));
            Files.write(dir.resolve("cc_test.png"), png(64));
            Files.write(dir.resolve("dd_test.png"), new byte[]{1, 2, 3}); // not a PNG: skipped
            if (store.adminReload() == null) return fail("reload reported no change");

            if (store.isValidSymbolId("aa_test")) return fail("deleted symbol still findable by id");
            if (!store.isValidSymbolId("cc_test")) return fail("new PNG in the folder not added");
            if (store.isValidSymbolId("dd_test")) return fail("invalid PNG added");
            if (ordinal(store, "bb_test") != bbOrdinal) return fail("existing symbol's ordinal changed");
            if (ordinal(store, "cc_test") != bbOrdinal + 1)
                return fail("new symbol ordinal " + ordinal(store, "cc_test") + ", expected " + (bbOrdinal + 1));
            if (store.adminReload() != null) return fail("second reload found changes");
            return pass("reload dropped the deleted file, added the new one after the last ordinal, skipped the invalid one");
        } catch (Exception e) {
            return fail(e.toString());
        } finally {
            delete(world);
        }
    }

    private static int ordinal(ShareSymbolStore store, String id) {
        return store.getEntries().stream().filter(e -> e.id().equals(id))
                .mapToInt(ShareSymbolStore.SymbolEntry::ordinal).findFirst().orElse(-1);
    }

    private static void delete(Path dir) {
        if (dir == null) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (Exception ignored) {
            // best effort
        }
    }
}
