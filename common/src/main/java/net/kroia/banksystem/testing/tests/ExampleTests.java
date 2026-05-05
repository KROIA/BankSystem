package net.kroia.banksystem.testing.tests;

import net.kroia.banksystem.testing.TestCategory;
import net.kroia.banksystem.testing.TestResult;
import net.kroia.banksystem.testing.TestSuite;

/**
 * Example test suite demonstrating how to write tests for the BankSystem test framework.
 *
 * To create your own test suite:
 * 1. Extend TestSuite
 * 2. Override getCategory() to return the appropriate TestCategory
 * 3. Override registerTests() and call addTest() for each test
 * 4. Write test methods that return a TestResult using the assertion helpers
 * 5. Register the suite in your mod init via TestRegistry.register(new YourTests())
 */
public class ExampleTests extends TestSuite {

    @Override
    public TestCategory getCategory() {
        return TestCategory.PERMISSION;
    }

    @Override
    public void registerTests() {
        addTest("example_pass", this::testExamplePass);
        addTest("example_assertEquals", this::testExampleAssertEquals);
        addTest("example_assertTrue", this::testExampleAssertTrue);
    }

    @Override
    public void setup() {
        // Set up test data here (e.g. create test UUIDs, temporary bank accounts)
    }

    @Override
    public void teardown() {
        // Clean up test data here (e.g. remove test accounts, reset state)
    }

    private TestResult testExamplePass() {
        // This is where a real test would go
        return pass("Example test passed");
    }

    private TestResult testExampleAssertEquals() {
        // Demonstrates assertEquals usage
        int expected = 42;
        int actual = 42;
        return assertEquals("Values should be equal", expected, actual);
    }

    private TestResult testExampleAssertTrue() {
        // Demonstrates assertTrue usage
        boolean condition = true;
        return assertTrue("Condition should be true", condition);
    }
}
