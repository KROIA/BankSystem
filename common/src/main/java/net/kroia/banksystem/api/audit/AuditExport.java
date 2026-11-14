package net.kroia.banksystem.api.audit;

import net.kroia.banksystem.data.AuditExporter;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Audit export hook (since 2.2.0). {@code /banksystem backup export [name]} writes one package with the files an
 * external audit tool reads: {@code <world>/audit_exports/<yyyyMMdd-HHmmss>[-name]/} plus the same content as
 * {@code .zip}. Inside, paths mirror the world's {@code data/} folder ({@code data/<YourMod>/...}), and
 * {@code manifest.json} at the root lists every file with its size and SHA-256.
 * <p>
 * An add-on registers once at mod init, on master and slave servers alike. Each export calls every participant on
 * the server thread with a {@link Context}; the participant adds its files there.
 */
public final class AuditExport {
    private AuditExport() {
    }

    /**
     * @param modId       your mod id; also the key of your files in the manifest. Registering the same id again
     *                    replaces the earlier participant.
     * @param participant called on the server thread for every export
     */
    public static void register(String modId, Consumer<Context> participant) {
        AuditExporter.register(modId, participant);
    }

    /** What a participant gets for one export. Valid only during the participant call. */
    public interface Context {
        /** Absolute root folder of the package. */
        Path packageRoot();

        /** True on a slave server of a multi-server network. */
        boolean isSlave();

        /**
         * Copies one file now (on the server thread, so it cannot race your own saves) to
         * {@code packageRoot/relPath}. A missing {@code src} is skipped and listed as {@code "missing"} in the
         * manifest. Manifest method {@code "file_copy"}.
         *
         * @param relPath package-relative path with {@code /}, e.g. {@code "data/MyMod/settings.json"}
         */
        void copyFile(Path src, String relPath);

        /** Copies a folder recursively, like {@link #copyFile}. A missing {@code src} is skipped. */
        void copyDir(Path src, String relPath);

        /**
         * BS passes the target file (packageRoot/relPath, parent dirs created); the participant writes a consistent
         * copy there on its own DB thread (VACUUM INTO) and completes the future. Manifest method
         * {@code "vacuum_into"}. BankSystem waits for all databases without blocking the server thread, at most
         * 5 minutes; a failed or late future fails the export.
         */
        void addDatabase(String relPath, Function<Path, CompletableFuture<Void>> writeConsistentCopy);
    }
}
