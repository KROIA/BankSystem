package net.kroia.banksystem.testing.tests;

import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.kroia.banksystem.screen.custom.CompanyManagementScreen;
import net.kroia.banksystem.testing.BankSystemTestCategories;
import net.kroia.modutilities.testing.TestCategory;
import net.kroia.modutilities.testing.TestResult;
import net.kroia.modutilities.testing.TestSuite;

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
        if (!isClient()) return pass("skipped: not client env");
        try {
            new CompanyManagementScreen(1234, "SmokeTestCo");
        } catch (Throwable t) {
            return fail("Screen constructor threw: " + t);
        }
        return pass("Screen constructed");
    }

    private TestResult testConstructEmptyName() {
        if (!isClient()) return pass("skipped: not client env");
        try {
            new CompanyManagementScreen(0, "");
        } catch (Throwable t) {
            return fail("Screen constructor threw: " + t);
        }
        return pass("Screen constructed with empty name");
    }
}
