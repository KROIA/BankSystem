package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.data.DiagnosticsDatabase;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Task #164 — {@link DiagnosticsDatabase} opens in WAL mode with synchronous=NORMAL and a busy timeout, and close
 * checkpoints the WAL into the .db (the -wal file is gone or empty afterwards). Offline, own temp file.
 */
public class DiagnosticsWalTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.LIFECYCLE;
    }

    @Override
    public boolean needsGame() {
        return false;
    }

    @Override
    public void registerTests() {
        addTest("wal_busy_timeout_and_checkpoint_on_close", this::walAndCheckpoint);
    }

    private TestResult walAndCheckpoint() {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("banksystem-diag-wal");
            Path f = dir.resolve(DiagnosticsDatabase.FILE_NAME);
            Path wal = dir.resolve(DiagnosticsDatabase.FILE_NAME + "-wal");
            DiagnosticsDatabase db = new DiagnosticsDatabase(f);
            db.setMeta(DiagnosticsDatabase.META_BANK_LAST_SAVE_MS, "1");
            db.flush().get(10, java.util.concurrent.TimeUnit.SECONDS);
            boolean walWhileOpen = Files.exists(wal); // only a WAL-mode writer leaves this file
            String mode;
            int busy, sync;
            try (Connection c = DiagnosticsDatabase.connect(f); Statement st = c.createStatement()) {
                mode = scalar(st, "PRAGMA journal_mode");
                busy = Integer.parseInt(scalar(st, "PRAGMA busy_timeout"));
                sync = Integer.parseInt(scalar(st, "PRAGMA synchronous"));
            }
            db.close();
            long walAfter = Files.exists(wal) ? Files.size(wal) : -1;
            if (!walWhileOpen) return fail("no -wal file while the writer was open");
            if (!"wal".equalsIgnoreCase(mode)) return fail("journal_mode " + mode);
            if (busy < 10_000) return fail("busy_timeout " + busy);
            if (sync != 1) return fail("synchronous " + sync + ", expected 1 (NORMAL)");
            if (walAfter > 0) return fail("-wal still " + walAfter + " bytes after close");
            return pass("journal_mode wal, busy_timeout " + busy + ", synchronous NORMAL, -wal after close "
                    + (walAfter < 0 ? "gone" : "empty"));
        } catch (Exception e) {
            return fail(e.toString());
        } finally {
            if (dir != null) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                } catch (Exception ignored) {
                    // best effort
                }
            }
        }
    }

    private static String scalar(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
