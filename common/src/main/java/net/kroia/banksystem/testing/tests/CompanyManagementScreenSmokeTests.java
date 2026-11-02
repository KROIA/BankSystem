package net.kroia.banksystem.testing.tests;

import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.kroia.banksystem.screen.custom.CompanyManagementScreen;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;
import net.minecraft.client.Minecraft;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Task #51 (v2.1.0) — client-only smoke test for {@link CompanyManagementScreen}.
 *
 * <p>MVP scope: only asserts that the screen constructs without throwing when given
 * a synthetic (companyId, companyName) pair. The tab-visibility logic runs off two
 * async ARRS round-trips ({@code loadInfoAsync} + {@code loadRightsAsync}) whose
 * completions Fire on the render thread — that path is genuinely hard to exercise
 * headless from the in-game test framework, so it is intentionally not covered here.
 *
 * <p>Runs under the LIFECYCLE category (BOTH). On dedicated servers the environment
 * check short-circuits every case with a pass, since the screen class ultimately
 * touches {@code Minecraft.getInstance()} which is client-only.
 */
public class CompanyManagementScreenSmokeTests extends TestSuite {

    private static final long RENDER_THREAD_TIMEOUT_SECONDS = 10;

    @Override
    public TestCategory getCategory() {
        return BankSystemTestCategories.LIFECYCLE;
    }

    @Override
    public void registerTests() {
        addTest("construct_with_valid_pair", this::testConstructValid);
        addTest("construct_with_empty_name", this::testConstructEmptyName);
    }

    private boolean isClient() {
        return Platform.getEnvironment().equals(Env.CLIENT);
    }

    private TestResult testConstructValid() {
        return construct(1234, "SmokeTestCo", "Screen constructed");
    }

    private TestResult testConstructEmptyName() {
        return construct(0, "", "Screen constructed with empty name");
    }

    /**
     * Constructs the screen on the render thread. In singleplayer the tests run on the
     * integrated server thread, and since 1.21.9 Screen construction asserts the render thread.
     */
    private TestResult construct(int companyId, String companyName, String okMessage) {
        if (!isClient()) return pass("skipped: not client env");
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.isSameThread()) {
                new CompanyManagementScreen(companyId, companyName);
            } else {
                // Timeout guards against the render thread being blocked on the server thread.
                mc.submit(() -> new CompanyManagementScreen(companyId, companyName))
                        .get(RENDER_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (TimeoutException e) {
            return fail("Render thread did not run the screen constructor within "
                    + RENDER_THREAD_TIMEOUT_SECONDS + "s");
        } catch (ExecutionException e) {
            return fail("Screen constructor threw: " + e.getCause());
        } catch (Throwable t) {
            return fail("Screen constructor threw: " + t);
        }
        return pass(okMessage);
    }
}
