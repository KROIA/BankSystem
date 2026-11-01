package net.kroia.banksystem.testing;

import dev.architectury.event.events.common.CommandRegistrationEvent;
import net.kroia.banksystem.BankSystemMod;
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

    /** Called once from {@code BankSystemModBackend} during mod setup when dev features are enabled. */
    public static void init() {
        if (TestRegistry.ENABLE_TESTS) {
            BankSystemTestRegistration.register();
        }
        CommandRegistrationEvent.EVENT.register((dispatcher, registryAccess, environment) ->
                TestCommandRegistration.register(dispatcher, "banksystem", "BankSystem", "banksystem",
                        BankSystemMod.getAPI().isSlave()));
    }
}
