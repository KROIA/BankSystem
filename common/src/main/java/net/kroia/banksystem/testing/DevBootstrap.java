package net.kroia.banksystem.testing;

import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.TickEvent;
import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.modutilities.networking.client_server.arrs.AsynchronousRequestResponseSystem;
import net.kroia.modutilities.testing.autotest.AutoTest;
import net.kroia.modutilities.testing.TestCommandRegistration;
import net.kroia.modutilities.testing.TestRegistry;

/**
 * The only way release code reaches dev-only code (unit tests, test commands).
 * <p>
 * Release builds ({@code enable_dev_features=false} in gradle.properties) do not compile the
 * {@code testing} package at all, so this class does not exist there. {@link BankSystemMod}
 * detects it and {@code BankSystemModBackend} loads it reflectively, which keeps release code
 * compiling without it.
 */
public final class DevBootstrap {
    private DevBootstrap() {}

    /** Set by the matrix's {@code *-trusted} cells: the slave ID this master trusts before any test runs. */
    private static final String TRUSTED_SLAVE = System.getProperty("modutilities.autotest.trustedSlave");
    private static boolean trustApplied;

    /** Called once from {@code BankSystemModBackend} during mod setup when dev features are enabled. */
    public static void init() {
        AsynchronousRequestResponseSystem.register(LedgerByRefRequest.INSTANCE); // Task #177: test-only master ledger read
        if (TestRegistry.ENABLE_TESTS) {
            BankSystemTestRegistration.register();
        }
        CommandRegistrationEvent.EVENT.register((dispatcher, registryAccess, environment) ->
                TestCommandRegistration.register(dispatcher, "banksystem", "BankSystem", "banksystem",
                        BankSystemMod.getAPI().isSlave()));
        if (AutoTest.isEnabled() && TRUSTED_SLAVE != null) {
            // First server tick, not SERVER_STARTED: onServerStart (also SERVER_STARTED) creates the manager and
            // loads the world's trust set, and listener order between loaders is not fixed. Any tick is still long
            // before a test: AutoTest starts the suites only after link + player + 40 settle ticks, on either side.
            TickEvent.SERVER_POST.register(server -> trustAutotestSlave());
            LifecycleEvent.SERVER_STOPPING.register(server -> trustApplied = false);
        }
    }

    private static void trustAutotestSlave() {
        if (trustApplied) return;
        BankSystemModBackend.Instances instances = BankSystemModBackend.getInstances_forTesting();
        if (instances.SERVER_BANK_MANAGER == null) return;
        trustApplied = true;
        if (BankSystemMod.getAPI().isSlave()) return;
        instances.SERVER_BANK_MANAGER.getSync().trustSlaveServer(TRUSTED_SLAVE);
        instances.LOGGER.info("[AutoTest] master trusts slave '" + TRUSTED_SLAVE + "' (modutilities.autotest.trustedSlave)");
    }
}
