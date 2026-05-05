package net.kroia.banksystem.testing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TestRegistry {

    /**
     * Master switch for the entire test framework.
     * Set to false for production/release builds to disable all test functionality.
     * When false:
     *   - Test suites are not registered
     *   - /banksystem test command is not registered
     *   - No test code is executed
     */
    public static final boolean ENABLE_TESTS = true;

    private static final List<TestSuite> testSuites = new ArrayList<>();

    /**
     * Register a test suite. Called during mod initialization.
     * Other mods (e.g. StockMarket) can call this to add their own tests.
     * Does nothing if {@link #ENABLE_TESTS} is false.
     * @param suite the test suite to register
     */
    public static void register(TestSuite suite) {
        if (!ENABLE_TESTS) return;
        testSuites.add(suite);
    }

    /**
     * Returns all registered test suites (unmodifiable).
     */
    public static List<TestSuite> getTestSuites() {
        return Collections.unmodifiableList(testSuites);
    }

    /**
     * Returns the names of categories that have registered suites and can run on the current server type.
     * @param isSlave true if the current server is a slave
     * @return list of category names
     */
    public static List<String> getAvailableCategories(boolean isSlave) {
        List<String> categories = new ArrayList<>();
        for (TestSuite suite : testSuites) {
            TestCategory category = suite.getCategory();
            if (category.canRunOn(isSlave)) {
                String name = category.getName();
                if (!categories.contains(name)) {
                    categories.add(name);
                }
            }
        }
        return categories;
    }

    /**
     * Clear all registered test suites. Primarily for testing the framework itself.
     */
    public static void clear() {
        testSuites.clear();
    }
}
